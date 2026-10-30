package nexplorer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.window.DialogProperties
import nexplorer.files.ExplorerSort
import nexplorer.files.FileType
import nexplorer.files.FileTypes
import nexplorer.files.SortBy
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun NameInputDialog(title: String, start: String, confirm: String, error: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c != '/' } },
                    singleLine = true,
                    isError = error != null,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentBlue, cursorColor = AccentBlue),
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirm, color = AccentBlue) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

@Composable
fun NotepadOrReaderDialog(
    name: String,
    onNotepad: () -> Unit,
    onReader: () -> Unit,
    onDismiss: () -> Unit,
    onWebPage: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 18.sp) },
        text = {
            Column {
                Text("Open in notepad", fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onNotepad).padding(vertical = 14.dp))
                Text("Open in reader", fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onReader).padding(vertical = 14.dp))
                if (onWebPage != null) {
                    Text("Open as web page", fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onWebPage).padding(vertical = 14.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

@Composable
fun SortDialog(sort: ExplorerSort, onSort: (ExplorerSort) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort by") },
        text = {
            Column {
                SortBy.entries.forEach { by ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSort(sort.copy(by = by)) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = sort.by == by,
                            onClick = { onSort(sort.copy(by = by)) },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentBlue),
                        )
                        Text(by.label)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (sort.by) {
                            SortBy.Name, SortBy.Type -> "Z to A"
                            SortBy.Date -> "Oldest first"
                            SortBy.Size -> "Smallest first"
                        },
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    Switch(
                        checked = sort.descending,
                        onCheckedChange = { onSort(sort.copy(descending = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = AccentBlue) } },
    )
}

@Composable
fun DetailsDialog(file: File, onDismiss: () -> Unit) {
    var folderSize by remember(file) { mutableStateOf<Pair<Long, Int>?>(null) }
    LaunchedEffect(file) {
        if (file.isDirectory) {
            folderSize = withContext(Dispatchers.IO) {
                var bytes = 0L
                var n = 0
                file.walkTopDown().filter { it.isFile }.forEach { bytes += it.length(); n++ }
                bytes to n
            }
        }
    }
    val type = FileTypes.of(file.name, file.isDirectory)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column {
                Detail("Name", file.name)
                Detail("Location", file.parent ?: "")
                Detail(
                    "Type",
                    when (type) {
                        FileType.Folder -> "Folder"
                        else -> file.extension.uppercase().ifEmpty { "File" } + when (type) {
                            FileType.Image -> " picture"
                            FileType.Text -> " text"
                            FileType.Archive -> " archive"
                            FileType.Document -> " document"
                            else -> " file"
                        }
                    },
                )
                Detail(
                    "Size",
                    if (file.isDirectory) {
                        folderSize?.let { (b, n) -> "${formatSize(b)} · $n ${if (n == 1) "file" else "files"}" } ?: "Counting…"
                    } else {
                        "${formatSize(file.length())} (${"%,d".format(file.length())} bytes)"
                    },
                )
                Detail("Modified", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.lastModified())))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = AccentBlue) } },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(78.dp))
        Text(value, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun BusyDialog(label: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(label, fontSize = 16.sp) },
        text = { LinearProgressIndicator(color = AccentBlue, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
    )
}

fun explorerSubtitle(isDir: Boolean, count: Int, size: Long, ext: String, modified: Long): String {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(modified))
    return if (isDir) {
        "${if (count == 1) "1 item" else "$count items"} · $date"
    } else {
        listOf(formatSize(size), ext.uppercase(), date).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
