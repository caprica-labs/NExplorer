package nexplorer.ui.explorer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.files.Archives
import nexplorer.files.Entry
import nexplorer.files.Volume
import nexplorer.jobs.FileJobs
import nexplorer.ui.Placeholder
import nexplorer.ui.ToolScreen
import nexplorer.ui.home.BusyDialog
import nexplorer.ui.home.EntryRow
import nexplorer.ui.home.FolderPickerDialog
import nexplorer.ui.home.formatSize
import nexplorer.ui.home.typeIcon
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ArchiveScreen(
    archive: File,
    volumes: List<Volume>,
    onOpen: (File) -> Unit,
    onExtracted: (File) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Archives.Item>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var dir by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { open = false } }

    LaunchedEffect(archive) {
        val r = withContext(Dispatchers.IO) { runCatching { Archives.entries(archive) } }
        r.onSuccess { items = it }.onFailure { failed = true }
    }
    fun up() { if (dir.isEmpty()) onClose() else dir = dir.substringBeforeLast('/', "") }
    BackHandler(onBack = ::up)

    fun open(item: Archives.Item) {
        busy = "Opening"
        scope.launch {
            val out = File(File(ctx.cacheDir, "archive"), item.name)
            val ok = withContext(Dispatchers.IO) {
                File(ctx.cacheDir, "archive").deleteRecursively()
                runCatching { Archives.extractOne(archive, item.path, out) }.isSuccess
            }
            busy = null
            if (ok) onOpen(out) else Toast.makeText(ctx, "Couldn't open this file", Toast.LENGTH_SHORT).show()
        }
    }

    ToolScreen {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::up) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text(archive.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (dir.isNotEmpty()) {
                        Text("/$dir", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { picking = true }, enabled = items != null) {
                    Text("Extract to", color = if (items != null) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            val all = items
            when {
                failed -> Placeholder("This archive couldn't be read")
                all == null -> Placeholder("Reading")
                else -> {
                    val here = remember(all, dir) { Archives.children(all, dir) }
                    if (here.isEmpty()) Placeholder("This folder is empty") else LazyColumn(Modifier.fillMaxSize()) {
                        items(here, key = { it.path }) { item ->
                            val inner = all.count { it.path.startsWith(item.path + "/") && it.path.substringAfter(item.path + "/").indexOf('/') < 0 }
                            EntryRow(
                                Entry(File(archive, item.path), item.isDir, inner),
                                onClick = { if (item.isDir) dir = item.path else open(item) },
                                onLongClick = {},
                                icon = typeIcon(item.name, item.isDir),
                                subtitle = if (item.isDir) (if (inner == 1) "1 item" else "$inner items") else formatSize(item.size),
                            )
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        FolderPickerDialog(
            title = "Extract \"${archive.name}\" to",
            confirmLabel = "Extract here",
            items = emptyList(),
            volumes = volumes,
            startDir = archive.parentFile ?: volumes.first().root,
            onPick = { dest ->
                picking = false
                FileJobs.extract(ctx, archive, into = dest, totalBytes = items.orEmpty().sumOf { it.size }) { folder ->
                    if (open) onExtracted(folder)
                }
            },
            onDismiss = { picking = false },
        )
    }
    busy?.let { BusyDialog(it) }
}
