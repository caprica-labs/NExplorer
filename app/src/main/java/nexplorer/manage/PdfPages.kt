package nexplorer.manage

import android.graphics.Bitmap
import android.util.LruCache
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import nexplorer.doc.PdfLock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

object PdfPages {
    fun write(src: File, password: String?, pages: List<Int>, out: File) {
        val doc = PdfLock.open(src.path, password)
        try {
            val pdf = doc.asPDF() ?: error("Not a PDF")
            val result = PDFDocument()
            try {
                val map = result.newPDFGraftMap()
                try {
                    for (p in pages) map.graftPage(-1, pdf, p)
                } finally {
                    map.destroy()
                }
                out.parentFile?.mkdirs()
                result.save(out.path, "compress,garbage")
            } finally {
                result.destroy()
            }
        } finally {
            doc.destroy()
        }
    }
}

class PageThumbs(file: File, private val password: String?) {
    private val executor = Executors.newSingleThreadExecutor()
    private val thread = executor.asCoroutineDispatcher()
    private val cache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private var doc: Document? = null
    private val path = file.path

    suspend fun open(): Result<Int> = withContext(thread) {
        runCatching {
            val d = PdfLock.open(path, password)
            doc = d
            when {
                d.asPDF() == null -> error("Not a PDF")
                d.countPages() == 0 -> error("This PDF is damaged or empty")
                else -> d.countPages()
            }
        }
    }

    suspend fun page(index: Int, w: Int, h: Int): Bitmap? {
        cache.get(index)?.let { return it }
        return withContext(thread) {
            runCatching {
                val page = doc!!.loadPage(index)
                try { AndroidDrawDevice.drawPageFit(page, w, h) } finally { page.destroy() }
            }.getOrNull()?.also { cache.put(index, it) }
        }
    }

    fun close() {
        executor.execute { doc?.destroy(); doc = null }
        executor.shutdown()
    }
}
