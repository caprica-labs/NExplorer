package nexplorer.wordpdf

import nexplorer.doc.DocumentOpener
import nexplorer.doc.OpenResult
import nexplorer.files.FormatKind
import nexplorer.files.Formats
import nexplorer.scan.PageSize
import java.io.File

object DocPdf {
    fun keepsPageSize(name: String) = Formats.kindOf(name) == FormatKind.FIXED

    fun write(file: File, size: PageSize, out: File, password: String? = null) {
        if (Formats.kindOf(file.name) == FormatKind.DOCX) return WordPdf.write(file, size, out)
        if (file.name.endsWith(".pdf", ignoreCase = true) && password == null) {
            file.copyTo(out, overwrite = true)
            return
        }
        val doc = when (val r = DocumentOpener.open(file, password)) {
            is OpenResult.Ok -> r.doc
            is OpenResult.Error -> error(r.message)
            is OpenResult.NeedsPassword -> error("This file is password-protected")
        }
        try {
            if (!keepsPageSize(file.name)) doc.layout(size.w, size.h, WordPdf.EM)
            writePdfPages(doc, out) {}
        } finally {
            doc.destroy()
        }
    }
}
