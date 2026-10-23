package com.s3vault.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.data.local.CloudLibrarySql
import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.LibraryFilters
import com.s3vault.app.domain.library.LibraryQuery
import com.s3vault.app.domain.library.LibraryScope
import com.s3vault.app.domain.library.SearchChip
import com.s3vault.app.domain.library.SearchParser
import com.s3vault.app.domain.library.SortField
import com.s3vault.app.presentation.library.BrowseState
import com.s3vault.app.presentation.library.ItemSource
import com.s3vault.app.presentation.library.LibraryBrowser
import com.s3vault.app.presentation.library.PagedWindow
import com.s3vault.app.settings.CloudViewMode
import com.s3vault.app.settings.GridDensity
import com.s3vault.app.sync.ListingResult
import com.s3vault.app.sync.PreviewResult
import com.s3vault.app.sync.Progress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteUiState(
    val configured: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val needsCredentials: Boolean = false,
    val message: String? = null,
    val detail: RemoteDetail? = null,
)

data class RemoteDetail(val key: String, val lines: List<Pair<String, String>>?, val error: String?)

/** Reads a page of the cached listing as [RemoteObject]s, marking the ones that also exist on this device. */
internal suspend fun AppContainer.remotePage(rows: List<RemoteObjectEntity>): List<RemoteObject> {
    val local = db.media().withRemoteKeys(rows.map { it.key }).filter { it.present }.mapNotNull { it.remoteKey }.toSet()
    return rows.map { it.toDomain(it.key in local) }
}

