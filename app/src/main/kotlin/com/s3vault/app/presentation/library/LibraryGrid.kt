package com.s3vault.app.presentation.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.skeleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val CellGap = 2.dp

/**
 * The lazy grid of a gallery over an [ItemSource]. Only the cells on screen exist; as the visible range changes the source is
 * asked for the matching pages, and a cell whose page has not arrived yet draws a skeleton (never a blank hole).
 * [header] draws the heading of a section, [cell] one photo.
 */
@Composable
fun <T : Any> LibraryGrid(
    layout: LibraryLayout,
    source: ItemSource<T>,
    minCell: Dp,
    state: LazyGridState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = Space.xl),
    header: @Composable (section: Int) -> Unit,
    cell: @Composable (index: Int, item: T?) -> Unit,
) {
    Box(modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minCell),
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(CellGap),
            verticalArrangement = Arrangement.spacedBy(CellGap),
        ) {
            items(
                count = layout.entryCount,
                span = { e -> GridItemSpan(if (layout.isHeader(e)) maxLineSpan else 1) },
                contentType = { e -> if (layout.isHeader(e)) 0 else 1 },
            ) { e ->
                val index = layout.itemIndexOf(e)
                if (index < 0) header(layout.sectionOfEntry(e)) else cell(index, source[index])
            }
        }
        LaunchedEffect(state, layout, source) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo }
                .map { v -> if (v.isEmpty()) null else v.first().index to v.last().index }
                .distinctUntilChanged()
                .collect { r -> if (r != null) source.ensure(layout.itemRange(r.first, r.second)) }
        }
        if (layout.hasHeaders && layout.entryCount > 80) {
            TimelineScroller(state, layout, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** Placeholder cell shaped like a photo, shown while its page loads. */
@Composable
fun CellSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1f).skeleton())
}

/**
 * Heading of a section: its name, how many photos it holds and, while selecting, a control to select them all. [note] explains
 * sections that are not what they seem (the "no capture date" one).
 */
@Composable
fun SectionHeading(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    note: String? = null,
    selectionMode: Boolean = false,
    sectionSelected: Boolean = false,
    onToggleSection: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = Space.screen, end = Space.xs, top = Space.l, bottom = Space.xs)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (count == 1) "1 elemento" else "$count elementos",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.s),
        )
        if (selectionMode && onToggleSection != null) {
            Box(
                Modifier.size(Space.touch).clip(CircleShape)
                    .clickable(role = Role.Checkbox, onClickLabel = if (sectionSelected) "Quitar de la selección" else "Seleccionar todo", onClick = onToggleSection)
                    .semantics { contentDescription = "$label: seleccionar todo"; stateDescription = if (sectionSelected) "Seleccionado" else "No seleccionado" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (sectionSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                    tint = if (sectionSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Fast scroller along the right edge: it appears while the list moves, can be dragged to travel years in a gesture, and shows
 * the date of the heading it is over. It only exists when the list has date headings.
 */
@Composable
fun TimelineScroller(state: LazyGridState, layout: LibraryLayout, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackPx by remember { mutableIntStateOf(1) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true else { delay(1_400); visible = false }
    }
    val total = layout.entryCount
    val span = (total - 1).coerceAtLeast(1)
    val fraction = if (dragging) dragFraction else (state.firstVisibleItemIndex.toFloat() / span).coerceIn(0f, 1f)
    val thumb = 48.dp

    AnimatedVisibility(visible, modifier.fillMaxHeight().width(48.dp), enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().onSizeChanged { trackPx = it.height.coerceAtLeast(1) }.pointerInput(total) {
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        dragFraction = (o.y / trackPx).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((dragFraction * (total - 1)).toInt()) }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ ->
                        dragFraction = (change.position.y / trackPx).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((dragFraction * (total - 1)).toInt()) }
                        change.consume()
                    },
                )
            },
        ) {
            val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { thumb.roundToPx() }
            Box(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, ((trackPx - thumbPx) * fraction).toInt()) }
                    .padding(end = 6.dp, top = 4.dp, bottom = 4.dp).width(6.dp).height(thumb - 8.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
            )
            if (dragging) {
                val label = layout.labelAt((fraction * (total - 1)).toInt())
                if (label.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(-56, ((trackPx - thumbPx) * fraction).toInt()) },
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shadowElevation = 4.dp,
                    ) { Text(label, Modifier.padding(horizontal = Space.m, vertical = Space.s).widthIn(max = 200.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                }
            }
        }
    }
}
