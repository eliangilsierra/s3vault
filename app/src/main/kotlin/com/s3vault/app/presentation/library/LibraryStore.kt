package com.s3vault.app.presentation.library

import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.LibraryFilters
import com.s3vault.app.domain.library.LibraryQuery
import com.s3vault.app.domain.library.SortField
import com.s3vault.app.settings.AppSettings
import com.s3vault.app.settings.CloudViewMode
import com.s3vault.app.settings.GridDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The current query of each gallery, shared by the screens that show it. The grid and the full-screen viewer both read it,
 * so the viewer swipes through exactly the filtered, sorted list the user was looking at. Sorting, grouping, density and
 * view mode are remembered between sessions; searches and filters deliberately start clean.
 */
class LibraryStore(private val settings: AppSettings) {
    val local = MutableStateFlow(LibraryQuery())
    val cloud = MutableStateFlow(LibraryQuery())
    val density = MutableStateFlow(GridDensity.MEDIUM)
    val cloudMode = MutableStateFlow(CloudViewMode.GRID)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loaded = false

    /** Applies the remembered sort/group/density once per process. */
    suspend fun load() = mutex.withLock {
        if (loaded) return
        val p = settings.libraryPrefs.first()
        local.update { it.copy(sort = p.localSort, descending = p.localDescending, group = p.localGroup) }
        cloud.update { it.copy(sort = p.cloudSort, descending = p.cloudDescending, group = p.cloudGroup) }
        density.value = p.density
        cloudMode.value = p.cloudMode
        loaded = true
    }

    fun setLocalView(sort: SortField, descending: Boolean, group: GroupBy) {
        local.update { it.copy(sort = sort, descending = descending, group = group) }
        scope.launch { settings.saveLocalView(sort, descending, group) }
    }

    fun setCloudView(sort: SortField, descending: Boolean, group: GroupBy) {
        cloud.update { it.copy(sort = sort, descending = descending, group = group) }
        scope.launch { settings.saveCloudView(sort, descending, group) }
    }

    fun setDensity(d: GridDensity) { density.value = d; scope.launch { settings.saveDensity(d) } }
    fun setCloudMode(m: CloudViewMode) { cloudMode.value = m; scope.launch { settings.saveCloudMode(m) } }

    fun setLocalFilters(f: LibraryFilters) = local.update { it.copy(filters = f) }
    fun setCloudFilters(f: LibraryFilters) = cloud.update { it.copy(filters = f) }
}
