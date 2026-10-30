package nexplorer.ui.reader

import android.graphics.RectF
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import nexplorer.reader.DarkMode
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import nexplorer.reader.Selection
import nexplorer.reader.TextPos
import nexplorer.reader.Prefetch
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import nexplorer.reader.Hit
import nexplorer.ui.theme.AccentBlue
import android.graphics.Bitmap
import android.util.LruCache
import android.util.SizeF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import nexplorer.doc.PageRenderer
import nexplorer.reader.Direction
import nexplorer.reader.Movement
import nexplorer.reader.ViewSettings
import kotlinx.coroutines.delay

internal val bitmapCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

internal fun bitmapKey(renderer: PageRenderer, version: Int, edit: Int, index: Int, widthPx: Int) =
    "${System.identityHashCode(renderer)}-$version.$edit-$index-$widthPx"

private val darkPage = ColorFilter.colorMatrix(ColorMatrix(DarkMode.matrix))

internal suspend fun readAhead(renderer: PageRenderer, current: Int, radius: Int) {
    val width = renderer.lastRenderWidthPx.takeIf { it > 0 } ?: return
    val version = renderer.layoutVersion
    val edit = renderer.editVersion
    val order = Prefetch.order(current, renderer.pageCount, radius)
    var limit = order.size
    val kept = ArrayList<String>()
    for ((n, i) in order.withIndex()) {
        if (n >= limit) break
        val key = bitmapKey(renderer, version, edit, i, width)
        val bmp = bitmapCache.get(key)
            ?: orNull { renderer.render(i, width) }?.also { bitmapCache.put(key, it) }
            ?: continue
        kept.add(key)
        if (n == 0) limit = Prefetch.pagesThatFit(bitmapCache.maxSize().toLong(), bmp.byteCount.toLong(), radius)
    }
    kept.asReversed().forEach { bitmapCache.get(it) }
}

@Composable
internal fun Pages(
    renderer: PageRenderer,
    s: ViewSettings,
    dark: Boolean,
    startPage: Int,
    jump: Pair<Int, Int>?,
    highlights: (Int) -> List<Hit>?,
    currentHit: Hit?,
    selection: Selection?,
    editMode: Boolean,
    onSelectWord: (page: Int, pt: Offset) -> Unit,
    onSelectTo: (window: Offset?, fixed: TextPos?, done: Boolean) -> Unit,
    onPlaced: (page: Int, PagePlace?) -> Unit,
    onSelRects: (page: Int, List<RectF>?) -> Unit,
    onPageTap: (page: Int, pt: Offset) -> Unit,
    onScrolling: (Boolean) -> Unit,
    onLimits: (back: Boolean, fwd: Boolean) -> Unit,
    textEdit: TextEdit?,
    onTextEdit: (TextEdit) -> Unit,
    signEdit: SignEdit?,
    onSignEdit: (SignEdit) -> Unit,
    keyboardTop: Float?,
    onOffscreen: (Float) -> Unit,
    nudge: Pair<Float, Int>?,
    autoScroll: Float,
    onAutoScrolled: () -> Unit,
    onJumpDone: () -> Unit,
    onPage: (Int) -> Unit,
) {
    val count = renderer.pageCount
    val start = startPage.coerceIn(0, (count - 1).coerceAtLeast(0))
    val scrollsUp = s.movement == Movement.Scroll && s.direction == Direction.Vertical
    var lift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(textEdit == null) { if (textEdit == null) lift = 0f }
    val offscreen: (Float) -> Unit = if (scrollsUp) onOffscreen else { px -> lift += px }
    val page: @Composable (Int, Boolean, Modifier) -> Unit = { i, fit, mod ->
        PageImage(
            renderer, i, dark, fit, mod, highlights(i), currentHit?.takeIf { it.page == i },
            selection?.takeIf { i in it.pages }, editMode, onSelectWord, onSelectTo, onPlaced, onSelRects, onPageTap,
            textEdit?.takeIf { it.page == i }, onTextEdit, signEdit?.takeIf { it.page == i }, onSignEdit, keyboardTop, offscreen,
        )
    }
    Box(Modifier.fillMaxSize().offset { IntOffset(0, -lift.roundToInt()) }) {
    if (s.movement == Movement.Scroll) {
        val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
        LaunchedEffect(state) { snapshotFlow { pageInView(state, count) }.collect(onPage) }
        LaunchedEffect(state) { snapshotFlow { state.isScrollInProgress }.collect(onScrolling) }
        LaunchedEffect(state) {
            snapshotFlow { state.canScrollBackward to state.canScrollForward }.collect { onLimits(it.first, it.second) }
        }
        LaunchedEffect(jump) { jump?.let { state.scrollToItem(it.first.coerceIn(0, count - 1)); onJumpDone() } }
        LaunchedEffect(nudge) { nudge?.let { if (scrollsUp) state.scrollBy(it.first) } }
        val scrolled by rememberUpdatedState(onAutoScrolled)
        LaunchedEffect(autoScroll) {
            if (autoScroll == 0f || !scrollsUp) return@LaunchedEffect
            while (true) {
                if (state.scrollBy(autoScroll) == 0f) break
                scrolled()
                delay(16)
            }
        }
        if (s.direction == Direction.Vertical) {
            LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(top = 64.dp, bottom = 72.dp)) {
                items(count) { i -> page(i, false, Modifier.fillMaxWidth().padding(bottom = 4.dp)) }
            }
        } else {
            LazyRow(Modifier.fillMaxSize(), state) {
                items(count) { i -> page(i, true, Modifier.fillParentMaxSize().padding(horizontal = 2.dp)) }
            }
        }
    } else {
        val state = rememberPagerState(initialPage = start) { count }
        LaunchedEffect(state) { snapshotFlow { state.currentPage }.collect(onPage) }
        LaunchedEffect(state) { snapshotFlow { state.isScrollInProgress }.collect(onScrolling) }
        LaunchedEffect(jump) { jump?.let { state.scrollToPage(it.first.coerceIn(0, count - 1)); onJumpDone() } }
        if (s.direction == Direction.Vertical) {
            VerticalPager(state, Modifier.fillMaxSize()) { i -> page(i, true, Modifier.fillMaxSize()) }
        } else {
            HorizontalPager(state, Modifier.fillMaxSize()) { i -> page(i, true, Modifier.fillMaxSize()) }
        }
    }
    }
}

