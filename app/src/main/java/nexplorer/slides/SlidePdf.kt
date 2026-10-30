package nexplorer.slides

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object SlidePdf {
    private const val VERSION = 1
    private const val KEEP = 3
    private const val BATCH = 8

    fun isSlides(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("pptx", "ppsx")

    fun asPdf(file: File, cacheDir: File): File {
        val dir = File(cacheDir, "slides").apply { mkdirs() }
        val out = File(dir, cacheName(file))
        if (out.isFile) {
            out.setLastModified(System.currentTimeMillis())
            return out
        }
        val tmp = File.createTempFile("deck", ".tmp", dir)
        try {
            SlideDeck.open(file).use { deck -> write(deck, tmp, dir) }
            if (!tmp.renameTo(out) && !out.isFile) error("Couldn't keep the slides")
        } finally {
            tmp.delete()
        }
        dir.listFiles { f -> f.name.endsWith(".pdf") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { it.delete() }
        return out
    }

    private fun cacheName(file: File): String {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}|$VERSION"
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return "${hash.take(20)}.pdf"
    }

    private fun write(deck: SlideDeck, out: File, dir: File) {
        val pageW = deck.width.roundToInt().coerceIn(1, 14400)
        val pageH = deck.height.roundToInt().coerceIn(1, 14400)
        val parts = ArrayList<File>()
        try {
            deck.slides.chunked(BATCH).forEachIndexed { b, chunk ->
                val painter = SlidePainter(deck)
                val pdf = PdfDocument()
                try {
                    chunk.forEachIndexed { i, slide ->
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, b * BATCH + i + 1).create())
                        page.canvas.scale(pageW / deck.width, pageH / deck.height)
                        painter.draw(page.canvas, slide, deck.width, deck.height)
                        pdf.finishPage(page)
                    }
                    val part = File.createTempFile("slides", ".part", dir)
                    parts += part
                    part.outputStream().use(pdf::writeTo)
                } finally {
                    pdf.close()
                }
            }
            join(parts, out)
        } finally {
            parts.forEach(File::delete)
        }
    }

    private fun join(parts: List<File>, out: File) {
        val dest = PDFDocument()
        try {
            for (part in parts) {
                val src = Document.openDocument(part.path) as PDFDocument
                try {
                    val map = dest.newPDFGraftMap()
                    for (i in 0 until src.countPages()) map.graftPage(-1, src, i)
                } finally {
                    src.destroy()
                }
            }
            val seen = HashSet<Int>()
            for (i in 0 until dest.countPages()) runCatching { smallerImages(dest, dest.findPage(i).get("Resources"), seen, 0) }
            dest.save(out.path, "compress,garbage=deduplicate")
        } finally {
            dest.destroy()
        }
    }

    private fun smallerImages(doc: PDFDocument, resources: PDFObject, seen: HashSet<Int>, depth: Int) {
        val xobjects = resources.get("XObject")
        if (!xobjects.isDictionary || depth > 8) return
        for (key in xobjects) {
            val obj = xobjects.get(key)
            if (obj.isIndirect && !seen.add(obj.asIndirect())) continue
            when (obj.get("Subtype").asName()) {
                "Form" -> smallerImages(doc, obj.get("Resources"), seen, depth + 1)
                "Image" -> runCatching { asJpeg(doc, obj) }
            }
        }
    }

    private fun asJpeg(doc: PDFDocument, img: PDFObject) {
        if (img.get("Filter").asName() != "FlateDecode" || img.get("BitsPerComponent").asInteger() != 8) return
        if (img.get("ColorSpace").asName() != "DeviceRGB") return
        val w = img.get("Width").asInteger()
        val h = img.get("Height").asInteger()
        if (w < 128 || h < 128 || w.toLong() * h > 16_000_000) return
        val raw = img.readRawStream()
        val pixels = img.readStream()
        if (pixels.size != w * h * 3) return
        val argb = IntArray(w * h) { i ->
            val o = i * 3
            (0xFF shl 24) or ((pixels[o].toInt() and 0xFF) shl 16) or ((pixels[o + 1].toInt() and 0xFF) shl 8) or (pixels[o + 2].toInt() and 0xFF)
        }
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        bmp.recycle()
        if (jpeg.size > raw.size * 0.6) return
        img.writeRawStream(Buffer().apply { writeBytes(jpeg) })
        img.put("Filter", doc.newName("DCTDecode"))
        img.delete("DecodeParms")
    }
}

