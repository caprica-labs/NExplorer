package nexplorer.download

import android.content.ClipboardManager
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nexplorer.files.Volume
import nexplorer.ui.OptionLabel
import nexplorer.ui.home.FolderPickerDialog
import nexplorer.ui.home.formatSize
import nexplorer.ui.theme.AccentBlue
import java.io.File

internal val ErrorRed = Color(0xFFD32F2F)

private sealed interface Check {
    data object Idle : Check
    data object Busy : Check
    data class Found(val probe: Probe) : Check
    data class Failed(val reason: String) : Check
}

@Composable
fun AddDownloadDialog(volumes: List<Volume>, initialLink: String = "", onDismiss: () -> Unit, onAdded: (started: Boolean) -> Unit) {
    val ctx = LocalContext.current
    var link by rememberSaveable { mutableStateOf(initialLink) }
    var name by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var folder by rememberSaveable {
        mutableStateOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
    }
    var picking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<Check>(Check.Idle) }
    val url = DownloadNames.normalize(link)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentBlue, cursorColor = AccentBlue)

    LaunchedEffect(url) {
        check = Check.Idle
        if (url == null) return@LaunchedEffect
        if (!nameEdited) name = DownloadNames.clean(DownloadNames.fromUrl(url) ?: DownloadNames.FALLBACK)
        delay(600)
        check = Check.Busy
        check = withContext(Dispatchers.IO) { runCatching { Downloads.probe(url) } }
            .fold({ Check.Found(it) }, { Check.Failed(it.message ?: "the connection failed") })
        (check as? Check.Found)?.let { if (!nameEdited) name = it.probe.name }
    }

    fun add(start: Boolean) {
        val u = url ?: return
        val size = (check as? Check.Found)?.probe?.size ?: -1L
        Downloads.add(ctx, u, File(folder), name.ifBlank { DownloadNames.FALLBACK }, size, start)
        onAdded(start)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New download") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OptionLabel("Link")
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it.trim() },
                    singleLine = true,
                    placeholder = { Text("https://") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            val found = DownloadNames.linkIn(text) ?: text?.trim()
                            if (found.isNullOrEmpty()) Toast.makeText(ctx, "Nothing to paste", Toast.LENGTH_SHORT).show() else link = found
                        }) { Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste") }
                    },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                val note: Pair<String, Boolean>? = when {
                    link.isBlank() -> null
                    url == null -> "That isn't a web link" to true
                    else -> when (val c = check) {
                        Check.Idle, Check.Busy -> "Checking the link…" to false
                        is Check.Found -> listOfNotNull(
                            if (c.probe.size >= 0) "Size: ${formatSize(c.probe.size)}" else "Size: unknown",
                            if (!c.probe.ranges) "can't be paused and resumed" else null,
                        ).joinToString(" · ") to false
                        is Check.Failed -> "Couldn't reach it: ${c.reason}" to true
                    }
                }
                note?.let { (text, bad) ->
                    Text(text, fontSize = 12.sp, color = if (bad) ErrorRed else muted, modifier = Modifier.padding(top = 6.dp))
                }
                if (url != null && url.startsWith("http://", ignoreCase = true)) {
                    Text("Only encrypted downloads are allowed, so this uses https://.", fontSize = 12.sp, color = muted)
                }
                OptionLabel("Save as")
                OutlinedTextField(
                    value = name,
                    onValueChange = { v ->
                        name = v.filter { it != '/' && it != '\\' }
                        nameEdited = true
                    },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OptionLabel("Save to")
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                        .clickable { picking = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = muted)
                    Spacer(Modifier.width(10.dp))
                    Text(folder, maxLines = 2, overflow = TextOverflow.StartEllipsis, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Row {
                TextButton(onClick = { add(start = false) }, enabled = url != null) { Text("Add", color = if (url != null) AccentBlue else muted) }
                TextButton(onClick = { add(start = true) }, enabled = url != null) { Text("Start", color = if (url != null) AccentBlue else muted) }
            }
        },
    )

    if (picking) {
        FolderPickerDialog(
            title = "Save to",
            confirmLabel = "Save here",
            items = emptyList(),
            volumes = volumes,
            startDir = File(folder),
            onPick = { folder = it.path; picking = false },
            onDismiss = { picking = false },
        )
    }
}
