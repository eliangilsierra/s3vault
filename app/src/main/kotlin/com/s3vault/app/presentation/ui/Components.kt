package com.s3vault.app.presentation.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status

/*
 * The small, deliberate component set shared by every screen. Screens compose these instead of styling raw
 * Material widgets, so spacing, shape, type and tone stay consistent.
 */

/**
 * The reduced-motion flag read once at the top of the app. Every grid cell asks for it, and a cell is composed again each time
 * it scrolls into view: reading the system setting per cell put a settings lookup on the scroll path.
 */
val LocalReducedMotion = androidx.compose.runtime.compositionLocalOf<Boolean?> { null }

/** True when the user turned system animations off; decorative motion must then be skipped. */
@Composable
fun rememberReducedMotion(): Boolean {
    val provided = LocalReducedMotion.current
    val ctx = LocalContext.current
    return provided ?: remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Semantic meaning of a message. Always rendered with an icon and words, never with colour alone. */
enum class Tone { Neutral, Info, Success, Warning, Error }

private data class ToneStyle(val container: Color, val onContainer: Color, val accent: Color, val icon: ImageVector?)

@Composable
private fun Tone.style(): ToneStyle = when (this) {
    Tone.Neutral -> ToneStyle(
        MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.onSurfaceVariant, null,
    )
    Tone.Info -> ToneStyle(
        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.primary, Icons.Filled.Info,
    )
    Tone.Success -> ToneStyle(
        MaterialTheme.status.successContainer, MaterialTheme.status.onSuccessContainer,
        MaterialTheme.status.success, Icons.Filled.CheckCircle,
    )
    Tone.Warning -> ToneStyle(
        MaterialTheme.status.warningContainer, MaterialTheme.status.onWarningContainer,
        MaterialTheme.status.warning, Icons.Filled.WarningAmber,
    )
    Tone.Error -> ToneStyle(
        MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
        MaterialTheme.colorScheme.error, Icons.Filled.ErrorOutline,
    )
}

/** Accent colour of a tone, for icons and small status text on the plain background. */
@Composable
fun Tone.accent(): Color = style().accent

// ------------------------------------------------------------------ Top bars

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.background,
    /** A smaller title for bars that carry many actions (selection mode), so a title like "3 seleccionadas" is not cut to "3". */
    compact: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                // One line, ellipsis at the end: a long title never wraps and loses its second line.
                Text(
                    title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                )
                if (subtitle != null) {
                    Text(
                        subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = { navigation?.invoke() },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
}

// ------------------------------------------------------------------ Buttons

private val ButtonMinHeight = 52.dp

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.heightIn(min = ButtonMinHeight),
    ) { ButtonContent(text, icon, loading) }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    loading: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.heightIn(min = ButtonMinHeight),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { ButtonContent(text, icon, loading) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false) {
    TextButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Space.touch),
        colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
    ) { Text(text) }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, loading: Boolean) {
    if (loading) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColorOrDefault())
        Spacer(Modifier.width(Space.s))
    } else if (icon != null) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(Space.s))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun LocalContentColorOrDefault(): Color = androidx.compose.material3.LocalContentColor.current

// ------------------------------------------------------------------ Messages & states

/**
 * Full-area state: what happened (title), what it means (body), what to do next (actions).
 * Used for empty, permission, error and not-configured states so they all share one layout.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    tone: Tone = Tone.Neutral,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val style = tone.style()
    Box(modifier.fillMaxSize().padding(horizontal = Space.xl), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(if (tone == Tone.Neutral) MaterialTheme.colorScheme.surfaceContainer else style.container),
                contentAlignment = Alignment.Center,
            ) {
                // Decorative: the title below carries the meaning.
                Icon(icon, null, Modifier.size(32.dp), tint = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurfaceVariant else style.accent)
            }
            Spacer(Modifier.height(Space.s))
            Text(
                title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(Space.s))
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 220.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) { actions() }
        }
    }
}

/** Inline message that sits above content (blocking condition, warning, confirmation). */
@Composable
fun StatusBanner(
    tone: Tone,
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val style = tone.style()
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = style.container, contentColor = style.onContainer) {
        Row(Modifier.padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            style.icon?.let { Icon(it, null, Modifier.size(22.dp), tint = style.accent) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    TextButton(
                        onClick = onAction,
                        modifier = Modifier.heightIn(min = Space.touch),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = Space.s),
                        colors = ButtonDefaults.textButtonColors(contentColor = style.onContainer),
                    ) { Text(actionLabel, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Space.l, bottom = Space.xs).semantics { heading() },
    )
}

// ------------------------------------------------------------------ Progress

/** Slim determinate bar, or indeterminate when [fraction] is null. Use only with real progress values. */
@Composable
fun SlimProgress(fraction: Float?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val shape = CircleShape
    val common = modifier.fillMaxWidth().height(6.dp).clip(shape)
    if (fraction == null) {
        LinearProgressIndicator(modifier = common, color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, strokeCap = StrokeCap.Round)
    } else {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) }, modifier = common, color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
        )
    }
}

/** Quiet pulsing placeholder; static when the user disabled animations. */
@Composable
fun Modifier.skeleton(): Modifier {
    val reduced = rememberReducedMotion()
    val alpha = if (reduced) 1f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha").value
    }
    return this.alpha(alpha).background(MaterialTheme.colorScheme.surfaceContainerHigh)
}

// ------------------------------------------------------------------ Settings rows

/** Rounded group of related rows on a slightly raised surface. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(content = content)
    }
}

@Composable
fun GroupDivider(inset: androidx.compose.ui.unit.Dp = 56.dp) = HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = if (onClick != null) modifier.clickable(role = Role.Button, onClick = onClick) else modifier
    Row(
        base.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Single-choice chip. The selected one carries a check mark and the accent container, so it never relies on colour alone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceChip(selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
        shape = MaterialTheme.shapes.medium,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/** Label above a value, for metadata and technical details. */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

// ------------------------------------------------------------------ Dialogs

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancelar",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { QuietButton(confirmLabel, onConfirm, destructive = destructive) },
        dismissButton = { QuietButton(dismissLabel, onDismiss) },
    )
}

/** Convenience for a clickable region with a spoken description (e.g. a custom control built from icons). */
fun Modifier.described(description: String): Modifier = semantics { contentDescription = description }
