package nexplorer.merge

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import nexplorer.doc.PasswordNeeded
import nexplorer.doc.PdfLock
import java.io.File

data class PdfInfo(val pages: Int, val problem: String?, val locked: Boolean = false)

object PdfMerge {
    fun info(file: File, password: String?): PdfInfo = runCatching {
        val doc = try {
            PdfLock.open(file.path, password)
        } catch (e: PasswordNeeded) {
            return PdfInfo(0, if (e.wrong) "Wrong password · tap to retry" else "Locked · tap to enter password", locked = true)
        }
        try {
            when {
                doc.asPDF() == null -> PdfInfo(0, "Not a PDF")
                else -> doc.countPages().let { if (it == 0) PdfInfo(0, "Damaged or empty") else PdfInfo(it, null) }
            }
        } finally {
            doc.destroy()
        }
    }.getOrElse { PdfInfo(0, "Can't be opened") }

    fun firstPage(file: File, password: String?, w: Int, h: Int): Bitmap? = runCatching {
        val doc = PdfLock.open(file.path, password)
        try {
            if (doc.countPages() == 0) return null
            val page = doc.loadPage(0)
            try { AndroidDrawDevice.drawPageFit(page, w, h) } finally { page.destroy() }
        } finally {
            doc.destroy()
        }
    }.getOrNull()

    fun write(files: List<File>, passwords: Map<String, String>, out: File, onFile: (Int) -> Unit = {}) {
        val merged = PDFDocument()
        try {
            files.forEachIndexed { i, f ->
                val doc = PdfLock.open(f.path, passwords[f.path])
                try {
                    val pdf = doc.asPDF() ?: error("${f.name} isn't a PDF")
                    val map = merged.newPDFGraftMap()
                    try {
                        for (p in 0 until pdf.countPages()) map.graftPage(-1, pdf, p)
                    } finally {
                        map.destroy()
                    }
                } finally {
                    doc.destroy()
                }
                onFile(i + 1)
            }
            out.parentFile?.mkdirs()
            merged.save(out.path, "compress,garbage")
        } finally {
            merged.destroy()
        }
    }
}
