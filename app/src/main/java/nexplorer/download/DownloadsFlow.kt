package nexplorer.download

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nexplorer.files.Volume
import nexplorer.scan.Dropdown
import nexplorer.ui.OptionLabel
import nexplorer.ui.Segments
import nexplorer.ui.ToolScreen
import nexplorer.ui.home.formatSize
import nexplorer.ui.theme.AccentBlue
import java.io.File

private enum class Page(val title: String, val label: String, val icon: ImageVector) {
    Settings("Download settings", "Settings", Icons.Outlined.Settings),
    Finished("Finished downloads", "Finished", Icons.Outlined.CheckCircle),
    Active("Active downloads", "Active", Icons.Outlined.PlayCircle),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsFlow(volumes: List<Volume>, onOpenFile: (File) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    remember { Downloads.init(ctx) }
    val version by Downloads.version.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (Downloads.busy > 0) tick++
        }
    }
    var page by rememberSaveable { mutableStateOf(Page.Active) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf<List<Download>?>(null) }
    val list = remember(version, page) {
        when (page) {
            Page.Active -> Downloads.active()
            Page.Finished -> Downloads.finished()
            Page.Settings -> emptyList()
        }
    }
    LaunchedEffect(page) { selected = emptySet() }
    LaunchedEffect(list) { selected = selected.filter { id -> list.any { it.id == id } }.toSet() }

    fun back() {
        if (selected.isNotEmpty()) selected = emptySet() else onClose()
    }
    BackHandler(onBack = ::back)
    fun targets() = if (selected.isEmpty()) list else list.filter { it.id in selected }
    fun toggle(d: Download) {
        selected = if (d.id in selected) selected - d.id else selected + d.id
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ToolScreen {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text(
                    if (selected.isNotEmpty()) "${selected.size} selected" else page.title,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (page != Page.Settings) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Select all") }, onClick = {
                                menu = false
                                selected = list.map { it.id }.toSet()
                            })
                            if (page == Page.Active) {
                                DropdownMenuItem(text = { Text("Start") }, onClick = {
                                    menu = false
                                    Downloads.start(ctx, targets())
                                    selected = emptySet()
                                })
                                DropdownMenuItem(text = { Text("Stop") }, onClick = {
                                    menu = false
                                    Downloads.stop(targets())
                                    selected = emptySet()
                                })
                                DropdownMenuItem(text = { Text("Clear list") }, onClick = {
                                    menu = false
                                    targets().takeIf { it.isNotEmpty() }?.let { confirmClear = it }
                                })
                            } else {
                                DropdownMenuItem(text = { Text("Clear") }, onClick = {
                                    menu = false
                                    Downloads.clear(targets())
                                    selected = emptySet()
                                })
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                when {
                    page == Page.Settings -> SettingsPage()
                    list.isEmpty() -> Text(
                        if (page == Page.Active) "No active downloads. Tap Add to start one." else "Finished downloads show here.",
                        color = muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { d ->
                            val info = remember(d, tick, version) { d.info() }
                            val on = d.id in selected
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (on) AccentBlue.copy(alpha = 0.12f) else Color.Transparent)
                                    .combinedClickable(
                                        onClick = {
                                            when {
                                                selected.isNotEmpty() -> toggle(d)
                                                info.state == DownloadState.Done -> {
                                                    val f = d.file
                                                    if (f != null && f.exists()) onOpenFile(f)
                                                    else Toast.makeText(ctx, "This file has been moved or deleted", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        onLongClick = { toggle(d) },
                                    )
                                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DownloadRow(d, info)
                                when (info.state) {
                                    DownloadState.Running, DownloadState.Queued ->
                                        IconButton(onClick = { Downloads.stop(listOf(d)) }) { Icon(Icons.Outlined.Pause, contentDescription = "Stop") }
                                    DownloadState.Paused, DownloadState.Failed ->
                                        IconButton(onClick = { Downloads.start(ctx, listOf(d)) }) {
                                            Icon(Icons.Outlined.PlayArrow, contentDescription = if (info.state == DownloadState.Failed) "Try again" else "Start")
                                        }
                                    DownloadState.Done -> {}
                                }
                            }
                        }
                    }
                }
            }
            val itemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentBlue,
                selectedTextColor = AccentBlue,
                indicatorColor = Color.Transparent,
                unselectedIconColor = muted,
                unselectedTextColor = muted,
            )
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Page.entries.forEach { p ->
                    NavigationBarItem(
                        selected = page == p,
                        onClick = { page = p },
                        icon = { Icon(p.icon, contentDescription = p.label) },
                        label = { Text(p.label) },
                        colors = itemColors,
                    )
                }
                NavigationBarItem(
                    selected = false,
                    onClick = { adding = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = "Add") },
                    label = { Text("Add") },
                    colors = itemColors,
                )
            }
        }
    }

    if (adding) {
        AddDownloadDialog(
            volumes = volumes,
            onDismiss = { adding = false },
            onAdded = {
                adding = false
                page = Page.Active
            },
        )
    }
    confirmClear?.let { targets ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text(if (targets.size == 1) "Remove this download?" else "Remove ${targets.size} downloads?") },
            text = {
                Text(
                    if (targets.size == 1) "It's taken off the list, and what was downloaded so far is deleted."
                    else "They're taken off the list, and what was downloaded so far is deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Downloads.clear(targets)
                    confirmClear = null
                    selected = emptySet()
                }) { Text("Remove", color = ErrorRed) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text("Cancel", color = AccentBlue) } },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DownloadRow(d: Download, info: DownloadInfo) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val amount = if (info.total > 0) "${formatSize(info.done)} of ${formatSize(info.total)}" else formatSize(info.done)
    val status = when (info.state) {
        DownloadState.Running -> listOfNotNull(
            info.total.takeIf { it > 0 }?.let { "${info.done * 100 / it}%" },
            amount,
            info.speed.takeIf { it > 0 }?.let { "${formatSize(it)}/s" },
        ).joinToString(" · ")
        DownloadState.Queued -> "Waiting · $amount"
        DownloadState.Paused -> if (info.done > 0) "Stopped · $amount" else if (info.total > 0) "Not started · ${formatSize(info.total)}" else "Not started"
        DownloadState.Done -> "${formatSize(info.total)} · ${d.dir.name}"
        DownloadState.Failed -> "Didn't finish: ${info.error ?: "unknown error"}"
    }
    Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = muted)
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
        Text(info.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, fontSize = 12.sp, color = if (info.state == DownloadState.Failed) ErrorRed else muted, maxLines = 2)
        if (info.state != DownloadState.Done && info.state != DownloadState.Failed) {
            if (info.total > 0 && (info.done > 0 || info.state == DownloadState.Running)) {
                LinearProgressIndicator(
                    progress = { (info.done.toFloat() / info.total).coerceIn(0f, 1f) },
                    color = AccentBlue,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else if (info.state == DownloadState.Running) {
                LinearProgressIndicator(color = AccentBlue, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SettingsPage() {
    val ctx = LocalContext.current
    val o by DownloadSettings.options.collectAsState()
    var agents by remember { mutableStateOf(false) }
    var scheduler by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun set(change: (DownloadOptions) -> DownloadOptions) = DownloadSettings.update(ctx, change)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Create empty file first")
                Text("For faster downloads", fontSize = 12.sp, color = muted.copy(alpha = 0.7f))
            }
            Switch(
                checked = o.preallocate,
                onCheckedChange = { on -> set { it.copy(preallocate = on) } },
                colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue),
            )
        }
        OptionLabel("Simultaneous downloads")
        Segments((1..5).map { "$it" }, selected = o.simultaneous - 1) { i -> set { it.copy(simultaneous = i + 1) } }
        OptionLabel("Threads per download")
        Dropdown(label = "${o.threads}", options = (1..16).map { "$it" }) { i -> set { it.copy(threads = i + 1) } }
        OptionLabel("Minimum part size per thread")
        val sizes = listOf(1, 2, 4, 8, 16)
        Dropdown(label = "${o.minPartMb} MB", options = sizes.map { "$it MB" }) { i -> set { it.copy(minPartMb = sizes[i]) } }
        OptionLabel("User-agent")
        SettingRow(o.userAgent.label) { agents = true }
        OptionLabel("Scheduler")
        SettingRow(if (o.schedule) "On · ${DownloadPlan.clock(o.startMinute)} to ${DownloadPlan.clock(o.stopMinute)}" else "Off") { scheduler = true }
        if (o.schedule && !DownloadScheduler.canSchedule(ctx)) {
            Text(
                "Needs the \"Alarms & reminders\" permission. Open Scheduler to allow it.",
                fontSize = 12.sp,
                color = ErrorRed,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (agents) {
        AlertDialog(
            onDismissRequest = { agents = false },
            title = { Text("User-agent") },
            text = {
                Column {
                    Text("How NExplorer introduces itself to download servers.", fontSize = 13.sp, color = muted)
                    UserAgent.entries.forEach { ua ->
                        Row(
                            Modifier.fillMaxWidth().clickable { set { it.copy(userAgent = ua) } }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = o.userAgent == ua,
                                onClick = { set { it.copy(userAgent = ua) } },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentBlue),
                            )
                            Column {
                                Text(ua.label)
                                if (ua == UserAgent.Android) Text("Includes your phone's model", fontSize = 12.sp, color = muted)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { agents = false }) { Text("Done", color = AccentBlue) } },
        )
    }
    if (scheduler) SchedulerDialog(o, onDismiss = { scheduler = false })
}

@Composable
private fun SettingRow(value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
    ) { Text(value) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulerDialog(o: DownloadOptions, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(o.schedule) }
    var start by remember { mutableIntStateOf(o.startMinute) }
    var stop by remember { mutableIntStateOf(o.stopMinute) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val allowed = DownloadScheduler.canSchedule(ctx)

    fun askPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scheduler") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) "On" else "Off", modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { on = it }, colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue))
                }
                Text(
                    "Every day, downloads in Active downloads start at the start time and stop at the stop time.",
                    fontSize = 12.sp,
                    color = muted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TimeRow("Start time", DownloadPlan.clock(start)) { picking = 0 }
                TimeRow("Stop time", DownloadPlan.clock(stop)) { picking = 1 }
                if (on && !allowed) {
                    Text(
                        "NExplorer needs the \"Alarms & reminders\" permission to start on time. Saving opens the setting.",
                        fontSize = 12.sp,
                        color = ErrorRed,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = {
                DownloadSettings.update(ctx) { it.copy(schedule = on, startMinute = start, stopMinute = stop) }
                if (on && !allowed) askPermission()
                onDismiss()
            }) { Text("Save", color = AccentBlue) }
        },
    )

    picking?.let { which ->
        val initial = if (which == 0) start else stop
        val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (which == 0) "Start time" else "Stop time") },
            text = {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        selectorColor = AccentBlue,
                        timeSelectorSelectedContainerColor = AccentBlue.copy(alpha = 0.2f),
                    ),
                )
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    if (which == 0) start = m else stop = m
                    picking = null
                }) { Text("OK", color = AccentBlue) }
            },
        )
    }
}

@Composable
private fun TimeRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = AccentBlue, fontSize = 18.sp)
    }
}
