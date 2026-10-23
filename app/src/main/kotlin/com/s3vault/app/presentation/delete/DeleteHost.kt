package com.s3vault.app.presentation.delete

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.sync.DeleteImpact

private fun items(n: Int) = if (n == 1) "1 elemento" else "$n elementos"

/**
 * All deletion UI in one place so every screen behaves identically: the "which copy?" sheet, one confirmation per
 * location, progress for S3 work, Android's own dialog for device files, and the result.
 *
 * Visual language: a phone icon always means "this device", a cloud icon always means "Amazon S3".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteHost(
    vm: DeleteViewModel,
    snackbar: SnackbarHostState,
    onStarted: () -> Unit = {},
    onOpenCredentials: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val started by rememberUpdatedState(onStarted)

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                DeleteEvent.Started -> started()
                is DeleteEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onSystemDialogResult(it.resultCode == Activity.RESULT_OK)
    }
    val s = state
    if (s is DeleteUiState.SystemDialog && !s.launched) {
        LaunchedEffect(s.request) {
            vm.markDialogLaunched()
            launcher.launch(IntentSenderRequest.Builder(s.request).build())
        }
    }

    when (s) {
        DeleteUiState.Idle, is DeleteUiState.SystemDialog -> Unit
        is DeleteUiState.Choose -> ChooseSheet(s.impact, vm::chooseDevice, vm::chooseCloud, vm::dismiss)
        is DeleteUiState.ConfirmDevice -> ConfirmDeviceDialog(s.impact, vm::confirmDevice, vm::dismiss)
        is DeleteUiState.ConfirmCloud -> ConfirmCloudDialog(s.impact, s.bucket, vm::confirmCloud, vm::dismiss)
        is DeleteUiState.Working -> WorkingDialog(s, vm::cancelWork)
        is DeleteUiState.CloudFailed -> FailedDialog(s, vm::retryCloud, vm::dismiss, onOpenCredentials)
    }
}

// ------------------------------------------------------------------ choose

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseSheet(impact: DeleteImpact, onDevice: () -> Unit, onCloud: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.screen).padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                "Eliminar ${items(impact.total)}", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Elige de dónde quieres eliminarlos. Se borra solo una ubicación cada vez; la otra copia no se toca.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceCard(
                icon = Icons.Filled.PhoneAndroid,
                title = "Del dispositivo",
                body = when {
                    impact.onDevice == 0 -> "Ninguno está en este dispositivo."
                    impact.inCloud > 0 && impact.deviceOnly == 0 -> "Se borran de este móvil. Siguen en S3 y podrás restaurarlos."
                    impact.deviceOnly > 0 && impact.inCloud == 0 -> "Se borran de este móvil. No tienen copia en S3."
                    else -> "Se borran de este móvil. ${impact.inCloud} siguen en S3; ${impact.deviceOnly} no tienen copia en S3."
                },
                count = impact.onDevice,
                warning = impact.deviceOnly > 0,
                enabled = impact.onDevice > 0,
                onClick = onDevice,
            )
            ChoiceCard(
                icon = Icons.Filled.Cloud,
                title = "De Amazon S3",
                body = when {
                    impact.inCloud == 0 -> "Ninguno está respaldado en S3."
                    impact.cloudOnly > 0 -> "Se borran de tu bucket. ${impact.cloudOnly} no están en este dispositivo y se perderían."
                    else -> "Se borran de tu bucket. Las copias de este móvil no se tocan."
                },
                count = impact.inCloud,
                warning = impact.cloudOnly > 0,
                enabled = impact.inCloud > 0,
                onClick = onCloud,
            )
            QuietButton("Cancelar", onDismiss, Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, body: String, count: Int, warning: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(
            Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (enabled) "$title ($count)" else title, style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
                Text(
                    body, style = MaterialTheme.typography.bodySmall,
                    color = if (warning && enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ confirmations

@Composable
private fun ConfirmDeviceDialog(impact: DeleteImpact, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val mixed = impact.deviceOnly > 0 && impact.deviceOnly < impact.onDevice
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { Icon(Icons.Filled.PhoneAndroid, null) },
        title = { Text("¿Eliminar ${items(impact.onDevice)} del dispositivo?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(
                    "Se borran de este móvil. Android te pedirá confirmarlo.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val safe = impact.onDevice - impact.deviceOnly
                if (safe > 0) {
                    Text(
                        "$safe siguen en Amazon S3: podrás restaurarlos desde la pestaña S3.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.status.success,
                    )
                }
                if (impact.deviceOnly > 0) WarningLine("${impact.deviceOnly} NO tienen copia en S3 y se perderán para siempre.")
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                QuietButton("Eliminar del dispositivo", { onConfirm(false) }, destructive = true)
                if (mixed) QuietButton("Solo las ${impact.onDevice - impact.deviceOnly} respaldadas", { onConfirm(true) })
                QuietButton("Cancelar", onDismiss)
            }
        },
    )
}

@Composable
private fun ConfirmCloudDialog(impact: DeleteImpact, bucket: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    val needsAck = impact.cloudOnly > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { Icon(Icons.Filled.Cloud, null) },
        title = { Text("¿Eliminar ${items(impact.inCloud)} de S3?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(
                    "Se borran de tu bucket" + (bucket?.let { " «$it»" } ?: "") + ". No se puede deshacer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val withLocal = impact.inCloud - impact.cloudOnly
                if (withLocal > 0) {
                    Text(
                        "Las copias de este dispositivo ($withLocal) no se tocan, pero dejarán de estar respaldadas y no se subirán de nuevo por sí solas.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.status.success,
                    )
                }
                if (needsAck) {
                    WarningLine("${impact.cloudOnly} solo existen en S3 y se perderán para siempre.")
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = understood, onCheckedChange = null)
                        Text(
                            "Entiendo que no se podrán recuperar", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = Space.m),
                        )
                    }
                }
            }
        },
        confirmButton = { QuietButton("Eliminar de S3", onConfirm, enabled = !needsAck || understood, destructive = true) },
        dismissButton = { QuietButton("Cancelar", onDismiss) },
    )
}

@Composable
private fun WarningLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.WarningAmber, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

// ------------------------------------------------------------------ progress and failures

@Composable
private fun WorkingDialog(s: DeleteUiState.Working, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(s.message, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                SlimProgress(if (s.total > 0) s.done.toFloat() / s.total else null)
                if (s.total > 0) {
                    Text(
                        "${s.done} de ${s.total}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { if (s.cancellable) QuietButton("Cancelar", onCancel) },
    )
}

@Composable
private fun FailedDialog(s: DeleteUiState.CloudFailed, onRetry: () -> Unit, onClose: () -> Unit, onCredentials: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
        title = {
            Text(
                "No se pudieron eliminar ${s.result.failed.size} de ${s.total}", style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                if (s.result.deleted.isNotEmpty()) {
                    Text(
                        "Eliminadas: ${s.result.deleted.size}. Lo que falló sigue en S3, intacto.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(s.summary, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                if (s.needsCredentials) QuietButton("Ir a credenciales", { onClose(); onCredentials() })
                QuietButton("Reintentar", onRetry)
                QuietButton("Cerrar", onClose)
            }
        },
    )
}
