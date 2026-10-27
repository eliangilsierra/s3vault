package com.s3vault.app.presentation.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.AppContainer
import com.s3vault.app.R
import com.s3vault.app.data.local.FacePlace
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone

/**
 * The questions the app could not answer by itself, kept apart from what it is sure of: faces that might be someone, groups that
 * might be one person, photos a backup rule would not decide on, and faces with no group. None of this changes anything until the
 * user answers, and a "no" is remembered.
 */
@Composable
fun ReviewScreen(vm: ReviewViewModel, container: AppContainer, onBack: () -> Unit, onOpenPerson: (Long) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var assigning by remember { mutableStateOf<FacePlace?>(null) }
    MessageEffect(message, snackbar, vm::dismissMessage)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppTopBar(stringResource(R.string.people_review_title), subtitle = stringResource(R.string.people_review_subtitle), navigation = { BackButton(onBack) }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !s.loaded -> Unit
                s.isEmpty -> EmptyState(Icons.Filled.Done, stringResource(R.string.people_review_empty_title), body = stringResource(R.string.people_review_empty_body))
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.xl),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (s.suggestions.isNotEmpty()) {
                        item(key = "sugg-title") { Section(R.string.people_review_sugg_title, R.string.people_review_sugg_body) }
                        items(s.suggestions, key = { "sg${it.personId}" }) { g ->
                            val label = g.person?.label() ?: ""
                            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenPerson(g.personId) }, verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.people_review_is_it, label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    if (g.faces.size > 1) QuietButton(stringResource(R.string.people_review_accept_all), { vm.acceptSuggestions(g.faces.map { it.faceId }) })
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                                    rowItems(g.faces, key = { it.faceId }) { f ->
                                        Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            FaceCrop(FacePlace(f.faceId, f.mediaStoreId, f.uri, f.boxLeft, f.boxTop, f.boxRight, f.boxBottom), container, 88.dp)
                                            Row {
                                                IconButton(onClick = { vm.acceptSuggestions(listOf(f.faceId)) }) { Icon(Icons.Filled.Check, stringResource(R.string.people_review_yes_face, label), tint = MaterialTheme.colorScheme.primary) }
                                                IconButton(onClick = { vm.rejectSuggestions(listOf(f.faceId)) }) { Icon(Icons.Filled.Close, stringResource(R.string.people_review_no_face, label), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (s.merges.isNotEmpty()) {
                        item(key = "merge-title") { Section(R.string.people_review_merge_title, R.string.people_review_merge_body) }
                        items(s.merges, key = { "mg${it.a.id}-${it.b.id}" }) { m ->
                            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                                    FaceCrop(m.coverA, container, 64.dp)
                                    Icon(Icons.Filled.CallMerge, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    FaceCrop(m.coverB, container, 64.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(m.a.label(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(m.b.label(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                    PrimaryButton(stringResource(R.string.people_review_merge_yes), { vm.merge(m.a.id, m.b.id) }, Modifier.weight(1f))
                                    SecondaryButton(stringResource(R.string.people_review_merge_no), { vm.keepApart(m.a.id, m.b.id) }, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    if (s.photos.isNotEmpty()) {
                        item(key = "photos-title") { Section(R.string.people_review_photos_title, R.string.people_review_photos_body) }
                        items(s.photos, key = { "ph${it.personId}" }) { g ->
                            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                Text(stringResource(R.string.people_review_photos_of, g.person?.label() ?: ""), style = MaterialTheme.typography.titleSmall)
                                val items: List<MediaItem> = g.photos.map { it.media.toDomain() }
                                ReviewStrip(
                                    items, onApprove = { vm.approvePhotos(g.personId, listOf(it)) }, onDismiss = { vm.dismissPhotos(g.personId, listOf(it)) },
                                    reasons = g.photos.associate { it.media.mediaStoreId to it.reason },
                                )
                            }
                        }
                    }
                    if (s.unassigned.isNotEmpty()) {
                        item(key = "un-title") { Section(R.string.people_review_alone_title, R.string.people_review_alone_body) }
                        item(key = "un-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                                rowItems(s.unassigned, key = { it.faceId }) { f ->
                                    Box(
                                        Modifier.heightIn(min = Space.touch).clickable(role = Role.Button, onClickLabel = stringResource(R.string.people_review_assign)) { assigning = f },
                                    ) { FaceCrop(f, container, 72.dp, description = stringResource(R.string.people_review_alone_face)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    assigning?.let { face ->
        PersonPicker(
            title = stringResource(R.string.people_review_assign_title), people = s.people, container = container, allowNew = true,
            onPick = { vm.assign(face.faceId, it.id); assigning = null }, onNew = { vm.assign(face.faceId, null); assigning = null }, onDismiss = { assigning = null },
        )
    }
}

@Composable
private fun Section(title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
