package com.networkanalyst.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight

enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    SCAN("Scan", "Network Analyst", Icons.Filled.NetworkCheck),
    WIFI("Wi-Fi", "Wi-Fi", Icons.Filled.Wifi),
    SPEED("Speed", "Speed test", Icons.Filled.Speed),
    DEVICES("Devices", "Devices", Icons.Filled.Devices),
}

/** The frame around every screen: title bar on top, tabs at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(selected: Tab, onSelect: (Tab) -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected.title, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}