internal fun pageInView(state: LazyListState, count: Int): Int {
    if (!state.canScrollForward && count > 0) return count - 1
    val info = state.layoutInfo
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.firstOrNull { mid >= it.offset && mid < it.offset + it.size }?.index
        ?: state.firstVisibleItemIndex
}

@Composable
internal fun PageImage(
    renderer: PageRenderer,
    index: Int,
    dark: Boolean,
    fit: Boolean,
    modifier: Modifier,
    hits: List<Hit>?,
    currentHit: Hit?,
    selection: Selection?,
    editMode: Boolean,
    onSelectWord: (page: Int, pt: Offset) -> Unit,
    onSelectTo: (window: Offset?, fixed: TextPos?, done: Boolean) -> Unit,
    onPlaced: (page: Int, PagePlace?) -> Unit,
    onSelRects: (page: Int, List<RectF>?) -> Unit,
    onPageTap: (page: Int, pt: Offset) -> Unit,
    textEdit: TextEdit?,
    onTextEdit: (TextEdit) -> Unit,
    signEdit: SignEdit?,
    onSignEdit: (SignEdit) -> Unit,
    keyboardTop: Float?,
    onOffscreen: (Float) -> Unit,
) {
    val version = renderer.layoutVersion
    val pageCoords = remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(index) { onDispose { onPlaced(index, null); onSelRects(index, null) } }
    var size by remember(version, index) { mutableStateOf<SizeF?>(null) }
    LaunchedEffect(version, index) { size = orNull { renderer.pageSize(index) } }
    val ratio = size?.let { it.width / it.height } ?: (1f / 1.414f)
    val pageBg = if (dark) Color.Black else Color.White

    val content: @Composable () -> Unit = {
        size?.let { sz ->
            val editGestures = if (!editMode) {
                Modifier
            } else {
                Modifier
                    .pointerInput(sz) {
                        val scope = this
                        var last: Offset? = null
                        fun toPt(p: Offset) = p * (sz.width / scope.size.width)
                        fun window(p: Offset) = pageCoords.value?.localToWindow(p) ?: p
                        detectDragGesturesAfterLongPress(
                            onDragStart = { last = null; onSelectWord(index, toPt(it)) },
                            onDrag = { change, _ ->
                                change.consume()
                                last = window(change.position)
                                onSelectTo(last, null, false)
                            },
                            onDragEnd = { onSelectTo(last, null, true) },
                            onDragCancel = { onSelectTo(last, null, true) },
                        )
                    }
                    .pointerInput(sz) {
                        val scope = this
                        detectTapGestures(
                            onTap = { onPageTap(index, it * (sz.width / scope.size.width)) },
                            onLongPress = {},
                        )
                    }
            }
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .onGloballyPositioned { c ->
                        pageCoords.value = c
                        val b = c.boundsInWindow()
                        if (b.width > 0f) onPlaced(index, PagePlace(b.left, b.top, b.width / sz.width, b.width, b.height))
                    }
                    .then(editGestures),
            ) {
                val k = with(LocalDensity.current) { maxWidth.toPx() } / sz.width
                PageBitmap(renderer, index, version, dark)
                if (!hits.isNullOrEmpty()) Highlights(sz, hits, currentHit)
                var rects by remember(index) { mutableStateOf<List<RectF>>(emptyList()) }
                LaunchedEffect(selection, index, version) {
                    if (selection == null) { rects = emptyList(); onSelRects(index, null); return@LaunchedEffect }
                    rects = orNull { renderer.selectionRects(selection, index) }.orEmpty()
                    onSelRects(index, rects)
                }
                if (selection != null) SelectionRects(sz, rects)
                val (barPx, grabPx) = textBoxHandleSizes()
                if (textEdit != null) {
                    val current = rememberUpdatedState(textEdit)
                    val pos = remember { derivedStateOf { current.value.let { BoxPos(it.rect, it.rotation) } } }
                    val gestures = Modifier.boxGestures(pos, k, sz, barPx, grabPx, keepShape = false) { p, resized ->
                        val e = current.value
                        onTextEdit(e.copy(rect = p.rect, rotation = p.rotation, manualH = if (resized) p.rect.h else e.manualH))
                    }
                    Box(Modifier.fillMaxSize().then(gestures)) {
                        TextBoxOverlay(textEdit, k, dark, keyboardTop, onTextEdit, onOffscreen)
                    }
                }
                if (signEdit != null) {
                    val current = rememberUpdatedState(signEdit)
                    val pos = remember { derivedStateOf { current.value.let { BoxPos(it.rect, it.rotation) } } }
                    val gestures = Modifier.boxGestures(pos, k, sz, barPx, grabPx, keepShape = true) { p, _ ->
                        onSignEdit(current.value.copy(rect = p.rect, rotation = p.rotation))
                    }
                    Box(Modifier.fillMaxSize().then(gestures)) {
                        SignBoxOverlay(signEdit, k, dark)
                    }
                }
            }
        }
    }
    if (fit) {
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val w = minOf(maxWidth, maxHeight * ratio)
            Box(Modifier.size(w, w / ratio).background(pageBg)) { content() }
        }
    } else {
        Box(modifier.aspectRatio(ratio).background(pageBg)) { content() }
    }
}

