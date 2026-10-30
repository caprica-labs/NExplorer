package nexplorer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nexplorer.files.FolderScanner
import nexplorer.files.Volume
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RenameDialog(
    target: File,
    error: String?,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
    asFile: Boolean = target.isFile,
    title: String = if (asFile) "Rename file" else "Rename folder",
    confirm: String = "Rename",
) {
    val dot = target.name.lastIndexOf('.')
    val split = asFile && dot > 0
    val base = if (split) target.name.substring(0, dot) else target.name
    var value by remember { mutableStateOf(TextFieldValue(base, TextRange(0, base.length))) }
    var ext by remember { mutableStateOf(if (split) target.name.substring(dot + 1) else "") }
    fun full(): String {
        val e = ext.trim().trimStart('.')
        return if (e.isEmpty()) value.text else "${value.text}.$e"
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentBlue, cursorColor = AccentBlue, focusedLabelColor = AccentBlue)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.copy(text = it.text.filter { c -> c != '/' }) },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = if (split) ImeAction.Next else ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRename(full()) }),
                    colors = colors,
                    modifier = Modifier.focusRequester(focus),
                )
                if (asFile) {
                    OutlinedTextField(
                        value = ext,
                        onValueChange = { ext = it.filter { c -> c != '/' } },
                        label = { Text("Extension") },
                        prefix = { Text(".") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onRename(full()) }),
                        colors = colors,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onRename(full()) }) { Text(confirm, color = AccentBlue) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

@Composable
fun ClashDialog(name: String, onReplace: () -> Unit, onKeepBoth: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\"$name\" already exists here") },
        text = { Text("Replace it, or keep both?", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onKeepBoth) { Text("Keep both", color = AccentBlue) }
                TextButton(onClick = onReplace) { Text("Replace", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
    )
}

@Composable
fun FolderPickerDialog(
    title: String,
    confirmLabel: String,
    items: List<File>,
    volumes: List<Volume>,
    startDir: File,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    var dir by remember { mutableStateOf(startDir) }
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var volumeMenu by remember { mutableStateOf(false) }
    val volume = volumes.firstOrNull { dir.path == it.root.path || dir.path.startsWith(it.root.path + "/") } ?: volumes.first()
    LaunchedEffect(dir) {
        folders = withContext(Dispatchers.IO) {
            dir.listFiles().orEmpty()
                .filter { it.isDirectory && !FolderScanner.shouldSkip(it) }
                .filter { d -> items.none { it.isDirectory && (d == it || d.path.startsWith(it.path + "/")) } }
                .sortedBy { it.name.lowercase() }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") }
                    Text(title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { volumeMenu = true }) {
                            Text(volume.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Choose storage")
                        }
                        DropdownMenu(expanded = volumeMenu, onDismissRequest = { volumeMenu = false }) {
                            volumes.forEach { v ->
                                DropdownMenuItem(text = { Text(v.label) }, onClick = { volumeMenu = false; dir = v.root })
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val atTop = dir.path == volume.root.path
                    IconButton(onClick = { dir.parentFile?.let { dir = it } }, enabled = !atTop) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Up one folder")
                    }
                    Text(
                        if (atTop) volume.label else volume.label + dir.path.removePrefix(volume.root.path),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                LazyColumn(Modifier.weight(1f)) {
                    if (folders.isEmpty()) {
                        item {
                            Text(
                                "No folders here",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                    items(folders, key = { it.path }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { dir = f }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(14.dp))
                            Text(f.name, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                Button(
                    onClick = { onPick(dir) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) { Text(confirmLabel) }
            }
        }
    }
}
