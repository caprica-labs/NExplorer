package nexplorer.ui.explorer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import nexplorer.ui.ToolScreen
import nexplorer.ui.home.shareFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

@Composable
fun ImageViewer(images: List<File>, start: Int, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    var bars by remember { mutableStateOf(true) }
    LaunchedEffect(pager.currentPage) { zoomed = false }

    ToolScreen(background = Color.Black) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { images[it].path },
            modifier = Modifier.fillMaxSize(),
        ) { i ->
            ZoomableImage(
                images[i],
                onZoomed = { if (pager.currentPage == i) zoomed = it },
                onTap = { bars = !bars },
            )
        }
        if (bars && images.isNotEmpty()) {
            val f = images[pager.currentPage]
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White) }
                Text(f.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${pager.currentPage + 1} / ${images.size}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                IconButton(onClick = { shareFiles(ctx, listOf(f)) }) { Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File, onZoomed: (Boolean) -> Unit, onTap: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(file) { mutableStateOf(false) }
        LaunchedEffect(file) {
            val b = withContext(Dispatchers.IO) { runCatching { decode(file, max(w, h) * 2) }.getOrNull() }
            if (b == null) failed = true else bitmap = b
        }
        var scale by remember(file) { mutableFloatStateOf(1f) }
        var offset by remember(file) { mutableStateOf(Offset.Zero) }
        fun clamp(o: Offset, s: Float) = Offset(
            o.x.coerceIn(-(s - 1) * w / 2, (s - 1) * w / 2),
            o.y.coerceIn(-(s - 1) * h / 2, (s - 1) * h / 2),
        )
        val b = bitmap
        when {
            b != null -> Image(
                b.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(file) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = { at ->
                                if (scale > 1f) {
                                    scale = 1f; offset = Offset.Zero
                                } else {
                                    // Zoom in on the spot tapped.
                                    scale = 2.5f
                                    offset = clamp(Offset((w / 2f - at.x) * 1.5f, (h / 2f - at.y) * 1.5f), scale)
                                }
                                onZoomed(scale > 1f)
                            },
                        )
                    }
                    .pointerInput(file) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                if (fingers >= 2 || scale > 1f) {
                                    scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                                    offset = clamp(offset + event.calculatePan(), scale)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (scale < 1.05f) { scale = 1f; offset = Offset.Zero }
                            onZoomed(scale > 1f)
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            failed -> Text("This picture couldn't be shown", color = Color.White.copy(alpha = 0.7f))
            else -> Box(Modifier)
        }
    }
}

private fun decode(file: File, maxSide: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val side = max(info.size.width, info.size.height)
            var sample = 1
            while (side / (sample * 2) >= maxSide) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Can't decode")
    val degrees = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
    if (degrees == 0) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}
