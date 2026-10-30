package nexplorer.doc

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.SizeF
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Rect
import java.io.File
import com.artifex.mupdf.fitz.Point
import nexplorer.reader.Selection
import nexplorer.reader.Signature
import nexplorer.reader.TextBox
import nexplorer.reader.TextPos
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class PageRenderer(private var doc: Document, private var path: String? = null, private val password: String? = null) {
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()

    var pageCount by mutableIntStateOf(0)
        private set

    @Volatile
    var lastRenderWidthPx: Int = 0

    var layoutVersion by mutableIntStateOf(0)
        private set

    suspend fun init() = withContext(dispatcher) { pageCount = doc.countPages() }

    suspend fun pageSize(index: Int): SizeF = withContext(dispatcher) {
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            SizeF(b.x1 - b.x0, b.y1 - b.y0)
        } finally {
            page.destroy()
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(dispatcher) {
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            AndroidDrawDevice.drawPage(page, Matrix(widthPx / (b.x1 - b.x0)))
        } finally {
            page.destroy()
        }
    }

    suspend fun renderPart(index: Int, pageWidthPx: Float, x: Int, y: Int, w: Int, h: Int): Bitmap = withContext(dispatcher) {
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            val s = pageWidthPx / (b.x1 - b.x0)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            val dev = AndroidDrawDevice(bmp)
            try {
                page.run(dev, Matrix(s, 0f, 0f, s, -(b.x0 * s + x), -(b.y0 * s + y)))
                dev.close()
            } finally {
                dev.destroy()
            }
            bmp
        } finally {
            page.destroy()
        }
    }

    suspend fun relayout(widthPt: Float, heightPt: Float, emPt: Float) = withContext(dispatcher) {
        if (doc.isReflowable) {
            forgetTexts()
            doc.layout(widthPt, heightPt, emPt)
            pageCount = doc.countPages()
            layoutVersion++
        }
    }

    suspend fun replace(newDoc: Document) = withContext(dispatcher) {
        forgetTexts()
        if (newDoc !== doc) doc.destroy()
        doc = newDoc
        pageCount = doc.countPages()
        layoutVersion++
    }

    suspend fun <T> withDoc(block: (Document) -> T): T = withContext(dispatcher) { block(doc) }

    suspend fun outline(): List<nexplorer.reader.TocEntry> = withContext(dispatcher) {
        val out = ArrayList<nexplorer.reader.TocEntry>()
        fun walk(items: Array<com.artifex.mupdf.fitz.Outline>?, level: Int) {
            for (o in items ?: return) {
                val page = try {
                    if (o.uri.isNullOrEmpty()) -1 else doc.pageNumberFromLocation(doc.resolveLink(o))
                } catch (_: RuntimeException) {
                    -1
                }
                out += nexplorer.reader.TocEntry(o.title?.trim().orEmpty(), level, if (page in 0 until pageCount) page else -1)
                walk(o.down, level + 1)
            }
        }
        walk(doc.loadOutline(), 0)
        out
    }

    suspend fun search(index: Int, text: String): List<RectF> = withContext(dispatcher) {
        val page = doc.loadPage(index)
        try {
            page.search(text).map { quads ->
                val r = RectF(quads[0].toRect().let { RectF(it.x0, it.y0, it.x1, it.y1) })
                quads.drop(1).forEach { q -> q.toRect().let { r.union(it.x0, it.y0, it.x1, it.y1) } }
                r
            }
        } finally {
            page.destroy()
        }
    }

    class SelectResult(val rects: List<RectF>, val text: String, val a: PointF, val b: PointF)

    suspend fun select(index: Int, ax: Float, ay: Float, bx: Float, by: Float): SelectResult =
        withContext(dispatcher) {
            val st = textOf(index).first
            var a = Point(ax, ay)
            var b = Point(bx, by)
            if (ax == bx && ay == by) {
                val q = st.snapSelection(Point(ax, ay), Point(ax, ay), StructuredText.SELECT_WORDS)
                val midY = (q.ul_y + q.ll_y) / 2
                a = Point(q.ul_x + 0.5f, midY)
                b = Point(q.lr_x - 0.5f, midY)
            } else {
                val q = st.snapSelection(Point(ax, ay), Point(ax, ay), StructuredText.SELECT_WORDS)
                a = Point(q.ul_x, (q.ul_y + q.ll_y) / 2)
            }
            val rects = st.highlight(a, b).map { it.toRect().let { r -> RectF(r.x0, r.y0, r.x1, r.y1) } }
            SelectResult(rects, st.copy(a, b) ?: "", PointF(a.x, a.y), PointF(b.x, b.y))
        }

    private fun <T> onPart(index: Int, a: PointF?, b: PointF?, use: (StructuredText, Point, Point) -> T): T {
        val (st, r) = textOf(index)
        return use(st, a?.let { Point(it.x, it.y) } ?: Point(r.x0, r.y0), b?.let { Point(it.x, it.y) } ?: Point(r.x1, r.y1))
    }

    private val texts = object : LinkedHashMap<Int, Pair<StructuredText, com.artifex.mupdf.fitz.Rect>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<StructuredText, com.artifex.mupdf.fitz.Rect>>): Boolean {
            if (size > 6) { eldest.value.first.destroy(); return true }
            return false
        }
    }

    private fun textOf(index: Int): Pair<StructuredText, com.artifex.mupdf.fitz.Rect> = texts.getOrPut(index) {
        val page = doc.loadPage(index)
        try { page.toStructuredText() to page.bounds } finally { page.destroy() }
    }

    private fun forgetTexts() {
        texts.values.forEach { it.first.destroy() }
        texts.clear()
    }

    private fun ends(sel: Selection, index: Int): Pair<PointF?, PointF?> =
        (if (index == sel.start.page) PointF(sel.start.x, sel.start.y) else null) to
            (if (index == sel.end.page) PointF(sel.end.x, sel.end.y) else null)

    suspend fun selectionRects(sel: Selection, index: Int): List<RectF> = withContext(dispatcher) {
        if (index !in sel.pages) return@withContext emptyList()
        val (a, b) = ends(sel, index)
        onPart(index, a, b) { st, pa, pb -> st.highlight(pa, pb).map { it.toRect().let { r -> RectF(r.x0, r.y0, r.x1, r.y1) } } }
    }

    suspend fun selectionText(sel: Selection): String = sel.pages.map { pageText(sel, it) }.joinToString(System.lineSeparator())

    suspend fun pageText(sel: Selection, index: Int): String = withContext(dispatcher) {
        val (a, b) = ends(sel, index)
        onPart(index, a, b) { st, pa, pb -> st.copy(pa, pb) ?: "" }.trimEnd()
    }

    suspend fun wordStart(index: Int, x: Float, y: Float): PointF = withContext(dispatcher) {
        onPart(index, null, null) { st, _, _ ->
            val q = st.snapSelection(Point(x, y), Point(x, y), StructuredText.SELECT_WORDS)
            if (q.ul_x == q.lr_x && q.ul_y == q.ll_y) PointF(x, y) else PointF(q.ul_x, (q.ul_y + q.ll_y) / 2)
        }
    }

    suspend fun wholeDocument(): Selection = withContext(dispatcher) {
        val last = doc.countPages() - 1
        val r = doc.loadPage(last).let { p -> try { p.bounds } finally { p.destroy() } }
        Selection(TextPos(0, 0f, 0f), TextPos(last, r.x1, r.y1), all = true)
    }

    var editVersion by mutableIntStateOf(0)
        private set

    suspend fun isPdf(): Boolean = withContext(dispatcher) { doc.isPDF }

    suspend fun addMark(sel: Selection, underline: Boolean, onPage: (done: Int) -> Unit = {}) = withContext(dispatcher) {
        var added = false
        for (index in sel.pages) {
            onPage(index - sel.start.page)
            val (a, b) = ends(sel, index)
            val quads = onPart(index, a, b) { st, pa, pb -> st.highlight(pa, pb) }
            if (quads.isEmpty()) continue
            val page = doc.loadPage(index) as PDFPage
            try {
                val annot = page.createAnnotation(if (underline) PDFAnnotation.TYPE_UNDERLINE else PDFAnnotation.TYPE_HIGHLIGHT)
                annot.setQuadPoints(quads)
                annot.setColor(if (underline) LIGHT_GREEN else LIGHT_YELLOW)
                annot.update()
                page.update()
                added = true
            } finally {
                page.destroy()
            }
        }
        if (added) saveInPlace()
    }

    suspend fun markAt(index: Int, x: Float, y: Float): Pair<Int, Boolean>? = withContext(dispatcher) {
        val page = doc.loadPage(index) as? PDFPage ?: return@withContext null
        try {
            page.annotations.orEmpty().withIndex().firstNotNullOfOrNull { (i, annot) ->
                val type = annot.type
                if (type != PDFAnnotation.TYPE_HIGHLIGHT && type != PDFAnnotation.TYPE_UNDERLINE) return@firstNotNullOfOrNull null
                val hit = annot.quadPoints.orEmpty().any { q ->
                    val r = q.toRect()
                    x >= r.x0 - 2 && x <= r.x1 + 2 && y >= r.y0 - 4 && y <= r.y1 + 4
                }
                if (hit) i to (type == PDFAnnotation.TYPE_UNDERLINE) else null
            }
        } finally {
            page.destroy()
        }
    }

    class AddedText(
        val annot: Int,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val rotation: Float,
        val text: String,
        val size: Float,
        val color: FloatArray,
    )

    suspend fun helveticaWidths(): FloatArray = withContext(dispatcher) {
        val font = Font("Helvetica")
        try {
            FloatArray(0x500) { c ->
                val gid = runCatching { font.encodeCharacter(c) }.getOrDefault(0)
                if (gid == 0 && c != 0) Float.NaN else runCatching { font.advanceGlyph(gid, false) }.getOrDefault(Float.NaN)
            }
        } finally {
            font.destroy()
        }
    }

    suspend fun textAt(index: Int, x: Float, y: Float): AddedText? = withContext(dispatcher) {
        val page = doc.loadPage(index) as? PDFPage ?: return@withContext null
        try {
            page.annotations.orEmpty().withIndex().firstNotNullOfOrNull { (i, annot) ->
                if (annot.type != PDFAnnotation.TYPE_FREE_TEXT) return@firstNotNullOfOrNull null
                val r = annot.rect
                if (x < r.x0 || x > r.x1 || y < r.y0 || y > r.y1) return@firstNotNullOfOrNull null
                val da = runCatching { annot.defaultAppearance }.getOrNull()
                val obj = annot.getObject()
                val box = obj.get(KEY_BOX).takeIf { !it.isNull && it.size() == 4 }
                val rot = obj.get(KEY_ROT).takeIf { !it.isNull }?.asFloat() ?: 0f
                val (bx, by, bw, bh) = if (box != null) {
                    listOf(box.get(0).asFloat(), box.get(1).asFloat(), box.get(2).asFloat() - box.get(0).asFloat(), box.get(3).asFloat() - box.get(1).asFloat())
                } else {
                    listOf(r.x0, r.y0, r.x1 - r.x0, r.y1 - r.y0)
                }
                AddedText(
                    i, bx, by, bw, bh, rot,
                    annot.contents ?: "",
                    da?.size?.takeIf { it > 0 } ?: 12f,
                    da?.color?.takeIf { it.size == 3 } ?: floatArrayOf(0f, 0f, 0f),
                )
            }
        } finally {
            page.destroy()
        }
    }

    suspend fun putText(
        index: Int,
        annotNumber: Int?,
        x: Float, y: Float, w: Float, h: Float,
        rotation: Float,
        text: String,
        size: Float,
        color: FloatArray,
    ) = withContext(dispatcher) {
        val page = doc.loadPage(index) as PDFPage
        try {
            val annot = annotNumber?.let { page.annotations.orEmpty().getOrNull(it) }
                ?: page.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
            annot.setRect(Rect(x, y, x + w, y + h))
            annot.setContents(text)
            annot.setDefaultAppearance("Helv", size, color)
            annot.setBorderWidth(0f)
            annot.update()
            page.update()
            val obj = annot.getObject()
            if (rotation % 360f != 0f) {
                rotateAppearance(obj, rotation)
                obj.put(KEY_ROT, rotation)
                obj.put(KEY_BOX, Rect(x, y, x + w, y + h))
            } else {
                obj.delete(KEY_ROT)
                obj.delete(KEY_BOX)
            }
        } finally {
            page.destroy()
        }
        saveInPlace()
    }

    class AddedSign(
        val annot: Int,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val rotation: Float,
        val width: Float,
        val color: FloatArray,
        val strokes: List<FloatArray>,
    )

    suspend fun signatureAt(index: Int, x: Float, y: Float): AddedSign? = withContext(dispatcher) {
        val page = doc.loadPage(index) as? PDFPage ?: return@withContext null
        try {
            page.annotations.orEmpty().withIndex().firstNotNullOfOrNull { (i, annot) ->
                if (annot.type != PDFAnnotation.TYPE_INK) return@firstNotNullOfOrNull null
                val r = annot.bounds
                if (x < r.x0 - 4 || x > r.x1 + 4 || y < r.y0 - 4 || y > r.y1 + 4) return@firstNotNullOfOrNull null
                val obj = annot.getObject()
                val box = obj.get(KEY_BOX).takeIf { !it.isNull && it.size() == 4 }
                    ?.let { b -> TextBox.Rect(b.get(0).asFloat(), b.get(1).asFloat(), b.get(2).asFloat() - b.get(0).asFloat(), b.get(3).asFloat() - b.get(1).asFloat()) }
                    ?: TextBox.Rect(r.x0, r.y0, (r.x1 - r.x0).coerceAtLeast(1f), (r.y1 - r.y0).coerceAtLeast(1f))
                val rot = obj.get(KEY_ROT).takeIf { !it.isNull }?.asFloat() ?: 0f
                val strokes = annot.inkList.orEmpty().filter { it.isNotEmpty() }.map { pts ->
                    FloatArray(pts.size * 2).also { out ->
                        pts.forEachIndexed { j, pt ->
                            val (fx, fy) = TextBox.fractions(box, rot, pt.x, pt.y)
                            out[2 * j] = fx * box.w
                            out[2 * j + 1] = fy * box.h
                        }
                    }
                }
                if (strokes.isEmpty()) return@firstNotNullOfOrNull null
                AddedSign(
                    i, box.x, box.y, box.w, box.h, rot,
                    annot.borderWidth.takeIf { it > 0 } ?: 1f,
                    annot.color?.takeIf { it.size == 3 } ?: floatArrayOf(0f, 0f, 0f),
                    strokes,
                )
            }
        } finally {
            page.destroy()
        }
    }

    suspend fun putSignature(index: Int, annotNumber: Int?, box: TextBox.Rect, rotation: Float, sig: Signature, color: FloatArray) =
        withContext(dispatcher) {
            val page = doc.loadPage(index) as PDFPage
            try {
                val annot = annotNumber?.let { page.annotations.orEmpty().getOrNull(it) }
                    ?: page.createAnnotation(PDFAnnotation.TYPE_INK)
                annot.setInkList(
                    sig.strokes.map { s ->
                        Array(s.size / 2) { j ->
                            val (x, y) = TextBox.corner(box, rotation, s[2 * j] / sig.w, s[2 * j + 1] / sig.h)
                            Point(x, y)
                        }
                    }.toTypedArray(),
                )
                annot.setColor(color)
                annot.setBorderWidth(sig.width * box.w / sig.w)
                annot.update()
                page.update()
                val obj = annot.getObject()
                obj.put(KEY_ROT, rotation)
                obj.put(KEY_BOX, Rect(box.x, box.y, box.x + box.w, box.y + box.h))
            } finally {
                page.destroy()
            }
            saveInPlace()
        }

    private fun rotateAppearance(annot: PDFObject, degrees: Float) {
        val ap = annot.get("AP").takeIf { !it.isNull }?.get("N")?.takeIf { !it.isNull } ?: return
        val bbox = ap.get("BBox").takeIf { !it.isNull && it.size() == 4 } ?: return
        val b = FloatArray(4) { bbox.get(it).asFloat() }
        val old = ap.get("Matrix").takeIf { !it.isNull && it.size() == 6 }
            ?.let { m -> FloatArray(6) { m.get(it).asFloat() } }
            ?: floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        val corners = listOf(b[0] to b[1], b[2] to b[1], b[2] to b[3], b[0] to b[3]).map { apply(old, it) }
        val cx = (corners.minOf { it.first } + corners.maxOf { it.first }) / 2
        val cy = (corners.minOf { it.second } + corners.maxOf { it.second }) / 2
        val a = Math.toRadians(-degrees.toDouble())
        val cos = kotlin.math.cos(a).toFloat()
        val sin = kotlin.math.sin(a).toFloat()
        val rot = floatArrayOf(cos, sin, -sin, cos, cx - cx * cos + cy * sin, cy - cx * sin - cy * cos)
        val m = concat(old, rot)
        ap.put("Matrix", Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        val turned = listOf(b[0] to b[1], b[2] to b[1], b[2] to b[3], b[0] to b[3]).map { apply(m, it) }
        annot.put(
            "Rect",
            Rect(turned.minOf { it.first }, turned.minOf { it.second }, turned.maxOf { it.first }, turned.maxOf { it.second }),
        )
    }

    private fun apply(m: FloatArray, p: Pair<Float, Float>) =
        (m[0] * p.first + m[2] * p.second + m[4]) to (m[1] * p.first + m[3] * p.second + m[5])

    private fun concat(m1: FloatArray, m2: FloatArray) = floatArrayOf(
        m1[0] * m2[0] + m1[1] * m2[2],
        m1[0] * m2[1] + m1[1] * m2[3],
        m1[2] * m2[0] + m1[3] * m2[2],
        m1[2] * m2[1] + m1[3] * m2[3],
        m1[4] * m2[0] + m1[5] * m2[2] + m2[4],
        m1[4] * m2[1] + m1[5] * m2[3] + m2[5],
    )

    suspend fun removeMark(index: Int, annotNumber: Int) = withContext(dispatcher) {
        val page = doc.loadPage(index) as PDFPage
        try {
            val annot = page.annotations.orEmpty().getOrNull(annotNumber) ?: return@withContext
            page.deleteAnnotation(annot)
            page.update()
        } finally {
            page.destroy()
        }
        saveInPlace()
    }

    private fun saveInPlace() {
        val pdf = doc.asPDF() ?: error("Not a PDF")
        val incremental = pdf.canBeSavedIncrementally()
        val before = source?.let(::File)?.takeIf { it.isFile }
        val undo = when {
            before == null -> null
            incremental -> Undo.Cut(before.length())
            else -> Undo.Copy(File.createTempFile("undo", ".nexplorer-tmp").also { before.copyTo(it, overwrite = true) })
        }
        val p = try {
            writeBack(pdf, if (incremental) "incremental" else "")
        } catch (t: Throwable) {
            (undo as? Undo.Copy)?.file?.delete()
            throw t
        }
        undo?.let { undos.addLast(it); undoSteps = undos.size }
        edited = true
        reopen(p)
    }

    private fun reopen(p: String) {
        val reopened = PdfLock.open(p, password)
        forgetTexts()
        doc.destroy()
        doc = reopened
        source = p
        editVersion++
    }

    private var source: String? = path

    private sealed interface Undo {
        class Cut(val length: Long) : Undo

        class Copy(val file: File) : Undo
    }

    private val undos = ArrayDeque<Undo>()

    /** How many edits can be undone. */
    var undoSteps by mutableIntStateOf(0)
        private set

    suspend fun undo() = withContext(dispatcher) {
        val step = undos.removeLastOrNull() ?: return@withContext
        undoSteps = undos.size
        val target = File(path ?: error("No file to save to"))
        when (step) {
            is Undo.Cut -> java.io.RandomAccessFile(target, "rw").use { it.setLength(step.length) }
            is Undo.Copy -> try {
                replaceByCopy(step.file, target)
            } finally {
                step.file.delete()
            }
        }
        reopen(target.path)
    }

    suspend fun hasEdits(): Boolean = withContext(dispatcher) {
        val pdf = doc.asPDF() ?: return@withContext false
        (0 until pdf.countPages()).any { markCount(pdf, it) > 0 }
    }

    suspend fun removeAllEdits() = withContext(dispatcher) {
        val pdf = doc.asPDF() ?: return@withContext
        var removed = false
        for (index in 0 until pdf.countPages()) {
            if (markCount(pdf, index) == 0) continue
            val page = doc.loadPage(index) as PDFPage
            try {
                page.annotations.orEmpty().filter { it.type in EDIT_TYPES }.forEach { page.deleteAnnotation(it) }
                page.update()
                removed = true
            } finally {
                page.destroy()
            }
        }
        if (removed) saveInPlace()
    }

    private fun markCount(pdf: com.artifex.mupdf.fitz.PDFDocument, index: Int): Int {
        val annots = pdf.findPage(index).get("Annots").takeIf { it.isArray } ?: return 0
        return (0 until annots.size()).count { annots.get(it).get("Subtype").asName() in EDIT_SUBTYPES }
    }

    private var edited = false

    private fun writeBack(pdf: com.artifex.mupdf.fitz.PDFDocument, opts: String): String {
        val p = path ?: error("No file to save to")
        var tmp = File("$p.nexplorer-tmp")
        try {
            runCatching { pdf.save(tmp.path, opts) }.onFailure {
                tmp.delete()
                tmp = File(System.getProperty("java.io.tmpdir"), "save.nexplorer-tmp")
                pdf.save(tmp.path, opts)
            }
            if (!tmp.renameTo(File(p))) replaceByCopy(tmp, File(p))
        } finally {
            tmp.delete()
        }
        return p
    }

    private fun replaceByCopy(saved: File, target: File) {
        val backup = File(System.getProperty("java.io.tmpdir"), "backup.nexplorer-tmp")
        if (target.exists()) target.copyTo(backup, overwrite = true) else backup.delete()
        try {
            saved.copyTo(target, overwrite = true)
            check(target.length() == saved.length()) { "Couldn't save the file completely" }
        } catch (t: Throwable) {
            runCatching { if (backup.exists()) backup.copyTo(target, overwrite = true) else target.delete() }
            throw t
        } finally {
            backup.delete()
        }
    }

    suspend fun saveEditsTo(newPath: String) = withContext(dispatcher) { path = newPath }

    fun close() {
        executor.execute {
            if (edited) doc.asPDF()?.let { pdf -> runCatching { writeBack(pdf, "garbage=compact,compress") } }
            undos.forEach { (it as? Undo.Copy)?.file?.delete() }
            forgetTexts()
            doc.destroy()
        }
        executor.shutdown()
    }

    private companion object {
        val LIGHT_YELLOW = floatArrayOf(1f, 0.961f, 0.616f)
        val LIGHT_GREEN = floatArrayOf(0.647f, 0.839f, 0.655f)

        const val KEY_ROT = "NExplorerRotation"
        const val KEY_BOX = "NExplorerBox"

        val EDIT_TYPES = setOf(PDFAnnotation.TYPE_HIGHLIGHT, PDFAnnotation.TYPE_UNDERLINE, PDFAnnotation.TYPE_FREE_TEXT, PDFAnnotation.TYPE_INK)
        val EDIT_SUBTYPES = setOf("Highlight", "Underline", "FreeText", "Ink")
    }
}
