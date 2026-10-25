package com.s3vault.app.presentation.integrity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.auth.AuthStatus
import com.s3vault.app.data.local.IntegrityAuditEntity
import com.s3vault.app.data.local.IntegrityRow
import com.s3vault.app.data.local.IntegrityVerdict
import com.s3vault.app.domain.integrity.AuditCost
import com.s3vault.app.domain.integrity.AuditCosts
import com.s3vault.app.domain.integrity.IntegrityPlanner
import com.s3vault.app.sync.AuditOutcome
import com.s3vault.app.sync.IntegrityAuditor
import com.s3vault.app.sync.ListingResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the list shows. Each filter is one or two verdicts. */
enum class AuditFilter(val label: String, val verdicts: List<IntegrityVerdict>) {
    MISSING("Faltan", listOf(IntegrityVerdict.MISSING)),
    CHANGED("Posible cambio", listOf(IntegrityVerdict.CHANGED)),
    UNKNOWN("Sin concluir", listOf(IntegrityVerdict.UNKNOWN)),
    STALE("Registros obsoletos", listOf(IntegrityVerdict.STALE_RECORD)),
    VERIFIED("Verificadas", listOf(IntegrityVerdict.VERIFIED)),
}

data class IntegrityState(
    val audit: IntegrityAuditEntity? = null,
    val counts: Map<IntegrityVerdict, Int> = emptyMap(),
    /** Objects with a backup record and no verdict yet. */
    val unchecked: Int = 0,
    val listing: ListingResult? = null,
    val region: String = "",
    val configured: Boolean = false,
    val auth: AuthStatus = AuthStatus.Missing,
    /** How many objects the next incremental deep run would check. */
    val deepPlan: Int = 0,
    val auditIntervalDays: Int = 0,
    val busy: Boolean = false,
    val loaded: Boolean = false,
) {
    val totalObjects: Int get() = counts.values.sum() + unchecked
    fun count(f: AuditFilter) = f.verdicts.sumOf { counts[it] ?: 0 }
    val problems: Int get() = count(AuditFilter.MISSING) + count(AuditFilter.CHANGED) + count(AuditFilter.STALE)
}

@OptIn(ExperimentalCoroutinesApi::class)
class IntegrityViewModel(private val c: AppContainer) : ViewModel() {
    private val filterFlow = MutableStateFlow<AuditFilter?>(null)
    private val limit = MutableStateFlow(PAGE)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val _message = MutableStateFlow<String?>(null)
    private val working = MutableStateFlow(false)
    private var job: Job? = null

    val message: StateFlow<String?> = _message
    val selected: StateFlow<Set<String>> = selection
    fun dismissMessage() { _message.value = null }

    private val counts: Flow<Map<IntegrityVerdict, Int>> = c.db.integrity().observeCounts().map { l -> l.associate { it.verdict to it.n } }

