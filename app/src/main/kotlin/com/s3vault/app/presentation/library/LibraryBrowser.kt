package com.s3vault.app.presentation.library

import com.s3vault.app.data.local.DayCount
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.LibraryQuery
import com.s3vault.app.domain.library.LibraryScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the screen needs to draw one gallery: how it is divided, how many items match, and whether that is known yet. */
data class BrowseState(
    val query: LibraryQuery,
    val layout: LibraryLayout,
    /** One row per day (empty when the list is not grouped by date): drives the timeline sheet and the year chips. */
    val days: List<DayCount>,
    val total: Int,
    /** False until the first answer for the current query arrived (the screen shows a skeleton). */
    val ready: Boolean,
)

/**
 * Connects a query to a [PagedWindow]: whenever the query changes the window starts over, whenever the data changes (a photo
 * was added, deleted, backed up...) it is refreshed in place. Only counts and one row per day are observed; photos are read
 * a page at a time as the grid asks for them. The same class serves the local and the S3 gallery; each one only provides its
 * own queries.
 */
class LibraryBrowser<T : Any>(
    scope: CoroutineScope,
    private val library: LibraryScope,
    queries: StateFlow<LibraryQuery>,
    private val observeCount: (LibraryQuery) -> Flow<Int>,
    private val observeDays: (LibraryQuery) -> Flow<List<DayCount>>,
    private val loadPage: suspend (LibraryQuery, offset: Int, limit: Int) -> List<T>,
    pageSize: Int = 96,
    /** Bursts of changes (a backup flipping states) are merged into one refresh per this many milliseconds. */
    private val settleMs: Long = 150,
) {
    @Volatile private var current: LibraryQuery = queries.value

    val window: PagedWindow<T> = PagedWindow(scope, pageSize) { offset, limit -> loadPage(current, offset, limit) }

    private val _state = MutableStateFlow(BrowseState(current, LibraryLayout.flat(0), emptyList(), 0, ready = false))
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    init {
        scope.launch { queries.collectLatest { run(it) } }
    }

    private suspend fun run(q: LibraryQuery) {
        val group = q.effectiveGroup(library)
        val source: Flow<Pair<List<DayCount>, Int>> =
            if (group != GroupBy.NONE) observeDays(q).map { days -> days to days.sumOf { it.n } }
            else observeCount(q).map { emptyList<DayCount>() to it }
        var first = true
        source.conflate().collect { (days, total) ->
            if (!first) delay(settleMs)
            current = q
            val layout = LibraryLayout.from(days, group).let { if (group == GroupBy.NONE) LibraryLayout.flat(total) else it }
            if (first) window.reset(total) else { window.setSize(total); window.invalidate() }
            _state.value = BrowseState(q, layout, days, total, ready = true)
            first = false
        }
    }
}
