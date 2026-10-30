package nexplorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.files.Entry
import nexplorer.files.FileKind
import nexplorer.files.FolderScanner
import nexplorer.files.Volume
import nexplorer.scan.NumberBadge
import nexplorer.ui.home.EntryRow
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FilePicker(
    kind: FileKind,
    title: String,
    noneFound: String,
    volumes: List<Volume>,
    onPick: (File) -> Unit,
    onBack: () -> Unit,
    picks: List<File>? = null,
    action: @Composable () -> Unit = {},
) {
    var volume by remember { mutableStateOf(volumes.first()) }
    var stack by remember { mutableStateOf(listOf<File>()) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var volumeMenu by remember { mutableStateOf(false) }
    val current = stack.lastOrNull()

    fun back() { if (stack.isEmpty()) onBack() else stack = stack.dropLast(1) }
    BackHandler(onBack = ::back)

    LaunchedEffect(current, volume) {
        entries = null
        entries = withContext(Dispatchers.IO) { FolderScanner.children(current ?: volume.root, kind) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text(
                current?.name ?: title,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (current == null && volumes.size > 1) {
                Box {
                    TextButton(onClick = { volumeMenu = true }) {
                        Text(volume.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Choose storage", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = volumeMenu, onDismissRequest = { volumeMenu = false }) {
                        volumes.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v.label, color = if (v == volume) AccentBlue else MaterialTheme.colorScheme.onSurface) },
                                onClick = { volumeMenu = false; volume = v; stack = emptyList() },
                            )
                        }
                    }
                }
            }
            action()
        }
        val list = entries
        when {
            list == null -> Placeholder("Scanning")
            list.isEmpty() -> Placeholder(noneFound)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.file.path }) { e ->
                    val n = picks?.indexOf(e.file)?.takeIf { it >= 0 }?.plus(1)
                    EntryRow(
                        e,
                        onClick = { if (e.isDir) stack = stack + e.file else onPick(e.file) },
                        onLongClick = {},
                        leading = n?.let { { NumberBadge(it) } },
                    )
                }
            }
        }
    }
}
