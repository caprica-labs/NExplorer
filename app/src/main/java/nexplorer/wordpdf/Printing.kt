package nexplorer.wordpdf

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.webkit.WebView
import nexplorer.scan.PageSize
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

object Printing {
    fun webPage(ctx: Context, web: WebView, name: String) {
        print(ctx, name, web.createPrintDocumentAdapter(name))
    }

    fun file(ctx: Context, file: File, password: String?) {
        print(ctx, file.nameWithoutExtension, FileAdapter(file, password, ctx.cacheDir))
    }

    private fun print(ctx: Context, name: String, adapter: PrintDocumentAdapter) {
        val manager = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(name, adapter, null)
    }
}

private class FileAdapter(private val file: File, private val password: String?, private val cache: File) : PrintDocumentAdapter() {
    private val worker = Executors.newSingleThreadExecutor()

    private var made: File? = null
    private var madeFor: Pair<Int, Int>? = null

    override fun onLayout(
        old: PrintAttributes?,
        new: PrintAttributes,
        cancel: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        val media = new.mediaSize ?: return callback.onLayoutFailed("No paper size")
        val paper = media.widthMils to media.heightMils
        val info = PrintDocumentInfo.Builder("${file.nameWithoutExtension}.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        worker.execute {
            if (cancel.isCanceled) return@execute callback.onLayoutCancelled()
            if (made != null && (paper == madeFor || DocPdf.keepsPageSize(file.name))) {
                return@execute callback.onLayoutFinished(info, false)
            }
            try {
                val out = File(cache, "print-${System.nanoTime()}.pdf")
                val points = PageSize("", media.widthMils * 72f / 1000f, media.heightMils * 72f / 1000f)
                DocPdf.write(file, points, out, password)
                made?.delete()
                made = out
                madeFor = paper
                if (cancel.isCanceled) callback.onLayoutCancelled() else callback.onLayoutFinished(info, true)
            } catch (t: Throwable) {
                callback.onLayoutFailed(t.message ?: "This file couldn't be prepared for printing")
            }
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancel: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        worker.execute {
            try {
                val pdf = made ?: error("Nothing to print")
                pdf.inputStream().use { i -> FileOutputStream(destination.fileDescriptor).use { i.copyTo(it) } }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (t: Throwable) {
                callback.onWriteFailed(t.message ?: "This file couldn't be printed")
            }
        }
    }

    override fun onFinish() {
        worker.execute { made?.delete() }
        worker.shutdown()
    }
}
