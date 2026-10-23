package com.s3vault.app.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.SyncState
import com.s3vault.app.presentation.theme.PhotoOverlay
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.ui.rememberReducedMotion
import com.s3vault.app.presentation.ui.skeleton
import com.s3vault.app.sync.Progress
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** The interface is Spanish-only, so dates are too (a mixed-language screen reads as a bug). */
private val UiLocale: Locale = Locale.forLanguageTag("es")

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, UiLocale).format(Date(ms))

fun formatDay(ms: Long): String =
    DateTimeFormatter.ofPattern("EEEE d MMM yyyy", UiLocale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

fun syncStateLabel(s: SyncState): String = when (s) {
    SyncState.LOCAL_ONLY -> "Solo en el dispositivo"
    SyncState.QUEUED -> "En cola"
    SyncState.UPLOADING -> "Subiendo"
    SyncState.VERIFYING -> "Verificando"
    SyncState.BACKED_UP -> "Respaldada"
    SyncState.FAILED -> "Error"
    SyncState.REMOTE_ONLY -> "Solo en S3"
    SyncState.RESTORING -> "Restaurando"
}

/** Icon + colour for each sync state. [onScrim] picks light foregrounds for use on top of a photograph. */
@Composable
fun SyncBadge(state: SyncState, modifier: Modifier = Modifier, onScrim: Boolean = false, decorative: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (state) {
        SyncState.BACKED_UP -> Icons.Filled.CloudDone to if (onScrim) PhotoOverlay.successOnScrim else MaterialTheme.status.success
        SyncState.QUEUED -> Icons.Filled.CloudQueue to if (onScrim) PhotoOverlay.onScrim else scheme.primary
        SyncState.UPLOADING, SyncState.VERIFYING, SyncState.RESTORING ->
            Icons.Filled.CloudSync to if (onScrim) PhotoOverlay.onScrim else scheme.primary
        SyncState.FAILED -> Icons.Filled.ErrorOutline to if (onScrim) PhotoOverlay.errorOnScrim else scheme.error
        SyncState.REMOTE_ONLY -> Icons.Filled.Cloud to if (onScrim) PhotoOverlay.onScrim else scheme.onSurfaceVariant
        SyncState.LOCAL_ONLY -> Icons.Filled.CloudOff to if (onScrim) PhotoOverlay.onScrim else scheme.outline
    }
    Icon(icon, contentDescription = if (decorative) null else syncStateLabel(state), tint = tint, modifier = modifier)
}

/** Icon + word chip, so status is never conveyed by colour alone. [onPhoto] uses a translucent chip for dark surfaces. */
@Composable
fun SyncPill(state: SyncState, modifier: Modifier = Modifier, onPhoto: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = if (onPhoto) {
        PhotoOverlay.chip to PhotoOverlay.onScrim
    } else when (state) {
        SyncState.BACKED_UP -> MaterialTheme.status.successContainer to MaterialTheme.status.onSuccessContainer
        SyncState.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        SyncState.QUEUED, SyncState.UPLOADING, SyncState.VERIFYING, SyncState.RESTORING -> scheme.primaryContainer to scheme.onPrimaryContainer
        SyncState.REMOTE_ONLY, SyncState.LOCAL_ONLY -> scheme.surfaceContainerHigh to scheme.onSurface
    }
    Row(
        modifier.clip(CircleShape).background(container).padding(horizontal = Space.m, vertical = Space.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp),
    ) {
        SyncBadge(state, Modifier.size(16.dp), onScrim = onPhoto, decorative = true)
        Text(syncStateLabel(state), style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** Quiet centred progress for the few places that load without a known layout. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

private val MinCell = 112.dp
private val CellGap = 2.dp

/** Placeholder shaped like the real grid, so nothing jumps when the library arrives. */
@Composable
fun GridSkeleton(modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(MinCell), modifier = modifier.fillMaxSize(), userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(CellGap), verticalArrangement = Arrangement.spacedBy(CellGap),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
                Modifier.padding(start = Space.screen, top = Space.l, bottom = Space.s).size(width = 140.dp, height = 14.dp)
                    .clip(MaterialTheme.shapes.extraSmall).skeleton(),
            )
        }
        items(48) { Box(Modifier.aspectRatio(1f).skeleton()) }
    }
}

private sealed interface GridEntry {
    val key: Any

    data class Header(override val key: String, val day: String, val count: Int) : GridEntry
    data class Cell(val item: MediaItem) : GridEntry {
        override val key: Any get() = item.mediaStoreId
    }
}

/** Builds headers and cells once per list change, not on every recomposition. */
private fun buildEntries(items: List<MediaItem>, grouped: Boolean): List<GridEntry> {
    if (!grouped) return items.map { GridEntry.Cell(it) }
    val out = ArrayList<GridEntry>(items.size + 32)
    var i = 0
    while (i < items.size) {
        val day = formatDay(items[i].dateTakenMs)
        var j = i
        while (j < items.size && formatDay(items[j].dateTakenMs) == day) j++
        out += GridEntry.Header("h-$day-${items[i].mediaStoreId}", day.replaceFirstChar { it.uppercase() }, j - i)
        for (k in i until j) out += GridEntry.Cell(items[k])
        i = j
    }
    return out
}

/**
 * Photo grid with on-demand thumbnails: Coil decodes at the cell size (never the full-resolution original) and only
 * for visible cells. Square, centre-cropped cells with hairline gaps keep rows perfectly aligned; items are grouped
 * under quiet day headers.
 */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    selected: Set<Long>,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
    uploads: Map<Long, Progress> = emptyMap(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyGridState = rememberLazyGridState(),
    grouped: Boolean = true,
) {
    val entries = remember(items, grouped) { buildEntries(items, grouped) }
    val selectionMode = selected.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(MinCell),
        modifier = modifier.fillMaxSize(),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(CellGap),
        verticalArrangement = Arrangement.spacedBy(CellGap),
    ) {
        items(
            entries,
            key = { it.key },
            span = { e -> if (e is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { if (it is GridEntry.Header) "header" else "cell" },
        ) { entry ->
            when (entry) {
                is GridEntry.Header -> DayHeader(entry.day, entry.count)
                is GridEntry.Cell -> MediaCell(
                    entry.item, entry.item.mediaStoreId in selected, selectionMode, uploads[entry.item.mediaStoreId], onClick, onLongClick,
                )
            }
        }
    }
}

@Composable
private fun DayHeader(day: String, count: Int) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = Space.screen, end = Space.screen, top = Space.l + Space.xs, bottom = Space.s)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(day, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
        Text(
            if (count == 1) "1 elemento" else "$count elementos",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Space.m),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaCell(
    item: MediaItem,
    selected: Boolean,
    selectionMode: Boolean,
    progress: Progress?,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
) {
    val ctx = LocalContext.current
    val reduced = rememberReducedMotion()
    val ms = if (reduced) 0 else 160
    // Selected photos shrink slightly into a tinted mat. graphicsLayer keeps this off the layout pass, so the
    // thumbnail is never re-decoded or re-measured while the state changes.
    val scale by animateFloatAsState(if (selected) 0.84f else 1f, tween(ms), label = "cellScale")
    val corner by animateDpAsState(if (selected) 12.dp else 0.dp, tween(ms), label = "cellCorner")
    val mat by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(ms), label = "cellMat",
    )
    val description = buildString {
        append(item.displayName)
        if (item.kind == MediaKind.VIDEO) append(", vídeo")
        append(", ").append(syncStateLabel(item.syncState))
    }
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Box(
        Modifier
            .aspectRatio(1f)
            .background(mat)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            }
            .combinedClickable(
                onClickLabel = if (selectionMode) "Alternar selección" else "Abrir",
                onClick = { onClick(item) },
                onLongClickLabel = "Seleccionar",
                onLongClick = { onLongClick(item) },
            ),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(item.uri).crossfade(!reduced).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                shape = RoundedCornerShape(corner)
                clip = true
            },
        )
        if (item.kind == MediaKind.VIDEO) {
            Box(
                Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape).background(PhotoOverlay.scrim),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, null, tint = PhotoOverlay.onScrim, modifier = Modifier.size(24.dp)) }
        }
        if (item.syncState != SyncState.LOCAL_ONLY && !selected) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(Space.xs + 2.dp).size(20.dp).clip(CircleShape).background(PhotoOverlay.scrim),
                contentAlignment = Alignment.Center,
            ) { SyncBadge(item.syncState, Modifier.size(14.dp), onScrim = true, decorative = true) }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = PhotoOverlay.onScrim, trackColor = PhotoOverlay.scrim,
            )
        }
        AnimatedVisibility(
            visible = selectionMode, enter = fadeIn(tween(ms)), exit = fadeOut(tween(ms)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Box(
                Modifier.padding(Space.s).size(22.dp).clip(CircleShape)
                    .background(if (selected) primary else PhotoOverlay.scrim)
                    .border(1.5.dp, if (selected) primary else PhotoOverlay.onScrim, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Filled.Check, null, tint = onPrimary, modifier = Modifier.size(14.dp)) }
        }
    }
}

fun shareMedia(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList<Uri>(items.map { Uri.parse(it.uri) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = items.first().mimeType
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (items.all { it.kind == MediaKind.IMAGE }) "image/*" else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val clip = android.content.ClipData.newRawUri(null, uris.first())
    uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
    intent.clipData = clip
    context.startActivity(Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openVideo(context: Context, item: MediaItem) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(item.uri), item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
