package nexplorer.ui.home

import nexplorer.ui.dragToSelect
import androidx.compose.foundation.lazy.rememberLazyListState
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.data.LibraryStore
import nexplorer.files.Entry
import nexplorer.files.FileOps
import nexplorer.files.FolderScanner
import nexplorer.files.Picking
import nexplorer.files.Volume
import nexplorer.ui.Placeholder
import nexplorer.ui.FileThumbnail
import nexplorer.ui.Thumbnails
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import nexplorer.files.Explorer
import nexplorer.files.ExplorerSort
import nexplorer.files.FileType
import nexplorer.files.FileTypes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    store: LibraryStore,
    volumes: List<Volume>,
    volumePath: String?,
    onVolumePath: (String) -> Unit,
    explorer: Boolean,
    onExplorer: (Boolean) -> Unit,
    sort: ExplorerSort,
    onSort: (ExplorerSort) -> Unit,
    onOpenFile: (file: File, listed: List<File>) -> Unit,
    onOpenWith: (File) -> Unit,
    onDeleted: (File) -> Unit,
    onMoved: (old: File, new: File) -> Unit,
    onCopied: () -> Unit,
    onRefresh: () -> Unit,
    resetSignal: Int,
    refreshSignal: Int = 0,
    goTo: Pair<File, Int>? = null,
    binOn: Boolean = false,
    recentPaths: List<String> = emptyList(),
    showRecents: Int = 0,
) {
    val volume = volumes.firstOrNull { it.root.path == volumePath } ?: volumes.first()
    val root = volume.root
    var volumeMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var newFileError by remember { mutableStateOf<String?>(null) }
    var newFolderError by remember { mutableStateOf<String?>(null) }
    var sorting by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hidden by store.hidden.collectAsState(initial = null)
    val opened by store.opened.collectAsState(initial = emptySet())
    var stack by remember { mutableStateOf(listOf<File>()) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var recents by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val positions = remember { HashMap<String, Pair<Int, Int>>() }
    var shown by remember { mutableStateOf<String?>(null) }

    val sel = rememberSelectionState()
    val current = stack.lastOrNull()

    fun showDir(target: File) {
        fun real(f: File) = runCatching { f.canonicalFile }.getOrDefault(f)
        val dir = real(target)
        val v = volumes.firstOrNull { real(it.root).let { r -> dir.path == r.path || dir.path.startsWith(r.path + "/") } } ?: return
        val rootPath = real(v.root).path
        onVolumePath(v.root.path)
        stack = generateSequence(dir) { it.parentFile }.takeWhile { it.path != rootPath }.toList().reversed()
        recents = false
        sel.clear()
    }
    val selecting = sel.active
    val chosen = entries.orEmpty().filter { it.file.path in sel.selected }
    val actions = FileActionCallbacks(
        onHide = { files -> scope.launch { files.forEach { store.hide(it.path) } } },
        onDeleted = onDeleted,
        onMoved = onMoved,
        onCopied = onCopied,
        onChanged = { refreshKey++ },
        opened = opened,
        onMarkOpened = { files, on -> scope.launch { store.setOpened(files.map { it.path }, on) } },
        explorer = explorer,
        onOpenWith = onOpenWith,
        binOn = binOn,
        onOpenFolder = if (recents) { dir -> showDir(dir) } else null,
    )

    LaunchedEffect(Unit) { FolderScanner.invalidate() }

    LaunchedEffect(resetSignal) { if (resetSignal > 0) { stack = emptyList(); recents = false; sel.clear() } }
    LaunchedEffect(stack, root) { recents = false }
    var recentsAsked by remember { mutableIntStateOf(showRecents) }
    LaunchedEffect(showRecents) {
        if (showRecents > recentsAsked) {
            recentsAsked = showRecents
            recents = true
            sel.clear()
        }
    }
    LaunchedEffect(current, root) { sel.clear() }
    LaunchedEffect(explorer) { sel.clear() }
    LaunchedEffect(goTo) { goTo?.first?.let(::showDir) }

    BackHandler(enabled = (stack.isNotEmpty() || recents) && !selecting) {
        if (recents) recents = false else stack = stack.dropLast(1)
    }
    BackHandler(enabled = selecting) { sel.clear() }

    LaunchedEffect(hidden, current, refreshKey, root, explorer, sort, refreshSignal, recents, recentPaths) {
        val h = hidden ?: return@LaunchedEffect
        val folder = if (recents) "recents" else "${root.path}|${current?.path}|$explorer"
        val moved = folder != shown
        if (moved) {
            shown?.let { positions[it] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            entries = null
        }
        entries = withContext(Dispatchers.IO) {
            FolderScanner.hidden = h
            when {
                recents -> recentPaths.map(::File).filter { it.isFile && !FolderScanner.isHidden(it) }
                    .map { Entry(it, false, 0, it.length(), it.lastModified()) }
                explorer -> Explorer.list(current ?: root, sort)
                else -> {
                    val listed = FolderScanner.children(current ?: root)
                        .map { e -> e.copy(size = if (e.isDir) 0L else e.file.length(), modified = e.file.lastModified()) }
                    Explorer.sorted(listed, sort)
                }
            }
        }
        shown = folder
        val back = positions[folder]
        val rows = entries.orEmpty().size
        if (moved && back != null && rows > 0) {
            withTimeoutOrNull(1_000) { snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it >= rows } }
            listState.scrollToItem(back.first, back.second)
        }
        sel.keepOnly(entries.orEmpty().map { it.file.path })
        refreshing = false
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(sel, chosen, entries.orEmpty().map { it.file.path }, actions)
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(12.dp))
                Text("NExplorer", fontSize = 22.sp, maxLines = 1)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { onExplorer(!explorer) }, modifier = Modifier.widthIn(max = 160.dp)) {
                        Text(
                            if (explorer) "< Explorer Mode >" else "< Reader Mode >",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 14.sp),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterEnd) {
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("New file") }, onClick = {
                                moreMenu = false
                                newFileError = null
                                newFile = true
                            })
                            DropdownMenuItem(text = { Text("New folder") }, onClick = {
                                moreMenu = false
                                newFolderError = null
                                newFolder = true
                            })
                            DropdownMenuItem(text = { Text("Sort by") }, onClick = { moreMenu = false; sorting = true })
                            DropdownMenuItem(text = { Text("Unhide") }, onClick = {
                                moreMenu = false
                                scope.launch { store.unhideAll() }
                                Toast.makeText(ctx, "Hidden items restored", Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 4.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (recents) {
                    IconButton(onClick = { recents = false }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                } else if (current != null) {
                    IconButton(onClick = { stack = stack.dropLast(1) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.width(4.dp))
                }
                if (recents) {
                    Text("Recent reads", fontSize = 18.sp, maxLines = 1)
                } else if (!explorer && current != null) {
                    Text(current.name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Box {
                        TextButton(onClick = { if (current == null || !explorer) volumeMenu = true else stack = emptyList() }) {
                            Text(
                                volume.label,
                                color = if (current == null) MaterialTheme.colorScheme.onBackground else AccentBlue,
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                            if (current == null) Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Choose storage")
                        }
                        DropdownMenu(
                            expanded = volumeMenu,
                            onDismissRequest = { volumeMenu = false },
                            border = BorderStroke(1.dp, Color(0xFFBDBDBD)),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            volumes.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v.label, color = if (v == volume) AccentBlue else MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        volumeMenu = false
                                        onVolumePath(v.root.path)
                                        stack = emptyList()
                                    },
                                )
                            }
                        }
                    }
                    stack.forEachIndexed { i, dir ->
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val last = i == stack.lastIndex
                        TextButton(onClick = { stack = stack.take(i + 1) }, enabled = !last) {
                            Text(
                                dir.name,
                                color = if (last) MaterialTheme.colorScheme.onBackground else AccentBlue,
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        val list = entries
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                FolderScanner.invalidate()
                onRefresh()
                refreshKey++
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().dragToSelect(
                    listState,
                    keys = { list.orEmpty().map { it.file.path } },
                    selected = { sel.selected },
                    onSelect = { sel.selected = it },
                ),
                state = listState,
            ) {
                val message = when {
                    list == null -> if (explorer || recents) null else "Scanning"
                    list.isEmpty() && recents -> "Nothing read yet"
                    list.isEmpty() && explorer -> "This folder is empty"
                    list.isEmpty() && current == null -> "No readable files found"
                    list.isEmpty() -> "No readable files"
                    else -> null
                }
                if (message != null) {
                    item { Box(Modifier.fillParentMaxSize()) { Placeholder(message) } }
                }
                val files = list.orEmpty().filter { !it.isDir }.map { it.file }
                items(list.orEmpty(), key = { it.file.path }) { e ->
                    val path = e.file.path
                    EntryRow(
                        e,
                        selected = if (selecting) path in sel.selected else null,
                        onClick = {
                            when {
                                selecting -> sel.toggle(path)
                                e.isDir -> stack = stack + e.file
                                else -> onOpenFile(e.file, files)
                            }
                        },
                        onLongClick = null,
                        above = if (recents) e.file.parent ?: "" else null,
                        opened = if (e.isDir || recents) null else path in opened,
                        icon = if (explorer || recents) typeIcon(e.file.name, e.isDir) else null,
                        subtitle = if (explorer || recents) explorerSubtitle(e.isDir, e.readableCount, e.size, extensionOf(e.file.name), e.modified) else null,
                        thumbnail = true,
                    )
                }
            }
        }
    }

    SelectionDialogs(sel, volumes, root, actions)

    if (newFolder) {
        NameInputDialog(
            title = "New folder",
            start = "New folder",
            confirm = "Create",
            error = newFolderError,
            onConfirm = { name ->
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Explorer.newFolder(current ?: root, name) } }
                    r.onSuccess {
                        newFolder = false
                        refreshKey++
                    }.onFailure {
                        newFolderError = when (it) {
                            is IllegalArgumentException -> "Enter a valid name"
                            is IllegalStateException -> if (it.message == "Name already used") "That name is already used here" else "Couldn't create the folder"
                            else -> "Couldn't create the folder"
                        }
                    }
                }
            },
            onDismiss = { newFolder = false },
        )
    }
    if (newFile) {
        RenameDialog(
            target = File(current ?: root, "New file.txt"),
            error = newFileError,
            asFile = true,
            title = "New file",
            confirm = "Create",
            onRename = { name ->
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Explorer.newFile(current ?: root, name) } }
                    r.onSuccess {
                        newFile = false
                        refreshKey++
                        onCopied()
                    }.onFailure {
                        newFileError = when (it) {
                            is IllegalArgumentException -> "Enter a valid name"
                            is IllegalStateException -> if (it.message == "Name already used") "That name is already used here" else "Couldn't create the file"
                            else -> "Couldn't create the file"
                        }
                    }
                }
            },
            onDismiss = { newFile = false },
        )
    }
    if (sorting) SortDialog(sort, onSort = onSort, onDismiss = { sorting = false })
}

