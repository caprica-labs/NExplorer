package nexplorer.ui.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.files.Entry
import nexplorer.files.Picking
import nexplorer.files.RecycleBin
import nexplorer.files.Volume
import nexplorer.jobs.FileJobs
import nexplorer.ui.Placeholder
import nexplorer.ui.ToolScreen
import nexplorer.ui.dragToSelect
import nexplorer.ui.home.EntryRow
import nexplorer.ui.home.formatSize
import nexplorer.ui.home.typeIcon
import nexplorer.ui.theme.AccentBlue
import nexplorer.ui.theme.IconRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private class Binned(val item: RecycleBin.Item, val isDir: Boolean, val size: Long, val inside: Int) {
    val key: String get() = item.holder.path
}

@Composable
fun RecycleBinScreen(volumes: List<Volume>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val roots = volumes.map { it.root }
    var listed by remember { mutableStateOf<List<Binned>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { FileJobs.finished.collect { reload++ } }
    LaunchedEffect(reload) {
        val all = withContext(Dispatchers.IO) {
            RecycleBin.purge(roots)
            RecycleBin.items(roots).map { i ->
                val dir = i.file.isDirectory
                Binned(i, dir, if (dir) 0L else i.file.length(), if (dir) i.file.list()?.size ?: 0 else 0)
            }
        }
        listed = all
        selected = selected.filter { key -> all.any { it.key == key } }.toSet()
    }
    BackHandler { if (selected.isNotEmpty()) selected = emptySet() else onClose() }

    val all = listed.orEmpty()
    val chosen = all.filter { it.key in selected }
    ToolScreen {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected.isEmpty()) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    Text("Recycle bin", fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { askClear = true }, enabled = all.isNotEmpty()) {
                        Text("Clear recycle bin", color = if (all.isNotEmpty()) IconRed else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, contentDescription = "Stop selecting") }
                    Text("${selected.size} selected", fontSize = 18.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { askDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete for good", tint = MaterialTheme.colorScheme.error)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Recover") }, onClick = {
                                menu = false
                                FileJobs.restore(ctx, chosen.map { it.item })
                                selected = emptySet()
                            })
                            DropdownMenuItem(text = { Text("Select all") }, onClick = {
                                menu = false
                                selected = all.map { it.key }.toSet()
                            })
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            when {
                listed == null -> Placeholder("Reading")
                all.isEmpty() -> Placeholder("The recycle bin is empty")
                else -> {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        Modifier.fillMaxSize().dragToSelect(
                            listState,
                            keys = { all.map { it.key } },
                            selected = { selected },
                            onSelect = { selected = it },
                        ),
                        state = listState,
                    ) {
                        items(all, key = { it.key }) { b ->
                            val deleted = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(b.item.deletedAt))
                            val what = if (b.isDir) (if (b.inside == 1) "1 item" else "${b.inside} items") else formatSize(b.size)
                            EntryRow(
                                Entry(b.item.file, b.isDir, b.inside),
                                onClick = { selected = Picking.toggle(selected, b.key) },
                                onLongClick = null,
                                selected = if (selected.isEmpty()) null else b.key in selected,
                                above = b.item.original.parent ?: "",
                                icon = typeIcon(b.item.file.name, b.isDir),
                                subtitle = "Deleted $deleted · $what",
                                showArrow = false,
                            )
                        }
                    }
                }
            }
        }
    }

    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("Clear the recycle bin?") },
            text = {
                Text(
                    "Everything in it (${if (all.size == 1) "1 item" else "${all.size} items"}) is deleted for good. This can't be undone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askClear = false
                    selected = emptySet()
                    FileJobs.deleteForever(ctx, all.map { it.item }, title = "Clearing the recycle bin")
                }) { Text("Clear", color = IconRed) }
            },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text("Cancel", color = AccentBlue) } },
        )
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(if (chosen.size == 1) "Delete this for good?" else "Delete ${chosen.size} items for good?") },
            text = { Text("They're removed from the recycle bin and can't be recovered.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    FileJobs.deleteForever(ctx, chosen.map { it.item })
                    selected = emptySet()
                }) { Text("Delete", color = IconRed) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel", color = AccentBlue) } },
        )
    }
}
