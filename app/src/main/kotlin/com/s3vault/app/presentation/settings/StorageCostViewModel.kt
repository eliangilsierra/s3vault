package com.s3vault.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.auth.AuthStatus
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.cost.CostEstimate
import com.s3vault.app.domain.cost.CostEstimator
import com.s3vault.app.domain.cost.PendingChanges
import com.s3vault.app.domain.cost.S3Pricing
import com.s3vault.app.domain.cost.StorageInventory
import com.s3vault.app.security.SafeLog
import com.s3vault.app.settings.InventoryAttempt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class StorageCostUiState(
    val loaded: Boolean = false,
    val config: S3Config? = null,
    val auth: AuthStatus = AuthStatus.Missing,
    /** The last complete inventory, only when it is of the bucket, region and prefix that are configured now. */
    val inventory: StorageInventory? = null,
    /** An inventory that exists but belongs to another configuration (the bucket was changed). */
    val inventoryIsOfAnotherBucket: Boolean = false,
    val estimate: CostEstimate? = null,
    val pending: PendingChanges = PendingChanges(),
    val lastAttempt: InventoryAttempt? = null,
    val refreshing: Boolean = false,
    val refreshError: String? = null,
    val refreshNeedsCredentials: Boolean = false,
    val regionHasPrices: Boolean = true,
    val nowMs: Long = 0,
) {
    val canRefresh: Boolean get() = config != null && !refreshing && auth !is AuthStatus.Missing && auth !is AuthStatus.Expired
}

/**
 * The storage and cost estimate screen. It adds no request of its own except the one the user asks for: "refresh" runs
 * the same `ListObjectsV2` sweep as the S3 library and a backup, and that sweep is what produces the inventory. Everything
 * else is read from what is already stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageCostViewModel(private val c: AppContainer) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<Pair<String, Boolean>?>(null)
    private val clock = MutableStateFlow(System.currentTimeMillis())

    private val pending: Flow<PendingChanges> = c.settings.inventory.flatMapLatest { inv ->
        if (inv == null) flowOf(PendingChanges())
        else combine(c.db.events().observeUploadsSince(inv.takenAtMs), c.db.events().observeCloudDeletionsSince(inv.takenAtMs)) { up, del ->
            PendingChanges(up, del)
        }
    }

    val state: StateFlow<StorageCostUiState> = combine(
        listOf(c.settings.s3Config, c.settings.inventory, c.settings.inventoryAttempt, c.settings.backup, c.credentials.status, pending, refreshing, error, clock),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        val cfg = v[0] as S3Config?
        val stored = v[1] as StorageInventory?
        val attempt = v[2] as InventoryAttempt?
        val backup = v[3] as com.s3vault.app.settings.BackupSettings
        val auth = v[4] as AuthStatus
        val changes = v[5] as PendingChanges
        val busy = v[6] as Boolean
        val err = v[7] as Pair<String, Boolean>?
        val now = v[8] as Long
        val inv = stored?.takeIf { cfg != null && it.matches(cfg) }
        val prices = cfg?.let { S3Pricing.forRegion(it.region) }
        StorageCostUiState(
            loaded = true, config = cfg, auth = auth, inventory = inv, inventoryIsOfAnotherBucket = stored != null && inv == null && cfg != null,
            estimate = inv?.let { CostEstimator.estimate(it, prices, Instant.ofEpochMilli(now), backup.cloudPreviews, changes) },
            pending = changes, lastAttempt = attempt, refreshing = busy, refreshError = err?.first, refreshNeedsCredentials = err?.second == true,
            regionHasPrices = prices != null || cfg == null, nowMs = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageCostUiState())

    /** Ages shown on screen are computed against this; call when the screen comes back to the front. */
    fun tick() { clock.value = System.currentTimeMillis() }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            error.value = null
            try {
                if (!c.remoteLibrary.refresh()) error.value = "Amazon S3 no está configurado." to false
            } catch (e: RemoteException) {
                // The previous complete inventory stays; only the failure is remembered.
                c.settings.saveInventoryFailure(System.currentTimeMillis(), e.message ?: e.kind.userMessage)
                error.update { (e.message ?: e.kind.userMessage) to e.needsCredentials }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.e("StorageCostViewModel", "Inventory refresh failed unexpectedly", e)
                error.value = RemoteErrorKind.UNKNOWN.userMessage to false
            } finally {
                refreshing.value = false
                clock.value = System.currentTimeMillis()
            }
        }
    }

    fun dismissError() { error.value = null }
}
