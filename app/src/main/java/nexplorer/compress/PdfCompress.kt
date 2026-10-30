package nexplorer.compress

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import nexplorer.doc.PdfLock
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

enum class Level(val label: String, val maxSide: Int, val quality: Int) {
    High("High quality", 2300, 80),
    Balanced("Balanced", 1750, 65),
    Small("Small", 1170, 50),
}

object PdfCompress {
    private const val MIN_SIDE = 64

    fun compress(
        src: File,
        password: String?,
        level: Level,
        out: File,
        cancelled: () -> Boolean = { false },
        onPicture: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val doc = PdfLock.open(src.path, password)
        try {
            val pdf = doc.asPDF() ?: error("Not a PDF")
            val pictures = (1 until pdf.countObjects()).mapNotNull { num ->
                runCatching { pdf.newIndirect(num, 0) }.getOrNull()?.takeIf(::isPicture)
            }
            pictures.forEachIndexed { i, obj ->
                if (cancelled()) throw CancellationException("Stopped")
                runCatching { shrink(pdf, obj, level) }
                onPicture(i + 1, pictures.size)
            }
            runCatching { pdf.subsetFonts() }
            out.parentFile?.mkdirs()
            runCatching { pdf.save(out.path, "garbage=deduplicate,compress,compress-fonts,compress-images,clean,objstms") }
                .onFailure { pdf.save(out.path, "garbage=deduplicate,compress") }
        } finally {
            doc.destroy()
        }
    }

    private fun isPicture(obj: PDFObject): Boolean {
        if (!obj.isStream || obj.get("Subtype").asNameOrNull() != "Image") return false
        if (obj.get("ImageMask").asBoolean()) return false
        if (!obj.get("SMask").isNull || !obj.get("Mask").isNull) return false
        if (obj.get("BitsPerComponent").toString() == "1") return false
        val filter = obj.get("Filter").let { f -> if (f.isArray) f.get(f.size() - 1) else f }.asNameOrNull()
        return filter != "JBIG2Decode" && filter != "CCITTFaxDecode"
    }

    private fun shrink(pdf: PDFDocument, obj: PDFObject, level: Level) {
        val image = pdf.loadImage(obj)
        try {
            val w = image.width
            val h = image.height
            if (w < MIN_SIDE || h < MIN_SIDE) return
            val k = minOf(1f, level.maxSide.toFloat() / max(w, h))
            val tw = (w * k).roundToInt().coerceAtLeast(1)
            val th = (h * k).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(android.graphics.Color.WHITE)
                val dev = AndroidDrawDevice(bmp)
                try {
                    dev.fillImage(image, Matrix(tw.toFloat(), 0f, 0f, th.toFloat(), 0f, 0f), 1f, 0)
                    dev.close()
                } finally {
                    dev.destroy()
                }
                val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, level.quality, it) }.toByteArray()
                if (jpeg.size >= obj.readRawStream().size * 0.9) return
                obj.writeRawStream(Buffer().apply { writeBytes(jpeg) })
                obj.put("Filter", pdf.newName("DCTDecode"))
                obj.put("ColorSpace", pdf.newName("DeviceRGB"))
                obj.put("BitsPerComponent", 8)
                obj.put("Width", tw)
                obj.put("Height", th)
                for (key in listOf("DecodeParms", "Decode")) obj.delete(key)
            } finally {
                bmp.recycle()
            }
        } finally {
            image.destroy()
        }
    }

    private fun PDFObject.asNameOrNull(): String? = if (isName) asName() else null
}
