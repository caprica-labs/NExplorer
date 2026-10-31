package nexplorer.download

import android.content.ClipboardManager
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nexplorer.files.Volume
import nexplorer.ui.OptionLabel
import nexplorer.ui.ToolScreen
import nexplorer.ui.home.FolderPickerDialog
import nexplorer.ui.home.formatSize
import nexplorer.ui.theme.AccentBlue
import java.io.File

private val ErrorRed = Color(0xFFD32F2F)

@Composable
fun DownloadsFlow(volumes: List<Volume>, onOpenFile: (File) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    val list by Downloads.list.collectAsState()
    val version by Downloads.version.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (Downloads.running > 0) tick++
        }
    }
    var link by rememberSaveable { mutableStateOf("") }
    var folderPath by rememberSaveable {
        mutableStateOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
    }
    var picking by remember { mutableStateOf(false) }
    var unencrypted by remember { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    fun begin(url: String) {
        keyboard?.hide()
        focus.clearFocus()
        Downloads.start(ctx, url, File(folderPath))
        link = ""
    }

    fun submit() {
        val url = Downloads.normalize(link)
        when {
            url == null -> Toast.makeText(ctx, "That isn't a web link", Toast.LENGTH_SHORT).show()
            url.startsWith("http://", ignoreCase = true) -> unencrypted = url
            else -> begin(url)
        }
    }

    ToolScreen {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text("Downloads", fontSize = 18.sp)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                OptionLabel("Link")
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it.trim() },
                    singleLine = true,
                    placeholder = { Text("https://") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
                            if (text.isNullOrEmpty()) Toast.makeText(ctx, "Nothing to paste", Toast.LENGTH_SHORT).show() else link = text
                        }) { Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste") }
                    },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentBlue, cursorColor = AccentBlue),
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
                    Text(folderPath, maxLines = 2, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
                }
                Button(
                    onClick = ::submit,
                    enabled = link.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                ) { Text("Download") }
            }
            if (list.isEmpty()) {
                Text(
                    "Downloads show here. Finished files stay in their folder.",
                    color = muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.id }) { d ->
                        DownloadRow(
                            remember(d, tick, version) { d.info() },
                            onOpen = { d.file?.let(onOpenFile) },
                            onPause = { Downloads.pause(d) },
                            onResume = { Downloads.resume(ctx, d) },
                            onRemove = { Downloads.remove(d) },
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        FolderPickerDialog(
            title = "Save to",
            confirmLabel = "Save here",
            items = emptyList(),
            volumes = volumes,
            startDir = File(folderPath),
            onPick = { folderPath = it.path; picking = false },
            onDismiss = { picking = false },
        )
    }
    unencrypted?.let { url ->
        val secure = "https://" + url.substringAfter("://")
        AlertDialog(
            onDismissRequest = { unencrypted = null },
            title = { Text("Not encrypted") },
            text = { Text("This link starts with http://. NExplorer only downloads over encrypted connections, so nothing can be changed on the way. Most sites also work with https://.") },
            confirmButton = { TextButton(onClick = { unencrypted = null; begin(secure) }) { Text("Try https://", color = AccentBlue) } },
            dismissButton = { TextButton(onClick = { unencrypted = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DownloadRow(d: DownloadInfo, onOpen: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val state = d.state
    val amount = if (d.total > 0) "${formatSize(d.done)} of ${formatSize(d.total)}" else formatSize(d.done)
    val status = when (state) {
        DownloadState.Running -> listOfNotNull(
            d.total.takeIf { it > 0 }?.let { "${d.done * 100 / it}%" },
            amount,
            d.speed.takeIf { it > 0 }?.let { "${formatSize(it)}/s" },
        ).joinToString(" · ")
        DownloadState.Paused -> "Paused · $amount"
        DownloadState.Done -> "Downloaded · ${formatSize(d.total)}"
        DownloadState.Failed -> "Didn't finish: ${d.error ?: "unknown error"}"
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = state == DownloadState.Done, onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, fontSize = 12.sp, color = if (state == DownloadState.Failed) ErrorRed else muted, maxLines = 2)
            if (state == DownloadState.Running || state == DownloadState.Paused) {
                if (d.total > 0) {
                    LinearProgressIndicator(
                        progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) },
                        color = AccentBlue,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                } else if (state == DownloadState.Running) {
                    LinearProgressIndicator(color = AccentBlue, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
        when (state) {
            DownloadState.Running -> IconButton(onClick = onPause) { Icon(Icons.Outlined.Pause, contentDescription = "Pause") }
            DownloadState.Paused, DownloadState.Failed -> IconButton(onClick = onResume) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = if (state == DownloadState.Failed) "Try again" else "Resume")
            }
            DownloadState.Done -> {}
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = if (state == DownloadState.Done) "Remove from list" else "Cancel")
        }
    }
}