private class SlidePainter(private val deck: SlideDeck) {
    private val bitmaps = HashMap<String, Bitmap?>()

    fun draw(canvas: Canvas, slide: Slide, w: Float, h: Float) {
        canvas.drawColor(Color.WHITE)
        val all = RectF(0f, 0f, w, h)
        slide.background?.let { paintFill(canvas, it, Path().apply { addRect(all, Path.Direction.CW) }, all) }
        for (item in slide.items) {
            runCatching {
                when (item) {
                    is Shape -> shape(canvas, item)
                    is Picture -> picture(canvas, item)
                    is Table -> table(canvas, item)
                    is Missing -> missing(canvas, item)
                }
            }
        }
    }

    // ---- Shapes ----

    private fun withFrame(canvas: Canvas, f: Frame, block: () -> Unit) {
        canvas.save()
        if (f.rot != 0f) canvas.rotate(f.rot, f.x + f.w / 2, f.y + f.h / 2)
        block()
        canvas.restore()
    }

    private fun flipped(canvas: Canvas, f: Frame, block: () -> Unit) {
        canvas.save()
        if (f.flipH || f.flipV) canvas.scale(if (f.flipH) -1f else 1f, if (f.flipV) -1f else 1f, f.x + f.w / 2, f.y + f.h / 2)
        block()
        canvas.restore()
    }

    private fun shape(canvas: Canvas, s: Shape) {
        val f = s.frame
        withFrame(canvas, f) {
            flipped(canvas, f) {
                val box = RectF(f.x, f.y, f.x + f.w, f.y + f.h)
                val outlines = Geometries.paths(s.geometry, box)
                for ((path, filled, stroked) in outlines) {
                    if (filled) s.fill?.let { paintFill(canvas, it, path, box) }
                    if (stroked) s.line?.let { stroke(canvas, it, path, open = !filled || Geometries.isLine(s.geometry)) }
                }
            }
            s.text?.let { text ->
                val tf = s.textFrame
                val box = if (tf != null) RectF(tf.x, tf.y, tf.x + tf.w, tf.y + tf.h) else RectF(f.x, f.y, f.x + f.w, f.y + f.h)
                drawText(canvas, text, box)
            }
        }
    }

