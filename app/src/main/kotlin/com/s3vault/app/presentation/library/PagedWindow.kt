package com.s3vault.app.presentation.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A list the screens can read by position without owning all of it. */
interface ItemSource<T> {
    val size: Int
    operator fun get(index: Int): T?

    /** Asks for the items in [range] (and their neighbours) to be loaded. Cheap to call on every scroll frame. */
    fun ensure(range: IntRange)
}

/** A plain list as an [ItemSource], for the small lists (albums) that need no paging. */
class ListSource<T>(private val items: List<T>) : ItemSource<T> {
    override val size: Int get() = items.size
    override fun get(index: Int): T? = items.getOrNull(index)
    override fun ensure(range: IntRange) = Unit
}

/**
 * Fixed-size pages of a large, ordered result set, loaded on demand around what is on screen.
 *
 * - Memory is bounded: at most [maxPages] pages stay loaded (the ones nearest to the last request).
 * - Cells read [get]; a missing page returns null (the screen draws a placeholder) and schedules its load. Pages live in
 *   snapshot state, so a cell recomposes by itself when its page arrives.
 * - When the data changes ([invalidate]) the loaded pages are re-read in place: the old content stays on screen until the new
 *   one replaces it, so a backup updating badges never makes the grid blink.
 * - A new query ([reset]) drops everything.
 *
 * Call everything from the main thread; [loader] does its own off-main work (Room suspend queries do).
 */
class PagedWindow<T : Any>(
    private val scope: CoroutineScope,
    private val pageSize: Int = 96,
    private val maxPages: Int = 20,
    private val loader: suspend (offset: Int, limit: Int) -> List<T>,
) : ItemSource<T> {
    private val pages = mutableStateMapOf<Int, List<T>>()
    private val loading = HashSet<Int>()
    private var generation = 0
    private var center = 0
    private var sizeState by mutableIntStateOf(0)

    override val size: Int get() = sizeState

    /** Number of pages currently held (for tests and diagnostics). */
    val loadedPages: Int get() = pages.size

    override fun get(index: Int): T? = if (index < 0) null else pages[index / pageSize]?.getOrNull(index % pageSize)

    fun setSize(n: Int) { sizeState = n.coerceAtLeast(0) }

    override fun ensure(range: IntRange) {
        if (sizeState == 0 || range.isEmpty()) return
        val first = (range.first.coerceAtLeast(0) / pageSize)
        val last = (range.last.coerceAtMost(sizeState - 1) / pageSize)
        center = (first + last) / 2
        // One page of look-ahead on each side keeps a normal fling from ever showing placeholders.
        for (p in (first - 1).coerceAtLeast(0)..(last + 1).coerceAtMost((sizeState - 1) / pageSize)) {
            if (p !in pages && p !in loading) load(p, generation)
        }
    }

    /**
     * The result set changed (rows added, removed or updated): re-read the pages around what is on screen and keep showing
     * them meanwhile. Pages further away are dropped instead of re-read (they load again if the user ever scrolls there):
     * a backup flipping badges many times a second used to re-read up to [maxPages] pages each time.
     */
    fun invalidate() {
        generation++
        // A page that was still being read when the data changed holds a stale answer that will be discarded: it must be asked
        // for again here, because nothing else will (the grid only asks when its visible range or layout changes).
        val wanted = HashSet<Int>(pages.keys).also { it += loading }
        loading.clear()
        for (p in wanted) {
            if (abs(p - center) <= REFRESH_RADIUS) load(p, generation) else pages.remove(p)
        }
    }

    /** A different query: nothing loaded is valid any more. */
    fun reset(newSize: Int) {
        generation++
        loading.clear()
        pages.clear()
        setSize(newSize)
    }

    private fun load(page: Int, gen: Int) {
        loading += page
        scope.launch {
            try {
                val data = loader(page * pageSize, pageSize)
                if (gen == generation) {
                    pages[page] = data
                    evict()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed page stays missing and is requested again on the next scroll; the grid keeps its placeholder.
            } finally {
                if (gen == generation) loading -= page
            }
        }
    }

    private companion object {
        /** Pages on each side of the visible ones that are re-read in place when the data changes. */
        const val REFRESH_RADIUS = 2
    }

    private fun evict() {
        while (pages.size > maxPages) {
            val farthest = pages.keys.maxByOrNull { abs(it - center) } ?: return
            pages.remove(farthest)
        }
    }
}
