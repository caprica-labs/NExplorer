package nexplorer.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

object IncomingFile {
    fun resolve(ctx: Context, uri: Uri, mime: String?): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.canRead() }
        if (uri.scheme != "content") return null
        val (name, size) = nameAndSize(ctx, uri)
        realFile(ctx, uri, name, size)?.let { return it }
        return copy(ctx, uri, withExtension(name ?: "document", mime))
    }

    private fun realFile(ctx: Context, uri: Uri, name: String?, size: Long?): File? {
        val candidates = buildList {
            if (uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isDocumentUri(ctx, uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                val volume = id.substringBefore(':')
                val rel = id.substringAfter(':', "")
                add(if (volume == "primary") File(Environment.getExternalStorageDirectory(), rel) else File("/storage/$volume", rel))
            }
            runCatching {
                ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { add(File(it)) }
                }
            }
            val path = Uri.decode(uri.encodedPath ?: "")
            path.indexOf("/storage/").takeIf { it >= 0 }?.let { add(File(path.substring(it))) }
            val segments = path.trim('/').split('/')
            for (i in 1 until segments.size) {
                add(File(Environment.getExternalStorageDirectory(), segments.drop(i).joinToString("/")))
            }
        }
        return candidates.firstOrNull { f ->
            f.isFile && f.canRead() && (name == null || f.name == name) && (size == null || f.length() == size)
        }
    }

    private fun nameAndSize(ctx: Context, uri: Uri): Pair<String?, Long?> = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            n to s
        }
    }.getOrNull() ?: (uri.lastPathSegment?.substringAfterLast('/') to null)

    fun isCopy(ctx: Context, file: File): Boolean = file.parentFile == File(ctx.cacheDir, "opened")

    private fun copy(ctx: Context, uri: Uri, name: String): File? = runCatching {
        val dir = File(ctx.cacheDir, "opened").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, name.replace('/', '_'))
        ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
        out
    }.getOrNull()

    fun withExtension(name: String, mime: String?): String {
        if (Formats.isReadable(name)) return name
        val ext = when (mime?.lowercase()) {
            "application/pdf" -> "pdf"
            "application/epub+zip" -> "epub"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/msword" -> "doc"
            "text/html" -> "html"
            "application/xhtml+xml" -> "xhtml"
            "text/plain" -> "txt"
            "application/vnd.ms-xpsdocument" -> "xps"
            "application/oxps" -> "oxps"
            "application/x-cbz", "application/vnd.comicbook+zip" -> "cbz"
            "application/x-cbr", "application/vnd.comicbook-rar" -> "cbr"
            "application/x-mobipocket-ebook" -> "mobi"
            "application/x-fictionbook+xml" -> "fb2"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow" -> "ppsx"
            else -> return name
        }
        return "$name.$ext"
    }
}