/**
 * The S3 library. Everything shown, searched and filtered here comes from the cached listing in Room; nothing is requested
 * from S3 except when the user refreshes the listing, opens a preview or asks for capture dates. [listing] says how old that
 * cache is and whether the last pass finished, so the screen never presents a stale or partial list as the whole bucket.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class RemoteViewModel(private val c: AppContainer) : ViewModel() {
    private val store = c.libraryStore
    val downloads: StateFlow<Map<String, Progress>> = c.progress.downloads

    private val transient = MutableStateFlow(RemoteUiState())
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val selectedSections = MutableStateFlow<Set<Int>>(emptySet())
    val selectedKeys: StateFlow<Set<String>> = selection
    val sectionSelection: StateFlow<Set<Int>> = selectedSections

    val query: StateFlow<LibraryQuery> = store.cloud
    val density: StateFlow<GridDensity> = store.density
    val mode: StateFlow<CloudViewMode> = store.cloudMode
    val searchInput = MutableStateFlow(store.cloud.value.search)

    private val browser = LibraryBrowser<RemoteObject>(
        scope = viewModelScope,
        library = LibraryScope.CLOUD,
        queries = store.cloud,
        observeCount = { c.db.remote().observeCount(CloudLibrarySql.count(it)) },
        observeDays = { c.db.remote().observeDayCounts(CloudLibrarySql.dayCounts(it)) },
        loadPage = { q, offset, limit -> c.remotePage(c.db.remote().rows(CloudLibrarySql.rows(q, limit, offset))) },
    )
    val window: PagedWindow<RemoteObject> get() = browser.window
    val browse: StateFlow<BrowseState> = browser.state

    val state: StateFlow<RemoteUiState> = combine(c.settings.s3Config, transient) { cfg, t -> t.copy(configured = cfg != null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUiState())

    /** Objects in the cached listing, regardless of search and filters. */
    val totalObjects: StateFlow<Int> = c.db.remote().observeObjectCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The last pass over the bucket: when it ended and whether it reached the end. Null: never listed on this device. */
    val listing: StateFlow<ListingResult?> = c.settings.remoteListing.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Objects without a capture date that nobody asked about yet (candidates of "Completar fechas"). */
    val pendingDates: StateFlow<Int> = c.db.remote().observePendingMetaCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Objects listed apart as "sin fecha de captura". */
    val undatedCount: StateFlow<Int> = c.db.remote().observeUndatedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            store.load()
            searchInput.value = store.cloud.value.search
        }
        viewModelScope.launch {
            searchInput.debounce(250).collect { text -> store.cloud.update { if (it.search == text) it else it.copy(search = text) } }
        }
    }

    // ------------------------------------------------------------------ search, filters, sort, view

    fun setSearchText(text: String) { searchInput.value = text }

    fun removeSearchChip(chip: SearchChip) {
        val text = SearchParser.remove(searchInput.value, chip)
        searchInput.value = text
        store.cloud.update { it.copy(search = text) }
    }

    fun setFilters(f: LibraryFilters) = store.setCloudFilters(f)

    fun resetAll() {
        searchInput.value = ""
        store.cloud.update { it.copy(search = "", filters = LibraryFilters()) }
    }

    fun setSort(sort: SortField, descending: Boolean) = store.cloud.value.let { store.setCloudView(sort, descending, it.group) }
    fun setGroup(group: GroupBy) = store.cloud.value.let { store.setCloudView(it.sort, it.descending, group) }
    fun setDensity(d: GridDensity) = store.setDensity(d)
    fun setMode(m: CloudViewMode) = store.setCloudMode(m)

    suspend fun countFor(filters: LibraryFilters): Int = c.db.remote().countOnce(CloudLibrarySql.count(store.cloud.value.copy(filters = filters)))

    // ------------------------------------------------------------------ selection

    fun toggle(key: String) = selection.update { if (key in it) it - key else it + key }

    fun clearSelection() { selection.value = emptySet(); selectedSections.value = emptySet() }

    fun selectAllMatching() {
        viewModelScope.launch { selection.value = c.db.remote().keyList(CloudLibrarySql.keys(store.cloud.value)).toSet() }
    }

    fun toggleSection(sectionIndex: Int) {
        val layout = browse.value.layout
        val section = layout.sections.getOrNull(sectionIndex) ?: return
        viewModelScope.launch {
            val keys = c.db.remote().keyList(CloudLibrarySql.keys(store.cloud.value, layout.group to section.key))
            if (section.key in selectedSections.value) {
                selection.update { it - keys.toSet() }
                selectedSections.update { it - section.key }
            } else {
                selection.update { it + keys }
                selectedSections.update { it + section.key }
            }
        }
    }

    /** The selected objects, read from the database (the grid only holds a page at a time). */
    suspend fun selectedObjects(): List<RemoteObject> =
        selection.value.toList().chunked(500).flatMap { c.remotePage(c.db.remote().getByKeys(it)) }

    // ------------------------------------------------------------------ listing, previews, restore

    /** Preview of one object without downloading the original (see RemotePreviewRepository for the lookup order). */
    suspend fun loadPreview(obj: RemoteObject): PreviewResult = c.previews.load(obj)

    /** Lists the bucket again: one ListObjectsV2 request per 1000 objects, shown in the grid as the pages arrive. */
    fun refresh() {
        if (transient.value.loading) return
        viewModelScope.launch {
            transient.update { it.copy(loading = true, error = null, needsCredentials = false) }
            try {
                if (!c.remoteLibrary.refresh()) transient.update { it.copy(error = "Amazon S3 no está configurado.") }
            } catch (e: RemoteException) {
                transient.update { it.copy(error = e.message, needsCredentials = e.needsCredentials) }
            } catch (e: Exception) {
                transient.update { it.copy(error = RemoteErrorKind.UNKNOWN.userMessage) }
            } finally {
                transient.update { it.copy(loading = false) }
            }
        }
    }

    /** Opt-in: reads the capture date of the objects that have none, in the background, once per object. */
    fun completeDates() {
        viewModelScope.launch {
            c.remoteLibrary.completeCaptureDates()
            transient.update { it.copy(message = "Leyendo las fechas de captura en segundo plano. Las fotos irán ocupando su sitio en la línea de tiempo.") }
        }
    }

    fun restoreSelected() = restoreKeys(selection.value.toList())

    fun restoreKeys(keys: List<String>) {
        viewModelScope.launch {
            val n = c.remoteLibrary.restore(keys)
            transient.update {
                it.copy(
                    message = if (n == 0) "Ya están en cola de restauración."
                    else "$n elemento(s) en cola de restauración. Se guardarán como copias nuevas en Imágenes/S3Vault; nunca se sobrescribe un archivo existente.",
                )
            }
            clearSelection()
        }
    }

    fun cancelRestore(key: String) { viewModelScope.launch { c.remoteLibrary.cancelRestore(listOf(key)) } }
    fun dismissMessage() = transient.update { it.copy(message = null) }
    fun postMessage(text: String) = transient.update { it.copy(message = text) }
    fun dismissDetail() = transient.update { it.copy(detail = null) }

    /** HEAD request for the metadata dialog (one request, only when the user asks). It also teaches the cache the capture date. */
    fun showDetail(obj: RemoteObject) {
        viewModelScope.launch {
            transient.update { it.copy(detail = RemoteDetail(obj.key, null, null)) }
            try {
                val ctx = c.remoteProvider.get() ?: throw RemoteException(RemoteErrorKind.NOT_CONFIGURED)
                val meta = ctx.storage.head(obj.key) ?: throw RemoteException(RemoteErrorKind.OBJECT_NOT_FOUND)
                val captured = meta.userMetadata["captured-at-ms"]?.toLongOrNull()
                c.catalog.recordCaptureDate(obj.key, captured)
                val lines = buildList {
                    add("Nombre" to obj.displayName)
                    add("Tamaño" to formatBytes(meta.sizeBytes))
                    meta.lastModifiedMs?.let { add("Subido a S3" to formatDateTime(it)) }
                    meta.contentType?.let { add("Tipo" to it) }
                    captured?.let { add("Fecha de captura" to formatDateTime(it)) }
                        ?: add("Fecha de captura" to "no disponible (se usa " + (if (obj.dateSource == com.s3vault.app.domain.library.DateSource.FILENAME) "la fecha del nombre" else "la fecha de subida") + ")")
                    meta.userMetadata["original-name"]?.let { add("Nombre original" to java.net.URLDecoder.decode(it, "UTF-8")) }
                    add("SHA-256 (S3)" to (meta.checksumSha256Base64 ?: "no disponible"))
                    add("Versión (SHA-256 hex)" to obj.versionId)
                    add("Clave" to obj.key)
                }
                transient.update { it.copy(detail = RemoteDetail(obj.key, lines, null)) }
            } catch (e: RemoteException) {
                transient.update { it.copy(detail = RemoteDetail(obj.key, null, e.message)) }
            }
        }
    }
}

