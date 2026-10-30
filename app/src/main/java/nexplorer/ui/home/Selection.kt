package nexplorer.ui.home

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.files.Entry
import nexplorer.files.FileOps
import nexplorer.files.FolderScanner
import nexplorer.files.Picking
import nexplorer.files.Volume
import nexplorer.jobs.FileJobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Transfer(val items: List<File>, val move: Boolean)

data class TransferQueue(val left: List<File>, val dest: File, val move: Boolean, val decided: List<Pair<File, FileOps.OnConflict>> = emptyList())

class FileActionCallbacks(
    val onHide: (List<File>) -> Unit,
    val onDeleted: (File) -> Unit,
    val onMoved: (old: File, new: File) -> Unit,
    val onCopied: () -> Unit,
    val onChanged: () -> Unit,
    val opened: Set<String> = emptySet(),
    val onMarkOpened: (files: List<File>, opened: Boolean) -> Unit = { _, _ -> },
    val explorer: Boolean = false,
    val onOpenWith: (File) -> Unit = {},
    val onOpenFolder: ((File) -> Unit)? = null,
    val binOn: Boolean = false,
)

@Stable
class SelectionState(private val ctx: Context, private val scope: CoroutineScope) {
    var selected by mutableStateOf(setOf<String>())
    var moreMenu by mutableStateOf(false)
    var deleting by mutableStateOf<List<Entry>?>(null)
    var renaming by mutableStateOf<File?>(null)
    var renameError by mutableStateOf<String?>(null)
    var picking by mutableStateOf<Transfer?>(null)
    var queue by mutableStateOf<TransferQueue?>(null)
    var clash by mutableStateOf<File?>(null)
    var zipping by mutableStateOf<List<File>?>(null)
    var details by mutableStateOf<File?>(null)

    val active: Boolean get() = selected.isNotEmpty()

    fun toggle(path: String) { selected = Picking.toggle(selected, path) }
    fun clear() { selected = emptySet(); moreMenu = false }

    fun keepOnly(paths: Collection<String>) { selected = selected.filter { it in paths }.toSet() }

    fun share(chosen: List<Entry>) {
        if (chosen.any { it.isDir }) {
            Toast.makeText(ctx, "Folders can't be shared. Select files only.", Toast.LENGTH_SHORT).show()
        } else {
            shareFiles(ctx, chosen.map { it.file })
        }
    }

    fun compress(items: List<File>, name: String) {
        val dir = items.first().parentFile ?: return
        val clean = name.trim().ifEmpty { "Archive" }.let { if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip" }
        FileJobs.zip(ctx, items, File(dir, FileOps.uniqueName(dir, clean.replace('/', '_'))))
    }

    fun extract(archive: File) = FileJobs.extract(ctx, archive, into = null, totalBytes = 0L)

    fun runQueue(cb: FileActionCallbacks, answer: FileOps.OnConflict? = null) {
        var q = queue ?: return
        var answerForFirst = answer
        while (q.left.isNotEmpty()) {
            val f = q.left.first()
            val choice = answerForFirst ?: if (FileOps.wouldClash(f, q.dest)) {
                queue = q
                clash = f
                return
            } else {
                FileOps.OnConflict.KeepBoth
            }
            answerForFirst = null
            q = q.copy(left = q.left.drop(1), decided = q.decided + (f to choice))
        }
        queue = null
        if (q.decided.isNotEmpty()) FileJobs.transfer(ctx, q.decided, q.dest, q.move, cb.onMoved)
    }
}

@Composable
fun rememberSelectionState(): SelectionState {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { SelectionState(ctx, scope) }
}

private val RenameIcon: ImageVector by lazy {
    ImageVector.Builder("Rename", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 4f); horizontalLineTo(16f); verticalLineTo(6f); horizontalLineTo(13f)
            verticalLineTo(18f); horizontalLineTo(16f); verticalLineTo(20f); horizontalLineTo(8f)
            verticalLineTo(18f); horizontalLineTo(11f); verticalLineTo(6f); horizontalLineTo(8f); close()
        }
    }.build()
}

