package nexplorer.slides

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min

class SlideDeck private constructor(
    private val pkg: SlidePackage,
    val width: Float,
    val height: Float,
    val slides: List<Slide>,
) : Closeable {
    fun image(name: String): ByteArray? = runCatching { pkg.bytes(name, MAX_IMAGE) }.getOrNull()

    override fun close() = pkg.close()

    class NotSlides(message: String) : IllegalArgumentException(message)

    companion object {
        const val MAX_SLIDES = 2000
        private const val MAX_IMAGE = 64 shl 20

        fun open(file: File): SlideDeck {
            val pkg = runCatching { SlidePackage(file) }.getOrElse { throw NotSlides("This isn't a PowerPoint file") }
            try {
                return DeckReader(pkg).read { w, h, slides -> SlideDeck(pkg, w, h, slides) }
            } catch (t: Throwable) {
                pkg.close()
                throw t
            }
        }
    }
}

private const val EMU = 12700f
private const val MAX_XML = 32 shl 20
private const val MAX_ITEMS = 5000
private const val MAX_DEPTH = 24

internal class SlidePackage(file: File) : Closeable {
    private val zip = ZipFile(file)

    fun bytes(name: String, limit: Int): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw SlideDeck.NotSlides("Part of this file is too big")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    fun xml(name: String): Element? = bytes(name, MAX_XML)?.let(::parseXml)

    fun rels(part: String): Map<String, Pair<String, String>> {
        val dir = part.substringBeforeLast('/', "")
        val relsName = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val root = xml(relsName) ?: return emptyMap()
        return root.kids("Relationship")
            .filter { !it.getAttribute("TargetMode").equals("External", ignoreCase = true) }
            .associate { it.getAttribute("Id") to (it.getAttribute("Type") to resolve(dir, it.getAttribute("Target"))) }
    }

    override fun close() = zip.close()

    companion object {
        fun resolve(dir: String, target: String): String {
            val path = if (target.startsWith("/")) target else "$dir/$target"
            val out = ArrayList<String>()
            for (seg in path.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                    else -> out.add(seg)
                }
            }
            return out.joinToString("/")
        }
    }
}

internal fun parseXml(bytes: ByteArray): Element {
    val text = String(bytes.filter { it != 0.toByte() }.toByteArray(), Charsets.ISO_8859_1)
    if (text.contains("<!DOCTYPE", ignoreCase = true)) throw SlideDeck.NotSlides("This file has unsafe content")
    val f = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
    runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    return f.newDocumentBuilder().parse(bytes.inputStream()).documentElement
}

internal fun Element.elements(): List<Element> {
    val out = ArrayList<Element>()
    var n = firstChild
    while (n != null) {
        if (n.nodeType == Node.ELEMENT_NODE) out.add(n as Element)
        n = n.nextSibling
    }
    return out
}

internal fun Element.child(local: String): Element? {
    var n = firstChild
    while (n != null) {
        if (n.nodeType == Node.ELEMENT_NODE && n.localName == local) return n as Element
        n = n.nextSibling
    }
    return null
}

internal fun Element.kids(local: String): List<Element> = elements().filter { it.localName == local }

internal fun Element.descendant(local: String): Element? {
    for (el in elements()) {
        if (el.localName == local) return el
        el.descendant(local)?.let { return it }
    }
    return null
}

internal fun Element.attr(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

internal fun Element.relId(local: String): String? {
    val attrs = attributes
    for (i in 0 until attrs.length) {
        val a = attrs.item(i)
        if (a.localName == local && a.namespaceURI?.endsWith("relationships") == true) return a.nodeValue
    }
    return null
}

internal fun Element.num(name: String): Float? = attr(name)?.toFloatOrNull()

internal fun Element.flag(name: String): Boolean? = when (attr(name)?.lowercase()) {
    "1", "true", "on" -> true
    "0", "false", "off" -> false
    else -> null
}

// ---- Theme and colors ----

internal class Theme(
    val colors: Map<String, Int>,
    val major: String,
    val minor: String,
    val fills: List<Element>,
    val lines: List<Element>,
    val bgFills: List<Element>,
) {
    companion object {
        val EMPTY = Theme(emptyMap(), "Calibri Light", "Calibri", emptyList(), emptyList(), emptyList())

        fun parse(root: Element?): Theme {
            val els = root?.child("themeElements") ?: return EMPTY
            val colors = HashMap<String, Int>()
            els.child("clrScheme")?.elements()?.forEach { slot ->
                val c = slot.elements().firstOrNull() ?: return@forEach
                val hex = when (c.localName) {
                    "srgbClr" -> c.attr("val")
                    "sysClr" -> c.attr("lastClr")
                    else -> null
                }
                hex?.let(::hexColor)?.let { colors[slot.localName] = it }
            }
            val fonts = els.child("fontScheme")
            val fmt = els.child("fmtScheme")
            return Theme(
                colors,
                fonts?.child("majorFont")?.child("latin")?.attr("typeface") ?: EMPTY.major,
                fonts?.child("minorFont")?.child("latin")?.attr("typeface") ?: EMPTY.minor,
                fmt?.child("fillStyleLst")?.elements().orEmpty(),
                fmt?.child("lnStyleLst")?.elements().orEmpty(),
                fmt?.child("bgFillStyleLst")?.elements().orEmpty(),
            )
        }
    }
}

internal fun hexColor(hex: String): Int? =
    hex.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }

private val COLOR_TAGS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")

private val PRESET_COLORS = mapOf(
    "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
    "yellow" to 0xFFFF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "gray" to 0x808080, "grey" to 0x808080,
    "orange" to 0xFFA500, "purple" to 0x800080, "darkGray" to 0xA9A9A9, "lightGray" to 0xD3D3D3,
    "navy" to 0x000080, "darkBlue" to 0x00008B, "darkRed" to 0x8B0000, "darkGreen" to 0x006400,
)

internal class Colors(val theme: Theme, val map: Map<String, String>) {
    fun inside(parent: Element?, ph: Int? = null): Int? =
        parent?.elements()?.firstOrNull { it.localName in COLOR_TAGS }?.let { of(it, ph) }