/** Opens the S3 viewer on a window over the same query as the grid, so swiping goes through the filtered, sorted list. */
class CloudViewerViewModel(private val c: AppContainer) : ViewModel() {
    data class Data(val source: ItemSource<RemoteObject>, val startIndex: Int)

    private val _data = MutableStateFlow<Data?>(null)
    val data: StateFlow<Data?> = _data
    private var opened = false

    /**
     * Opens the viewer on the S3-only photos of an album. They are the album's own list (not the S3 library's query), so
     * swiping stays inside the album; the list follows the database, so a photo deleted from here drops out by itself.
     */
    fun openAlbum(albumId: Long, startKey: String) {
        if (opened) return
        opened = true
        viewModelScope.launch {
            var first = true
            c.db.albums().observeAlbumCloud(albumId).collect { rows ->
                val items = c.remotePage(rows)
                val start = if (first) items.indexOfFirst { it.key == startKey }.coerceAtLeast(0) else 0
                first = false
                _data.value = Data(com.s3vault.app.presentation.library.ListSource(items), start)
            }
        }
    }

    fun open(startKey: String) {
        if (opened) return
        opened = true
        viewModelScope.launch {
            val query = c.libraryStore.cloud.value
            val entity = c.db.remote().get(startKey)
            val position = entity?.let { c.db.remote().countOnce(CloudLibrarySql.positionOf(query, it)) } ?: 0
            val browser = LibraryBrowser<RemoteObject>(
                scope = viewModelScope, library = LibraryScope.CLOUD, queries = MutableStateFlow(query),
                observeCount = { c.db.remote().observeCount(CloudLibrarySql.count(it)) },
                observeDays = { c.db.remote().observeDayCounts(CloudLibrarySql.dayCounts(it)) },
                loadPage = { q, offset, limit -> c.remotePage(c.db.remote().rows(CloudLibrarySql.rows(q, limit, offset))) },
            )
            val ready = browser.state.first { it.ready }
            _data.value = Data(browser.window, position.coerceIn(0, (ready.total - 1).coerceAtLeast(0)))
        }
    }
}
