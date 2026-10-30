package nexplorer.share

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import nexplorer.files.FileKind
import nexplorer.files.Volume
import nexplorer.scan.Lists
import nexplorer.ui.FilePicker
import nexplorer.ui.Glyph
import nexplorer.ui.Tool
import nexplorer.ui.ToolCell
import nexplorer.ui.ToolScreen
import nexplorer.ui.theme.AccentBlue
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val ShutdownRed = Color(0xFFD32F2F)

@Composable
fun NetworkShareFlow(volumes: List<Volume>, onOpenFile: (File) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val running by NetworkShare.running.collectAsState()
    var wasOn by remember { mutableStateOf(running != null) }
    LaunchedEffect(running) {
        if (running != null) {
            wasOn = true
        } else if (wasOn) {
            NetworkShare.endedBecause?.let { Toast.makeText(ctx, "Network Share was shut down. $it", Toast.LENGTH_LONG).show() }
            onClose()
        }
    }
    ToolScreen {
        val r = running
        if (r == null) {
            if (!wasOn) StartDialogs(onClose)
        } else {
            SharePage(r, volumes, onOpenFile, onClose)
        }
    }
}

@Composable
private fun StartDialogs(onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var warned by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(NetworkShare.deviceName(ctx)) }
    var starting by remember { mutableStateOf(false) }

    fun start() {
        starting = true
        scope.launch {
            val problem = withContext(Dispatchers.IO) { NetworkShare.start(ctx, name) }
            if (problem != null) {
                Toast.makeText(ctx, problem, Toast.LENGTH_LONG).show()
                onCancel()
            }
        }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }

    BackHandler(onBack = onCancel)
    if (!warned) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Before you start sharing") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Use your home Wi-Fi, or turn on this device's hotspot and connect the other devices to it. " +
                            "Avoid public Wi-Fi (cafés, airports, hotels).",
                    )
                    Text(
                        "Files go straight between devices and never leave your local network. The connection is not " +
                            "encrypted, so on a shared network someone could see the files as they pass.",
                    )
                }
            },
            confirmButton = { TextButton(onClick = { warned = true }) { Text("Start sharing", color = AccentBlue) } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("This device's name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Others see this name, and the files you share show in a folder with it.")
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !starting && ShareNames.clean(name) != null,
                    onClick = {
                        val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needsAsking) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
                    },
                ) { Text("Start", color = if (starting) MaterialTheme.colorScheme.onSurfaceVariant else AccentBlue) }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SharePage(r: NetworkShare.Running, volumes: List<Volume>, onOpenFile: (File) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var picks by remember { mutableStateOf(listOf<File>()) }
    if (picking) {
        FilePicker(
            kind = FileKind.ANY,
            title = "Pick files to share",
            noneFound = "No files found",
            volumes = volumes,
            picks = picks,
            onPick = { picks = Lists.togglePick(picks, it) },
            onBack = { picks = emptyList(); picking = false },
            action = {
                TextButton(
                    enabled = picks.isNotEmpty(),
                    onClick = {
                        r.session.share(picks)
                        picks = emptyList()
                        picking = false
                    },
                ) {
                    Text(
                        if (picks.isEmpty()) "Share" else "Share (${picks.size})",
                        color = if (picks.isNotEmpty()) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    } else {
        BackHandler(onBack = onClose)
        MainPage(r, onUploadNew = { picking = true }, onOpenFile = onOpenFile, onShutdown = { NetworkShare.shutdown(ctx) }, onClose = onClose)
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        Text(title, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class Popup { Qr, Pin, Upload, Shared, Devices }

@Composable
private fun MainPage(
    r: NetworkShare.Running,
    onUploadNew: () -> Unit,
    onOpenFile: (File) -> Unit,
    onShutdown: () -> Unit,
    onClose: () -> Unit,
) {
    val session = r.session
    val changes by session.changes.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(session) {
        while (true) {
            delay(300)
            if (session.transfers().any { it.state == TransferState.Downloading }) tick++
        }
    }
    val devices = remember(changes) { session.devices() }
    val transfers = remember(changes, tick) { session.transfers() }
    var popup by remember { mutableStateOf<Popup?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize()) {
        TopBar("Network Share", onClose)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Open in a browser on the other device", fontSize = 12.sp, color = muted)
                    Text(r.address, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
                SmallButton(Icons.Outlined.QrCode2, "QR code") { popup = Popup.Qr }
                SmallButton(Icons.Outlined.Pin, "PIN") { popup = Popup.Pin }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolCell(Tool("Upload", Glyph.Vector(Icons.Outlined.Upload)), Modifier.weight(1f)) { popup = Popup.Upload }
                ToolCell(
                    Tool(if (devices.isEmpty()) "Connected devices" else "Connected devices (${devices.size})", Glyph.Vector(Icons.Outlined.Devices)),
                    Modifier.weight(1f),
                ) { popup = Popup.Devices }
            }
            if (!session.pinOn) {
                Text(
                    "Joining by PIN was turned off after too many wrong tries. The QR code still works.",
                    color = ShutdownRed, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Received", fontSize = 13.sp, color = muted, fontWeight = FontWeight.SemiBold)
        }
        if (transfers.isEmpty()) {
            Text(
                "Files other devices send show here.",
                color = muted,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(transfers) { t -> TransferRow(t, t.state, t.received, onOpen = { t.file?.let(onOpenFile) }) }
            }
        }
        Button(
            onClick = onShutdown,
            colors = ButtonDefaults.buttonColors(containerColor = ShutdownRed, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) { Text("Shutdown", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }

    val close = { popup = null }
    when (popup) {
        Popup.Qr -> {
            val qr = remember(r) { qrCode("http://${r.address}/#k=${session.key}") }
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Scan to join") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Image(
                            qr.asImageBitmap(), contentDescription = "QR code",
                            modifier = Modifier.size(240.dp).clip(RoundedCornerShape(8.dp)).background(Color.White),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Scan with the other device's camera. It only asks for a name, no PIN.", color = muted, fontSize = 13.sp)
                    }
                },
                confirmButton = { TextButton(onClick = close) { Text("Close", color = AccentBlue) } },
            )
        }
        Popup.Pin -> AlertDialog(
            onDismissRequest = close,
            title = { Text("PIN") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pin = remember(changes) { session.pin }
                        Text(pin.chunked(3).joinToString(" "), fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { session.newPin() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "New PIN", tint = AccentBlue)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Type it on the other device after opening ${r.address}. It's new every time sharing starts, " +
                            "or when you tap the arrow for a new one (devices already in stay in).",
                        color = muted, fontSize = 13.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = close) { Text("Close", color = AccentBlue) } },
        )
        Popup.Upload -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Upload") },
            text = {
                Column {
                    val shared = remember(changes) { session.folders().firstOrNull { it.first == session.hostName }?.second?.size ?: 0 }
                    ChoiceRow(Icons.AutoMirrored.Outlined.List, "List of shared files", "$shared shared") { popup = Popup.Shared }
                    ChoiceRow(Icons.Outlined.Upload, "Upload new file", "Pick files on this phone to share") { popup = null; onUploadNew() }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        Popup.Shared -> {
            val files = remember(changes) { session.folders().firstOrNull { it.first == session.hostName }?.second.orEmpty() }
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Shared files") },
                text = {
                    if (files.isEmpty()) {
                        Text("Nothing is shared yet.", color = muted)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(files, key = { it.id }) { f ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                        Text(f.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            shownPath(f.file), color = muted.copy(alpha = 0.7f), fontSize = 12.sp,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(onClick = { session.unshare(f.id) }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Stop sharing ${f.file.name}", tint = muted)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = close) { Text("Close", color = AccentBlue) } },
            )
        }
        Popup.Devices -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Connected devices") },
            text = {
                if (devices.isEmpty()) {
                    Text("Nobody has joined yet.", color = muted)
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(devices, key = { it.token }) { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Devices, contentDescription = null, tint = muted)
                                Spacer(Modifier.width(12.dp))
                                Text(d.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { session.remove(d) }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Disconnect ${d.name}", tint = muted)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text("Close", color = AccentBlue) } },
        )
        null -> Unit
    }
}

private fun shownPath(f: File): String {
    val dir = f.parentFile?.path ?: return ""
    val root = Environment.getExternalStorageDirectory().path
    return when {
        dir == root -> "Internal storage"
        dir.startsWith("$root/") -> dir.removePrefix("$root/")
        else -> dir
    }
}

@Composable
private fun SmallButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Text(label, fontSize = 11.sp)
    }
}

@Composable
private fun ChoiceRow(icon: ImageVector, title: String, note: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AccentBlue)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title)
            Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TransferRow(t: Transfer, state: TransferState, received: Long, onOpen: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val pct = if (t.size == 0L) 100 else (received * 100 / t.size).toInt()
    val status = when (state) {
        TransferState.Waiting -> "Waiting for your approval"
        TransferState.Rejected -> "Rejected"
        TransferState.Approved -> "Approved, waiting for it"
        TransferState.Downloading -> "Downloading · $pct%"
        TransferState.Done -> "Downloaded · ${fileSize(t.size)}"
        TransferState.Failed -> "Didn't finish"
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = state == TransferState.Done, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.InsertDriveFile, contentDescription = null, tint = muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "from ${t.from} · $status",
                fontSize = 12.sp,
                color = if (state == TransferState.Rejected || state == TransferState.Failed) ShutdownRed else muted,
            )
            if (state == TransferState.Downloading) {
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    color = AccentBlue,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun ShareApprovals() {
    val ctx = LocalContext.current
    val running by NetworkShare.running.collectAsState()
    val session = running?.session ?: return
    val changes by session.changes.collectAsState()
    val offer = remember(changes) { session.pending().firstOrNull() } ?: return
    var sure by remember(offer) { mutableStateOf(false) }

    fun answer(approve: Boolean, all: Boolean = false) {
        session.answer(offer, approve, trustDevice = all)
        if (session.pending().isEmpty()) ShareService.answered(ctx)
    }

    if (sure) {
        AlertDialog(
            onDismissRequest = { sure = false },
            title = { Text("Approve everything from ${offer.device.name}?") },
            text = { Text("Until sharing is shut down, files ${offer.device.name} sends will be saved on this phone without asking.") },
            confirmButton = { TextButton(onClick = { answer(true, all = true) }) { Text("Approve all", color = AccentBlue) } },
            dismissButton = { TextButton(onClick = { sure = false }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Files to approve") },
        text = {
            Column {
                Text("${offer.device.name} wants to share ${NetworkShare.filesText(offer)} with you.")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(offer.files) { f ->
                        Text(
                            "${f.name} · ${fileSize(f.size)}",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    TextButton(onClick = { answer(false) }) { Text("Reject", color = ShutdownRed) }
                    TextButton(onClick = { answer(true) }) { Text("Approve", color = AccentBlue) }
                }
                TextButton(onClick = { sure = true }) {
                    Text("Approve all (this session)", color = AccentBlue, textAlign = TextAlign.End)
                }
            }
        },
    )
}

private fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

private fun qrCode(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val px = IntArray(size * size) { if (m[it % size, it / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
