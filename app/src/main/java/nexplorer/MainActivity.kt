package nexplorer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import nexplorer.download.DownloadsFlow
import nexplorer.ui.theme.AccentBlue
import nexplorer.ui.home.shareFiles
import nexplorer.ui.home.zipForSharing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import nexplorer.data.LibraryStore
import nexplorer.data.ReaderPrefs
import nexplorer.files.FileOps
import nexplorer.files.Formats
import nexplorer.files.IncomingFile
import nexplorer.files.FolderScanner
import nexplorer.files.Volumes
import nexplorer.ui.home.NotepadOrReaderDialog
import nexplorer.ui.home.RecentRow
import nexplorer.scan.ScanFlow
import nexplorer.scan.ScanStart
import nexplorer.textpdf.TextPdfFlow
import nexplorer.textpdf.TextStart
import nexplorer.textpdf.TextStartDialog
import nexplorer.wordpdf.WordPdfFlow
import nexplorer.merge.MergeFlow
import nexplorer.compress.CompressFlow
import nexplorer.manage.ManageFlow
import nexplorer.lock.LockFlow
import nexplorer.export.PdfToImageFlow
import nexplorer.export.PdfToTextFlow
import nexplorer.export.PdfToWordFlow
import nexplorer.lock.UnlockFlow
import nexplorer.share.NetworkShare
import nexplorer.share.NetworkShareFlow
import nexplorer.share.ShareApprovals
import nexplorer.ui.ToolsScreen
import nexplorer.ui.home.FileActionCallbacks
import nexplorer.reader.AppTheme
import nexplorer.reader.ReaderTheme
import nexplorer.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import nexplorer.files.DeviceIndex
import nexplorer.files.ExplorerSort
import nexplorer.files.FileTypes
import nexplorer.ui.explorer.ArchiveScreen
import nexplorer.ui.explorer.ImageViewer
import nexplorer.ui.explorer.TextEditorScreen
import nexplorer.ui.explorer.WebViewerScreen
import nexplorer.ui.home.openWithApp
import nexplorer.files.FileSearch
import androidx.compose.runtime.key
import nexplorer.ui.reader.ReaderScreen
import nexplorer.ui.search.SearchResults
import kotlinx.coroutines.launch
import java.io.File
import nexplorer.ui.MainScreen
import nexplorer.ui.explorer.RecycleBinScreen
import nexplorer.files.RecycleBin
import nexplorer.jobs.FileJobs
import nexplorer.ui.Placeholder
import nexplorer.ui.home.HomeScreen
import nexplorer.ui.theme.NExplorerTheme

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Intent?>(null)

    private var openShare by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) incoming = intent
        if (intent.getBooleanExtra(OPEN_SHARE, false)) openShare = true
    }

    companion object {
        const val OPEN_SHARE = "open_network_share"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) incoming = intent
        if (savedInstanceState == null && intent?.getBooleanExtra(OPEN_SHARE, false) == true) openShare = true
        nexplorer.doc.SystemFonts.install()
        enableEdgeToEdge()
        val store = LibraryStore(this)
        val prefs = ReaderPrefs(this)
        setContent {
            val appTheme by prefs.appTheme.collectAsState(initial = AppTheme.FollowSystem)
            val dark = when (appTheme) {
                AppTheme.FollowSystem -> isSystemInDarkTheme()
                AppTheme.Light -> false
                AppTheme.Dark -> true
            }
            val view = LocalView.current
            LaunchedEffect(Unit) { prefs.migrateTheme(appDark = dark) }
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            NExplorerTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionGate { App(store, prefs, dark, incoming, onIncomingDone = { incoming = null }, onLeave = ::finish, openShare = openShare, onOpenShareDone = { openShare = false }) }
                }
            }
        }
    }
}