@Composable
fun SelectionBar(state: SelectionState, chosen: List<Entry>, allPaths: List<String>, cb: FileActionCallbacks) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = state::clear) { Icon(Icons.Outlined.Close, contentDescription = "Stop selecting") }
        Text("${state.selected.size} selected", fontSize = 18.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = { state.deleting = chosen }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
        }
        IconButton(onClick = { state.share(chosen) }) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
        IconButton(
            onClick = {
                state.renameError = null
                state.renaming = chosen.firstOrNull()?.file
            },
            enabled = state.selected.size == 1,
        ) { Icon(RenameIcon, contentDescription = "Rename") }
        Box {
            IconButton(onClick = { state.moreMenu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = state.moreMenu, onDismissRequest = { state.moreMenu = false }) {
                DropdownMenuItem(text = { Text("Select all") }, onClick = {
                    state.selected = allPaths.toSet()
                    state.moreMenu = false
                })
                DropdownMenuItem(text = { Text("Copy to") }, onClick = {
                    state.picking = Transfer(chosen.map { it.file }, move = false)
                    state.moreMenu = false
                })
                DropdownMenuItem(text = { Text("Move to") }, onClick = {
                    state.picking = Transfer(chosen.map { it.file }, move = true)
                    state.moreMenu = false
                })
                val single = chosen.singleOrNull()
                DropdownMenuItem(
                    text = { Text("Open with") },
                    enabled = single != null && !single.isDir,
                    onClick = {
                        single?.let { cb.onOpenWith(it.file) }
                        state.clear()
                    },
                )
                cb.onOpenFolder?.let { openFolder ->
                    DropdownMenuItem(text = { Text("Open folder") }, enabled = single != null, onClick = {
                        single?.file?.parentFile?.let(openFolder)
                        state.clear()
                    })
                }
                if (cb.explorer) {
                    DropdownMenuItem(text = { Text("Compress to zip") }, onClick = {
                        state.zipping = chosen.map { it.file }
                        state.moreMenu = false
                    })
                    if (single != null && !single.isDir && nexplorer.files.FileTypes.isArchive(single.file.name)) {
                        DropdownMenuItem(text = { Text("Extract here") }, onClick = {
                            state.clear()
                            state.extract(single.file)
                        })
                    }
                    DropdownMenuItem(text = { Text("Details") }, enabled = single != null, onClick = {
                        state.details = single?.file
                        state.moreMenu = false
                    })
                }
                val files = chosen.filter { !it.isDir }.map { it.file }
                if (files.any { it.path !in cb.opened }) {
                    DropdownMenuItem(text = { Text("Mark as opened") }, onClick = {
                        cb.onMarkOpened(files, true)
                        state.clear()
                    })
                }
                if (files.any { it.path in cb.opened }) {
                    DropdownMenuItem(text = { Text("Mark as new") }, onClick = {
                        cb.onMarkOpened(files, false)
                        state.clear()
                    })
                }
                DropdownMenuItem(text = { Text("Hide") }, onClick = {
                    cb.onHide(chosen.map { it.file })
                    state.clear()
                })
            }
        }
    }
}

@Composable
fun SelectionDialogs(state: SelectionState, volumes: List<Volume>, fallbackDir: File, cb: FileActionCallbacks) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    state.renaming?.let { f ->
        RenameDialog(
            target = f,
            error = state.renameError,
            onDismiss = { state.renaming = null },
            onRename = { name ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { FileOps.rename(f, name) } }
                    result.onSuccess { out ->
                        state.renaming = null
                        state.clear()
                        if (out != f) cb.onMoved(f, out)
                        FolderScanner.invalidate()
                        cb.onChanged()
                    }.onFailure {
                        state.renameError = when (it) {
                            is IllegalArgumentException -> "Enter a valid name"
                            is IllegalStateException -> if (it.message == "Name already used") "That name is already used here" else "Couldn't rename"
                            else -> "Couldn't rename"
                        }
                    }
                }
            },
        )
    }

    state.picking?.let { t ->
        val what = if (t.items.size == 1) "\"${t.items[0].name}\"" else "${t.items.size} items"
        FolderPickerDialog(
            title = (if (t.move) "Move " else "Copy ") + what + " to",
            confirmLabel = if (t.move) "Move here" else "Copy here",
            items = t.items,
            volumes = volumes,
            startDir = t.items.first().parentFile ?: fallbackDir,
            onDismiss = { state.picking = null },
            onPick = { dest ->
                state.picking = null
                state.clear()
                state.queue = TransferQueue(t.items, dest, t.move)
                state.runQueue(cb)
            },
        )
    }

    state.clash?.let { f ->
        ClashDialog(
            name = f.name,
            onReplace = { state.clash = null; state.runQueue(cb, FileOps.OnConflict.Replace) },
            onKeepBoth = { state.clash = null; state.runQueue(cb, FileOps.OnConflict.KeepBoth) },
            onDismiss = {
                state.clash = null
                state.queue = state.queue?.let { it.copy(left = it.left.drop(1)) }
                state.runQueue(cb)
            },
        )
    }

    state.zipping?.let { items ->
        val start = items.singleOrNull()?.let { if (it.isDirectory) it.name else it.nameWithoutExtension }
            ?: items.first().parentFile?.name ?: "Archive"
        NameInputDialog(
            title = if (items.size == 1) "Compress \"${items[0].name}\"" else "Compress ${items.size} items",
            start = "$start.zip",
            confirm = "Compress",
            error = null,
            onConfirm = { name ->
                state.zipping = null
                state.clear()
                state.compress(items, name)
            },
            onDismiss = { state.zipping = null },
        )
    }

    state.details?.let { f -> DetailsDialog(f) { state.details = null; state.clear() } }

    state.deleting?.let { list ->
        DeleteDialog(
            entries = list,
            toBin = cb.binOn,
            onDismiss = { state.deleting = null },
            onConfirm = {
                state.deleting = null
                state.clear()
                FileJobs.delete(ctx, list.map { it.file }, cb.binOn, volumes.map { it.root }, cb.onDeleted)
            },
        )
    }
}
