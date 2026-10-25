package com.s3vault.app.presentation.smart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.data.local.CloudLibrarySql
import com.s3vault.app.data.local.FolderCount
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.SmartAlbumEntity
import com.s3vault.app.data.local.SmartAlbumSql
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.library.CloudAvailability
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.LibraryFilters
import com.s3vault.app.domain.library.LibraryQuery
import com.s3vault.app.domain.library.LibraryScope
import com.s3vault.app.domain.smart.SmartAlbum
import com.s3vault.app.domain.smart.SmartAlbums
import com.s3vault.app.domain.smart.SmartFilter
import com.s3vault.app.domain.smart.SmartFilterCodec
import com.s3vault.app.domain.smart.SmartIcon
import com.s3vault.app.domain.space.SafetyRules
import com.s3vault.app.presentation.delete.toDeleteEntry
import com.s3vault.app.presentation.library.BrowseState
import com.s3vault.app.presentation.library.ItemSource
import com.s3vault.app.presentation.library.LibraryBrowser
import com.s3vault.app.presentation.library.PagedWindow
import com.s3vault.app.sync.DeleteEntry
import com.s3vault.app.sync.ListingResult
import com.s3vault.app.sync.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A smart album with the number of photos in it right now. */
data class AlbumCount(val album: SmartAlbum, val count: Int)

/** An album of S3 objects. Its numbers come from the cached listing, which may be old or incomplete: the screen says so. */
data class CloudAlbum(val id: String, val title: String, val subtitle: String, val filters: LibraryFilters, val count: Int)

