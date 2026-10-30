package nexplorer.ui.reader

import android.graphics.RectF
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import nexplorer.reader.Bookmark
import nexplorer.ui.home.shareFiles
import nexplorer.wordpdf.Printing
import nexplorer.reader.Bookmarks
import nexplorer.reader.Selection
import nexplorer.reader.Signature
import nexplorer.reader.Signatures
import nexplorer.reader.TocEntry
import nexplorer.reader.TextPos
import nexplorer.reader.Prefetch
import nexplorer.reader.Zoom
import nexplorer.reader.TextBox
import nexplorer.reader.TextMeasure
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import nexplorer.reader.Hit
import nexplorer.reader.ReaderSearchState
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.data.ReaderPrefs
import nexplorer.doc.DocumentOpener
import nexplorer.doc.OpenResult
import nexplorer.files.EditedCopy
import nexplorer.files.IncomingFile
import nexplorer.ui.PasswordDialog
import nexplorer.doc.PageRenderer
import nexplorer.doc.PositionMapper
import nexplorer.doc.ReflowHtml
import nexplorer.files.FormatKind
import nexplorer.reader.Direction
import nexplorer.reader.Movement
import nexplorer.reader.ReaderTheme
import nexplorer.slides.SlidePdf
import nexplorer.ui.Placeholder
import nexplorer.ui.theme.NExplorerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Placing {
    data object Text : Placing
    data class Sign(val sig: Signature) : Placing
}

private data class MarkTarget(val page: Int, val annot: Int, val underline: Boolean)

private sealed interface Loaded {
    data object Loading : Loaded
    data class Ready(val renderer: PageRenderer, val kind: FormatKind, val isPdf: Boolean) : Loaded
    data class Failed(val message: String) : Loaded
    data class Locked(val wrong: Boolean) : Loaded
}

@Composable
fun ReaderScreen(file: File, appDark: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ReaderPrefs(ctx) }
    val theme by prefs.theme.collectAsState(initial = null)
    val dark = theme == ReaderTheme.Dark
    NExplorerTheme(dark = appDark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.systemBarsPadding().displayCutoutPadding()) {
                theme?.let { ReaderContent(file, prefs, it, dark, onBack) }
            }
        }
    }
}

