package nexplorer.ui.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import nexplorer.files.Entry
import nexplorer.files.FileOps
import nexplorer.ui.theme.IconRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

@Composable
fun DeleteDialog(entries: List<Entry>, onConfirm: () -> Unit, onDismiss: () -> Unit, toBin: Boolean = false) {
    var total by remember(entries) { mutableStateOf<Int?>(null) }
    LaunchedEffect(entries) {
        if (entries.any { it.isDir }) total = withContext(Dispatchers.IO) { entries.sumOf { FileOps.countAll(it.file) } }
    }
    val one = entries.singleOrNull()
    val title = when {
        one == null -> "Delete ${entries.size} items?"
        one.isDir -> "Delete this folder?"
        else -> "Delete this file?"
    }
    val t = total
    val body = when {
        one != null && !one.isDir -> "Deleting this file removes \"${one.file.name}\" from your device storage."
        one != null && t != null -> "Deleting this folder removes \"${one.file.name}\" and all ${files(t)} in it from your device storage."
        one != null -> "Deleting this folder removes \"${one.file.name}\" and everything in it from your device storage."
        t != null -> "This removes ${entries.size} items (${files(t)} in total) from your device storage."
        else -> "This removes ${entries.size} items from your device storage."
    }.let {
        if (toBin) it.replace("removes", "moves").replace("from your device storage", "to the recycle bin") + " It's kept there for 30 days."
        else "$it This can't be undone."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = IconRed) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}