@Composable
internal fun Highlights(pageSize: SizeF, hits: List<Hit>, current: Hit?) {
    Canvas(Modifier.fillMaxSize()) {
        val k = size.width / pageSize.width
        for (hit in hits) {
            val alpha = if (hit == current) 0.55f else 0.25f
            for (r in hit.rects) {
                drawRect(
                    AccentBlue.copy(alpha = alpha),
                    topLeft = Offset(r.left * k, r.top * k),
                    size = Size(r.width() * k, r.height() * k),
                )
            }
        }
    }
}

@Composable
internal fun SelectionRects(pageSize: SizeF, rects: List<RectF>) {
    Canvas(Modifier.fillMaxSize()) {
        val k = size.width / pageSize.width
        for (r in rects) {
            drawRect(
                AccentBlue.copy(alpha = 0.35f),
                topLeft = Offset(r.left * k, r.top * k),
                size = Size(r.width() * k, r.height() * k),
            )
        }
    }
}

private class CloseUp(val bitmap: Bitmap, val at: IntOffset, val size: IntSize)

@Composable
internal fun PageBitmap(renderer: PageRenderer, index: Int, version: Int, dark: Boolean) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { coords = it }) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        if (widthPx > 0) renderer.lastRenderWidthPx = widthPx
        val edit = renderer.editVersion
        val key = bitmapKey(renderer, version, edit, index, widthPx)
        var bmp by remember(version, index, widthPx) { mutableStateOf(bitmapCache.get(key)) }
        LaunchedEffect(key) {
            val cached = bitmapCache.get(key)
            if (cached != null) {
                bmp = cached
            } else if (widthPx > 0) {
                orNull { renderer.render(index, widthPx) }?.let {
                    bitmapCache.put(key, it)
                    bmp = it
                }
            }
        }
        val filter = if (dark) darkPage else null
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, colorFilter = filter, modifier = Modifier.fillMaxSize())
        }

        var closeUp by remember(key) { mutableStateOf<CloseUp?>(null) }
        LaunchedEffect(key) {
            var last: List<Int>? = null
            var drawn: List<Int>? = null
            while (true) {
                delay(250)
                val c = coords?.takeIf { it.isAttached } ?: continue
                val w = c.size.width.toFloat()
                if (w <= 0f) continue
                val scale = (c.localToWindow(Offset(w, 0f)).x - c.localToWindow(Offset.Zero).x) / w
                val seen = c.boundsInWindow()
                if (scale < 1.2f || seen.isEmpty) {
                    closeUp = null
                    drawn = null
                    last = null
                    continue
                }
                val a = c.windowToLocal(seen.topLeft)
                val b = c.windowToLocal(seen.bottomRight)
                val now = listOf(a.x, a.y, b.x, b.y, scale * 100).map { it.roundToInt() }
                if (now != last) { last = now; continue }
                if (now == drawn) continue
                val at = IntOffset(a.x.roundToInt(), a.y.roundToInt())
                val size = IntSize((b.x - a.x).roundToInt(), (b.y - a.y).roundToInt())
                if (size.width <= 0 || size.height <= 0) continue
                val sharp = orNull {
                    renderer.renderPart(
                        index, w * scale,
                        (at.x * scale).roundToInt(), (at.y * scale).roundToInt(),
                        (size.width * scale).roundToInt().coerceAtLeast(1), (size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                } ?: continue
                closeUp = CloseUp(sharp, at, size)
                drawn = now
            }
        }
        closeUp?.let { cu ->
            val image = remember(cu) { cu.bitmap.asImageBitmap() }
            Canvas(Modifier.fillMaxSize()) {
                drawImage(image, dstOffset = cu.at, dstSize = cu.size, colorFilter = filter, filterQuality = FilterQuality.High)
            }
        }
    }
}
