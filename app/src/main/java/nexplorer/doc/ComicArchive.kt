package nexplorer.doc

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ComicArchive {
    private val PICTURES = setOf("jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "jp2", "jpx", "jxr", "webp")
    private const val KEEP = 3

    fun isPicture(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in PICTURES

    fun cacheName(file: File): String {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return "${hash.take(20)}.cbz"
    }

    fun asCbz(file: File, cacheDir: File): File {
        val dir = File(cacheDir, "comics").apply { mkdirs() }
        val out = File(dir, cacheName(file))
        if (out.isFile) {
            out.setLastModified(System.currentTimeMillis())
            return out
        }
        val tmp = File(dir, out.name + ".tmp")
        try {
            val pictures = copyPictures(file, tmp)
            check(pictures > 0) { "No pictures in this comic" }
            check(tmp.renameTo(out)) { "Couldn't keep the comic" }
        } finally {
            tmp.delete()
        }
        dir.listFiles { f -> f.name.endsWith(".cbz") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { it.delete() }
        return out
    }

    private fun copyPictures(src: File, dst: File): Int {
        val archive = Archive.readNew()
        var count = 0
        try {
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenFileName(archive, src.path.toByteArray(), 64 * 1024)
            val buffer = ByteBuffer.allocateDirect(64 * 1024)
            val chunk = ByteArray(buffer.capacity())
            ZipOutputStream(dst.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                val names = HashSet<String>()
                while (true) {
                    val entry = Archive.readNextHeader(archive)
                    if (entry == 0L) break
                    val name = entryName(entry)
                    if (ArchiveEntry.filetype(entry) != ArchiveEntry.AE_IFREG || !isPicture(name) || !names.add(name)) {
                        Archive.readDataSkip(archive)
                        continue
                    }
                    zip.putNextEntry(ZipEntry(name))
                    while (true) {
                        buffer.clear()
                        Archive.readData(archive, buffer)
                        buffer.flip()
                        val n = buffer.remaining()
                        if (n == 0) break
                        buffer.get(chunk, 0, n)
                        zip.write(chunk, 0, n)
                    }
                    zip.closeEntry()
                    count++
                }
            }
        } finally {
            runCatching { Archive.readFree(archive) }
        }
        return count
    }

    private fun entryName(entry: Long): String =
        runCatching { ArchiveEntry.pathnameUtf8(entry) }.getOrNull()
            ?: ArchiveEntry.pathname(entry)?.toString(Charsets.ISO_8859_1)
            ?: "page"
}