    val state: StateFlow<IntegrityState> = combine(
        listOf(
            c.db.integrity().observeLatestAudit(), counts, c.db.integrity().observeUnchecked(), c.settings.remoteListing, c.settings.s3Config,
            c.credentials.status, c.settings.auditIntervalDays, working,
            c.db.integrity().observeLatestAudit().mapLatest { withContext(Dispatchers.IO) { deepPlanSize() } },
        ),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        IntegrityState(
            audit = v[0] as IntegrityAuditEntity?, counts = v[1] as Map<IntegrityVerdict, Int>, unchecked = v[2] as Int,
            listing = v[3] as ListingResult?, region = (v[4] as com.s3vault.app.domain.S3Config?)?.region.orEmpty(), configured = v[4] != null,
            auth = v[5] as AuthStatus, auditIntervalDays = v[6] as Int,
            busy = (v[7] as Boolean) || (v[0] as IntegrityAuditEntity?)?.status == com.s3vault.app.data.local.AuditStatus.RUNNING,
            deepPlan = v[8] as Int, loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntegrityState())

    val filter: StateFlow<AuditFilter?> = filterFlow

    /** The list: the chosen filter, or the most useful non-empty one when nothing was chosen. */
    val rows: StateFlow<List<IntegrityRow>> = combine(filterFlow, counts, limit) { f, cs, l -> Triple(f, cs, l) }.flatMapLatest { (f, cs, l) ->
        val effective = f ?: AuditFilter.entries.firstOrNull { (cs[it.verdicts.first()] ?: 0) > 0 } ?: AuditFilter.VERIFIED
        c.db.integrity().observeRows(effective.verdicts, l)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val effectiveFilter: StateFlow<AuditFilter> = combine(filterFlow, counts) { f, cs ->
        f ?: AuditFilter.entries.firstOrNull { (cs[it.verdicts.first()] ?: 0) > 0 } ?: AuditFilter.VERIFIED
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuditFilter.VERIFIED)

    private suspend fun deepPlanSize(): Int {
        val targets = c.db.media().auditTargets()
        if (targets.isEmpty()) return 0
        val results = c.db.integrity().allResults().associateBy { it.remoteKey }
        return IntegrityPlanner.nextDeep(targets, results, System.currentTimeMillis() - IntegrityAuditor.DEEP_FRESH_MS, IntegrityAuditor.DEFAULT_DEEP_LIMIT).size
    }

    // ------------------------------------------------------------------ costs the screen shows before asking

    fun listingCost(s: IntegrityState, reuse: Boolean): AuditCost =
        AuditCosts.listing(s.listing?.count?.takeIf { it > 0 } ?: s.totalObjects, s.region, reuse)

    fun deepCost(s: IntegrityState): AuditCost = AuditCosts.deep(s.deepPlan, s.region)

    // ------------------------------------------------------------------ actions

    fun select(f: AuditFilter) { limit.value = PAGE; selection.value = emptySet(); filterFlow.value = f }
    fun loadMore() { limit.update { it + PAGE } }
    fun toggle(key: String) = selection.update { if (key in it) it - key else it + key }
    fun clearSelection() { selection.value = emptySet() }
    fun selectAllShown(keys: List<String>) { selection.value = keys.toSet() }

    private suspend fun wifiOnly() = c.settings.currentBackup().wifiOnly

    /** [reuse]: compare with the saved listing instead of asking S3 again (free, as old as the listing). */
    fun startListing(reuse: Boolean) = launch {
        c.scheduler.kickAudit(deep = false, wifiOnly = wifiOnly(), reuseListing = reuse)
        _message.value = "Auditoría en cola. Se ejecuta cuando haya red${if (wifiOnly()) " Wi-Fi" else ""}; verás el resultado aquí."
    }

    fun startDeep() = launch {
        c.scheduler.kickAudit(deep = true, wifiOnly = wifiOnly())
        _message.value = "Verificación profunda en cola. Revisa hasta ${IntegrityAuditor.DEFAULT_DEEP_LIMIT} objetos por vez."
    }

    fun cancelAudit() = launch { c.scheduler.cancelAudit() }

    fun setInterval(days: Int) = launch {
        c.settings.setAuditIntervalDays(days)
        c.scheduler.configureAuditSchedule(days, wifiOnly())
    }

    /** A recheck of a few chosen objects runs here, now: it is a handful of HEAD requests the user is waiting for. */
    fun recheck(keys: List<String>) {
        if (keys.isEmpty() || working.value) return
        job = viewModelScope.launch {
            working.value = true
            try {
                _message.value = when (val o = c.integrityAuditor.runDeep(onlyKeys = keys.toSet(), limit = keys.size)) {
                    is AuditOutcome.Completed -> "Comprobadas ${o.audit.checked}: ${o.audit.verified} verificadas" +
                        (if (o.audit.missing + o.audit.changed > 0) ", ${o.audit.missing + o.audit.changed} con problemas" else "") +
                        (if (o.audit.unknown > 0) ", ${o.audit.unknown} sin concluir" else "") + "."
                    is AuditOutcome.NeedsCredentials -> o.reason.userMessage
                    is AuditOutcome.RetryLater -> o.reason.userMessage
                    is AuditOutcome.Blocked -> o.reason.userMessage
                    AuditOutcome.NotConfigured -> "Amazon S3 no está configurado."
                }
                clearSelection()
            } catch (e: CancellationException) {
                throw e
            } finally {
                working.value = false
            }
        }
    }

    /** Re-uploads the selected objects' files. The caller shows the confirmation first. */
    fun reupload(keys: List<String>) = launch {
        val n = c.integrityRepair.reupload(keys)
        _message.value = if (n == 0) "Ninguna de las seleccionadas tiene su archivo en este dispositivo." else "$n foto(s) en la cola de subida. Cuentan como respaldadas solo cuando pasen la verificación."
        clearSelection()
    }

    fun discard(keys: List<String>) = launch {
        val n = c.integrityRepair.discardRecords(keys)
        _message.value = if (n == 0) "No había registros que descartar." else "$n registro(s) descartados de este dispositivo. No se tocó nada en S3."
        clearSelection()
    }

    private fun launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    private companion object { const val PAGE = 60 }
}