    private fun paintFill(canvas: Canvas, fill: Fill, path: Path, box: RectF) {
        when (fill) {
            NoFill -> Unit
            is Solid -> canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.color })
            is Gradient -> canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient(fill, box) })
            is ImageFill -> {
                val bmp = bitmap(fill.image) ?: return
                canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(bmp, null, box, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                canvas.restore()
            }
        }
    }

    private fun gradient(g: Gradient, box: RectF): Shader {
        val colors = g.stops.map { it.second }.toIntArray()
        val pos = g.stops.map { it.first.coerceIn(0f, 1f) }.toFloatArray()
        if (g.radial) {
            val r = max(hypot(box.width(), box.height()) / 2f, 0.1f)
            return RadialGradient(box.centerX(), box.centerY(), r, colors, pos, Shader.TileMode.CLAMP)
        }
        val a = Math.toRadians(g.angle.toDouble())
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        val half = (abs(box.width() * dx) + abs(box.height() * dy)) / 2f
        val cx = box.centerX()
        val cy = box.centerY()
        return LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, pos, Shader.TileMode.CLAMP)
    }

    private fun stroke(canvas: Canvas, line: Line, path: Path, open: Boolean) {
        val w = max(line.width, 0.25f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w
            color = line.color
            strokeJoin = Paint.Join.ROUND
            dash(line.dash, w)?.let { pathEffect = DashPathEffect(it, 0f) }
        }
        canvas.drawPath(path, paint)
        if (!open || (line.head == null && line.tail == null)) return
        val m = PathMeasure(path, false)
        val len = m.length
        if (len <= 0f) return
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line.color }
        line.head?.let { type ->
            m.getPosTan(0f, pos, tan)
            arrow(canvas, type, pos[0], pos[1], -tan[0], -tan[1], w, fill, paint)
        }
        line.tail?.let { type ->
            m.getPosTan(len, pos, tan)
            arrow(canvas, type, pos[0], pos[1], tan[0], tan[1], w, fill, paint)
        }
    }

    private fun arrow(canvas: Canvas, type: String, x: Float, y: Float, dx: Float, dy: Float, w: Float, fill: Paint, line: Paint) {
        val size = max(w * 3f, 4f)
        val nx = -dy
        val ny = dx
        val bx = x - dx * size
        val by = y - dy * size
        val p = Path()
        when (type) {
            "oval" -> { canvas.drawCircle(x - dx * size / 2, y - dy * size / 2, size / 2, fill); return }
            "diamond" -> {
                p.moveTo(x, y); p.lineTo(x - dx * size / 2 + nx * size / 2, y - dy * size / 2 + ny * size / 2)
                p.lineTo(bx, by); p.lineTo(x - dx * size / 2 - nx * size / 2, y - dy * size / 2 - ny * size / 2); p.close()
            }
            "arrow" -> {
                p.moveTo(bx + nx * size / 2, by + ny * size / 2); p.lineTo(x, y); p.lineTo(bx - nx * size / 2, by - ny * size / 2)
                canvas.drawPath(p, Paint(line).apply { pathEffect = null })
                return
            }
            else -> { p.moveTo(x, y); p.lineTo(bx + nx * size / 2, by + ny * size / 2); p.lineTo(bx - nx * size / 2, by - ny * size / 2); p.close() }
        }
        canvas.drawPath(p, fill)
    }

    private fun dash(name: String?, w: Float): FloatArray? = when (name) {
        null, "solid" -> null
        "dot", "sysDot" -> floatArrayOf(w, w)
        "dash", "sysDash" -> floatArrayOf(3 * w, w * if (name == "dash") 3 else 1)
        "lgDash" -> floatArrayOf(8 * w, 3 * w)
        "dashDot", "sysDashDot" -> floatArrayOf(4 * w, 3 * w, w, 3 * w)
        "lgDashDot" -> floatArrayOf(8 * w, 3 * w, w, 3 * w)
        "lgDashDotDot", "sysDashDotDot" -> floatArrayOf(8 * w, 3 * w, w, 3 * w, w, 3 * w)
        else -> null
    }

    // ---- Pictures ----

    private fun picture(canvas: Canvas, p: Picture) {
        val bmp = bitmap(p.image) ?: return
        val f = p.frame
        val box = RectF(f.x, f.y, f.x + f.w, f.y + f.h)
        withFrame(canvas, f) {
            flipped(canvas, f) {
                val (l, t, r, b) = p.crop.toList()
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()
                val src = RectF(l * bw, t * bh, bw - r * bw, bh - b * bh)
                val clipped = RectF(max(src.left, 0f), max(src.top, 0f), min(src.right, bw), min(src.bottom, bh))
                if (clipped.width() <= 0f || clipped.height() <= 0f || src.width() <= 0f || src.height() <= 0f) return@flipped
                val sx = box.width() / src.width()
                val sy = box.height() / src.height()
                val dst = RectF(
                    box.left + (clipped.left - src.left) * sx, box.top + (clipped.top - src.top) * sy,
                    box.right - (src.right - clipped.right) * sx, box.bottom - (src.bottom - clipped.bottom) * sy,
                )
                val outline = Geometries.paths(p.geometry, box).firstOrNull()?.first
                canvas.save()
                if (p.geometry.preset != "rect" && outline != null) canvas.clipPath(outline)
                canvas.drawBitmap(bmp, android.graphics.Rect(clipped.left.toInt(), clipped.top.toInt(), ceil(clipped.right).toInt(), ceil(clipped.bottom).toInt()), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                canvas.restore()
                p.line?.let { line -> outline?.let { stroke(canvas, line, it, open = false) } }
            }
        }
    }

    private fun bitmap(name: String): Bitmap? {
        if (bitmaps.containsKey(name)) return bitmaps[name]
        return decode(name).also { bitmaps[name] = it }
    }

    private fun decode(name: String): Bitmap? =
        runCatching {
            val bytes = deck.image(name) ?: return@runCatching null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return@runCatching null
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            val side = max(bmp.width, bmp.height)
            if (side <= MAX_SIDE) bmp else {
                val k = MAX_SIDE.toFloat() / side
                Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt().coerceAtLeast(1), (bmp.height * k).roundToInt().coerceAtLeast(1), true)
            }
        }.getOrNull()

    // ---- Tables ----

    private fun table(canvas: Canvas, t: Table) {
        val f = t.frame
        val colX = FloatArray(t.cols.size + 1).also { for (i in t.cols.indices) it[i + 1] = it[i] + t.cols[i] }
        val rowH = t.rows.toFloatArray()
        for (cell in t.cells) {
            if (cell.rowSpan != 1 || cell.row >= rowH.size) continue
            val text = cell.text ?: continue
            val w = span(colX, cell.col, cell.colSpan) - text.left - text.right
            val needed = layout(text, w, 1f)?.height?.plus(text.top + text.bottom) ?: continue
            rowH[cell.row] = max(rowH[cell.row], needed)
        }
        val rowY = FloatArray(rowH.size + 1).also { for (i in rowH.indices) it[i + 1] = it[i] + rowH[i] }
        fun rect(c: TableCell) = RectF(
            f.x + colX[c.col.coerceAtMost(t.cols.size)], f.y + rowY[c.row.coerceAtMost(rowH.size)],
            f.x + colX[(c.col + c.colSpan).coerceAtMost(t.cols.size)], f.y + rowY[(c.row + c.rowSpan).coerceAtMost(rowH.size)],
        )
        withFrame(canvas, f) {
            for (c in t.cells) c.fill?.let { paintFill(canvas, it, Path().apply { addRect(rect(c), Path.Direction.CW) }, rect(c)) }
            for (c in t.cells) c.text?.let { drawText(canvas, it, rect(c)) }
            for (c in t.cells) {
                val r = rect(c)
                fun edge(line: Line?, x1: Float, y1: Float, x2: Float, y2: Float) {
                    line ?: return
                    stroke(canvas, line, Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, open = true)
                }
                edge(c.left, r.left, r.top, r.left, r.bottom)
                edge(c.top, r.left, r.top, r.right, r.top)
                edge(c.right, r.right, r.top, r.right, r.bottom)
                edge(c.bottom, r.left, r.bottom, r.right, r.bottom)
            }
        }
    }

    private fun span(colX: FloatArray, col: Int, n: Int): Float {
        val a = col.coerceIn(0, colX.size - 1)
        val b = (col + n).coerceIn(0, colX.size - 1)
        return colX[b] - colX[a]
    }

    private fun missing(canvas: Canvas, m: Missing) {
        val f = m.frame
        val box = RectF(f.x, f.y, f.x + f.w, f.y + f.h)
        canvas.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFF9E9E9E.toInt(); pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
        })
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF757575.toInt(); textSize = 12f; textAlign = Paint.Align.CENTER }
        canvas.drawText(m.label, box.centerX(), box.centerY() + 4f, paint)
    }

    // ---- Text ----

    private fun drawText(canvas: Canvas, body: TextBody, box: RectF) {
        canvas.save()
        var outer = box
        if (body.vertical != 0) {
            canvas.rotate(body.vertical.toFloat(), box.centerX(), box.centerY())
            outer = RectF(box.centerX() - box.height() / 2, box.centerY() - box.width() / 2, box.centerX() + box.height() / 2, box.centerY() + box.width() / 2)
        }
        val w = outer.width() - body.left - body.right
        val h = outer.height() - body.top - body.bottom
        var scale = body.fontScale
        var layout = layout(body, w, scale)
        if (body.shrink) {
            while (layout != null && layout.height > h && scale > 0.3f) {
                scale -= 0.05f
                layout = layout(body, w, scale)
            }
        }
        if (layout == null) { canvas.restore(); return }
        val dy = when (body.anchor) {
            'c' -> (h - layout.height) / 2f
            'b' -> h - layout.height
            else -> 0f
        }
        val extra = layout.width - w
        val dx = if (body.wrap || extra <= 0f) 0f else when (body.paras.firstOrNull()?.align) {
            'c' -> -extra / 2f
            'r' -> -extra
            else -> 0f
        }
        canvas.translate(outer.left + body.left + dx, outer.top + body.top + dy)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layout(body: TextBody, width: Float, scale: Float): StaticLayout? {
        val text = SpannableStringBuilder()
        val reduction = body.spacingReduction
        val looks = ArrayList<Triple<Any, Int, Int>>()
        body.paras.forEachIndexed { i, p ->
            val start = text.length
            val first = p.runs.firstOrNull { it.text.isNotEmpty() }
            if (first == null) {
                text.append('​')
                text.setSpan(SizeOnly(p.endSize * scale), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            for (run in p.runs) {
                if (run.text.isEmpty()) continue
                val s = text.length
                text.append(run.text)
                text.setSpan(RunSpan(run, scale), s, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (i < body.paras.size - 1) text.append('\n')
            val end = text.length
            val align = when (p.align) {
                'c' -> Layout.Alignment.ALIGN_CENTER
                'r' -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            looks += Triple(AlignmentSpan.Standard(align), start, end)
            val firstSize = (first?.size ?: p.endSize) * scale
            looks += Triple(ParaSpan(p, firstSize, first?.color ?: Color.BLACK, i == 0, reduction), start, end)
        }
        for ((span, start, end) in looks) text.setSpan(span, start, end, Spanned.SPAN_PARAGRAPH)
        if (text.isEmpty()) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textSize = 18f }
        var w = max(width, 1f)
        if (!body.wrap) w = max(w, Layout.getDesiredWidth(text, paint) + 1f)
        val justify = body.paras.filter { p -> p.runs.any { it.text.isNotBlank() } }.let { ps -> ps.isNotEmpty() && ps.all { it.align == 'j' } }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, ceil(w).toInt())
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .apply { if (justify) setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD) }
            .build()
    }

    companion object {
        const val MAX_SIDE = 2048
    }
}

private class RunSpan(private val run: Run, private val scale: Float) : MetricAffectingSpan() {
    private val typeface = Fonts.typeface(run.font, run.bold, run.italic)
    private val narrow = Fonts.widthScale(run.font)

    private fun apply(p: TextPaint) {
        val size = run.size * scale
        p.textSize = if (run.baseline != 0f) size * 0.7f else size
        p.typeface = typeface
        p.textScaleX = narrow
        p.baselineShift = (-size * run.baseline / 100f).roundToInt()
        val missing = ((if (run.bold) Typeface.BOLD else 0) or (if (run.italic) Typeface.ITALIC else 0)) and typeface.style.inv()
        p.isFakeBoldText = missing and Typeface.BOLD != 0
        p.textSkewX = if (missing and Typeface.ITALIC != 0) -0.2f else 0f
    }

    override fun updateMeasureState(p: TextPaint) = apply(p)

    override fun updateDrawState(p: TextPaint) {
        apply(p)
        p.color = run.color
        p.isUnderlineText = run.underline
        p.isStrikeThruText = run.strike
    }
}

private class SizeOnly(private val size: Float) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) { p.textSize = size }
    override fun updateDrawState(p: TextPaint) { p.textSize = size }
}