@Composable
private fun App(
    store: LibraryStore,
    prefs: ReaderPrefs,
    appDark: Boolean,
    incoming: Intent?,
    onIncomingDone: () -> Unit,
    onLeave: () -> Unit,
    openShare: Boolean,
    onOpenShareDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var volumes by remember { mutableStateOf(Volumes.list(ctx)) }
    var volumePath by rememberSaveable { mutableStateOf<String?>(null) }
    val index = remember { DeviceIndex(File(ctx.cacheDir, "index.txt")) }
    fun rescanEverything() {
        volumes = Volumes.list(ctx)
        index.refresh(scope, volumes.map { it.root })
    }
    LaunchedEffect(Unit) {
        FolderScanner.hidden = store.hidden.first()
        index.refresh(scope, volumes.map { it.root })
    }
    LaunchedEffect(Unit) { NetworkShare.received.collect { index.add(it) } }
    val hidden by store.hidden.collectAsState(initial = emptySet())
    val opened by store.opened.collectAsState(initial = emptySet())
    val allFiles by index.files.collectAsState()
    var results by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(allFiles, query, hidden) {
        results = withContext(Dispatchers.Default) { FileSearch.filter(allFiles, index.lowerNames(allFiles), query) }
    }
    var homeReset by remember { mutableIntStateOf(0) }
    var toolsReset by remember { mutableIntStateOf(0) }
    var settingsReset by remember { mutableIntStateOf(0) }
    val binOn by prefs.recycleBin.collectAsState(initial = false)
    val recents by prefs.recents.collectAsState(initial = emptyList())
    var showRecents by remember { mutableIntStateOf(0) }

    var openPath by rememberSaveable { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf<OpenTool?>(null) }
    LaunchedEffect(openShare) {
        if (!openShare) return@LaunchedEffect
        tool = OpenTool.NetworkShare
        onOpenShareDone()
    }
    var askTextStart by remember { mutableStateOf(false) }
    var savedPictures by remember { mutableStateOf<Pair<File, Int>?>(null) }
    var webPageInReader by rememberSaveable { mutableStateOf(false) }
    val open: (File) -> Unit = {
        keyboard?.hide()
        focus.clearFocus()
        openPath = it.path
        webPageInReader = false
        scope.launch {
            val copy = IncomingFile.isCopy(ctx, it)
            prefs.setRecent(it.path, inList = !copy)
            if (!copy) store.setOpened(listOf(it.path), true)
        }
    }
    var explorer by rememberSaveable { mutableStateOf(true) }
    val explorerSort by prefs.explorerSort.collectAsState(initial = ExplorerSort())
    var viewing by remember { mutableStateOf<Pair<List<File>, Int>?>(null) }
    var textFile by remember { mutableStateOf<File?>(null) }
    var notepadOrReader by remember { mutableStateOf<File?>(null) }
    var archive by remember { mutableStateOf<File?>(null) }
    var homeRefresh by remember { mutableIntStateOf(0) }
    var homeGoTo by remember { mutableStateOf<Pair<File, Int>?>(null) }
    var showHome by remember { mutableIntStateOf(0) }
    val showFolder: (File) -> Unit = { folder ->
        explorer = true
        query = ""
        showHome++
        homeGoTo = folder to ((homeGoTo?.second ?: 0) + 1)
    }
    LaunchedEffect(Unit) {
        FileJobs.finished.collect { madeFiles ->
            FolderScanner.invalidate()
            homeRefresh++
            if (madeFiles) index.refresh(scope, volumes.map { it.root })
        }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        FileJobs.started.collect {
            val app = ctx.getSharedPreferences("app", 0)
            val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needed && !app.getBoolean("asked_notifications", false)) {
                app.edit().putBoolean("asked_notifications", true).apply()
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { RecycleBin.purge(volumes.map { it.root }) } } }
    fun markOpened(f: File) { scope.launch { store.setOpened(listOf(f.path), true) } }
    val openWith: (File) -> Unit = { f ->
        if (openWithApp(ctx, f, choose = true)) markOpened(f)
    }

    val openAny: (File, List<File>) -> Unit = { f, listed ->
        when {
            FileTypes.isArchive(f.name) && !Formats.isReadable(f.name) -> {
                archive = f
                markOpened(f)
            }
            FileTypes.isImage(f.name) -> {
                val pictures = listed.filter { FileTypes.isImage(it.name) }.ifEmpty { listOf(f) }
                viewing = pictures to pictures.indexOf(f).coerceAtLeast(0)
                markOpened(f)
            }
            FileTypes.isNotepadOrReader(f.name) -> notepadOrReader = f
            FileTypes.isText(f.name) -> {
                textFile = f
                markOpened(f)
            }
            Formats.isReadable(f.name) -> open(f)
            else -> if (openWithApp(ctx, f, choose = false)) markOpened(f)
        }
    }
    val onDeleted: (File) -> Unit = {
        index.remove(it)
        scope.launch { store.openedRemoved(it.path) }
    }
    var fromOutside by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(incoming) {
        val i = incoming ?: return@LaunchedEffect
        val file = i.data?.let { withContext(Dispatchers.IO) { IncomingFile.resolve(ctx, it, i.type) } }
        onIncomingDone()
        if (file != null && Formats.isReadable(file.name)) {
            fromOutside = true
            open(file)
        } else {
            Toast.makeText(ctx, "Couldn't open this file", Toast.LENGTH_SHORT).show()
        }
    }
    val onMoved: (File, File) -> Unit = { old, new ->
        scope.launch {
            prefs.moved(old.path, new.path)
            store.openedMoved(old.path, new.path)
        }
        index.refresh(scope, volumes.map { it.root })
    }
    val onCopied: () -> Unit = { index.refresh(scope, volumes.map { it.root }) }
    val recentPath by prefs.recent.collectAsState(initial = null)
    val recentFile = remember(recentPath, openPath) { recentPath?.let(::File)?.takeIf { it.isFile } }

    Box(Modifier.safeDrawingPadding()) {
        MainScreen(
            homeContent = {
                HomeScreen(
                    store, volumes,
                    volumePath = volumePath,
                    onVolumePath = { volumePath = it },
                    explorer = explorer,
                    onExplorer = { explorer = it },
                    sort = explorerSort,
                    onSort = { s -> scope.launch { prefs.setExplorerSort(s) } },
                    onOpenFile = openAny,
                    onOpenWith = openWith,
                    onDeleted = onDeleted,
                    onMoved = onMoved,
                    onCopied = onCopied,
                    onRefresh = ::rescanEverything,
                    resetSignal = homeReset,
                    refreshSignal = homeRefresh,
                    goTo = homeGoTo,
                    binOn = binOn,
                    recentPaths = recents,
                    showRecents = showRecents,
                )
            },
            recentContent = {
                recentFile?.let {
                    RecentRow(
                        it,
                        onOpen = open,
                        onOpenFolder = { f ->
                            if (IncomingFile.isCopy(ctx, f)) {
                                Toast.makeText(ctx, "This file was opened from another app, so it isn't in any folder here.", Toast.LENGTH_LONG).show()
                            } else {
                                f.parentFile?.let(showFolder)
                            }
                        },
                        onRecents = { showRecents++ },
                    )
                }
            },
            settingsContent = { SettingsScreen(prefs, resetSignal = settingsReset) },
            toolsContent = {
                ToolsScreen(resetSignal = toolsReset) { t ->
                    if (t.label == "Text to PDF") {
                        askTextStart = true
                    } else if (t.label == "Recycle bin" && !binOn) {
                        Toast.makeText(ctx, "The recycle bin is off. Turn it on in Settings.", Toast.LENGTH_LONG).show()
                    } else {
                        tool = OpenTool.byLabel[t.label] ?: return@ToolsScreen false
                    }
                    true
                }
            },
            onHomeTabTap = { homeReset++ },
            onToolsTabTap = { toolsReset++ },
            onSettingsTabTap = { settingsReset++ },
            searchQuery = query,
            onSearchQueryChange = { query = it },
            searchContent = {
                SearchResults(
                    results = results,
                    query = query,
                    volumes = volumes,
                    onOpen = openAny,
                    actions = FileActionCallbacks(
                        onHide = { files ->
                            scope.launch { files.forEach { store.hide(it.path) } }
                            FolderScanner.hidden = hidden + files.map { it.path }
                        },
                        onDeleted = onDeleted,
                        onMoved = { old, new ->
                            index.remove(old)
                            onMoved(old, new)
                        },
                        onCopied = onCopied,
                        onChanged = {},
                        opened = opened,
                        onMarkOpened = { files, on -> scope.launch { store.setOpened(files.map { it.path }, on) } },
                        explorer = explorer,
                        onOpenWith = openWith,
                        onOpenFolder = showFolder,
                        binOn = binOn,
                    ),
                )
            },
            showHome = showHome,
        )
    }
    if (askTextStart) {
        TextStartDialog(
            onPick = { askTextStart = false; tool = if (it == TextStart.Import) OpenTool.TextImport else OpenTool.TextWrite },
            onDismiss = { askTextStart = false },
        )
    }
    val close = { tool = null }
    val saved: (File) -> Unit = { f ->
        tool = null
        index.refresh(scope, volumes.map { it.root })
        FolderScanner.invalidate()
        open(f)
    }
    when (tool) {
        OpenTool.Scanner -> ScanFlow(ScanStart.Scanner, volumes, onSaved = saved, onClose = close)
        OpenTool.ImageToPdf -> ScanFlow(ScanStart.Images, volumes, onSaved = saved, onClose = close)
        OpenTool.TextWrite, OpenTool.TextImport -> {
            val fileTheme by prefs.theme.collectAsState(initial = ReaderTheme.Light)
            TextPdfFlow(
                start = if (tool == OpenTool.TextImport) TextStart.Import else TextStart.Write,
                volumes = volumes,
                fileDark = fileTheme == ReaderTheme.Dark,
                onSaved = saved,
                onClose = close,
            )
        }
        OpenTool.WordToPdf -> WordPdfFlow(volumes, onSaved = saved, onClose = close)
        OpenTool.Merge -> MergeFlow(volumes, onSaved = saved, onClose = close)
        OpenTool.Compress -> CompressFlow(volumes, onSaved = saved, onClose = close)
        OpenTool.Manage -> ManageFlow(volumes, onSaved = saved, onClose = close)
        OpenTool.Lock -> LockFlow(volumes, onSaved = saved, onClose = close)
        OpenTool.Unlock -> UnlockFlow(volumes, onSaved = saved, onClose = close)
        OpenTool.PdfToText -> PdfToTextFlow(volumes, onSaved = saved, onClose = close)
        OpenTool.PdfToWord -> PdfToWordFlow(volumes, onSaved = saved, onClose = close)
        OpenTool.NetworkShare -> NetworkShareFlow(volumes, onOpenFile = { f -> openAny(f, listOf(f)) }, onClose = close)
        OpenTool.RecycleBin -> RecycleBinScreen(volumes, onClose = close)
        OpenTool.Downloads -> DownloadsFlow(
            volumes,
            onOpenFile = { f -> openAny(f, listOf(f)) },
            onOpenFolder = { dir ->
                tool = null
                showFolder(dir)
            },
            onClose = close,
        )
        OpenTool.PdfToImage -> PdfToImageFlow(
            volumes,
            onSaved = { f, n ->
                tool = null
                savedPictures = f to n
            },
            onClose = close,
        )
        null -> Unit
    }
    archive?.let { a ->
        ArchiveScreen(
            a, volumes,
            onOpen = { f -> openAny(f, listOf(f)) },
            onExtracted = { folder ->
                archive = null
                onCopied()
                showFolder(folder)
            },
            onClose = { archive = null },
        )
    }
    viewing?.let { (pictures, at) -> ImageViewer(pictures, at, onClose = { viewing = null; homeRefresh++ }) }
    textFile?.let { f -> TextEditorScreen(f, onClose = { textFile = null; homeRefresh++ }) }
    notepadOrReader?.let { f ->
        NotepadOrReaderDialog(
            f.name,
            onNotepad = {
                notepadOrReader = null
                textFile = f
                markOpened(f)
            },
            onReader = {
                notepadOrReader = null
                open(f)
                webPageInReader = true
            },
            onDismiss = { notepadOrReader = null },
            onWebPage = if (FileTypes.isWebPage(f.name)) {
                {
                    notepadOrReader = null
                    open(f)
                }
            } else {
                null
            },
        )
    }
    savedPictures?.let { (f, n) ->
        val where = f.parentFile?.name ?: ""
        AlertDialog(
            onDismissRequest = { savedPictures = null },
            title = { Text("Saved") },
            text = { Text(if (n == 1) "${f.name} in $where" else "$n pictures in $where/${f.name}") },
            confirmButton = {
                TextButton(onClick = {
                    savedPictures = null
                    if (n == 1) {
                        shareFiles(ctx, listOf(f))
                    } else {
                        scope.launch {
                            val zip = withContext(Dispatchers.IO) { runCatching { zipForSharing(ctx, f) }.getOrNull() }
                            if (zip == null) Toast.makeText(ctx, "Couldn't prepare the pictures", Toast.LENGTH_SHORT).show()
                            else shareFiles(ctx, listOf(zip))
                        }
                    }
                }) { Text("Share", color = AccentBlue) }
            },
            dismissButton = { TextButton(onClick = { savedPictures = null }) { Text("Done") } },
        )
    }
    ShareApprovals()
    openPath?.let { path ->
        key(path, webPageInReader) {
            val back = {
                openPath = null
                if (fromOutside) {
                    fromOutside = false
                    onLeave()
                }
            }
            if (FileTypes.isWebPage(path) && !webPageInReader) {
                val fileTheme by prefs.theme.collectAsState(initial = null)
                fileTheme?.let { WebViewerScreen(File(path), dark = it == ReaderTheme.Dark, prefs = prefs, onClose = back) }
            } else {
                ReaderScreen(File(path), appDark, onBack = back)
            }
        }
    }
}

