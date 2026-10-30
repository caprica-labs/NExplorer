package nexplorer.doc

import com.artifex.mupdf.fitz.Document

object ReflowHtml {
    fun fromPageTexts(pages: List<String>): String {
        val body = StringBuilder()
        for (text in pages) {
            for (para in text.replace("\r", "").split(Regex("\n\\s*\n"))) {
                val joined = para.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                if (joined.isNotEmpty()) body.append("<p>").append(escape(joined)).append("</p>")
            }
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head></head>" +
            "<body>$body</body></html>"
    }

    fun fromDocument(doc: Document): Document {
        val texts = (0 until doc.countPages()).map { i ->
            val page = doc.loadPage(i)
            try {
                val st = page.toStructuredText()
                try { st.asText() } finally { st.destroy() }
            } finally {
                page.destroy()
            }
        }
        return Document.openDocument(fromPageTexts(texts).toByteArray(), "xhtml")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
