package com.s3vault.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.data.local.FolderCount
import com.s3vault.app.data.local.LocalLibrarySql
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.domain.Album
import com.s3vault.app.domain.MediaItem
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
import com.s3vault.app.security.Redactor
import com.s3vault.app.settings.GridDensity
import com.s3vault.app.sync.MediaAccess
import com.s3vault.app.sync.Progress
import com.s3vault.app.presentation.delete.toDeleteEntry
import com.s3vault.app.sync.DeleteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GalleryUiState(
    val access: MediaAccess,
    val selected: Set<Long> = emptySet(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

private const val SUMMARY_SETTLE_MS = 60L

/** What the selection contains, for the contextual bar. */
data class SelectionSummary(val total: Int = 0, val inCloud: Int = 0)

/**
 * The local gallery. The photos themselves are never held as a list: [browse] describes how the (filtered) library is divided
 * and [window] hands out the photos a page at a time as the grid scrolls. Search, filters, sort and grouping live in the shared
 * [LibraryQuery] so the viewer opens on the same list.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GalleryViewModel(private val c: AppContainer) : ViewModel() {
    private val store = c.libraryStore
    private val access = MutableStateFlow(c.mediaAccess())
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val selectedSections = MutableStateFlow<Set<Int>>(emptySet())
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    val uploads: StateFlow<Map<Long, Progress>> = c.progress.uploads
    val query: StateFlow<LibraryQuery> = store.local
    val density: StateFlow<GridDensity> = store.density

    /** What the user is typing; it becomes part of [query] after a short pause so each keystroke does not run a query. */
    val searchInput = MutableStateFlow(store.local.value.search)

    private val browser = LibraryBrowser<MediaItem>(
        scope = viewModelScope,
        library = LibraryScope.LOCAL,
        queries = store.local,
        observeCount = { c.db.media().observeCount(LocalLibrarySql.count(it)) },
        observeDays = { c.db.media().observeDayCounts(LocalLibrarySql.dayCounts(it)) },
        loadPage = { q, offset, limit -> c.db.media().rows(LocalLibrarySql.rows(q, limit, offset)).map(MediaItemEntity::toDomain) },
    )
    val window: PagedWindow<MediaItem> get() = browser.window
    val browse: StateFlow<BrowseState> = browser.state

    /** Photos on the device regardless of search and filters, for "312 de 1 204". */
    val totalInLibrary: StateFlow<Int> = c.db.media().observePresentCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val folders: StateFlow<List<FolderCount>> = c.db.media().observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    /** Photos with no capture date, for the filter sheet. */
    val undatedCount: StateFlow<Int> = c.db.media().observeUndatedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), 0)

    /** (waiting or uploading, failed): the quiet backup line above the grid. */
    val backupGlance: StateFlow<Pair<Int, Int>> = c.db.media().observeCounts().map { counts ->
        fun n(vararg states: com.s3vault.app.domain.SyncState) = counts.filter { it.syncState in states }.sumOf { it.n }
        n(com.s3vault.app.domain.SyncState.QUEUED, com.s3vault.app.domain.SyncState.UPLOADING, com.s3vault.app.domain.SyncState.VERIFYING) to
            n(com.s3vault.app.domain.SyncState.FAILED)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0 to 0)

    /**
     * The albums to pick from. Only collected while the "add to album" sheet is open: the query joins several tables and
     * SQLite re-runs it after any write to the media table, so it must not run behind the grid all the time (a backup, or
     * queueing 300 photos, writes constantly).
     */
    val albums: StateFlow<List<Album>> = c.db.albums().observeAlbums().map { l -> l.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    val state: StateFlow<GalleryUiState> = combine(access, selected, refreshing, error, message) { values ->
        @Suppress("UNCHECKED_CAST")
        GalleryUiState(
            access = values[0] as MediaAccess,
            selected = values[1] as Set<Long>,
            refreshing = values[2] as Boolean,
            error = values[3] as String?,
            message = values[4] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState(c.mediaAccess()))

    /**
     * "12 con copia en S3" for the selection bar. The count itself comes from SQLite (one COUNT per 400 ids), and rapid taps are
     * merged into one query: tapping ten photos in a second asks the database once, not ten times. The size shown is always exact.
     */
    val selectionSummary: StateFlow<SelectionSummary> = combine(selected, c.db.media().observePresentCount()) { ids, _ -> ids }
        .debounce { if (it.isEmpty()) 0L else SUMMARY_SETTLE_MS }
        .mapLatest { ids ->
            if (ids.isEmpty()) SelectionSummary()
            else SelectionSummary(ids.size, withContext(Dispatchers.IO) { ids.chunked(400).sumOf { c.db.media().countWithRemote(it) } })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectionSummary())

    init {
        viewModelScope.launch {
            store.load()
            searchInput.value = store.local.value.search
        }
        viewModelScope.launch {
            searchInput.debounce(250).collect { text -> store.local.update { if (it.search == text) it else it.copy(search = text) } }
        }
    }

    /**
     * Re-reads the permission state and rescans MediaStore on a background dispatcher (the scanner switches to IO).
     * [force] is an explicit request (pull to refresh, retry, a permission change): it always scans and shows the refresh
     * indicator. Without it (coming back to the app) the scan only runs when MediaStore changed since the last one.
     */
    fun refresh(force: Boolean = true) {
        access.value = c.mediaAccess()
        viewModelScope.launch {
            // The very first scan of the process shows as work in progress (an empty library would otherwise flash "no photos").
            val indicator = force || !c.library.hasScanned
            if (indicator) refreshing.value = true
            try {
                c.library.refresh(force)
                error.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // leaving the screen is not a failure to read the library
            } catch (e: Exception) {
                error.value = "No se pudo leer la galería: " + Redactor.describe(e)
            } finally {
                if (indicator) refreshing.value = false
            }
        }
    }

    // ------------------------------------------------------------------ search, filters, sort, view

    fun setSearchText(text: String) { searchInput.value = text }

    /** Removes the part of the search that produced [chip], applied at once (a tap on ×, not typing). */
    fun removeSearchChip(chip: SearchChip) {
        val text = SearchParser.remove(searchInput.value, chip)
        searchInput.value = text
        store.local.update { it.copy(search = text) }
    }

    fun setFilters(f: LibraryFilters) = store.setLocalFilters(f)

    fun resetAll() {
        searchInput.value = ""
        store.local.update { it.copy(search = "", filters = LibraryFilters()) }
    }

    fun setSort(sort: SortField, descending: Boolean) = store.local.value.let { store.setLocalView(sort, descending, it.group) }
    fun setGroup(group: GroupBy) = store.local.value.let { store.setLocalView(it.sort, it.descending, group) }
    fun setDensity(d: GridDensity) = store.setDensity(d)

    /** How many photos [filters] would show together with the current search: the live count of the filter sheet. */
    suspend fun countFor(filters: LibraryFilters): Int = c.db.media().countOnce(LocalLibrarySql.count(store.local.value.copy(filters = filters)))

    // ------------------------------------------------------------------ selection

    fun toggle(id: Long) = selected.update { if (id in it) it - id else it + id }

    fun clearSelection() { selected.value = emptySet(); selectedSections.value = emptySet() }

    fun dismissMessage() { message.value = null }

    /** Everything the current search and filters match, not only what is loaded on screen. */
    fun selectAllMatching() {
        viewModelScope.launch { selected.value = c.db.media().idList(LocalLibrarySql.ids(store.local.value)).toSet() }
    }

    /** Selects (or, a second time, deselects) every photo under one heading. */
    fun toggleSection(sectionIndex: Int) {
        val layout = browse.value.layout
        val section = layout.sections.getOrNull(sectionIndex) ?: return
        viewModelScope.launch {
            val ids = c.db.media().idList(LocalLibrarySql.ids(store.local.value, layout.group to section.key))
            val key = section.key
            if (key in selectedSections.value) {
                selected.update { it - ids.toSet() }
                selectedSections.update { it - key }
            } else {
                selected.update { it + ids }
                selectedSections.update { it + key }
            }
        }
    }

    val sectionSelection: StateFlow<Set<Int>> get() = selectedSections

    /** The selected photos, read from the database (the grid only holds a page at a time). Reading and mapping run off the main thread. */
    suspend fun selectedItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        selected.value.toList().chunked(400).flatMap { c.db.media().getAll(it) }.map(MediaItemEntity::toDomain)
    }

    /** What the delete flow needs for the selection, built off the main thread (a "select all" can be thousands of rows). */
    suspend fun selectedDeleteEntries(): List<DeleteEntry> = withContext(Dispatchers.IO) {
        selected.value.toList().chunked(400).flatMap { c.db.media().getAll(it) }.map { it.toDomain().toDeleteEntry() }
    }

    fun backupSelected() {
        val ids = selected.value.toList()
        viewModelScope.launch {
            val n = c.backup.enqueue(ids)
            message.value = when {
                c.settings.currentS3Config() == null -> "$n en cola. Configura Amazon S3 para que se suban."
                n == 0 -> "Las fotos seleccionadas ya están respaldadas o en cola."
                else -> "$n elemento(s) en cola. El sistema decidirá cuándo subirlos según las restricciones (red, batería)."
            }
            clearSelection()
        }
    }

    fun addSelectedToAlbum(albumId: Long) {
        val ids = selected.value.toList()
        viewModelScope.launch { c.albums.add(albumId, ids); message.value = "Añadidas a un álbum."; clearSelection() }
    }

    fun createAlbumWithSelected(name: String) {
        val ids = selected.value.toList()
        viewModelScope.launch {
            val id = c.albums.create(name)
            c.albums.add(id, ids)
            message.value = "Álbum «$name» creado."
            clearSelection()
        }
    }
}