data class SmartHome(
    val builtIn: List<AlbumCount> = emptyList(),
    val years: List<AlbumCount> = emptyList(),
    val custom: List<AlbumCount> = emptyList(),
    val cloud: List<CloudAlbum> = emptyList(),
    val listing: ListingResult? = null,
    val configured: Boolean = false,
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SmartAlbumsViewModel(private val c: AppContainer) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun dismissMessage() { _message.value = null }

    private val custom: Flow<List<SmartAlbum>> = c.db.smartAlbums().observeAll().map { rows -> rows.map(::customOf) }

    /** Counts of built-in and custom albums in one pass; it re-runs when any table an album reads changes (batched by Room). */
    private val counts: Flow<Pair<List<SmartAlbum>, List<Int>>> =
        combine(c.settings.spaceRules, custom) { r, cu -> r to (SmartAlbums.builtIn + cu) }.flatMapLatest { (rules, albums) ->
            c.db.invalidationTracker.createFlow("media_items", "integrity_results", "album_items").mapLatest {
                albums to withContext(Dispatchers.IO) {
                    c.db.query(SmartAlbumSql.countsOf(albums, rules, System.currentTimeMillis())).use { cur -> cur.moveToFirst(); albums.indices.map { cur.getInt(it) } }
                }
            }
        }

    private val cloud: Flow<List<CloudAlbum>> = combine(CLOUD_ALBUMS.map { def ->
        c.db.remote().observeCount(CloudLibrarySql.count(LibraryQuery(filters = def.filters))).map { n -> CloudAlbum(def.id, def.title, def.subtitle, def.filters, n) }
    }) { it.toList() }

    val home: StateFlow<SmartHome> = combine(counts, c.db.smartAlbums().observeYears(), cloud, c.settings.remoteListing, c.settings.s3Config) { (albums, n), years, cl, listing, cfg ->
        val byId = albums.indices.associate { albums[it].id to n[it] }
        SmartHome(
            builtIn = SmartAlbums.builtIn.map { AlbumCount(it, byId[it.id] ?: 0) },
            years = years.map { AlbumCount(SmartAlbums.year(it.year), it.n) },
            custom = albums.filter { it.custom }.map { AlbumCount(it, byId[it.id] ?: 0) },
            cloud = cl, listing = listing, configured = cfg != null, loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartHome())

    val folders: StateFlow<List<FolderCount>> = c.db.media().observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    val years: StateFlow<List<Int>> = c.db.smartAlbums().observeYears().map { l -> l.map { it.year } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    /** The live count of an album being edited, so the user sees what it would contain before saving it. */
    suspend fun previewCount(f: SmartFilter): Int = withContext(Dispatchers.IO) {
        c.db.media().countOnce(SmartAlbumSql.count(f, c.settings.currentSpaceRules(), System.currentTimeMillis()))
    }

    fun save(id: Long?, name: String, filter: SmartFilter) {
        viewModelScope.launch {
            val json = SmartFilterCodec.encode(filter)
            if (id == null) c.db.smartAlbums().insert(SmartAlbumEntity(name = name.trim(), filterJson = json, createdAtMs = System.currentTimeMillis()))
            else c.db.smartAlbums().update(id, name.trim(), json)
            _message.value = "Álbum «${name.trim()}» guardado. Se rellena solo con lo que cumpla las condiciones."
        }
    }

    fun delete(a: SmartAlbum) {
        val id = a.id.removePrefix("custom:").toLongOrNull() ?: return
        viewModelScope.launch { c.db.smartAlbums().delete(id); _message.value = "Álbum eliminado. No se ha tocado ninguna foto." }
    }

    /** Opens an S3 album as the S3 library with its filters applied (the S3 tab already says how old and complete its list is). */
    fun openCloud(a: CloudAlbum) { c.libraryStore.setCloudFilters(a.filters) }

    companion object {
        fun customOf(e: SmartAlbumEntity) = SmartAlbum("custom:${e.id}", e.name, "Álbum tuyo", SmartIcon.CUSTOM, SmartFilterCodec.decode(e.filterJson), custom = true)

        private val CLOUD_ALBUMS = listOf(
            CloudAlbum("cloud:only", "Solo en S3", "Sin copia en este dispositivo", LibraryFilters(availability = CloudAvailability.CLOUD_ONLY), 0),
            CloudAlbum("cloud:videos", "Vídeos en S3", "Según la extensión del archivo", LibraryFilters(kinds = setOf(MediaKind.VIDEO)), 0),
            CloudAlbum("cloud:large", "Grandes en S3", "25 MB o más", LibraryFilters(minSizeBytes = SmartAlbums.LARGE_BYTES), 0),
            CloudAlbum("cloud:undated", "Sin fecha de captura", "Se ordenan por fecha de subida", LibraryFilters(undatedOnly = true), 0),
        )
    }
}

/** What the detail of a smart album needs: its definition, the paged window over its photos, and how it is divided. */
class SmartAlbumData(val album: SmartAlbum, val window: PagedWindow<MediaItem>, val browse: StateFlow<BrowseState>, val queries: MutableStateFlow<LibraryQuery>)

@OptIn(ExperimentalCoroutinesApi::class)
class SmartAlbumDetailViewModel(private val c: AppContainer) : ViewModel() {
    private val _data = MutableStateFlow<SmartAlbumData?>(null)
    val data: StateFlow<SmartAlbumData?> = _data
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = selected
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    val uploads: StateFlow<Map<Long, Progress>> = c.progress.uploads
    private var opened: String? = null

    fun open(id: String) {
        if (opened == id) return
        opened = id
        viewModelScope.launch {
            val customs = c.db.smartAlbums().observeAll().first().map(SmartAlbumsViewModel::customOf)
            val album = SmartAlbums.find(id, customs)
            if (album == null) { _missing.value = true; return@launch }
            val rules = c.settings.currentSpaceRules()
            val initial = LibraryQuery(group = if (id.startsWith("year:")) GroupBy.MONTH else GroupBy.DAY)
            val queries = MutableStateFlow(initial)
            val browser = LibraryBrowser<MediaItem>(
                scope = viewModelScope, library = LibraryScope.LOCAL, queries = queries,
                observeCount = { c.db.media().observeSmartCount(SmartAlbumSql.count(album.filter, rules, System.currentTimeMillis())) },
                observeDays = { q -> c.db.media().observeSmartDays(SmartAlbumSql.dayCounts(album.filter, rules, System.currentTimeMillis(), q.descending)) },
                loadPage = { q, offset, limit ->
                    c.db.media().rows(SmartAlbumSql.rows(album.filter, rules, System.currentTimeMillis(), q.descending, limit, offset)).map(MediaItemEntity::toDomain)
                },
            )
            _data.value = SmartAlbumData(album, browser.window, browser.state, queries)
        }
    }

    fun setGroup(g: GroupBy) { _data.value?.queries?.update { it.copy(group = g) } }
    fun toggle(id: Long) = selected.update { if (id in it) it - id else it + id }
    fun clearSelection() { selected.value = emptySet() }
    fun dismissMessage() { _message.value = null }

    fun selectAll() {
        val d = _data.value ?: return
        viewModelScope.launch {
            val rules = c.settings.currentSpaceRules()
            selected.value = withContext(Dispatchers.IO) { c.db.media().idList(SmartAlbumSql.ids(d.album.filter, rules, System.currentTimeMillis())).toSet() }
        }
    }

    fun backupSelected() {
        val ids = selected.value.toList()
        viewModelScope.launch {
            val n = c.backup.enqueue(ids)
            _message.value = if (n == 0) "Las seleccionadas ya están respaldadas o en cola." else "$n en cola. El sistema decidirá cuándo subirlas."
            clearSelection()
        }
    }

    suspend fun selectedDeleteEntries(): List<DeleteEntry> = withContext(Dispatchers.IO) {
        selected.value.toList().chunked(400).flatMap { c.db.media().getAll(it) }.map { it.toDomain().toDeleteEntry() }
    }
}

/** Opens the viewer on a smart album, so swiping goes through that album (same pattern as the gallery's viewer). */
class SmartViewerViewModel(private val c: AppContainer) : ViewModel() {
    data class Data(val source: ItemSource<MediaItem>, val startIndex: Int)

    private val _data = MutableStateFlow<Data?>(null)
    val data: StateFlow<Data?> = _data
    private var opened = false

    fun open(albumId: String, startId: Long) {
        if (opened) return
        opened = true
        viewModelScope.launch {
            val customs = c.db.smartAlbums().observeAll().first().map(SmartAlbumsViewModel::customOf)
            val album = SmartAlbums.find(albumId, customs) ?: return@launch
            val rules = c.settings.currentSpaceRules()
            val ids = withContext(Dispatchers.IO) { c.db.media().idList(SmartAlbumSql.ids(album.filter, rules, System.currentTimeMillis())) }
            val position = ids.indexOf(startId).coerceAtLeast(0)
            val browser = LibraryBrowser<MediaItem>(
                scope = viewModelScope, library = LibraryScope.LOCAL, queries = MutableStateFlow(LibraryQuery(group = GroupBy.NONE)),
                observeCount = { c.db.media().observeSmartCount(SmartAlbumSql.count(album.filter, rules, System.currentTimeMillis())) },
                observeDays = { q -> c.db.media().observeSmartDays(SmartAlbumSql.dayCounts(album.filter, rules, System.currentTimeMillis(), q.descending)) },
                loadPage = { q, offset, limit -> c.db.media().rows(SmartAlbumSql.rows(album.filter, rules, System.currentTimeMillis(), q.descending, limit, offset)).map(MediaItemEntity::toDomain) },
            )
            val ready = browser.state.first { it.ready }
            _data.value = Data(browser.window, position.coerceIn(0, (ready.total - 1).coerceAtLeast(0)))
        }
    }
}