private fun hasStorageAccess(ctx: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

@Composable
internal fun PermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasStorageAccess(ctx)) }
    LifecycleResumeEffect(Unit) {
        granted = hasStorageAccess(ctx)
        onPauseOrDispose { }
    }
    val legacyRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasStorageAccess(ctx) }

    fun ask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}"),
            )
            runCatching { ctx.startActivity(intent) }
                .onFailure { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            legacyRequest.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }

    if (granted) {
        content()
        return
    }

    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences("app", 0)
        if (!prefs.getBoolean("asked_storage", false)) {
            prefs.edit().putBoolean("asked_storage", true).apply()
            ask()
        }
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "NExplorer needs access to all files to find and open your documents.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = ::ask, modifier = Modifier.padding(top = 20.dp)) { Text("Grant access") }
    }
}

private enum class OpenTool(val label: String?) {
    Scanner("Scanner"), ImageToPdf("Image to PDF"), TextWrite(null), TextImport(null), WordToPdf("Word to PDF"),
    Merge("Merge PDFs"), Compress("Compress"), Manage("Manage PDF"), Lock("Lock PDF"), Unlock("Unlock PDF"),
    PdfToText("PDF to Text"), PdfToWord("PDF to Word"), PdfToImage("PDF to Image"), NetworkShare("Network Share"),
    RecycleBin("Recycle bin"), Downloads("Downloads");

    companion object {
        val byLabel: Map<String, OpenTool> = entries.mapNotNull { t -> t.label?.let { it to t } }.toMap()
    }
}