    fun scheme(name: String): Int? = theme.colors[map[name] ?: name]

    fun of(el: Element, ph: Int? = null): Int? {
        var c: Int = when (el.localName) {
            "srgbClr" -> el.attr("val")?.let(::hexColor)
            "schemeClr" -> el.attr("val")?.let { if (it == "phClr") ph else scheme(it) }
            "sysClr" -> el.attr("lastClr")?.let(::hexColor) ?: 0xFF000000.toInt()
            "prstClr" -> PRESET_COLORS[el.attr("val")]?.let { it or 0xFF000000.toInt() } ?: 0xFF000000.toInt()
            "scrgbClr" -> {
                fun ch(n: String) = ((el.num(n) ?: 0f) / 100000f * 255f).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (ch("r") shl 16) or (ch("g") shl 8) or ch("b")
            }
            "hslClr" -> hslToRgb((el.num("hue") ?: 0f) / 60000f, (el.num("sat") ?: 0f) / 100000f, (el.num("lum") ?: 0f) / 100000f)
            else -> null
        } ?: return null
        for (m in el.elements()) {
            val v = (m.num("val") ?: continue) / 100000f
            c = when (m.localName) {
                "lumMod" -> hsl(c) { h, s, l -> Triple(h, s, l * v) }
                "lumOff" -> hsl(c) { h, s, l -> Triple(h, s, l + v) }
                "satMod" -> hsl(c) { h, s, l -> Triple(h, s * v, l) }
                "tint" -> channels(c) { 255f - (255f - it) * v }
                "shade" -> channels(c) { it * v }
                "alpha" -> (c and 0xFFFFFF) or ((v * 255f).toInt().coerceIn(0, 255) shl 24)
                else -> c
            }
        }
        return c
    }

    private fun channels(c: Int, f: (Float) -> Float): Int {
        fun ch(shift: Int) = f(((c shr shift) and 0xFF).toFloat()).toInt().coerceIn(0, 255) shl shift
        return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }

    private fun hsl(c: Int, f: (Float, Float, Float) -> Triple<Float, Float, Float>): Int {
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        val s = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * l - 1f))
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        val (h2, s2, l2) = f(h, s, l)
        return (c and 0xFF000000.toInt()) or (hslToRgb(h2, s2.coerceIn(0f, 1f), l2.coerceIn(0f, 1f)) and 0xFFFFFF)
    }
}