private class ParaSpan(
    private val p: Para,
    private val size: Float,
    private val textColor: Int,
    private val firstInBox: Boolean,
    private val reduction: Float,
) : LeadingMarginSpan, LineHeightSpan {
    private val bulletPaint = p.bullet?.let { b ->
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size * b.sizePercent / 100f
            color = b.color ?: textColor
        }
    }

    override fun getLeadingMargin(first: Boolean): Int =
        (if (first && p.bullet == null) p.marginLeft + p.indent else p.marginLeft).coerceAtLeast(0f).roundToInt()

    override fun drawLeadingMargin(
        c: Canvas, paint: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) {
        val bullet = p.bullet ?: return
        val bp = bulletPaint ?: return
        if (!first || (text as? Spanned)?.getSpanStart(this) != start) return
        val at = x + dir * (p.marginLeft + p.indent).coerceAtLeast(0f)
        c.drawText(bullet.text, if (dir < 0) at - bp.measureText(bullet.text) else at, baseline.toFloat(), bp)
    }

    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val spanned = text as? Spanned ?: return
        val natural = fm.descent - fm.ascent
        val single = size * 1.2f
        val target = when {
            p.lineSpacing?.points != null -> p.lineSpacing.points
            else -> single * ((p.lineSpacing?.percent ?: 100f) - reduction) / 100f
        }
        val more = (target - natural).roundToInt()
        fm.ascent -= more
        fm.top -= more
        if (start == spanned.getSpanStart(this) && !firstInBox) {
            val before = space(p.before, single)
            fm.ascent -= before
            fm.top -= before
        }
        if (end == spanned.getSpanEnd(this)) {
            val after = space(p.after, single)
            fm.descent += after
            fm.bottom += after
        }
    }

    private fun space(s: Spacing?, single: Float): Int = when {
        s == null -> 0
        s.points != null -> s.points.roundToInt()
        else -> (single * (s.percent ?: 0f) / 100f).roundToInt()
    }
}

