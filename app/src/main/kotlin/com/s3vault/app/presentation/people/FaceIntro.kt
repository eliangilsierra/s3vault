package com.s3vault.app.presentation.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.s3vault.app.R
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton

/**
 * What the face analysis does, where its data lives, how it is erased and how much to trust it, in plain words. It is shown before
 * the analysis is first turned on (so the user's yes is informed) and stays reachable from the People screen and its settings.
 */
@Composable
fun FacePrivacyExplainer(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Point(Icons.Filled.Face, R.string.people_intro_what_title, R.string.people_intro_what_body)
        Point(Icons.Filled.Smartphone, R.string.people_intro_where_title, R.string.people_intro_where_body)
        Point(Icons.Filled.CloudOff, R.string.people_intro_s3_title, R.string.people_intro_s3_body)
        Point(Icons.Filled.Lock, R.string.people_intro_stored_title, R.string.people_intro_stored_body)
        Point(Icons.Filled.DeleteSweep, R.string.people_intro_delete_title, R.string.people_intro_delete_body)
        Point(Icons.Filled.Rule, R.string.people_intro_limits_title, R.string.people_intro_limits_body)
        Point(Icons.Filled.Gavel, R.string.people_intro_duty_title, R.string.people_intro_duty_body)
    }
}

@Composable
private fun Point(icon: ImageVector, title: Int, body: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(Space.m))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** First-use explanation. "Activar" is the user's explicit choice; closing the dialog changes nothing. */
@Composable
fun FaceIntroDialog(enabled: Boolean, onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.people_intro_title), style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { FacePrivacyExplainer() } },
        confirmButton = {
            if (enabled) QuietButton(stringResource(R.string.people_close), onDismiss)
            else PrimaryButton(stringResource(R.string.people_intro_accept), onAccept)
        },
        dismissButton = { if (!enabled) QuietButton(stringResource(R.string.people_intro_later), onDismiss) },
    )
}
