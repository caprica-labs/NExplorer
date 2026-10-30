package nexplorer.textpdf

import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Story
import nexplorer.reader.TextBox
import nexplorer.scan.PageSize
import nexplorer.wordpdf.PDF_OPTIONS
import java.io.File

object TextPdf {
    const val MARGIN = 50f

    private const val CSS = "body { font-family: sans-serif; } p { margin: 0; white-space: pre-wrap; line-height: 1.3; }"

    private const val MAX_PAGES = 10_000

    fun write(text: RichText, size: PageSize, out: File, onPage: (Int) -> Unit = {}) {
        out.parentFile?.mkdirs()
        val story = Story(text.toHtml(), CSS, TextBox.DEFAULT_SIZE)
        val writer = DocumentWriter(out.path, "pdf", PDF_OPTIONS)
        try {
            val page = Rect(0f, 0f, size.w, size.h)
            val area = Rect(MARGIN, MARGIN, size.w - MARGIN, size.h - MARGIN)
            var pages = 0
            do {
                val more = story.place(area, Rect(), 0) != Story.ALL_FITTED
                val device = writer.beginPage(page)
                story.draw(device, Matrix())
                writer.endPage()
                onPage(++pages)
            } while (more && pages < MAX_PAGES)
            writer.close()
        } finally {
            story.destroy()
            writer.destroy()
        }
    }
}
