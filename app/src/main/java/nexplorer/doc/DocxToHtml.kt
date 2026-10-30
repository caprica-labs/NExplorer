package nexplorer.doc

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object DocxToHtml {
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun convert(input: InputStream, css: String = ""): String {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) parts[e.name] = z.readBytes()
            }
        }
        val docBytes = parts["word/document.xml"] ?: throw IllegalArgumentException("Not a DOCX file")
        val rels = parts["word/_rels/document.xml.rels"]?.let(::relationships) ?: emptyMap()
        val decimalLists = parts["word/numbering.xml"]?.let(::decimalNumIds) ?: emptySet()
        val body = parse(docBytes).documentElement.child("body") ?: return wrap("", css)

        val ctx = Ctx(parts, rels, decimalLists)
        val sb = StringBuilder()
        block(body, sb, ctx)
        ctx.closeList(sb)
        return wrap(sb.toString(), css)
    }

    private class Ctx(val parts: Map<String, ByteArray>, val rels: Map<String, String>, val decimal: Set<String>) {
        var openList: String? = null
        fun closeList(sb: StringBuilder) {
            openList?.let { sb.append("</$it>") }
            openList = null
        }
    }

    private fun block(parent: Element, sb: StringBuilder, ctx: Ctx) {
        for (el in parent.elements()) {
            when (el.localName) {
                "p" -> paragraph(el, sb, ctx)
                "tbl" -> { ctx.closeList(sb); table(el, sb, ctx) }
                "sdt" -> el.child("sdtContent")?.let { block(it, sb, ctx) }
            }
        }
    }

    private fun paragraph(p: Element, sb: StringBuilder, ctx: Ctx) {
        val pPr = p.child("pPr")
        val style = pPr?.child("pStyle")?.wAttr("val") ?: ""
        val numId = pPr?.child("numPr")?.child("numId")?.wAttr("val")
        val inner = StringBuilder().also { inline(p, it, ctx) }.toString()

        if (numId != null && numId != "0") {
            val tag = if (numId in ctx.decimal) "ol" else "ul"
            if (ctx.openList != tag) { ctx.closeList(sb); sb.append("<$tag>"); ctx.openList = tag }
            sb.append("<li dir=\"auto\">").append(inner).append("</li>")
            return
        }
        ctx.closeList(sb)
        val tag = when {
            style == "Title" -> "h1"
            style.startsWith("Heading") && style.removePrefix("Heading").toIntOrNull() in 1..6 ->
                "h" + style.removePrefix("Heading")
            else -> "p"
        }
        sb.append("<$tag dir=\"auto\">").append(inner).append("</$tag>")
    }

    private fun inline(node: Element, sb: StringBuilder, ctx: Ctx) {
        for (el in node.elements()) {
            when (el.localName) {
                "pPr" -> Unit
                "r" -> run(el, sb, ctx)
                else -> inline(el, sb, ctx)
            }
        }
    }

    private fun run(r: Element, sb: StringBuilder, ctx: Ctx) {
        val rPr = r.child("rPr")
        val tags = listOf("b", "i").filter { rPr?.child(it)?.let(::isOn) == true } +
            listOfNotNull("u".takeIf { rPr?.child("u")?.let { it.wAttr("val") != "none" && it.wAttr("val") != "0" } == true })
        val content = StringBuilder()
        fun walk(n: Element) {
            for (el in n.elements()) {
                when (el.localName) {
                    "rPr" -> Unit
                    "t" -> content.append(escape(el.textContent))
                    "tab" -> content.append(" ")
                    "br", "cr" -> content.append("<br/>")
                    "drawing", "pict" -> drawing(el, ctx)?.let(content::append)
                    "blip" -> image(el, ctx, null)?.let(content::append)
                    else -> walk(el)
                }
            }
        }
        walk(r)
        if (content.isEmpty()) return
        tags.forEach { sb.append("<$it>") }
        sb.append(content)
        tags.asReversed().forEach { sb.append("</$it>") }
    }

    private fun drawing(d: Element, ctx: Ctx): String? {
        val blip = d.descendant("blip") ?: return null
        val widthPt = d.descendant("extent")?.getAttribute("cx")?.toLongOrNull()?.let { it / 12700 }
        return image(blip, ctx, widthPt?.takeIf { it > 0 })
    }

    private fun image(blip: Element, ctx: Ctx, widthPt: Long?): String? {
        val id = blip.getAttributeNS(R_NS, "embed").ifEmpty { return null }
        val target = ctx.rels[id] ?: return null
        val path = if (target.startsWith("/")) target.drop(1) else "word/$target"
        val bytes = ctx.parts[path] ?: return null
        val ext = path.substringAfterLast('.').lowercase().let { if (it == "jpg") "jpeg" else it }
        val size = widthPt?.let { " style=\"width:${it}pt\"" } ?: ""
        return "<img$size src=\"data:image/$ext;base64,${Base64.getEncoder().encodeToString(bytes)}\"/>"
    }

    private fun table(tbl: Element, sb: StringBuilder, ctx: Ctx) {
        sb.append("<table>")
        for (tr in tbl.elements().filter { it.localName == "tr" }) {
            sb.append("<tr>")
            for (tc in tr.elements().filter { it.localName == "tc" }) {
                sb.append("<td>")
                block(tc, sb, ctx)
                ctx.closeList(sb)
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
    }

    private fun relationships(bytes: ByteArray): Map<String, String> =
        parse(bytes).documentElement.elements()
            .filter { it.localName == "Relationship" }
            .associate { it.getAttribute("Id") to it.getAttribute("Target") }

    private fun decimalNumIds(bytes: ByteArray): Set<String> {
        val root = parse(bytes).documentElement
        val decimalAbstract = root.elements().filter { it.localName == "abstractNum" }
            .filter { a ->
                a.elements().firstOrNull { it.localName == "lvl" }?.child("numFmt")?.wAttr("val") == "decimal"
            }
            .map { it.wAttr("abstractNumId") }
            .toSet()
        return root.elements().filter { it.localName == "num" }
            .filter { it.child("abstractNumId")?.wAttr("val") in decimalAbstract }
            .map { it.wAttr("numId") }
            .toSet()
    }

    private fun isOn(el: Element): Boolean = el.wAttr("val").let { it.isEmpty() || it == "1" || it == "true" || it == "on" }

    private fun parse(bytes: ByteArray): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(bytes.inputStream())

    private fun Element.elements(): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE) out.add(n as Element)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(local: String): Element? = elements().firstOrNull { it.localName == local }

    private fun Element.descendant(local: String): Element? {
        for (el in elements()) {
            if (el.localName == local) return el
            el.descendant(local)?.let { return it }
        }
        return null
    }

    private fun Element.wAttr(local: String): String {
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.localName == local) return a.nodeValue
        }
        return ""
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    internal fun wrap(body: String, css: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head>" +
            "<style>table{border-collapse:collapse}td{border:1px solid #888;padding:4px}img{max-width:100%}$css</style>" +
            "</head><body>$body</body></html>"
}