private object Fonts {
    private val cache = HashMap<String, Typeface>()

    fun typeface(name: String, bold: Boolean, italic: Boolean): Typeface {
        val style = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        return cache.getOrPut("$name|$style") { Typeface.create(family(name), style) }
    }

    private fun family(name: String): String {
        val n = name.lowercase()
        return when {
            listOf("mono", "courier", "consolas", "console", "menlo").any { it in n } -> "monospace"
            "sans" !in n && listOf("times", "georgia", "garamond", "cambria", "book", "palatino", "serif", "constantia", "baskerville", "didot", "bodoni", "minion", "rockwell").any { it in n } -> "serif"
            listOf("narrow", "condensed", "compressed").any { it in n } -> "sans-serif-condensed"
            listOf("light", "thin").any { it in n } -> "sans-serif-light"
            listOf("black", "heavy").any { it in n } -> "sans-serif-black"
            else -> "sans-serif"
        }
    }

    fun widthScale(name: String): Float = if (name.startsWith("Calibri", ignoreCase = true)) 0.9f else 1f
}

private object Geometries {
    private val LINES = setOf("line", "straightConnector1", "bentConnector2", "bentConnector3", "bentConnector4", "curvedConnector2", "curvedConnector3", "curvedConnector4", "arc")

    fun isLine(g: Geometry) = g.preset in LINES

