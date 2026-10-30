package nexplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import nexplorer.ui.theme.AccentBlue

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Outlined.Home),
    Tools("Tools", Icons.Outlined.Build),
    Settings("Settings", Icons.Outlined.Settings),
    Storage("Storage", Icons.Outlined.Storage),
}

@Composable
fun MainScreen(
    homeContent: @Composable () -> Unit,
    recentContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    toolsContent: @Composable () -> Unit,
    onHomeTabTap: () -> Unit,
    onToolsTabTap: () -> Unit,
    onSettingsTabTap: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchContent: @Composable () -> Unit,
    showHome: Int = 0,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showHome) { if (showHome > 0) tab = Tab.Home }
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = AccentBlue,
        selectedTextColor = AccentBlue,
        indicatorColor = Color.Transparent,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val short = LocalConfiguration.current.screenHeightDp < 480
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        val showSearch = !short || searchOpen || searchQuery.isNotEmpty()
        Column(Modifier.fillMaxHeight().widthIn(max = 600.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    searchQuery.isNotBlank() -> searchContent()
                    tab == Tab.Home -> homeContent()
                    tab == Tab.Settings -> settingsContent()
                    tab == Tab.Tools -> toolsContent()
                    tab == Tab.Storage -> StorageScreen()
                    else -> Placeholder(tab.label)
                }
            }
            if (!short && tab == Tab.Home && searchQuery.isBlank()) recentContent()
            if (showSearch) {
                SearchField(searchQuery, onSearchQueryChange, focusNow = short, onLeave = {
                    if (searchQuery.isEmpty()) searchOpen = false
                })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            if (t == Tab.Home) onHomeTabTap()
                            if (t == Tab.Tools) onToolsTabTap()
                            if (t == Tab.Settings) onSettingsTabTap()
                            tab = t
                            searchOpen = false
                            onSearchQueryChange("")
                        },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = itemColors,
                    )
                }
                if (short) {
                    NavigationBarItem(
                        selected = false,
                        onClick = {
                            searchOpen = !showSearch
                            if (!searchOpen) onSearchQueryChange("")
                        },
                        icon = { Icon(Icons.Outlined.Search, contentDescription = "Search files") },
                        label = { Text("Search") },
                        colors = itemColors,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    focusNow: Boolean = false,
    onLeave: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val keyboardOpen = WindowInsets.isImeVisible
    LaunchedEffect(keyboardOpen) { if (!keyboardOpen && focused) focus.clearFocus() }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusNow && query.isEmpty()) requester.requestFocus() }
    TextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Search files") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                }
            }
        },
        shape = RoundedCornerShape(10.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = AccentBlue,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .focusRequester(requester)
            .onFocusChanged {
                if (focused && !it.isFocused) onLeave()
                focused = it.isFocused
            },
    )
}

@Composable
fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
