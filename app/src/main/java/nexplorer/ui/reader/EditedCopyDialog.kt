package nexplorer.ui.reader

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.files.EditedCopy
import nexplorer.files.Volumes
import nexplorer.ui.OptionLabel
import nexplorer.ui.home.FolderPickerDialog
import nexplorer.ui.theme.AccentBlue
import java.io.File

@Composable
fun EditedCopyDialog(original: File, startFolder: File, onSave: (File) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var folder by remember { mutableStateOf(startFolder) }
    var picking by remember { mutableStateOf(false) }
    val target = remember(folder) { EditedCopy.target(original, folder) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save edits to a new file") },
        text = {
            Column {
                Text("${original.name} stays as it is. Your edits go into:", fontSize = 14.sp)
                OptionLabel("Name")
                Text(target.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OptionLabel("Where to")
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                        .clickable { picking = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = muted)
                    Spacer(Modifier.width(10.dp))
                    Text(folder.path, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
                }
                Text(
                    "Tip: keep \"${EditedCopy.SUFFIX}\" at the end of the name, and you can keep editing this file directly. If it's renamed, NExplorer will ask to save a new copy again.",
                    fontSize = 12.sp,
                    color = muted,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(target) }) { Text("Save", color = AccentBlue) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = muted) } },
    )

    if (picking) {
        FolderPickerDialog(
            title = "Save edited file to",
            confirmLabel = "Save here",
            items = emptyList(),
            volumes = remember { Volumes.list(ctx) },
            startDir = folder,
            onPick = { folder = it; picking = false },
            onDismiss = { picking = false },
        )
    }
}
