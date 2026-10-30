package nexplorer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import nexplorer.files.FileTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Thumbnails {
    private val VIDEOS = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    private fun isVideo(name: String) = ext(name) in VIDEOS
    private fun isPdf(name: String) = ext(name) == "pdf"

    fun canShow(name: String) = FileTypes.isImage(name) || isVideo(name) || isPdf(name)

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val makers = Dispatchers.IO.limitedParallelism(3)

    private fun key(file: File, px: Int) = "${file.path}|${file.lastModified()}|$px"

    fun cached(file: File, px: Int): Bitmap? = cache.get(key(file, px))

    suspend fun load(file: File, px: Int): Bitmap? {
        val k = key(file, px)
        cache.get(k)?.let { return it }
        if (k in failed) return null
        return withContext(makers) {
            val made = runCatching { make(file, px) }.getOrNull()
            if (made != null) cache.put(k, made) else failed += k
            made
        }
    }

    private fun make(file: File, px: Int): Bitmap? = when {
        FileTypes.isImage(file.name) -> picture(file, px)
        isVideo(file.name) -> video(file, px)
        isPdf(file.name) -> pdf(file, px)
        else -> null
    }

    private fun picture(file: File, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return ThumbnailUtils.createImageThumbnail(file, Size(px, px), null)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun video(file: File, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return ThumbnailUtils.createVideoThumbnail(file, Size(px, px), null)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val frame = r.frameAtTime ?: return null
            val scale = px.toFloat() / maxOf(frame.width, frame.height)
            return Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
        } finally {
            r.release()
        }
    }

    private fun pdf(file: File, px: Int): Bitmap? {
        val doc = Document.openDocument(file.path)
        try {
            if (doc.needsPassword() || doc.countPages() == 0) return null
            val page = doc.loadPage(0)
            try {
                val b = page.bounds
                val scale = px / maxOf(b.x1 - b.x0, b.y1 - b.y0)
                return AndroidDrawDevice.drawPage(page, Matrix(scale))
            } finally {
                page.destroy()
            }
        } finally {
            doc.destroy()
        }
    }
}

@Composable
fun FileThumbnail(file: File, size: Dp, fallback: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState(Thumbnails.cached(file, px), file, px) {
        if (value == null) value = Thumbnails.load(file, px)
    }
    val b = bmp
    if (b == null) {
        fallback()
    } else {
        Image(
            b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)),
        )
    }
}