fun extensionOf(name: String): String = name.lastIndexOf('.').let { if (it > 0) name.substring(it + 1) else "" }

fun typeIcon(name: String, isDir: Boolean): androidx.compose.ui.graphics.vector.ImageVector = when (FileTypes.of(name, isDir)) {
    FileType.Folder -> Icons.Outlined.Folder
    FileType.Image -> Icons.Outlined.Image
    FileType.Text -> Icons.AutoMirrored.Outlined.Article
    FileType.Archive -> Icons.Outlined.FolderZip
    FileType.Document -> if (name.endsWith(".pdf", ignoreCase = true)) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description
    FileType.Other -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(
    e: Entry,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    selected: Boolean? = null,
    menuOpen: Boolean = false,
    onDismissMenu: () -> Unit = {},
    above: String? = null,
    opened: Boolean? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    menuItems: @Composable ColumnScope.() -> Unit = {},
    thumbnail: Boolean = false,
    showArrow: Boolean = true,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .background(if (selected == true) AccentBlue.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.align(Alignment.End).padding(end = 16.dp)) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu, content = menuItems)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(if (thumbnail) Modifier.size(THUMB) else Modifier, contentAlignment = Alignment.Center) {
                val plain = @Composable {
                    Icon(
                        icon ?: if (e.isDir) Icons.Outlined.Folder else Icons.Outlined.Description,
                        contentDescription = null,
                        tint = muted,
                    )
                }
                when {
                    leading != null -> leading()
                    selected == true -> Icon(Icons.Outlined.CheckCircle, contentDescription = "Selected", tint = AccentBlue)
                    selected == false -> Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = "Not selected", tint = muted)
                    thumbnail && !e.isDir && Thumbnails.canShow(e.file.name) -> FileThumbnail(e.file, THUMB, plain)
                    else -> plain()
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (above != null) {
                    Text(above, color = muted.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (opened == false) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(AccentBlue))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        e.file.name,
                        color = if (opened == true) muted.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(subtitle ?: subtitle(e), color = muted, fontSize = 12.sp)
            }
            if (e.isDir && selected == null && showArrow) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = muted)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

private val THUMB = 40.dp

private fun subtitle(e: Entry): String =
    if (e.isDir) {
        if (e.readableCount == 1) "1 file" else "${e.readableCount} files"
    } else {
        "${formatSize(e.file.length())} · ${e.file.extension.uppercase()}"
    }

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "${bytes shr 10} KB"
    else -> "$bytes B"
}