    fun paths(g: Geometry, b: RectF): List<Triple<Path, Boolean, Boolean>> {
        if (g.preset == "custom") return custom(g, b)
        val p = Path()
        val w = b.width()
        val h = b.height()
        val x = b.left
        val y = b.top
        val ss = min(w, h)
        fun adj(name: String, def: Float) = (g.adjust[name] ?: def) / 100000f
        fun poly(vararg pts: Float) {
            p.moveTo(x + pts[0], y + pts[1])
            var i = 2
            while (i < pts.size) { p.lineTo(x + pts[i], y + pts[i + 1]); i += 2 }
            p.close()
        }
        when (g.preset) {
            "line", "straightConnector1" -> { p.moveTo(x, y); p.lineTo(x + w, y + h) }
            "bentConnector2" -> { p.moveTo(x, y); p.lineTo(x + w, y); p.lineTo(x + w, y + h) }
            "bentConnector3", "bentConnector4" -> {
                val mx = x + w * adj("adj1", 50000f)
                p.moveTo(x, y); p.lineTo(mx, y); p.lineTo(mx, y + h); p.lineTo(x + w, y + h)
            }
            "curvedConnector2", "curvedConnector3", "curvedConnector4" -> { p.moveTo(x, y); p.cubicTo(x + w / 2, y, x + w / 2, y + h, x + w, y + h) }
            "arc" -> {
                val st = (g.adjust["adj1"] ?: 16200000f) / 60000f
                val en = (g.adjust["adj2"] ?: 0f) / 60000f
                p.addArc(b, st, ((en - st) % 360f + 360f) % 360f)
            }
            "ellipse", "flowChartConnector", "cloud" -> p.addOval(b, Path.Direction.CW)
            "roundRect", "flowChartAlternateProcess", "wedgeRoundRectCallout", "round2SameRect", "round1Rect" -> {
                val r = ss * adj("adj", 16667f)
                p.addRoundRect(b, r, r, Path.Direction.CW)
            }
            "flowChartTerminator" -> p.addRoundRect(b, ss / 2, ss / 2, Path.Direction.CW)
            "triangle", "flowChartExtract" -> poly(w * adj("adj", 50000f), 0f, w, h, 0f, h)
            "rtTriangle" -> poly(0f, 0f, w, h, 0f, h)
            "diamond", "flowChartDecision" -> poly(w / 2, 0f, w, h / 2, w / 2, h, 0f, h / 2)
            "parallelogram", "flowChartInputOutput" -> { val o = ss * adj("adj", 25000f); poly(o, 0f, w, 0f, w - o, h, 0f, h) }
            "trapezoid" -> { val o = ss * adj("adj", 25000f); poly(o, 0f, w - o, 0f, w, h, 0f, h) }
            "pentagon" -> poly(w / 2, 0f, w, h * 0.38f, w * 0.81f, h, w * 0.19f, h, 0f, h * 0.38f)
            "hexagon" -> { val o = ss * adj("adj", 25000f); poly(o, 0f, w - o, 0f, w, h / 2, w - o, h, o, h, 0f, h / 2) }
            "octagon" -> { val o = ss * adj("adj", 29289f); poly(o, 0f, w - o, 0f, w, o, w, h - o, w - o, h, o, h, 0f, h - o, 0f, o) }
            "homePlate" -> { val o = ss * adj("adj", 50000f); poly(0f, 0f, w - o, 0f, w, h / 2, w - o, h, 0f, h) }
            "chevron" -> { val o = ss * adj("adj", 50000f); poly(0f, 0f, w - o, 0f, w, h / 2, w - o, h, 0f, h, o, h / 2) }
            "rightArrow", "leftArrow", "upArrow", "downArrow" -> arrowShape(p, g, b)
            "plus", "mathPlus" -> { val o = ss * adj("adj", 25000f); poly(o, 0f, w - o, 0f, w - o, o, w, o, w, h - o, w - o, h - o, w - o, h, o, h, o, h - o, 0f, h - o, 0f, o, o, o) }
            "star5" -> star(p, b, 5, 0.38f)
            "star4" -> star(p, b, 4, 0.25f)
            "star6" -> star(p, b, 6, 0.5f)
            "donut" -> {
                p.addOval(b, Path.Direction.CW)
                val o = ss * adj("adj", 25000f)
                p.addOval(RectF(b.left + o, b.top + o, b.right - o, b.bottom - o), Path.Direction.CCW)
            }
            "frame" -> {
                p.addRect(b, Path.Direction.CW)
                val o = ss * adj("adj1", 12500f)
                p.addRect(RectF(b.left + o, b.top + o, b.right - o, b.bottom - o), Path.Direction.CCW)
            }
            else -> p.addRect(b, Path.Direction.CW)
        }
        val line = g.preset in LINES
        return listOf(Triple(p, !line, true))
    }

