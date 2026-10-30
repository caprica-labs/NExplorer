package nexplorer.doc

import com.artifex.mupdf.fitz.Document
import nexplorer.files.FormatKind
import nexplorer.files.Formats
import nexplorer.slides.SlideDeck
import nexplorer.slides.SlidePdf
import java.io.File

sealed interface OpenResult {
    data class Ok(val doc: Document, val kind: FormatKind) : OpenResult
    data class Error(val message: String) : OpenResult
    data class NeedsPassword(val wrong: Boolean) : OpenResult
}

object DocumentOpener {
    fun precheck(file: File): String? = when {
        Formats.kindOf(file.name) == null -> "This format isn't supported"
        !file.exists() -> "This file no longer exists"
        file.length() == 0L -> "This file is empty"
        else -> null
    }

    fun open(file: File, password: String? = null): OpenResult {
        precheck(file)?.let { return OpenResult.Error(it) }
        val kind = Formats.kindOf(file.name)!!
        return try {
            val doc = if (kind == FormatKind.DOCX) {
                val html = WordHtml.of(file)
                Document.openDocument(html.toByteArray(), "xhtml")
            } else if (file.name.endsWith(".cbr", ignoreCase = true)) {
                val cache = File(System.getProperty("java.io.tmpdir") ?: error("No cache folder"))
                Document.openDocument(ComicArchive.asCbz(file, cache).path)
            } else if (SlidePdf.isSlides(file.name)) {
                val cache = File(System.getProperty("java.io.tmpdir") ?: error("No cache folder"))
                Document.openDocument(SlidePdf.asPdf(file, cache).path)
            } else {
                Document.openDocument(file.path)
            }
            if (doc.needsPassword() && (password == null || !doc.authenticatePassword(password))) {
                doc.destroy()
                OpenResult.NeedsPassword(wrong = password != null)
            } else {
                OpenResult.Ok(doc, kind)
            }
        } catch (t: Throwable) {
            OpenResult.Error((t as? DocToHtml.Unsupported)?.message ?: (t as? SlideDeck.NotSlides)?.message ?: "This file couldn't be opened")
        }
    }
}
