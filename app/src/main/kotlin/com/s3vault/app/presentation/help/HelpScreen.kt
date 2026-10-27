package com.s3vault.app.presentation.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.s3vault.app.R
import com.s3vault.app.presentation.onboarding.Links
import com.s3vault.app.presentation.onboarding.rememberLinkOpener
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SettingsGroup
import com.s3vault.app.presentation.ui.SettingsRow

private class Topic(val id: Int, val title: Int, val body: Int, val links: List<Pair<Int, String>> = emptyList())

private val AllTopics = listOf(
    Topic(0, R.string.help_what_title, R.string.help_what_body),
    Topic(1, R.string.help_features_title, R.string.help_features_body),
    Topic(2, R.string.help_setup_title, R.string.help_setup_body, listOf(
        R.string.help_link_aws_bucket to Links.AWS_CREATE_BUCKET,
        R.string.help_link_aws_iam to Links.AWS_CREATE_IAM_USER,
        R.string.help_link_iam_doc to Links.IAM_POLICY_DOC,
    )),
    Topic(3, R.string.help_backup_title, R.string.help_backup_body),
    Topic(4, R.string.help_restore_title, R.string.help_restore_body),
    Topic(5, R.string.help_delete_title, R.string.help_delete_body),
    Topic(6, R.string.help_creds_title, R.string.help_creds_body),
    Topic(7, R.string.help_cost_title, R.string.help_cost_body, listOf(R.string.help_link_aws_pricing to Links.AWS_S3_PRICING)),
    Topic(8, R.string.help_private_title, R.string.help_private_body),
    Topic(10, R.string.help_people_title, R.string.help_people_body),
    Topic(9, R.string.help_trouble_title, R.string.help_trouble_body),
)

/** The People topic is left out of a build made without the feature (`-PwithFaces=false`). */
private val Topics get() = AllTopics.filter { it.id != 10 || com.s3vault.app.data.faces.FaceFeature.available }

/** Help that stays available after the first-run tour: the same facts, by topic, plus the version and where to report problems. */
@Composable
fun HelpScreen(versionName: String, onBack: () -> Unit, onReplayTour: () -> Unit) {
    val open = rememberLinkOpener()
    var expanded by rememberSaveable(stateSaver = listSaver<Set<Int>, Int>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet<Int>()) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.help_title), subtitle = stringResource(R.string.help_subtitle), navigation = { BackButton(onBack) }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = Space.maxContent).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = Space.xxl),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                SettingsGroup(Modifier.padding(top = Space.s)) {
                    SettingsRow(
                        title = stringResource(R.string.help_replay_title), subtitle = stringResource(R.string.help_replay_subtitle),
                        icon = Icons.Filled.School, onClick = onReplayTour,
                    )
                }
                SectionHeader(stringResource(R.string.help_topics_title))
                Topics.forEach { t ->
                    TopicCard(t, t.id in expanded, onToggle = { expanded = if (t.id in expanded) expanded - t.id else expanded + t.id }, open = open)
                }
                SectionHeader(stringResource(R.string.help_about_title))
                SettingsGroup {
                    SettingsRow(title = stringResource(R.string.help_about_version, versionName))
                    SettingsRow(
                        title = stringResource(R.string.help_about_issues), subtitle = stringResource(R.string.help_about_issues_sub),
                        icon = Icons.AutoMirrored.Filled.OpenInNew, onClick = { open(Links.ISSUES) },
                    )
                }
                Text(
                    stringResource(R.string.help_about_privacy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s),
                )
            }
        }
    }
}

@Composable
private fun TopicCard(topic: Topic, expanded: Boolean, onToggle: () -> Unit, open: (String) -> Unit) {
    val title = stringResource(topic.title)
    val stateText = stringResource(if (expanded) R.string.help_collapse else R.string.help_expand)
    val stateDescription = stringResource(if (expanded) R.string.help_state_expanded else R.string.help_state_collapsed)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClickLabel = stateText, onClick = onToggle)
                    .semantics { this.stateDescription = stateDescription }
                    .padding(horizontal = Space.l, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Column(Modifier.padding(start = Space.l, end = Space.l, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(stringResource(topic.body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // The text button has its own side padding: shifted back so the link lines up with the paragraph above it.
                    topic.links.forEach { (label, url) -> QuietButton(stringResource(label), { open(url) }, Modifier.offset(x = (-12).dp)) }
                }
            }
        }
    }
}
