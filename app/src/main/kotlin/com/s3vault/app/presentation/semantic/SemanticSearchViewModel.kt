package com.s3vault.app.presentation.semantic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.data.local.JobEntity
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.LocalLibrarySql
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.library.LibraryQuery
import com.s3vault.app.domain.semantic.ModelPack
import com.s3vault.app.domain.semantic.ModelPacks
import com.s3vault.app.domain.semantic.PackStatus
import com.s3vault.app.domain.semantic.QueryTranslator
import com.s3vault.app.domain.semantic.TextImageEmbedder
import com.s3vault.app.sync.SemanticResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SemanticState(
    val enabled: Boolean = false,
    val pack: ModelPack = ModelPacks.ClipB32,
    val status: PackStatus = PackStatus.MISSING,
    val download: JobEntity? = null,
    val indexRun: JobEntity? = null,
    val images: Int = 0,
    val searchable: Int = 0,
    val pending: Int = 0,
    val indexBytes: Long = 0,
    val onlyCharging: Boolean = true,
    val wifiOnly: Boolean = true,
    val verifying: Boolean = false,
    val loaded: Boolean = false,
) {
    val ready: Boolean get() = status == PackStatus.READY
}

/** What the search screen shows below the field. */
data class SearchView(
    val query: String = "",
    /** What was really searched (the English form when words were translated), null when it is the query as typed. */
    val ranAs: String? = null,
    val semantic: List<MediaItem> = emptyList(),
    val byMetadata: List<MediaItem> = emptyList(),
    val searchable: Int = 0,
    val pending: Int = 0,
    val searching: Boolean = false,
    val semanticUsed: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchViewModel(private val c: AppContainer) : ViewModel() {
    private val pack = ModelPacks.ClipB32
    private val refresh = MutableStateFlow(0)
    private val verifying = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun dismissMessage() { _message.value = null }

    private val _view = MutableStateFlow(SearchView())
    val view: StateFlow<SearchView> = _view

    private val embedderLock = Mutex()
    private var embedder: TextImageEmbedder? = null
    private var searchJob: Job? = null

    private val status = combine(refresh, c.db.jobs().observe(JobNames.MODEL_DOWNLOAD)) { _, _ -> }.flatMapLatest {
        kotlinx.coroutines.flow.flow { emit(withContext(Dispatchers.IO) { c.modelStore.status(pack) }) }
    }

    val state: StateFlow<SemanticState> = combine(
        listOf(
            c.settings.semanticEnabled, status, c.db.jobs().observe(JobNames.MODEL_DOWNLOAD), c.db.jobs().observe(JobNames.SEMANTIC),
            c.db.semantic().observeImages(), c.db.semantic().observeSearchable(pack.id), c.db.semantic().observeNeedIndex(pack.id),
            c.db.semantic().observeBytes(), c.settings.semanticOnlyCharging, c.settings.modelWifiOnly, verifying,
        ),
    ) { v ->
        SemanticState(
            enabled = v[0] as Boolean, pack = pack, status = v[1] as PackStatus, download = v[2] as JobEntity?, indexRun = v[3] as JobEntity?,
            images = v[4] as Int, searchable = v[5] as Int, pending = v[6] as Int, indexBytes = v[7] as Long,
            onlyCharging = v[8] as Boolean, wifiOnly = v[9] as Boolean, verifying = v[10] as Boolean, loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SemanticState())

    // ------------------------------------------------------------------ turning it on and off

    /** The user opted in. If the model is already there it starts indexing; otherwise the screen offers the download. */
    fun enable() {
        viewModelScope.launch {
            c.settings.setSemanticEnabled(true)
            c.scheduler.configureSemanticSchedule(true, c.settings.semanticOnlyCharging.first())
            maybeVerifyAndIndex()
        }
    }

    /** Stops the background work and the search; nothing is deleted (the model and the vectors stay until the user removes them). */
    fun disable() {
        viewModelScope.launch {
            c.settings.setSemanticEnabled(false)
            c.scheduler.cancelSemanticIndex(); c.scheduler.cancelModelDownload(); c.scheduler.configureSemanticSchedule(false, true)
            closeEmbedder()
            _view.value = SearchView()
        }
    }

    fun startDownload() {
        viewModelScope.launch { c.scheduler.kickModelDownload(c.settings.modelWifiOnly.first()) }
    }

    fun cancelDownload() { c.scheduler.cancelModelDownload() }

    fun setWifiOnly(v: Boolean) { viewModelScope.launch { c.settings.setModelWifiOnly(v) } }

    fun setOnlyCharging(v: Boolean) {
        viewModelScope.launch {
            c.settings.setSemanticOnlyCharging(v)
            if (c.settings.semanticEnabled.first()) c.scheduler.configureSemanticSchedule(true, v)
        }
    }

    /** Hashes a pack that was put there by hand, once; a mismatch deletes the offending file. */
    private suspend fun maybeVerifyAndIndex() {
        var st = withContext(Dispatchers.IO) { c.modelStore.status(pack) }
        if (st == PackStatus.UNVERIFIED) {
            verifying.value = true
            try {
                val bad = withContext(Dispatchers.IO) { c.modelStore.verify(pack).also { if (it != null) c.modelStore.deleteFile(pack, it) } }
                if (bad != null) _message.value = "${bad.name} no coincide con la huella esperada y se descartó. El modelo no se usa."
            } finally { verifying.value = false }
            st = withContext(Dispatchers.IO) { c.modelStore.status(pack) }
            refresh.value++
        }
        if (st == PackStatus.READY) c.scheduler.kickSemanticIndex(c.settings.semanticOnlyCharging.first())
    }

    /** "Indexar ahora": runs without waiting for the charger (it still waits for the battery not to be low). */
    fun indexNow() { c.scheduler.kickSemanticIndex(onlyCharging = false) }

    fun pauseIndexing() { c.scheduler.cancelSemanticIndex() }

    /** Removes every vector (and so the ability to search by content) and starts building them again. */
    fun rebuild() {
        viewModelScope.launch {
            c.scheduler.cancelSemanticIndex()
            withContext(Dispatchers.IO) { c.db.semantic().clear() }
            _view.value = SearchView(query = _view.value.query)
            if (state.value.ready) c.scheduler.kickSemanticIndex(c.settings.semanticOnlyCharging.first())
            _message.value = "Índice borrado. Se vuelve a construir desde cero."
        }
    }

    /** Deletes the vectors only. The model stays; nothing is rebuilt until the user asks. */
    fun deleteVectors() {
        viewModelScope.launch {
            c.scheduler.cancelSemanticIndex()
            withContext(Dispatchers.IO) { c.db.semantic().clear() }
            _view.value = SearchView(query = _view.value.query)
            _message.value = "Se han borrado los vectores de tus fotos."
        }
    }

    /** Deletes the model and the vectors, and turns the feature off. Frees the space of both. */
    fun deleteEverything() {
        viewModelScope.launch {
            c.scheduler.cancelSemanticIndex(); c.scheduler.cancelModelDownload(); c.scheduler.configureSemanticSchedule(false, true)
            closeEmbedder()
            withContext(Dispatchers.IO) { c.db.semantic().clear(); c.modelStore.delete(pack) }
            c.settings.setSemanticEnabled(false)
            refresh.value++
            _view.value = SearchView()
            _message.value = "Modelo y vectores eliminados. Ya no ocupan espacio."
        }
    }

    // ------------------------------------------------------------------ searching

    fun search(text: String) {
        searchJob?.cancel()
        val q = text.trim()
        if (q.isEmpty()) { _view.value = SearchView(); return }
        _view.value = _view.value.copy(query = text, searching = true, error = null)
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250) // a pause in typing, not a request per keystroke
            val t = QueryTranslator.translate(q)
            val metadata = withContext(Dispatchers.IO) {
                c.db.media().rows(LocalLibrarySql.rows(LibraryQuery(search = q), 120, 0)).map(MediaItemEntity::toDomain)
            }
            // Metadata search never depends on the model: it already answered while the model (if any) works.
            _view.value = SearchView(query = text, byMetadata = metadata, searching = state.value.enabled && state.value.ready)
            val st = state.value
            if (!st.enabled || !st.ready) { _view.value = _view.value.copy(searching = false); return@launch }
            try {
                val e = ensureEmbedder() ?: run { _view.value = _view.value.copy(searching = false, error = "El modelo no está disponible."); return@launch }
                val r: SemanticResult = c.semanticSearcher.search(e, t.effective)
                _view.value = SearchView(
                    query = text, ranAs = t.effective.takeIf { t.changed }, semantic = r.hits.map { it.first }, byMetadata = metadata,
                    searchable = r.searchable, pending = r.pending, searching = false, semanticUsed = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.s3vault.app.security.SafeLog.e("SemanticSearchViewModel", "Search failed", e)
                _view.value = _view.value.copy(searching = false, error = "No se pudo buscar por contenido. Los resultados por nombre siguen disponibles.")
            }
        }
    }

    private suspend fun ensureEmbedder(): TextImageEmbedder? = embedderLock.withLock { embedder ?: c.embedderProvider.open().also { embedder = it } }

    private suspend fun closeEmbedder() = embedderLock.withLock { embedder?.close(); embedder = null }

    /** The roughly 150 MB of weights go with the screen. */
    override fun onCleared() { embedder?.close(); embedder = null }
}
