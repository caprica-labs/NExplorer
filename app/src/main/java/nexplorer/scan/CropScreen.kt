package nexplorer.scan

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun CropScreen(page: ScanPage, onDone: (Quad) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var quad by remember { mutableStateOf(page.crop) }
    LaunchedEffect(page.source) {
        bmp = withContext(Dispatchers.IO) {
            ScanImaging.loadOriginal(ctx, page.source, 1600)?.let { ScanImaging.finish(it, page.rotation, page.mirrored, Filter.Original) }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Cancel", color = Color.White) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { quad = Quad.FULL }) { Text("Reset", color = Color.White) }
            TextButton(onClick = { onDone(quad) }) { Text("Done", color = AccentBlue, fontSize = 16.sp) }
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b == null) {
                CircularProgressIndicator(color = AccentBlue)
            } else {
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = minOf(boxW / b.width, boxH / b.height)
                val w = b.width * scale
                val h = b.height * scale
                val current by rememberUpdatedState(quad)
                fun toPx(p: P): Offset = Orient.toDisplay(p, page.rotation, page.mirrored).let { Offset(it.x * w, it.y * h) }
                fun toImage(o: Offset): P = Orient.fromDisplay(P(o.x / w, o.y / h), page.rotation, page.mirrored)

                val marginPx = with(density) { 32.dp.toPx() }
                Box(
                    Modifier
                        .requiredSize(with(density) { (w + 2 * marginPx).toDp() }, with(density) { (h + 2 * marginPx).toDp() })
                        .pointerInput(w, h) {
                            var handle = -1
                            var last = Offset.Zero
                            val grab = 36.dp.toPx()
                            val m = Offset(marginPx, marginPx)
                            detectDragGestures(
                                onDragStart = { at ->
                                    val start = at - m
                                    val hs = current.handles().map(::toPx)
                                    val nearest = hs.indices.minBy { (hs[it] - start).getDistance() }
                                    handle = if ((hs[nearest] - start).getDistance() <= grab) nearest else -1
                                    last = start
                                },
                                onDrag = { change, _ ->
                                    if (handle < 0) return@detectDragGestures
                                    change.consume()
                                    val pos = change.position - m
                                    quad = if (handle < 4) {
                                        val p = toImage(Offset(pos.x.coerceIn(0f, w), pos.y.coerceIn(0f, h)))
                                        current.moveCorner(handle, p.x, p.y)
                                    } else {
                                        val a = toImage(last)
                                        val c = toImage(pos)
                                        current.moveSide(handle - 4, c.x - a.x, c.y - a.y)
                                    }
                                    last = pos
                                },
                                onDragEnd = { handle = -1 },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
                    Image(b.asImageBitmap(), null, Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        val pts = quad.points.map(::toPx)
                        // Dim everything outside the crop.
                        val outside = Path().apply {
                            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                        }
                        drawPath(outside, Color.Black.copy(alpha = 0.5f))
                        val edge = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(edge, AccentBlue, style = Stroke(width = 2.dp.toPx()))
                    }
                    quad.handles().forEachIndexed { i, hp ->
                        val o = toPx(hp)
                        val d = if (i < 4) 22.dp else 16.dp
                        val half = with(density) { d.toPx() } / 2
                        Box(
                            Modifier
                                .offset { IntOffset((o.x - half).roundToInt(), (o.y - half).roundToInt()) }
                                .size(d)
                                .systemGestureExclusion { c -> androidx.compose.ui.geometry.Rect(-60f, -60f, c.size.width + 60f, c.size.height + 60f) }
                                .background(Color.White, androidx.compose.foundation.shape.CircleShape)
                                .padding(3.dp)
                                .background(AccentBlue, androidx.compose.foundation.shape.CircleShape),
                        )
                    }
                }
                }
            }
        }
        Text(
            "Drag the corners to the paper's corners. Side handles move a whole side.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}