@Composable
private fun ReaderContent(file: File, prefs: ReaderPrefs, theme: ReaderTheme, dark: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var shown by remember(file) { mutableStateOf(file) }
    val path = shown.path
    var loaded by remember { mutableStateOf<Loaded>(Loaded.Loading) }
    val settings by prefs.view.collectAsState(initial = null)
    var barsVisible by remember { mutableStateOf(true) }
    var showViewMode by remember { mutableStateOf(false) }

    var currentPage by remember { mutableIntStateOf(0) }
    var startFraction by remember { mutableStateOf<Float?>(null) }
    var epoch by remember { mutableIntStateOf(-1) }
    var pdfReflowed by remember { mutableStateOf(false) }

    var searchOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(ReaderSearchState()) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var jump by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    var editMode by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selectJob by remember { mutableStateOf<Job?>(null) }
    var wordJob by remember { mutableStateOf<Job?>(null) }
    var markTarget by remember { mutableStateOf<MarkTarget?>(null) }

    var textEdit by remember { mutableStateOf<TextEdit?>(null) }
    var nextTextId by remember { mutableIntStateOf(1) }
    var lastTextSize by remember { mutableFloatStateOf(TextBox.DEFAULT_SIZE) }
    var lastTextColor by remember { mutableIntStateOf(TextBox.DEFAULT_COLOR) }
    var signEdit by remember { mutableStateOf<SignEdit?>(null) }
    var placing by remember { mutableStateOf<Placing?>(null) }
    var showSignatures by remember { mutableStateOf(false) }
    var fontWidths by remember { mutableStateOf<FloatArray?>(null) }

    val fallbackPaint = remember { android.graphics.Paint().apply { textSize = 100f } }
    val measure = remember(fontWidths) {
        val widths = fontWidths
        TextMeasure.mixed(
            table = { c -> if (widths != null && c.code < widths.size) widths[c.code].takeIf { !it.isNaN() } else null },
            fallback = { run -> fallbackPaint.measureText(run) / 100f * 1.08f },
        )
    }

    fun fit(e: TextEdit): TextEdit {
        if (fontWidths == null) return e
        val lines = TextBox.wrap(e.text.text, e.rect.w - TextBox.PAD, e.size, measure)
        val h = maxOf(e.manualH, TextBox.neededHeight(lines.size, e.size))
        return if (h == e.rect.h) e else e.copy(rect = e.rect.copy(h = h))
    }
    fun changeText(e: TextEdit) {
        if (textEdit?.id != e.id) return
        textEdit = fit(e)
        lastTextSize = e.size
        lastTextColor = e.color
    }
    var nudge by remember { mutableStateOf<Pair<Float, Int>?>(null) }
    LaunchedEffect(textEdit == null) { if (textEdit == null) nudge = null }

    var canScrollBack by remember { mutableStateOf(false) }
    var canScrollFwd by remember { mutableStateOf(false) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f }

    var scrolling by remember { mutableStateOf(false) }
    var recentMove by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, currentPage) {
        recentMove = true
        if (!scrolling) {
            delay(1500)
            recentMove = false
        }
    }

    LaunchedEffect(epoch) {
        searchJob?.cancel()
        search = search.copy(hits = emptyList(), current = -1)
        jump = null
        selection = null
        markTarget = null
        textEdit = null
        signEdit = null
        placing = null
        resetZoom()
    }

    BackHandler(enabled = textEdit != null) { textEdit = null }
    BackHandler(enabled = signEdit != null) { signEdit = null }
    BackHandler(enabled = placing != null && textEdit == null && signEdit == null) { placing = null }
    val keyboard = LocalSoftwareKeyboardController.current
    var toolbarTop by remember { mutableStateOf<Float?>(null) }
    val keyboardTop = toolbarTop.takeIf { textEdit != null }

    val placed = remember { mutableStateMapOf<Int, PagePlace>() }
    val selRects = remember { mutableStateMapOf<Int, List<RectF>>() }

    fun at(w: Offset): TextPos? {
        val (page, p) = placed.entries.minByOrNull { it.value.distance(w) }?.toPair() ?: return null
        return TextPos(page, ((w.x - p.left) / p.k).coerceIn(0f, p.width / p.k), ((w.y - p.top) / p.k).coerceIn(0f, p.height / p.k))
    }

    fun selectWord(page: Int, pt: Offset) {
        val l = loaded as? Loaded.Ready ?: return
        selecting = true
        markTarget = null
        selectJob?.cancel()
        wordJob?.cancel()
        wordJob = scope.launch {
            val r = orNull { l.renderer.select(page, pt.x, pt.y, pt.x, pt.y) } ?: return@launch
            selection = if (r.rects.isEmpty()) null else Selection(TextPos(page, r.a.x, r.a.y), TextPos(page, r.b.x, r.b.y))
        }
    }

    fun selectTo(w: Offset?, fixed: TextPos?, done: Boolean) {
        val l = loaded as? Loaded.Ready ?: return
        selecting = !done
        if (w == null) return
        val anchor = fixed ?: selection?.start ?: return
        val pos = at(w) ?: return
        selectJob?.cancel()
        selectJob = scope.launch {
            selection = if (pos.before(anchor)) {
                val p = orNull { l.renderer.wordStart(pos.page, pos.x, pos.y) } ?: return@launch
                Selection(TextPos(pos.page, p.x, p.y), anchor)
            } else {
                Selection(anchor, pos)
            }
        }
    }

    var pendingEdit by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun saving(edit: () -> Unit) {
        if (EditedCopy.isEditedCopy(shown)) edit() else pendingEdit = edit
    }

    fun commitText() {
        val l = loaded as? Loaded.Ready ?: return
        val e = textEdit ?: return
        textEdit = null
        val text = e.text.text.trimEnd()
        if (text.isBlank() && e.annot == null) return
        saving { scope.launch {
            val ok = orNull {
                when {
                    text.isNotBlank() -> l.renderer.putText(
                        e.page, e.annot, e.rect.x, e.rect.y, e.rect.w, e.rect.h, e.rotation, text, e.size, TextBox.COLORS[e.color],
                    )
                    e.annot != null -> l.renderer.removeMark(e.page, e.annot)
                    else -> Unit
                }
            }
            if (ok == null) Toast.makeText(ctx, "Couldn't save the change to the file", Toast.LENGTH_SHORT).show()
        } }
    }

    fun commitSign() {
        val l = loaded as? Loaded.Ready ?: return
        val e = signEdit ?: return
        signEdit = null
        saving { scope.launch {
            if (orNull { l.renderer.putSignature(e.page, e.annot, e.rect, e.rotation, e.sig, TextBox.COLORS[e.color]) } == null) {
                Toast.makeText(ctx, "Couldn't save the change to the file", Toast.LENGTH_SHORT).show()
            }
        } }
    }

    fun removeSign() {
        val l = loaded as? Loaded.Ready ?: return
        val e = signEdit ?: return
        signEdit = null
        val annot = e.annot ?: return
        saving { scope.launch {
            if (orNull { l.renderer.removeMark(e.page, annot) } == null) {
                Toast.makeText(ctx, "Couldn't save the change to the file", Toast.LENGTH_SHORT).show()
            }
        } }
    }

    fun leaveEditMode() {
        commitText()
        commitSign()
        placing = null
        editMode = false
        selection = null
        markTarget = null
    }
    BackHandler(enabled = editMode && !searchOpen && textEdit == null && signEdit == null && placing == null) { leaveEditMode() }

    fun removeText() {
        val l = loaded as? Loaded.Ready ?: return
        val e = textEdit ?: return
        textEdit = null
        val annot = e.annot ?: return
        saving { scope.launch {
            if (orNull { l.renderer.removeMark(e.page, annot) } == null) {
                Toast.makeText(ctx, "Couldn't save the change to the file", Toast.LENGTH_SHORT).show()
            }
        } }
    }

    fun pageTap(page: Int, pt: Offset) {
        val l = loaded as? Loaded.Ready ?: return
        if (textEdit != null) { commitText(); return }
        if (signEdit != null) { commitSign(); return }
        if (selection != null || markTarget != null) { selection = null; markTarget = null; return }
        val writable = l.isPdf && !pdfReflowed
        if (!writable) { barsVisible = !barsVisible; return }
        scope.launch {
            val added = orNull { l.renderer.textAt(page, pt.x, pt.y) }
            if (added != null) {
                textEdit = fit(
                    TextEdit(
                        id = nextTextId++,
                        page = page,
                        rect = TextBox.Rect(added.x, added.y, added.w, added.h),
                        rotation = added.rotation,
                        manualH = added.h,
                        text = TextFieldValue(added.text, TextRange(added.text.length)),
                        size = added.size,
                        color = nearestColor(added.color),
                        annot = added.annot,
                    ),
                )
                return@launch
            }
            val sign = orNull { l.renderer.signatureAt(page, pt.x, pt.y) }
            if (sign != null) {
                signEdit = SignEdit(
                    id = nextTextId++,
                    page = page,
                    rect = TextBox.Rect(sign.x, sign.y, sign.w, sign.h),
                    rotation = sign.rotation,
                    sig = Signature("", "", 0, sign.width, sign.w, sign.h, sign.strokes),
                    color = nearestColor(sign.color),
                    annot = sign.annot,
                )
                return@launch
            }
            val hit = orNull { l.renderer.markAt(page, pt.x, pt.y) }
            if (hit != null) {
                markTarget = MarkTarget(page, hit.first, hit.second)
                return@launch
            }
            val what = placing
            if (what == null) { barsVisible = !barsVisible; return@launch }
            placing = null
            val size = orNull { l.renderer.pageSize(page) } ?: return@launch
            if (what is Placing.Sign) {
                signEdit = SignEdit(
                    id = nextTextId++,
                    page = page,
                    rect = Signatures.newAt(what.sig, pt.x, pt.y, size.width, size.height),
                    rotation = 0f,
                    sig = what.sig,
                    color = what.sig.color,
                    annot = null,
                )
                return@launch
            }
            val rect = TextBox.newAt(pt.x, pt.y, lastTextSize, size.width, size.height)
            textEdit = TextEdit(
                id = nextTextId++,
                page = page,
                rect = rect,
                rotation = 0f,
                manualH = rect.h,
                text = TextFieldValue(""),
                size = lastTextSize,
                color = lastTextColor,
                annot = null,
            )
        }
    }

    var readArea by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    var lastDrag by remember { mutableStateOf<Pair<Offset, TextPos?>?>(null) }
    val edgeZone = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }

    fun dragSelect(w: Offset?, fixed: TextPos?, done: Boolean) {
        selectTo(w, fixed, done)
        if (done || w == null) { autoScroll = 0f; lastDrag = null; return }
        lastDrag = w to fixed
        val a = readArea
        autoScroll = when {
            a.isEmpty -> 0f
            w.y < a.top + edgeZone -> -MAX_AUTO_SCROLL * ((a.top + edgeZone - w.y) / edgeZone).coerceIn(0.2f, 1f)
            w.y > a.bottom - edgeZone -> MAX_AUTO_SCROLL * ((w.y - (a.bottom - edgeZone)) / edgeZone).coerceIn(0.2f, 1f)
            else -> 0f
        }
    }

    var askSelectAll by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var progressJob by remember { mutableStateOf<Job?>(null) }
    var allText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selection) { if (selection?.all != true) allText = null }

    fun selectAll(long: Boolean) {
        val l = loaded as? Loaded.Ready ?: return
        progressJob = scope.launch {
            val sel = orNull { l.renderer.wholeDocument() } ?: return@launch
            selection = sel
            if (!long) return@launch
            val n = sel.end.page + 1
            val text = StringBuilder()
            for (i in 0 until n) {
                progress = Progress("Reading page ${i + 1} of $n", i, n, cancellable = true)
                if (i > 0) text.append(System.lineSeparator())
                text.append(orNull { l.renderer.pageText(sel, i) }.orEmpty())
            }
            allText = text.toString()
            progress = null
        }
    }

    fun mark(underline: Boolean) {
        val l = loaded as? Loaded.Ready ?: return
        val sel = selection ?: return
        selection = null
        val n = sel.pages.count()
        saving { scope.launch {
            val what = if (underline) "Underlining" else "Highlighting"
            val track: (Int) -> Unit = { i -> if (n > LONG_FILE) progress = Progress("$what page ${i + 1} of $n", i, n, cancellable = false) }
            val ok = orNull { l.renderer.addMark(sel, underline, track) }
            progress = null
            if (ok == null) Toast.makeText(ctx, "Couldn't save the change to the file", Toast.LENGTH_SHORT).show()
        } }
    }

    fun removeMark() {
        val l = loaded as? Loaded.Ready ?: return
        val t = markTarget ?: return
        markTarget = null
        saving { scope.launch {
            if (orNull { l.renderer.removeMark(t.page, t.annot) } == null) {
                Toast.makeText(ctx, "Couldn't save the change to the file", Toast.LENGTH_SHORT).show()
            }
        } }
    }

    var hasEdits by remember { mutableStateOf(false) }
    var askRemoveAll by remember { mutableStateOf(false) }
    val editRenderer = (loaded as? Loaded.Ready)?.takeIf { it.isPdf }?.renderer
    LaunchedEffect(editRenderer, editRenderer?.editVersion) {
        hasEdits = editRenderer != null && orNull { editRenderer.hasEdits() } == true
    }

    fun canEdit() = editMode && editRenderer != null && !pdfReflowed

    fun clearEditing() {
        selection = null
        markTarget = null
        textEdit = null
        signEdit = null
        placing = null
        keyboard?.hide()
    }

    fun undo() {
        val r = editRenderer ?: return
        clearEditing()
        scope.launch {
            if (orNull { r.undo() } == null) Toast.makeText(ctx, "Couldn't undo", Toast.LENGTH_SHORT).show()
        }
    }

    fun removeAll() {
        val r = editRenderer ?: return
        clearEditing()
        saving { scope.launch {
            if (orNull { r.removeAllEdits() } == null) {
                Toast.makeText(ctx, "Couldn't save the change to the file", Toast.LENGTH_SHORT).show()
            }
        } }
    }

    fun closeSearch() {
        searchJob?.cancel()
        search = ReaderSearchState()
        searchOpen = false
        jump = null
    }

    BackHandler(enabled = searchOpen) { closeSearch() }

    fun goToHit(s: ReaderSearchState) {
        search = s
        s.hits.getOrNull(s.current)?.let { jump = it.page to ((jump?.second ?: 0) + 1) }
    }

    fun runSearch() {
        val l = loaded as? Loaded.Ready ?: return
        val q = search.query.trim()
        searchJob?.cancel()
        search = search.copy(hits = emptyList(), current = -1)
        if (q.isEmpty()) return
        searchJob = scope.launch {
            searching = true
            try {
                for (i in 0 until l.renderer.pageCount) {
                    if (i >= l.renderer.pageCount) break
                    val rects = orNull { l.renderer.search(i, q) } ?: continue
                    if (rects.isEmpty()) continue
                    val hits = search.hits + rects.map { Hit(i, listOf(it)) }
                    val first = search.current < 0
                    search = search.copy(hits = hits, current = if (first) 0 else search.current)
                    if (first) goToHit(search)
                }
            } finally {
                searching = false
            }
        }
    }

    var pdfToc by remember(file) { mutableStateOf<Pair<List<TocEntry>, Int>?>(null) }
    var password by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file, password) {
        val r = withContext(Dispatchers.IO) { DocumentOpener.open(file, password) }
        loaded = when (r) {
            is OpenResult.Error -> Loaded.Failed(r.message)
            is OpenResult.NeedsPassword -> Loaded.Locked(r.wrong)
            is OpenResult.Ok -> {
                val renderer = PageRenderer(r.doc, path.takeIf { r.kind == FormatKind.FIXED }, password)
                if (orNull { renderer.init() } == null || renderer.pageCount == 0) {
                    renderer.close()
                    Loaded.Failed("This file couldn't be opened")
                } else {
                    startFraction = prefs.position(path)
                    val isPdf = orNull { renderer.isPdf() } == true && !SlidePdf.isSlides(file.name)
                    if (isPdf) {
                        fontWidths = orNull { renderer.helveticaWidths() }
                        pdfToc = orNull { renderer.outline() }?.let { it to renderer.pageCount }
                    }
                    Loaded.Ready(renderer, r.kind, isPdf)
                }
            }
        }
    }
    DisposableEffect(file) {
        onDispose {
            (loaded as? Loaded.Ready)?.renderer?.close()
            bitmapCache.evictAll()
        }
    }
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    fun savePosition() {
        val l = loaded as? Loaded.Ready ?: return
        if (epoch < 0) return
        val f = PositionMapper.toFraction(currentPage, l.renderer.pageCount)
        CoroutineScope(Dispatchers.IO + NonCancellable).launch { prefs.savePosition(path, f) }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { currentPage to epoch }.collectLatest {
            delay(600)
            savePosition()
        }
    }
    DisposableEffect(Unit) { onDispose { savePosition() } }

    val bookmarks by remember(path) { prefs.bookmarks(path) }.collectAsState(initial = emptyList())
    var menu by remember { mutableStateOf<ReaderMenu?>(null) }
    var editBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var toc by remember { mutableStateOf<List<TocEntry>?>(null) }
    fun goTo(page: Int) {
        menu = null
        jump = page to ((jump?.second ?: 0) + 1)
    }

    val isSelecting by rememberUpdatedState(selecting)
    var showJump by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF121212) else Color.White)) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            when {
                                markTarget != null -> markTarget = null
                                selection != null -> selection = null
                                else -> barsVisible = !barsVisible
                            }
                        },
                        onDoubleTap = { resetZoom() },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        flingJob?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPosition(first.uptimeMillis, first.position)
                        var total = Offset.Zero
                        var target: Zoom.DragTarget? = null
                        var pinched = false
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.filter { it.pressed }
                            if (down.size >= 2) {
                                pinched = true
                                val c = event.calculateCentroid(useCurrent = true)
                                val f = event.calculateZoom()
                                val p = event.calculatePan()
                                val (sx, ox) = Zoom.zoomAround(zoom, panX, c.x, f)
                                val oy = Zoom.zoomAround(zoom, panY, c.y, f).second
                                zoom = sx
                                panX = Zoom.clampOffset(ox + p.x, sx, w)
                                panY = Zoom.clampOffset(oy + p.y, sx, h)
                                event.changes.forEach { it.consume() }
                            } else if (down.size == 1 && zoom > 1f && !isSelecting && !pinched) {
                                val c = down[0]
                                val d = c.positionChange()
                                tracker.addPosition(c.uptimeMillis, c.position)
                                if (target == null) {
                                    total += d
                                    if (total.getDistance() > viewConfiguration.touchSlop) {
                                        val s = settings
                                        val vertical = s?.direction != Direction.Horizontal
                                        val along = if (vertical) total.y else total.x
                                        val pan = if (vertical) panY else panX
                                        val minPan = (if (vertical) h else w) * (1 - zoom)
                                        target = Zoom.dragTarget(
                                            dx = total.x,
                                            dy = total.y,
                                            verticalAxis = vertical,
                                            scrollingList = s?.movement != Movement.Paged,
                                            contentCanScroll = if (along < 0) canScrollFwd else canScrollBack,
                                            canPanAlong = if (along < 0) pan > minPan + 0.5f else pan < -0.5f,
                                        )
                                    }
                                }
                                if (target == Zoom.DragTarget.Pan) {
                                    panX = Zoom.clampOffset(panX + d.x, zoom, w)
                                    panY = Zoom.clampOffset(panY + d.y, zoom, h)
                                    c.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (target == Zoom.DragTarget.Pan) {
                            val v = tracker.calculateVelocity()
                            flingJob = scope.launch {
                                launch {
                                    AnimationState(panX, v.x).animateDecay(exponentialDecay()) {
                                        panX = Zoom.clampOffset(value, zoom, w)
                                    }
                                }
                                AnimationState(panY, v.y).animateDecay(exponentialDecay()) {
                                    panY = Zoom.clampOffset(value, zoom, h)
                                }
                            }
                        }
                    }
                },
        ) {
            val l = loaded
            val s = settings
            when {
                l is Loaded.Failed -> Placeholder(l.message)
                l is Loaded.Locked -> {
                    Placeholder("This PDF is locked")
                    PasswordDialog(file.name, l.wrong, onPassword = { password = it }, onDismiss = onBack)
                }
                l is Loaded.Ready && s != null -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val w = maxWidth.value
                    val h = maxHeight.value
                    LaunchedEffect(l, s.reflow, s.textSizePct, w, h) {
                        val r = l.renderer
                        val fraction = startFraction ?: PositionMapper.toFraction(currentPage, r.pageCount)
                        startFraction = null
                        val ok = orNull {
                            if (l.kind == FormatKind.FIXED && s.reflow != pdfReflowed) {
                                if (s.reflow) {
                                    r.replace(r.withDoc { ReflowHtml.fromDocument(it) })
                                } else {
                                    val orig = withContext(Dispatchers.IO) { DocumentOpener.open(file, password) }
                                    if (orig is OpenResult.Ok) r.replace(orig.doc) else error("reopen failed")
                                }
                                pdfReflowed = s.reflow
                            }
                            if (s.reflow) {
                                r.relayout(w * 0.75f, h * 0.75f, 11f * s.textSizePct / 100f)
                            } else {
                                r.relayout(420f, 595f, 11f)
                            }
                        }
                        if (ok == null || r.pageCount == 0) {
                            loaded = Loaded.Failed("This file couldn't be displayed")
                            return@LaunchedEffect
                        }
                        currentPage = PositionMapper.toPage(fraction, r.pageCount)
                        epoch++
                    }
                    if (epoch >= 0) {
                        LaunchedEffect(l.renderer, currentPage, epoch, s.direction, s.movement, l.renderer.editVersion) {
                            delay(250)
                            val radius = if (l.kind == FormatKind.FIXED) Prefetch.PDF_RADIUS else Prefetch.RADIUS
                            readAhead(l.renderer, currentPage, radius)
                        }
                        val byPage = remember(search.hits) { search.hits.groupBy { it.page } }
                        key(epoch, s.direction, s.movement) {
                            Box(
                                Modifier.fillMaxSize().onGloballyPositioned { readArea = it.boundsInWindow() }.clipToBounds().graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = panX
                                    translationY = panY
                                },
                            ) {
                                Pages(
                                    l.renderer, s, dark, currentPage,
                                    jump = jump,
                                    highlights = { byPage[it] },
                                    currentHit = search.hits.getOrNull(search.current),
                                    selection = selection,
                                    editMode = editMode,
                                    onSelectWord = ::selectWord,
                                    onSelectTo = ::dragSelect,
                                    autoScroll = autoScroll,
                                    onAutoScrolled = { lastDrag?.let { (w, fixed) -> selectTo(w, fixed, false) } },
                                    onPlaced = { i, p -> if (p == null) placed.remove(i) else placed[i] = p },
                                    onSelRects = { i, r -> if (r == null) selRects.remove(i) else selRects[i] = r },
                                    onPageTap = ::pageTap,
                                    onScrolling = { scrolling = it },
                                    onLimits = { back, fwd -> canScrollBack = back; canScrollFwd = fwd },
                                    textEdit = textEdit,
                                    onTextEdit = ::changeText,
                                    signEdit = signEdit,
                                    onSignEdit = { e -> if (signEdit?.id == e.id) signEdit = e },
                                    keyboardTop = keyboardTop,
                                    onOffscreen = { px -> if (nudge?.first != px) nudge = px to ((nudge?.second ?: 0) + 1) },
                                    nudge = nudge,
                                    onJumpDone = { jump = null },
                                ) { currentPage = it }
                                selection?.let { sel ->
                                    SelectionHandles(sel, placed, selRects) { w, fixed, done -> dragSelect(w, fixed, done) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (askRemoveAll) {
            AlertDialog(
                onDismissRequest = { askRemoveAll = false },
                title = { Text("Remove all edits?") },
                text = { Text("All highlights, underlines, added text and signatures in this file will be removed. Undo edit can bring them back while the file is open.") },
                confirmButton = { TextButton(onClick = { askRemoveAll = false; removeAll() }) { Text("Remove all", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { askRemoveAll = false }) { Text("Cancel") } },
            )
        }
        if (askSelectAll) {
            AlertDialog(
                onDismissRequest = { askSelectAll = false },
                title = { Text("This file is big") },
                text = { Text("It has ${(loaded as? Loaded.Ready)?.renderer?.pageCount ?: 0} pages, so selecting all of it can take a while. Continue?") },
                confirmButton = { TextButton(onClick = { askSelectAll = false; selectAll(long = true) }) { Text("Continue", color = AccentBlue) } },
                dismissButton = { TextButton(onClick = { askSelectAll = false }) { Text("Cancel") } },
            )
        }
        progress?.let { p ->
            ProgressDialog(p, onCancel = { progressJob?.cancel(); progress = null; selection = null })
        }
        pendingEdit?.let { edit ->
            val r = loaded as? Loaded.Ready
            EditedCopyDialog(
                original = shown,
                startFolder = if (IncomingFile.isCopy(ctx, shown)) {
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                } else {
                    shown.parentFile ?: android.os.Environment.getExternalStorageDirectory()
                },
                onSave = { target ->
                    pendingEdit = null
                    scope.launch {
                        r?.renderer?.saveEditsTo(target.path) ?: return@launch
                        prefs.copyBookmarks(shown.path, target.path)
                        nexplorer.data.LibraryStore(ctx).setOpened(listOf(target.path), true)
                        shown = target
                        prefs.setRecent(target.path)
                        edit()
                    }
                },
                onCancel = { pendingEdit = null },
            )
        }
        AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            val r = (loaded as? Loaded.Ready)?.renderer?.takeIf { epoch >= 0 }
            TopBar(
                name = shown.name,
                bookmarked = r?.let { Bookmarks.isMarked(bookmarks, currentPage, it.pageCount) },
                onBack = onBack,
                onBookmark = {
                    if (r != null) {
                        val new = Bookmarks.toggle(bookmarks, currentPage, r.pageCount)
                        scope.launch { prefs.setBookmarks(path, new) }
                    }
                },
                enabled = { m ->
                    when (m) {
                        ReaderMenu.Undo -> canEdit() && (editRenderer?.undoSteps ?: 0) > 0
                        ReaderMenu.RemoveAll -> canEdit() && hasEdits
                        else -> true
                    }
                },
                onMenu = { m ->
                    when (m) {
                        ReaderMenu.Undo -> undo()
                        ReaderMenu.Share -> shareFiles(ctx, listOf(shown))
                        ReaderMenu.Print -> Printing.file(ctx, shown, password)
                        ReaderMenu.RemoveAll -> askRemoveAll = true
                        else -> menu = m
                    }
                    if (m == ReaderMenu.Contents && r != null) {
                        toc = null
                        scope.launch {
                            val base = pdfToc
                            toc = if (pdfReflowed && base != null) {
                                Bookmarks.remap(base.first, base.second, r.pageCount)
                            } else {
                                orNull { r.outline() } ?: emptyList()
                            }
                        }
                    }
                },
            )
        }
        val ready = loaded as? Loaded.Ready
        if (ready != null && epoch >= 0) {
            AnimatedVisibility(
                barsVisible || recentMove,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = if (barsVisible) 66.dp else 10.dp),
            ) {
                Text(
                    "${currentPage + 1} / ${ready.renderer.pageCount}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                        .clickable { showJump = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            when (menu) {
                ReaderMenu.Undo, ReaderMenu.RemoveAll, ReaderMenu.Share, ReaderMenu.Print -> Unit
                ReaderMenu.Contents -> ContentsSheet(toc, currentPage, onGo = ::goTo, onDismiss = { menu = null })
                ReaderMenu.Bookmarks -> BookmarksSheet(
                    bookmarks,
                    ready.renderer.pageCount,
                    currentPage,
                    onGo = ::goTo,
                    onEdit = { editBookmark = it },
                    onDismiss = { menu = null },
                )
                null -> Unit
            }
            editBookmark?.let { b ->
                fun save(new: List<Bookmark>) {
                    editBookmark = null
                    scope.launch { prefs.setBookmarks(path, new) }
                }
                BookmarkDialog(
                    b,
                    onRename = { name -> save(bookmarks.map { if (it == b) it.copy(name = name) else it }) },
                    onDelete = { save(bookmarks - b) },
                    onDismiss = { editBookmark = null },
                )
            }
            if (showJump) {
                JumpToPageDialog(
                    current = currentPage,
                    pageCount = ready.renderer.pageCount,
                    onJump = { page ->
                        showJump = false
                        jump = page to ((jump?.second ?: 0) + 1)
                    },
                    onDismiss = { showJump = false },
                )
            }
        }
        val sel = selection
        val canMark = ready?.isPdf == true && !pdfReflowed
        val actionsModifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (barsVisible) 84.dp else 24.dp)
        if (sel != null && !selecting) {
            val clipboard = LocalClipboard.current
            Row(actionsModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Copy") {
                    val r = ready ?: return@ActionButton
                    selection = null
                    scope.launch {
                        val text = allText?.takeIf { sel.all } ?: orNull { r.renderer.selectionText(sel) }
                        if (text == null) {
                            Toast.makeText(ctx, "Couldn't copy the text", Toast.LENGTH_SHORT).show()
                        } else {
                            clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("text", text)))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (!sel.all) {
                    ActionButton("Select all") {
                        val r = ready ?: return@ActionButton
                        if (r.renderer.pageCount > LONG_FILE) askSelectAll = true else selectAll(long = false)
                    }
                }
                if (canMark) {
                    ActionButton("Highlight", swatch = HighlightYellow) { mark(underline = false) }
                    ActionButton("Underline", swatch = UnderlineGreen) { mark(underline = true) }
                }
            }
        }
        if (editMode && canMark && sel == null && markTarget == null && textEdit == null && signEdit == null && !searchOpen) {
            val what = placing
            Row(actionsModifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (what == null) {
                    ActionButton("Text") { placing = Placing.Text }
                    ActionButton("Sign") { showSignatures = true }
                } else {
                    Text(
                        if (what is Placing.Sign) "Tap where the signature goes" else "Tap where the text goes",
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                    ActionButton("Cancel") { placing = null }
                }
            }
        }
        markTarget?.let { t ->
            Row(actionsModifier) {
                ActionButton(if (t.underline) "Remove underline" else "Remove highlight") { removeMark() }
            }
        }
        if (searchOpen) {
            Box(Modifier.align(Alignment.BottomCenter).imePadding()) {
                ReaderSearchBar(
                    query = search.query,
                    counter = if (searching && search.hits.isEmpty()) "..." else search.counterText,
                    onQuery = { search = search.copy(query = it) },
                    onSubmit = ::runSearch,
                    onPrev = { goToHit(search.prev()) },
                    onNext = { goToHit(search.next()) },
                    onClose = ::closeSearch,
                )
            }
        } else if (textEdit == null && signEdit == null) {
            AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                BottomBar(
                    editMode = editMode,
                    onEdit = {
                        if (editMode) {
                            leaveEditMode()
                        } else if (loaded is Loaded.Ready) {
                            editMode = true
                        }
                    },
                    onViewMode = { if (settings != null) showViewMode = true },
                    onSearch = { if (loaded is Loaded.Ready) searchOpen = true },
                )
            }
        }
        signEdit?.let { e ->
            Box(Modifier.align(Alignment.BottomCenter)) {
                SignToolbar(
                    edit = e,
                    onChange = { signEdit = it },
                    onDone = ::commitSign,
                    onCancel = { signEdit = null },
                    onRemove = if (e.annot != null) ::removeSign else null,
                )
            }
        }
        if (showSignatures) {
            SignaturesScreen(
                onPick = { sig -> showSignatures = false; placing = Placing.Sign(sig) },
                onClose = { showSignatures = false },
            )
        }
        textEdit?.let { e ->
            Box(
                Modifier.align(Alignment.BottomCenter).imePadding()
                    .onGloballyPositioned { toolbarTop = it.boundsInWindow().top },
            ) {
                TextToolbar(
                    edit = e,
                    onChange = ::changeText,
                    onDone = { commitText(); keyboard?.hide() },
                    onCancel = { textEdit = null; keyboard?.hide() },
                    onRemove = if (e.annot != null) ({ removeText(); keyboard?.hide() }) else null,
                )
            }
        }
    }

    val s = settings
    if (showViewMode && s != null) {
        ViewModePanel(
            settings = s,
            theme = theme,
            onSettings = { new ->
                scope.launch { prefs.setView(new) }
            },
            onTheme = { t -> scope.launch { prefs.setTheme(t) } },
            onDismiss = { showViewMode = false },
        )
    }
}

internal suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    android.util.Log.w("NExplorer", "MuPDF call failed", t)
    null
}
