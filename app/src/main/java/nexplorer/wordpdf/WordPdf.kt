package nexplorer.wordpdf

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import nexplorer.doc.WordHtml
import nexplorer.scan.PageSize
import java.io.File

const val PDF_OPTIONS = "compress"

object WordPdf {
    private const val MARGIN = 50

    const val EM = 12f

    fun write(docx: File, size: PageSize, out: File, onPage: (Int) -> Unit = {}) {
        val html = WordHtml.of(docx, "body{margin:${MARGIN}pt;font-family:sans-serif}")
        val doc = Document.openDocument(html.toByteArray(), "xhtml")
        try {
            doc.layout(size.w, size.h, EM)
            writePdfPages(doc, out, onPage)
        } finally {
            doc.destroy()
        }
    }
}

fun writePdfPages(doc: Document, out: File, onPage: (Int) -> Unit) {
    out.parentFile?.mkdirs()
    val writer = DocumentWriter(out.path, "pdf", PDF_OPTIONS)
    try {
        for (i in 0 until doc.countPages()) {
            val page = doc.loadPage(i)
            try {
                val device = writer.beginPage(page.bounds)
                page.run(device, Matrix())
                writer.endPage()
            } finally {
                page.destroy()
            }
            onPage(i + 1)
        }
        writer.close()
    } finally {
        writer.destroy()
    }
}
