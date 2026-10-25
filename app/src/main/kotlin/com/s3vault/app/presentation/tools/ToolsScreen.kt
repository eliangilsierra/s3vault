package com.s3vault.app.presentation.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.GroupDivider
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SettingsGroup
import com.s3vault.app.presentation.ui.SettingsRow

/** One entry of the tools hub. Order and grouping are the screen's, the destinations are the navigation's. */
data class Tool(val id: String, val title: String, val subtitle: String, val icon: ImageVector, val group: String)

/**
 * The single door to the library tools, so the five main tabs stay five. Every tool says in one line what it does and what it
 * touches (this device, Amazon S3, or only local data).
 */
@Composable
fun ToolsScreen(tools: List<Tool>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar("Herramientas", subtitle = "Ordenar, comprobar y proteger tu biblioteca", navigation = { BackButton(onBack) }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = Space.maxContent).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.screen).padding(bottom = Space.xxl),
            ) {
                tools.groupBy { it.group }.forEach { (group, items) ->
                    SectionHeader(group)
                    SettingsGroup {
                        items.forEachIndexed { i, t ->
                            if (i > 0) GroupDivider()
                            SettingsRow(title = t.title, subtitle = t.subtitle, icon = t.icon, onClick = { onOpen(t.id) })
                        }
                    }
                }
            }
        }
    }
}