internal fun hslToRgb(h: Float, s: Float, l: Float): Int {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when ((((h % 360f) + 360f) % 360f / 60f).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

private data class RStyle(
    val size: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val caps: Boolean? = null,
    val color: Int? = null,
    val font: String? = null,
    val baseline: Float? = null,
) {
    fun over(b: RStyle) = RStyle(
        size ?: b.size, bold ?: b.bold, italic ?: b.italic, underline ?: b.underline, strike ?: b.strike,
        caps ?: b.caps, color ?: b.color, font ?: b.font, baseline ?: b.baseline,
    )
}

private sealed interface BulletKind {
    data object None : BulletKind
    data class Char(val c: String, val font: String?) : BulletKind
    data class Auto(val scheme: String, val start: Int) : BulletKind
}

private data class PStyle(
    val align: String? = null,
    val marL: Float? = null,
    val indent: Float? = null,
    val bullet: BulletKind? = null,
    val bulletColor: Int? = null,
    val bulletColorSet: Boolean = false,
    val bulletSize: Float? = null,
    val lineSpacing: Spacing? = null,
    val before: Spacing? = null,
    val after: Spacing? = null,
    val r: RStyle = RStyle(),
) {
    fun over(b: PStyle) = PStyle(
        align ?: b.align, marL ?: b.marL, indent ?: b.indent, bullet ?: b.bullet,
        if (bulletColorSet) bulletColor else b.bulletColor, bulletColorSet || b.bulletColorSet,
        bulletSize ?: b.bulletSize, lineSpacing ?: b.lineSpacing, before ?: b.before, after ?: b.after, r.over(b.r),
    )
}

private class ListStyle(val all: PStyle?, val levels: Array<PStyle?>) {
    fun at(level: Int): PStyle? {
        val l = levels[level]
        return if (all == null) l else l?.over(all) ?: all
    }
}

private class Ph(val type: String, val idx: String?, val el: Element)

private class Master(
    val part: String,
    val el: Element,
    val rels: Map<String, Pair<String, String>>,
    val theme: Theme,
    val clrMap: Map<String, String>,
    val phs: List<Ph>,
)

private class Layout(
    val part: String,
    val el: Element,
    val rels: Map<String, Pair<String, String>>,
    val master: Master,
    val phs: List<Ph>,
)

private class Ctx(
    val rels: Map<String, Pair<String, String>>,
    val colors: Colors,
    val master: Master,
    val layout: Layout?,
    val decor: Boolean,
    val slideNumber: Int,
) {
    fun withRels(r: Map<String, Pair<String, String>>, decor: Boolean = this.decor) = Ctx(r, colors, master, layout, decor, slideNumber)
    fun target(id: String?): String? = id?.let { rels[it]?.second }
}

private class Xf(val sx: Double = 1.0, val sy: Double = 1.0, val dx: Double = 0.0, val dy: Double = 0.0) {
    fun x(v: Double) = dx + v * sx
    fun y(v: Double) = dy + v * sy
    fun then(sx2: Double, sy2: Double, dx2: Double, dy2: Double) = Xf(sx * sx2, sy * sy2, dx + sx * dx2, dy + sy * dy2)
}

private class CellLook(val fill: Fill?, val bold: Boolean?, val italic: Boolean?, val color: Int?, val borders: Map<String, Line?>)

private class DeckReader(private val pkg: SlidePackage) {
    private val themes = HashMap<String, Theme>()
    private val masters = HashMap<String, Master>()
    private val layouts = HashMap<String, Layout?>()
    private var defaultText: Element? = null
    private var tableStyles: Map<String, Element> = emptyMap()
    private var defaultTableStyle: String? = null
    private var items = 0

    fun <T> read(make: (Float, Float, List<Slide>) -> T): T {
        val presName = pkg.rels("").values.firstOrNull { it.first.endsWith("/officeDocument") }?.second
            ?: "ppt/presentation.xml"
        val pres = pkg.xml(presName)?.takeIf { it.localName == "presentation" }
            ?: throw SlideDeck.NotSlides("This isn't a PowerPoint file")
        val size = pres.child("sldSz")
        val width = (size?.num("cx") ?: 9144000f) / EMU
        val height = (size?.num("cy") ?: 6858000f) / EMU
        defaultText = pres.child("defaultTextStyle")
        val rels = pkg.rels(presName)
        rels.values.firstOrNull { it.first.endsWith("/tableStyles") }?.let { (_, name) ->
            val root = runCatching { pkg.xml(name) }.getOrNull()
            defaultTableStyle = root?.attr("def")
            tableStyles = root?.kids("tblStyle")?.associateBy { it.getAttribute("styleId") }.orEmpty()
        }
        val first = pres.attr("firstSlideNum")?.toIntOrNull() ?: 1
        val names = pres.child("sldIdLst")?.kids("sldId").orEmpty().mapNotNull { rels[it.relId("id")]?.second }
        if (names.size > SlideDeck.MAX_SLIDES) throw SlideDeck.NotSlides("This deck has too many slides")
        if (names.isEmpty()) throw SlideDeck.NotSlides("This deck has no slides")
        val full = Frame(0f, 0f, width, height)
        val slides = names.mapIndexed { i, name ->
            runCatching { slide(name, first + i) }.getOrElse { Slide(null, listOf(Missing(full, "This slide couldn't be shown"))) }
        }
        return make(width, height, slides)
    }

    private fun theme(name: String?): Theme =
        name?.let { themes.getOrPut(it) { runCatching { Theme.parse(pkg.xml(it)) }.getOrDefault(Theme.EMPTY) } } ?: Theme.EMPTY

    private fun master(name: String): Master = masters.getOrPut(name) {
        val el = pkg.xml(name) ?: throw SlideDeck.NotSlides("A slide master is missing")
        val rels = pkg.rels(name)
        val theme = theme(rels.values.firstOrNull { it.first.endsWith("/theme") }?.second)
        val map = el.child("clrMap")?.let(::attrMap) ?: DEFAULT_MAP
        Master(name, el, rels, theme, map, placeholders(el))
    }

    private fun layout(name: String?): Layout? = name?.let {
        layouts.getOrPut(it) {
            val el = pkg.xml(it) ?: return@getOrPut null
            val rels = pkg.rels(it)
            val masterName = rels.values.firstOrNull { r -> r.first.endsWith("/slideMaster") }?.second ?: return@getOrPut null
            Layout(it, el, rels, master(masterName), placeholders(el))
        }
    }

    private fun slide(name: String, number: Int): Slide {
        val el = pkg.xml(name) ?: throw SlideDeck.NotSlides("A slide is missing")
        val rels = pkg.rels(name)
        val layout = layout(rels.values.firstOrNull { it.first.endsWith("/slideLayout") }?.second)
        val master = layout?.master ?: bare
        val map = el.child("clrMapOvr")?.child("overrideClrMapping")?.let(::attrMap) ?: master.clrMap
        val ctx = Ctx(rels, Colors(master.theme, map), master, layout, false, number)

        val cSld = el.child("cSld")
        val background = background(cSld, ctx)
            ?: layout?.let { background(it.el.child("cSld"), ctx.withRels(it.rels)) }
            ?: background(master.el.child("cSld"), ctx.withRels(master.rels))
        items = 0
        val out = ArrayList<Item>()
        val showLayout = el.flag("showMasterSp") != false
        if (showLayout && layout?.el?.flag("showMasterSp") != false) {
            master.el.child("cSld")?.child("spTree")?.let { walk(it, ctx.withRels(master.rels, decor = true), Xf(), out, 0) }
        }
        if (showLayout && layout != null) layout.el.child("cSld")?.child("spTree")?.let { walk(it, ctx.withRels(layout.rels, decor = true), Xf(), out, 0) }
        cSld?.child("spTree")?.let { walk(it, ctx, Xf(), out, 0) }
        return Slide(background, out)
    }

    private val bare by lazy {
        val el = parseXml("""<p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree/></p:cSld></p:sldMaster>""".toByteArray())
        Master("", el, emptyMap(), Theme.EMPTY, DEFAULT_MAP, emptyList())
    }

    private fun background(cSld: Element?, ctx: Ctx): Fill? {
        val bg = cSld?.child("bg") ?: return null
        bg.child("bgPr")?.let { return fill(it, ctx) }
        val ref = bg.child("bgRef") ?: return null
        return themeFill(ref.num("idx")?.toInt() ?: 0, ctx.colors.inside(ref), ctx)
    }

    // ---- Shapes ----

    private fun walk(tree: Element, ctx: Ctx, t: Xf, out: MutableList<Item>, depth: Int) {
        if (depth > MAX_DEPTH) return
        for (el in tree.elements()) {
            if (items >= MAX_ITEMS) return
            if (nvProps(el)?.child("cNvPr")?.flag("hidden") == true) continue
            runCatching {
                when (el.localName) {
                    "sp", "cxnSp" -> shape(el, ctx, t)?.let { out.add(it); items++ }
                    "pic" -> picture(el, ctx, t)?.let { out.add(it); items++ }
                    "grpSp" -> group(el, ctx, t, out, depth)
                    "graphicFrame" -> graphicFrame(el, ctx, t, out, depth)
                    "AlternateContent" -> (el.child("Fallback") ?: el.child("Choice"))?.let { walk(it, ctx, t, out, depth + 1) }
                }
            }
        }
    }

    private fun group(el: Element, ctx: Ctx, t: Xf, out: MutableList<Item>, depth: Int) {
        val x = el.child("grpSpPr")?.child("xfrm")
        val inner = if (x == null) t else {
            val off = x.child("off")
            val ext = x.child("ext")
            val chOff = x.child("chOff")
            val chExt = x.child("chExt")
            fun d(e: Element?, n: String) = (e?.num(n) ?: 0f).toDouble()
            val sx = d(chExt, "cx").let { if (it > 0) d(ext, "cx") / it else 1.0 }
            val sy = d(chExt, "cy").let { if (it > 0) d(ext, "cy") / it else 1.0 }
            t.then(sx, sy, d(off, "x") - d(chOff, "x") * sx, d(off, "y") - d(chOff, "y") * sy)
        }
        walk(el, ctx, inner, out, depth + 1)
    }

    private fun nvProps(el: Element): Element? = el.elements().firstOrNull { it.localName.startsWith("nv") }

    private fun phOf(el: Element): Ph? {
        val ph = nvProps(el)?.child("nvPr")?.child("ph") ?: return null
        return Ph(ph.attr("type") ?: "obj", ph.attr("idx"), el)
    }

    private fun placeholders(root: Element): List<Ph> =
        root.child("cSld")?.child("spTree")?.elements().orEmpty().mapNotNull(::phOf)

    private fun inherited(ph: Ph, ctx: Ctx): List<Ph> {
        val layout = ctx.layout ?: return emptyList()
        val lay = ph.idx?.let { idx -> layout.phs.firstOrNull { it.idx == idx } }
            ?: layout.phs.firstOrNull { it.type == ph.type }
            ?: layout.phs.firstOrNull { sameKind(it.type, ph.type) }
        val type = masterType(lay?.type ?: ph.type)
        val mas = layout.master.phs.firstOrNull { masterType(it.type) == type }
        return listOfNotNull(lay, mas)
    }

    private fun masterType(type: String) = when (type) {
        "title", "ctrTitle" -> "title"
        "dt", "ftr", "sldNum", "hdr" -> type
        else -> "body"
    }

    private fun sameKind(a: String, b: String) = masterType(a) == masterType(b)

    private fun frame(xfrm: Element, t: Xf): Frame {
        val off = xfrm.child("off")
        val ext = xfrm.child("ext")
        val x = t.x((off?.num("x") ?: 0f).toDouble())
        val y = t.y((off?.num("y") ?: 0f).toDouble())
        val w = (ext?.num("cx") ?: 0f) * t.sx
        val h = (ext?.num("cy") ?: 0f) * t.sy
        return Frame(
            (x / EMU).toFloat(), (y / EMU).toFloat(), (w / EMU).toFloat(), (h / EMU).toFloat(),
            (xfrm.num("rot") ?: 0f) / 60000f, xfrm.flag("flipH") == true, xfrm.flag("flipV") == true,
        )
    }

    private fun shape(el: Element, ctx: Ctx, t: Xf): Item? {
        val ph = phOf(el)
        if (ph != null && ctx.decor) return null
        val from = ph?.let { inherited(it, ctx) }.orEmpty()
        val spPrs = listOfNotNull(el.child("spPr")) + from.mapNotNull { it.el.child("spPr") }
        val xfrm = spPrs.firstNotNullOfOrNull { it.child("xfrm") } ?: return null
        val frame = frame(xfrm, t)
        val style = el.child("style")
        val geometry = spPrs.firstNotNullOfOrNull { geometry(it) } ?: Geometry.RECT
        val fill = spPrs.firstNotNullOfOrNull { fill(it, ctx) }
            ?: style?.child("fillRef")?.let { r -> themeFill(r.num("idx")?.toInt() ?: 0, ctx.colors.inside(r), ctx) }
        val styleLine = style?.child("lnRef")?.let { r -> themeLine(r.num("idx")?.toInt() ?: 0, ctx.colors.inside(r), ctx) }
        val ln = spPrs.firstNotNullOfOrNull { it.child("ln") }
        val line = line(ln, ctx, styleLine)
        val fontRef = style?.child("fontRef")
        val text = el.child("txBody")?.let { body ->
            val lists = ArrayList<Element?>()
            lists += defaultText
            when (ph?.let { masterType(from.lastOrNull()?.type ?: it.type) }) {
                null -> Unit
                "title" -> lists += ctx.master.el.child("txStyles")?.child("titleStyle")
                "body" -> lists += ctx.master.el.child("txStyles")?.child("bodyStyle")
                else -> lists += ctx.master.el.child("txStyles")?.child("otherStyle")
            }
            val extra = fontRef?.let {
                RStyle(
                    color = ctx.colors.inside(it),
                    font = when (it.attr("idx")) { "major" -> "+mj-lt"; "minor" -> "+mn-lt"; else -> null },
                )
            }
            val layered = lists.map { parseList(it, ctx) } +
                listOfNotNull(extra?.let { ListStyle(PStyle(r = it), arrayOfNulls(9)) }) +
                from.reversed().map { parseList(it.el.child("txBody")?.child("lstStyle"), ctx) } +
                listOf(parseList(body.child("lstStyle"), ctx))
            val bodyPrs = listOfNotNull(body.child("bodyPr")) + from.mapNotNull { it.el.child("txBody")?.child("bodyPr") }
            textBody(body, layered.filterNotNull(), bodyPrs, ctx)
        }
        val textFrame = el.child("txXfrm")?.let { frame(it, t) }
        if (fill == null && line == null && text == null) return null
        return Shape(frame, geometry, fill, line, text, textFrame)
    }

    private fun picture(el: Element, ctx: Ctx, t: Xf, frameOverride: Frame? = null): Item? {
        val ph = phOf(el)
        if (ph != null && ctx.decor) return null
        val from = ph?.let { inherited(it, ctx) }.orEmpty()
        val spPrs = listOfNotNull(el.child("spPr")) + from.mapNotNull { it.el.child("spPr") }
        val frame = frameOverride ?: spPrs.firstNotNullOfOrNull { it.child("xfrm") }?.let { frame(it, t) } ?: return null
        val blipFill = el.child("blipFill") ?: return null
        val image = ctx.target(blipFill.child("blip")?.relId("embed")) ?: return null
        val src = blipFill.child("srcRect")
        val crop = floatArrayOf(
            (src?.num("l") ?: 0f) / 100000f, (src?.num("t") ?: 0f) / 100000f,
            (src?.num("r") ?: 0f) / 100000f, (src?.num("b") ?: 0f) / 100000f,
        )
        val styleLine = el.child("style")?.child("lnRef")?.let { r -> themeLine(r.num("idx")?.toInt() ?: 0, ctx.colors.inside(r), ctx) }
        return Picture(
            frame, image, crop,
            spPrs.firstNotNullOfOrNull { geometry(it) } ?: Geometry.RECT,
            line(spPrs.firstNotNullOfOrNull { it.child("ln") }, ctx, styleLine),
        )
    }

    private fun graphicFrame(el: Element, ctx: Ctx, t: Xf, out: MutableList<Item>, depth: Int) {
        val ph = phOf(el)
        if (ph != null && ctx.decor) return
        val xfrm = el.child("xfrm") ?: ph?.let { inherited(it, ctx) }?.firstNotNullOfOrNull { it.el.child("xfrm") } ?: return
        val frame = frame(xfrm, t)
        val data = el.child("graphic")?.child("graphicData") ?: return
        val uri = data.attr("uri").orEmpty()
        data.child("tbl")?.let { out.add(table(it, frame, ctx)); items++; return }
        when {
            uri.endsWith("/chart") || uri.contains("chartex") -> { out.add(Missing(frame, "Chart")); items++ }
            uri.endsWith("/diagram") -> {
                val dataPart = ctx.target(data.child("relIds")?.relId("dm")) ?: return
                val drawingId = pkg.xml(dataPart)?.descendant("dataModelExt")?.attr("relId")
                val drawing = ctx.target(drawingId) ?: return
                val tree = pkg.xml(drawing)?.child("spTree") ?: return
                val off = xfrm.child("off")
                val inner = t.then(1.0, 1.0, (off?.num("x") ?: 0f).toDouble(), (off?.num("y") ?: 0f).toDouble())
                walk(tree, ctx.withRels(pkg.rels(drawing)), inner, out, depth + 1)
            }
            else -> data.descendant("pic")?.let { pic -> picture(pic, ctx, t, frame)?.let { out.add(it); items++ } }
        }
    }

    private fun fill(parent: Element, ctx: Ctx, ph: Int? = null): Fill? {
        for (el in parent.elements()) {
            when (el.localName) {
                "noFill" -> return NoFill
                "solidFill" -> return ctx.colors.inside(el, ph)?.let(::Solid)
                "gradFill" -> {
                    val stops = el.child("gsLst")?.kids("gs").orEmpty().mapNotNull { gs ->
                        ctx.colors.inside(gs, ph)?.let { ((gs.num("pos") ?: 0f) / 100000f) to it }
                    }.sortedBy { it.first }
                    if (stops.isEmpty()) return null
                    if (stops.size == 1) return Solid(stops[0].second)
                    val path = el.child("path")
                    return Gradient(stops, (el.child("lin")?.num("ang") ?: 0f) / 60000f, path != null && path.attr("path") != "rect")
                }
                "blipFill" -> return ctx.target(el.child("blip")?.relId("embed"))?.let(::ImageFill)
                "pattFill" -> return ctx.colors.inside(el.child("fgClr"), ph)?.let(::Solid)
            }
        }
        return null
    }

    private fun themeFill(idx: Int, color: Int?, ctx: Ctx): Fill? {
        val theme = ctx.colors.theme
        val el = when {
            idx >= 1001 -> theme.bgFills.getOrNull(idx - 1001)
            idx >= 1 -> theme.fills.getOrNull(idx - 1)
            else -> return NoFill
        }
        val holder = el?.ownerDocument?.createElement("x")?.also { it.appendChild(el.cloneNode(true)) }
        return holder?.let { fill(it, ctx, color) } ?: color?.let(::Solid)
    }

    private fun themeLine(idx: Int, color: Int?, ctx: Ctx): Line? {
        if (idx <= 0) return null
        val el = ctx.colors.theme.lines.getOrNull(idx - 1)
        val c = el?.child("solidFill")?.let { ctx.colors.inside(it, color) } ?: color ?: return null
        return Line(c, (el?.num("w") ?: 9525f) / EMU, el?.child("prstDash")?.attr("val"))
    }

    private fun line(ln: Element?, ctx: Ctx, base: Line?): Line? {
        if (ln == null) return base
        if (ln.child("noFill") != null) return null
        val color = ctx.colors.inside(ln.child("solidFill"))
            ?: ln.child("gradFill")?.child("gsLst")?.kids("gs")?.firstOrNull()?.let { ctx.colors.inside(it) }
            ?: base?.color ?: return null
        fun end(n: String) = ln.child(n)?.attr("type")?.takeIf { it != "none" }
        return Line(
            color,
            ln.num("w")?.let { it / EMU } ?: base?.width ?: 0.75f,
            ln.child("prstDash")?.attr("val") ?: base?.dash,
            end("headEnd") ?: base?.head,
            end("tailEnd") ?: base?.tail,
        )
    }

    private fun geometry(spPr: Element): Geometry? {
        spPr.child("prstGeom")?.let { g ->
            val adj = g.child("avLst")?.kids("gd").orEmpty().associate {
                it.getAttribute("name") to (it.getAttribute("fmla").removePrefix("val ").trim().toFloatOrNull() ?: 0f)
            }
            return Geometry(g.getAttribute("prst"), adj)
        }
        val cust = spPr.child("custGeom") ?: return null
        val paths = cust.child("pathLst")?.kids("path").orEmpty().map { p ->
            fun pt(e: Element?) = (e?.num("x") ?: 0f) to (e?.num("y") ?: 0f)
            val cmds = p.elements().mapNotNull { c ->
                val pts = c.kids("pt").map(::pt)
                when (c.localName) {
                    "moveTo" -> pts.firstOrNull()?.let { PathCmd.Move(it.first, it.second) }
                    "lnTo" -> pts.firstOrNull()?.let { PathCmd.LineTo(it.first, it.second) }
                    "cubicBezTo" -> if (pts.size >= 3) PathCmd.Cubic(pts[0].first, pts[0].second, pts[1].first, pts[1].second, pts[2].first, pts[2].second) else null
                    "quadBezTo" -> if (pts.size >= 2) PathCmd.Quad(pts[0].first, pts[0].second, pts[1].first, pts[1].second) else null
                    "arcTo" -> PathCmd.Arc(c.num("wR") ?: 0f, c.num("hR") ?: 0f, (c.num("stAng") ?: 0f) / 60000f, (c.num("swAng") ?: 0f) / 60000f)
                    "close" -> PathCmd.Close
                    else -> null
                }
            }
            GeoPath(p.num("w") ?: 0f, p.num("h") ?: 0f, p.attr("fill") != "none", p.flag("stroke") != false, cmds)
        }
        return Geometry("custom", paths = paths)
    }

    // ---- Text ----

    private fun parseList(el: Element?, ctx: Ctx): ListStyle? {
        el ?: return null
        val levels = arrayOfNulls<PStyle>(9)
        for (i in 0 until 9) levels[i] = el.child("lvl${i + 1}pPr")?.let { pStyle(it, ctx) }
        return ListStyle(el.child("defPPr")?.let { pStyle(it, ctx) }, levels)
    }

    private fun spacing(el: Element?): Spacing? {
        el ?: return null
        el.child("spcPct")?.num("val")?.let { return Spacing(percent = it / 1000f) }
        el.child("spcPts")?.num("val")?.let { return Spacing(points = it / 100f) }
        return null
    }

    private fun pStyle(p: Element, ctx: Ctx): PStyle {
        var bullet: BulletKind? = null
        var bulletFont: String? = null
        var color: Int? = null
        var colorSet = false
        var size: Float? = null
        for (c in p.elements()) {
            when (c.localName) {
                "buNone" -> bullet = BulletKind.None
                "buChar" -> bullet = BulletKind.Char(c.getAttribute("char"), null)
                "buAutoNum" -> bullet = BulletKind.Auto(c.getAttribute("type"), c.num("startAt")?.toInt() ?: 1)
                "buBlip" -> bullet = BulletKind.Char("•", null)
                "buFont" -> bulletFont = c.attr("typeface")
                "buClrTx" -> { color = null; colorSet = true }
                "buClr" -> { color = ctx.colors.inside(c); colorSet = true }
                "buSzTx" -> size = 100f
                "buSzPct" -> size = c.num("val")?.let { it / 1000f }
            }
        }
        if (bullet is BulletKind.Char && bulletFont != null) bullet = BulletKind.Char(bullet.c, bulletFont)
        return PStyle(
            align = p.attr("algn"),
            marL = p.num("marL")?.let { it / EMU },
            indent = p.num("indent")?.let { it / EMU },
            bullet = bullet,
            bulletColor = color,
            bulletColorSet = colorSet,
            bulletSize = size,
            lineSpacing = spacing(p.child("lnSpc")),
            before = spacing(p.child("spcBef")),
            after = spacing(p.child("spcAft")),
            r = p.child("defRPr")?.let { rStyle(it, ctx) } ?: RStyle(),
        )
    }

    private fun rStyle(r: Element, ctx: Ctx): RStyle {
        val color = r.child("solidFill")?.let { ctx.colors.inside(it) }
            ?: r.child("gradFill")?.child("gsLst")?.kids("gs")?.firstOrNull()?.let { ctx.colors.inside(it) }
            ?: if (r.child("noFill") != null) 0 else null
        return RStyle(
            size = r.num("sz")?.let { it / 100f },
            bold = r.flag("b"),
            italic = r.flag("i"),
            underline = r.attr("u")?.let { it != "none" },
            strike = r.attr("strike")?.let { it != "noStrike" },
            caps = r.attr("cap")?.let { it == "all" },
            color = color,
            font = r.child("latin")?.attr("typeface") ?: r.child("ea")?.attr("typeface"),
            baseline = r.num("baseline")?.let { it / 1000f },
        )
    }

    private fun font(name: String?, ctx: Ctx): String = when {
        name == null -> ctx.colors.theme.minor
        name.startsWith("+mj") -> ctx.colors.theme.major
        name.startsWith("+mn") -> ctx.colors.theme.minor
        else -> name
    }

    private fun textBody(body: Element, lists: List<ListStyle>, bodyPrs: List<Element>, ctx: Ctx, extra: RStyle? = null): TextBody? {
        val paras = paragraphs(body, lists, ctx, extra)
        if (paras.none { p -> p.runs.any { it.text.isNotBlank() } }) return null
        fun a(n: String) = bodyPrs.firstNotNullOfOrNull { it.attr(n) }
        fun inset(n: String, def: Float) = (a(n)?.toFloatOrNull() ?: def) / EMU
        val fit = bodyPrs.firstNotNullOfOrNull { b -> b.elements().firstOrNull { it.localName in FITS } }
        val scale = fit?.takeIf { it.localName == "normAutofit" }?.num("fontScale")
        return TextBody(
            inset("lIns", 91440f), inset("tIns", 45720f), inset("rIns", 91440f), inset("bIns", 45720f),
            when (a("anchor")) { "ctr" -> 'c'; "b" -> 'b'; else -> 't' },
            a("wrap") != "none",
            shrink = fit?.localName == "normAutofit" && scale == null,
            fontScale = scale?.let { it / 100000f } ?: 1f,
            spacingReduction = fit?.num("lnSpcReduction")?.let { it / 1000f } ?: 0f,
            vertical = when (a("vert")) { "vert", "eaVert", "wordArtVert" -> 90; "vert270" -> 270; else -> 0 },
            paras = paras,
        )
    }

    private fun paragraphs(body: Element, lists: List<ListStyle>, ctx: Ctx, extra: RStyle?): List<Para> {
        val base = PStyle(
            align = "l", marL = 0f, indent = 0f, bullet = BulletKind.None,
            r = RStyle(18f, false, false, false, false, false, ctx.colors.scheme("tx1") ?: 0xFF000000.toInt(), "+mn-lt", 0f),
        )
        val counters = IntArray(9)
        val numbered = BooleanArray(9)
        val schemes = arrayOfNulls<String>(9)
        return body.kids("p").map { p ->
            val pPr = p.child("pPr")
            val level = (pPr?.num("lvl")?.toInt() ?: 0).coerceIn(0, 8)
            var st = base
            for (l in lists) l.at(level)?.let { st = it.over(st) }
            if (extra != null) st = st.copy(r = extra.over(st.r))
            pPr?.let { st = pStyle(it, ctx).over(st) }

            val runs = ArrayList<Run>()
            for (c in p.elements()) {
                val text = when (c.localName) {
                    "r" -> c.child("t")?.textContent
                    "br" -> " "
                    "fld" -> if (c.attr("type") == "slidenum") ctx.slideNumber.toString() else c.child("t")?.textContent
                    else -> null
                } ?: continue
                val rs = (c.child("rPr")?.let { rStyle(it, ctx) } ?: RStyle()).over(st.r)
                runs += Run(
                    if (rs.caps == true) text.uppercase() else text,
                    rs.size ?: 18f, rs.bold == true, rs.italic == true, rs.underline == true, rs.strike == true,
                    rs.color ?: 0xFF000000.toInt(), font(rs.font, ctx), rs.baseline ?: 0f,
                )
            }
            val endSize = p.child("endParaRPr")?.num("sz")?.let { it / 100f } ?: st.r.size ?: 18f

            for (l in level + 1 until 9) numbered[l] = false
            val hasText = runs.any { it.text.isNotBlank() }
            val kind = st.bullet
            val bullet = if (!hasText) null else when (kind) {
                is BulletKind.Char -> Bullet(bulletChar(kind.c, kind.font), st.bulletColor, st.bulletSize ?: 100f)
                is BulletKind.Auto -> {
                    val goingOn = numbered[level] && schemes[level] == kind.scheme
                    counters[level] = if (goingOn) counters[level] + 1 else kind.start
                    numbered[level] = true
                    schemes[level] = kind.scheme
                    Bullet(autoNumber(kind.scheme, counters[level]), st.bulletColor, st.bulletSize ?: 100f)
                }
                else -> null
            }
            if (hasText && kind !is BulletKind.Auto) numbered[level] = false
            Para(
                when (st.align) { "ctr" -> 'c'; "r" -> 'r'; "just", "dist" -> 'j'; else -> 'l' },
                st.marL ?: 0f, st.indent ?: 0f, bullet, st.lineSpacing, st.before, st.after, runs, endSize,
            )
        }
    }

    // ---- Tables ----

    private fun table(tbl: Element, frame: Frame, ctx: Ctx): Table {
        val cols = tbl.child("tblGrid")?.kids("gridCol").orEmpty().map { (it.num("w") ?: 0f) / EMU }
        val trs = tbl.kids("tr")
        val rows = trs.map { (it.num("h") ?: 0f) / EMU }
        val pr = tbl.child("tblPr")
        val styleId = pr?.child("tableStyleId")?.textContent?.trim() ?: defaultTableStyle
        val style = styleId?.let { id -> tableStyles[id]?.let { tableLooks(it, ctx) } ?: builtInLooks(ctx) }.orEmpty()
        fun on(n: String) = pr?.flag(n) == true
        val firstRow = on("firstRow")
        val lastRow = on("lastRow")
        val defaults = listOfNotNull(parseList(defaultText, ctx))
        val cells = ArrayList<TableCell>()
        trs.forEachIndexed { r, tr ->
            tr.kids("tc").forEachIndexed { c, tc ->
                if (tc.flag("hMerge") == true || tc.flag("vMerge") == true) return@forEachIndexed
                val colSpan = (tc.num("gridSpan")?.toInt() ?: 1).coerceAtLeast(1)
                val rowSpan = (tc.num("rowSpan")?.toInt() ?: 1).coerceAtLeast(1)
                val parts = ArrayList<String>()
                parts += "wholeTbl"
                val bodyRow = r - (if (firstRow) 1 else 0)
                if (on("bandRow") && bodyRow >= 0 && !(lastRow && r == trs.size - 1)) parts += if (bodyRow % 2 == 0) "band1H" else "band2H"
                if (on("bandCol")) parts += if (c % 2 == 0) "band1V" else "band2V"
                if (on("firstCol") && c == 0) parts += "firstCol"
                if (on("lastCol") && c + colSpan == cols.size) parts += "lastCol"
                if (lastRow && r + rowSpan == trs.size) parts += "lastRow"
                if (firstRow && r == 0) parts += "firstRow"
                val looks = parts.mapNotNull { style[it] }

                val tcPr = tc.child("tcPr")
                val fill = tcPr?.let { fill(it, ctx) } ?: looks.lastOrNull { it.fill != null }?.fill
                fun side(own: String, edge: String, inside: String, isEdge: Boolean): Line? {
                    tcPr?.child(own)?.let { return line(it, ctx, null) }
                    val key = if (isEdge) edge else inside
                    return looks.lastOrNull { it.borders.containsKey(key) }?.borders?.get(key)
                }
                val text = tc.child("txBody")?.let { body ->
                    val extra = RStyle(
                        bold = looks.lastOrNull { it.bold != null }?.bold,
                        italic = looks.lastOrNull { it.italic != null }?.italic,
                        color = looks.lastOrNull { it.color != null }?.color,
                    )
                    textBody(body, defaults + listOfNotNull(parseList(body.child("lstStyle"), ctx)), listOfNotNull(body.child("bodyPr")), ctx, extra)
                }?.let { tb ->
                    fun m(n: String, def: Float) = (tcPr?.num(n) ?: def) / EMU
                    TextBody(
                        m("marL", 91440f), m("marT", 45720f), m("marR", 91440f), m("marB", 45720f),
                        when (tcPr?.attr("anchor")) { "ctr" -> 'c'; "b" -> 'b'; else -> 't' },
                        true, false, 1f, 0f,
                        when (tcPr?.attr("vert")) { "vert", "eaVert" -> 90; "vert270" -> 270; else -> 0 },
                        tb.paras,
                    )
                }
                cells += TableCell(
                    r, c, rowSpan, colSpan, fill,
                    side("lnL", "left", "insideV", c == 0),
                    side("lnT", "top", "insideH", r == 0),
                    side("lnR", "right", "insideV", c + colSpan >= cols.size),
                    side("lnB", "bottom", "insideH", r + rowSpan >= trs.size),
                    text,
                )
            }
        }
        return Table(frame, cols, rows, cells)
    }

    private fun tableLooks(style: Element, ctx: Ctx): Map<String, CellLook> = style.elements().associate { part ->
        val tcStyle = part.child("tcStyle")
        val fill = tcStyle?.child("fill")?.let { fill(it, ctx) }
            ?: tcStyle?.child("fillRef")?.let { themeFill(it.num("idx")?.toInt() ?: 0, ctx.colors.inside(it), ctx) }
        val borders = HashMap<String, Line?>()
        tcStyle?.child("tcBdr")?.elements()?.forEach { side ->
            borders[side.localName] = side.child("ln")?.let { line(it, ctx, null) }
                ?: side.child("lnRef")?.let { themeLine(it.num("idx")?.toInt() ?: 0, ctx.colors.inside(it), ctx) }
        }
        val tx = part.child("tcTxStyle")
        val color = tx?.let { ctx.colors.inside(it) ?: ctx.colors.inside(it.child("fontRef")) }
        part.localName to CellLook(fill, tx?.flag("b"), tx?.flag("i"), color, borders)
    }

    private fun builtInLooks(ctx: Ctx): Map<String, CellLook> {
        val accent = ctx.colors.scheme("accent1") ?: 0xFF4472C4.toInt()
        val light = ctx.colors.scheme("lt1") ?: 0xFFFFFFFF.toInt()
        val dark = ctx.colors.scheme("dk1") ?: 0xFF000000.toInt()
        fun tint(v: Float) = Solid(mix(accent, 0xFFFFFFFF.toInt(), v))
        val white = Line(light, 1f)
        val grid = mapOf("left" to white, "right" to white, "top" to white, "bottom" to white, "insideH" to white, "insideV" to white)
        return mapOf(
            "wholeTbl" to CellLook(tint(0.2f), null, null, dark, grid),
            "band1H" to CellLook(tint(0.4f), null, null, null, emptyMap()),
            "firstRow" to CellLook(Solid(accent), true, null, light, mapOf("bottom" to Line(light, 3f))),
            "lastRow" to CellLook(Solid(accent), true, null, light, emptyMap()),
            "firstCol" to CellLook(Solid(accent), true, null, light, emptyMap()),
            "lastCol" to CellLook(Solid(accent), true, null, light, emptyMap()),
        )
    }

    private fun mix(c: Int, white: Int, amount: Float): Int {
        fun ch(s: Int) = ((((c shr s) and 0xFF) * amount + ((white shr s) and 0xFF) * (1 - amount)).toInt() and 0xFF) shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    companion object {
        private val FITS = setOf("normAutofit", "spAutoFit", "noAutofit")

        private val DEFAULT_MAP = mapOf(
            "bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2",
            "accent1" to "accent1", "accent2" to "accent2", "accent3" to "accent3", "accent4" to "accent4",
            "accent5" to "accent5", "accent6" to "accent6", "hlink" to "hlink", "folHlink" to "folHlink",
        )

        private fun attrMap(el: Element): Map<String, String> {
            val out = HashMap<String, String>()
            val attrs = el.attributes
            for (i in 0 until attrs.length) attrs.item(i).let { out[it.localName ?: it.nodeName] = it.nodeValue }
            return out
        }

        private val SYMBOL_BULLETS = mapOf(
            'l' to "●", 'n' to "■", 'q' to "❑", 'v' to "❖", 'u' to "◆", 'Ø' to "➢", 'ü' to "✔",
            '§' to "▪", 'Ÿ' to "•", '·' to "•", 'o' to "○", 'p' to "□", 'à' to "➔", 'è' to "➔", 'Ü' to "✔",
        )

        internal fun bulletChar(c: String, font: String?): String {
            val ch = c.firstOrNull() ?: return "•"
            val symbolic = font != null && (font.startsWith("Wingdings", true) || font.equals("Symbol", true) || font.startsWith("Webdings", true))
            val plain = if (ch.code in 0xF000..0xF0FF) (ch.code - 0xF000).toChar() else ch
            return if (symbolic || plain != ch) SYMBOL_BULLETS[plain] ?: "•" else c
        }

        internal fun autoNumber(scheme: String, n: Int): String {
            val body = when {
                scheme.startsWith("alphaLc") -> alpha(n).lowercase()
                scheme.startsWith("alphaUc") -> alpha(n)
                scheme.startsWith("romanLc") -> roman(n).lowercase()
                scheme.startsWith("romanUc") -> roman(n)
                else -> n.toString()
            }
            return when {
                scheme.endsWith("ParenBoth") -> "($body)"
                scheme.endsWith("ParenR") -> "$body)"
                scheme.endsWith("Period") -> "$body."
                else -> body
            }
        }

        private fun alpha(n: Int): String {
            var v = n.coerceAtLeast(1)
            val sb = StringBuilder()
            while (v > 0) { v--; sb.insert(0, 'A' + v % 26); v /= 26 }
            return sb.toString()
        }

        private fun roman(n: Int): String {
            if (n !in 1..3999) return n.toString()
            val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var v = n
            val sb = StringBuilder()
            for (i in vals.indices) while (v >= vals[i]) { sb.append(syms[i]); v -= vals[i] }
            return sb.toString()
        }
    }
}