/**
 * Opens the full-screen viewer on a page-by-page window over the same query as the grid, so swiping goes through the filtered
 * and sorted list. [data] stays null until the position of the photo that was tapped is known.
 */
class LibraryViewerViewModel(private val c: AppContainer) : ViewModel() {
    data class Data(val source: ItemSource<MediaItem>, val startIndex: Int)

    private val _data = MutableStateFlow<Data?>(null)
    val data: StateFlow<Data?> = _data
    private var opened = false

    fun open(startId: Long) {
        if (opened) return
        opened = true
        viewModelScope.launch {
            val query = c.libraryStore.local.value
            val entity = c.db.media().get(startId)
            val position = entity?.let { c.db.media().countOnce(LocalLibrarySql.positionOf(query, it)) } ?: 0
            val browser = LibraryBrowser<MediaItem>(
                scope = viewModelScope, library = LibraryScope.LOCAL, queries = MutableStateFlow(query),
                observeCount = { c.db.media().observeCount(LocalLibrarySql.count(it)) },
                observeDays = { c.db.media().observeDayCounts(LocalLibrarySql.dayCounts(it)) },
                loadPage = { q, offset, limit -> c.db.media().rows(LocalLibrarySql.rows(q, limit, offset)).map(MediaItemEntity::toDomain) },
            )
            val ready = browser.state.first { it.ready }
            _data.value = Data(browser.window, position.coerceIn(0, (ready.total - 1).coerceAtLeast(0)))
        }
    }
}
