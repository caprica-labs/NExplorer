package nexplorer.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

data class ScanPage(
    val id: Long,
    val source: String,
    val rotation: Int = 0,
    val mirrored: Boolean = false,
    val filter: Filter = Filter.Original,
    val crop: Quad = Quad.FULL,
)

object ScanImaging {
    fun loadOriginal(ctx: Context, source: String, maxSide: Int): Bitmap? {
        val uri = Uri.parse(source)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    val long = max(info.size.width, info.size.height)
                    if (long > maxSide) {
                        val k = maxSide.toFloat() / long
                        d.setTargetSize((info.size.width * k).roundToInt().coerceAtLeast(1), (info.size.height * k).roundToInt().coerceAtLeast(1))
                    }
                }
                return plain(bmp)
            }
        }
        return loadWithBitmapFactory(ctx, uri, maxSide) ?: loadWithMuPdf(ctx, uri, maxSide)
    }

    private fun loadWithMuPdf(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val image = Image(bytes)
        try {
            val long = max(image.width, image.height)
            val k = if (long > maxSide) maxSide.toFloat() / long else 1f
            val w = (image.width * k).roundToInt().coerceAtLeast(1)
            val h = (image.height * k).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            val dev = AndroidDrawDevice(bmp)
            try {
                dev.fillImage(image, com.artifex.mupdf.fitz.Matrix(w.toFloat(), 0f, 0f, h.toFloat(), 0f, 0f), 1f, 0)
                dev.close()
            } finally {
                dev.destroy()
            }
            bmp
        } finally {
            image.destroy()
        }
    }.getOrNull()

    private fun loadWithBitmapFactory(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                },
            )
        } ?: return null
        raw = plain(raw)
        val long = max(raw.width, raw.height)
        if (long > maxSide) {
            val k = maxSide.toFloat() / long
            raw = Bitmap.createScaledBitmap(raw, (raw.width * k).roundToInt().coerceAtLeast(1), (raw.height * k).roundToInt().coerceAtLeast(1), true)
                .also { if (it !== raw) raw.recycle() }
        }
        val exif = ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        val m = Matrix()
        when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { raw.recycle() }
    }.getOrNull()

    fun plain(b: Bitmap): Bitmap {
        val srgb = b.colorSpace == null || b.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)
        val gain = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && b.hasGainmap()
        if (b.config == Bitmap.Config.ARGB_8888 && srgb && !gain && !b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        b.recycle()
        return out
    }

    fun render(ctx: Context, page: ScanPage, maxSide: Int): Bitmap? {
        val original = loadOriginal(ctx, page.source, maxSide) ?: return null
        val cropped = if (page.crop.isFull()) original else straighten(original, page.crop).also { original.recycle() }
        return finish(cropped, page.rotation, page.mirrored, page.filter)
    }

    fun straighten(src: Bitmap, quad: Quad): Bitmap {
        val pts = quad.points.map { it.x * src.width to it.y * src.height }
        fun dist(a: Int, b: Int) = hypot(pts[a].first - pts[b].first, pts[a].second - pts[b].second)
        val w = max(dist(0, 1), dist(3, 2)).roundToInt().coerceAtLeast(1)
        val h = max(dist(0, 3), dist(1, 2)).roundToInt().coerceAtLeast(1)
        val m = Matrix()
        val from = floatArrayOf(
            pts[0].first, pts[0].second, pts[1].first, pts[1].second,
            pts[2].first, pts[2].second, pts[3].first, pts[3].second,
        )
        val to = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        m.setPolyToPoly(from, 0, to, 0, 4)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun finish(src: Bitmap, rotation: Int, mirrored: Boolean, filter: Filter): Bitmap {
        val m = Matrix()
        if (rotation % 360 != 0) m.postRotate(rotation.toFloat())
        if (mirrored) m.postScale(-1f, 1f)
        val turned = if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { src.recycle() }
        val cm = filter.matrix ?: return turned
        val out = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(turned, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(cm)) })
        turned.recycle()
        return out
    }

    fun cached(ctx: Context, page: ScanPage, quality: Int): File =
        File(ctx.filesDir, "scans/jpeg/${page.id}_${page.hashCode().toUInt()}_$quality.jpg")

    fun estimate(ctx: Context, pages: List<ScanPage>, qualities: List<Int>, cancelled: () -> Boolean = { false }): Map<Int, Long> {
        fun check() { if (cancelled()) throw CancellationException("Stopped") }
        val perPage = pages.map { page ->
            check()
            val files = qualities.associateWith { cached(ctx, page, it) }
            if (files.values.all { it.exists() }) return@map files.mapValues { it.value.length() }
            val bmp = render(ctx, page, maxSide = SizeEstimate.MAX_SIDE)?.let(::plain) ?: return@map emptyMap<Int, Long>()
            try {
                files.mapValues { (q, file) ->
                    check()
                    if (!file.exists()) {
                        file.parentFile?.mkdirs()
                        val part = File(file.path + ".part")
                        part.outputStream().buffered().use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }
                        part.renameTo(file)
                    }
                    file.length()
                }
            } finally {
                bmp.recycle()
            }
        }
        return SizeEstimate.total(perPage, qualities)
    }

    fun writePdf(ctx: Context, pages: List<ScanPage>, size: PageSize, quality: Int, out: File, onProgress: (Int) -> Unit = {}) {
        val doc = PDFDocument()
        try {
            pages.forEachIndexed { i, page ->
                val ready = cached(ctx, page, quality).takeIf { it.exists() }
                val jpeg: ByteArray
                val iw: Int
                val ih: Int
                if (ready != null) {
                    jpeg = ready.readBytes()
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
                    iw = o.outWidth
                    ih = o.outHeight
                } else {
                    val bmp = render(ctx, page, maxSide = SizeEstimate.MAX_SIDE)?.let(::plain) ?: error("Couldn't read page ${i + 1}")
                    jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                    iw = bmp.width
                    ih = bmp.height
                    bmp.recycle()
                }
                val image = Image(Buffer().apply { writeBytes(jpeg) })
                val place = PageSizes.place(iw, ih, size)
                val resources = doc.newDictionary()
                val xobjects = doc.newDictionary()
                xobjects.put("Im0", doc.addImage(image))
                resources.put("XObject", xobjects)
                val contents = "q ${pdfNum(place.w)} 0 0 ${pdfNum(place.h)} ${pdfNum(place.x)} ${pdfNum(place.y)} cm /Im0 Do Q"
                val pageObj = doc.addPage(Rect(0f, 0f, place.pageW, place.pageH), 0, resources, contents)
                doc.insertPage(doc.countPages(), pageObj)
                image.destroy()
                onProgress(i + 1)
            }
            out.parentFile?.mkdirs()
            doc.save(out.path, "compress,garbage")
        } finally {
            doc.destroy()
        }
    }
}
