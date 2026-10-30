package nexplorer.files

import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Picking {
    fun toggle(selected: Set<String>, path: String): Set<String> =
        if (path in selected) selected - path else selected + path
}

object Share {
    private val types = mapOf(
        "pdf" to "application/pdf",
        "epub" to "application/epub+zip",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "doc" to "application/msword",
        "html" to "text/html",
        "htm" to "text/html",
        "xhtml" to "application/xhtml+xml",
        "txt" to "text/plain",
        "xps" to "application/oxps",
        "oxps" to "application/oxps",
        "cbz" to "application/vnd.comicbook+zip",
        "cbr" to "application/vnd.comicbook-rar",
        "fb2" to "application/x-fictionbook+xml",
        "mobi" to "application/x-mobipocket-ebook",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "zip" to "application/zip",
    )

    fun mimeFor(name: String): String =
        types[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    fun zip(files: List<File>, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun commonType(mimes: List<String>): String {
        if (mimes.isEmpty()) return "*/*"
        if (mimes.all { it == mimes[0] }) return mimes[0]
        val family = mimes[0].substringBefore('/')
        return if (mimes.all { it.substringBefore('/') == family }) "$family/*" else "*/*"
    }
}