    private fun arrowShape(p: Path, g: Geometry, b: RectF) {
        val m = android.graphics.Matrix()
        val (w, h) = when (g.preset) { "upArrow", "downArrow" -> b.height() to b.width(); else -> b.width() to b.height() }
        val shaft = h * (g.adjust["adj1"] ?: 50000f) / 100000f
        val head = min(w, min(h, w) * (g.adjust["adj2"] ?: 50000f) / 100000f)
        val t = (h - shaft) / 2
        p.moveTo(0f, t); p.lineTo(w - head, t); p.lineTo(w - head, 0f); p.lineTo(w, h / 2)
        p.lineTo(w - head, h); p.lineTo(w - head, h - t); p.lineTo(0f, h - t); p.close()
        when (g.preset) {
            "leftArrow" -> m.setScale(-1f, 1f, w / 2, h / 2)
            "downArrow" -> { m.setRotate(90f); m.postTranslate(h, 0f) }
            "upArrow" -> { m.setRotate(-90f); m.postTranslate(0f, w) }
        }
        m.postTranslate(b.left, b.top)
        p.transform(m)
    }

    private fun star(p: Path, b: RectF, points: Int, inner: Float) {
        val cx = b.centerX()
        val cy = b.centerY()
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) 1f else inner
            val a = Math.toRadians(-90.0 + i * 180.0 / points)
            val px = cx + cos(a).toFloat() * b.width() / 2 * r
            val py = cy + sin(a).toFloat() * b.height() / 2 * r
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
    }

    private fun custom(g: Geometry, b: RectF): List<Triple<Path, Boolean, Boolean>> = g.paths.map { gp ->
        val sx = if (gp.w > 0) b.width() / gp.w else 1f
        val sy = if (gp.h > 0) b.height() / gp.h else 1f
        val p = Path()
        var cx = 0f
        var cy = 0f
        for (c in gp.cmds) {
            when (c) {
                is PathCmd.Move -> { cx = c.x; cy = c.y; p.moveTo(b.left + cx * sx, b.top + cy * sy) }
                is PathCmd.LineTo -> { cx = c.x; cy = c.y; p.lineTo(b.left + cx * sx, b.top + cy * sy) }
                is PathCmd.Cubic -> {
                    p.cubicTo(b.left + c.x1 * sx, b.top + c.y1 * sy, b.left + c.x2 * sx, b.top + c.y2 * sy, b.left + c.x3 * sx, b.top + c.y3 * sy)
                    cx = c.x3; cy = c.y3
                }
                is PathCmd.Quad -> {
                    p.quadTo(b.left + c.x1 * sx, b.top + c.y1 * sy, b.left + c.x2 * sx, b.top + c.y2 * sy)
                    cx = c.x2; cy = c.y2
                }
                is PathCmd.Arc -> {
                    val st = Math.toRadians(c.start.toDouble())
                    val ox = cx - c.wR * cos(st).toFloat()
                    val oy = cy - c.hR * sin(st).toFloat()
                    p.arcTo(RectF(b.left + (ox - c.wR) * sx, b.top + (oy - c.hR) * sy, b.left + (ox + c.wR) * sx, b.top + (oy + c.hR) * sy), c.start, c.sweep, false)
                    val en = Math.toRadians((c.start + c.sweep).toDouble())
                    cx = ox + c.wR * cos(en).toFloat()
                    cy = oy + c.hR * sin(en).toFloat()
                }
                PathCmd.Close -> p.close()
            }
        }
        Triple(p, gp.fill, gp.stroke)
    }
}
