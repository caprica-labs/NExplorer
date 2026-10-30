package nexplorer.files

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Archives {
    fun zip(items: List<File>, out: File, progress: Progress? = null, onFile: (Int) -> Unit = {}) {
        var done = 0
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun add(f: File, name: String) {
                if (f.isDirectory) {
                    zip.putNextEntry(ZipEntry("$name/"))
                    zip.closeEntry()
                    f.listFiles().orEmpty().sortedBy { it.name }.forEach { add(it, "$name/${it.name}") }
                } else {
                    zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                    f.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            zip.write(buffer, 0, n)
                            progress?.add(n.toLong())
                        }
                    }
                    zip.closeEntry()
                    onFile(++done)
                }
            }
            items.filter { it.canonicalPath != out.canonicalPath }.forEach { add(it, it.name) }
        }
    }

    fun safePath(name: String): String? {
        val parts = name.replace('\\', '/').split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." && !it.endsWith(":") }
        return parts.joinToString("/").ifEmpty { null }
    }

    fun extract(archive: File, into: File? = null, progress: Progress? = null, onFile: (Int) -> Unit = {}): File {
        val parent = into ?: archive.parentFile ?: error("No folder to extract into")
        val base = archive.name.substringBeforeLast('.').removeSuffix(".tar").ifEmpty { archive.name }
        val dest = File(parent, FileOps.uniqueName(parent, base))
        check(dest.mkdirs()) { "Couldn't create the folder" }
        try {
            extractInto(archive, dest, onFile, progress = progress)
        } catch (t: Throwable) {
            dest.deleteRecursively()
            throw t
        }
        return dest
    }

    data class Item(val path: String, val isDir: Boolean, val size: Long) {
        val name: String get() = path.substringAfterLast('/')
    }

    fun entries(archive: File): List<Item> {
        val a = Archive.readNew()
        try {
            Archive.readSupportFilterAll(a)
            Archive.readSupportFormatAll(a)
            Archive.readOpenFileName(a, archive.path.toByteArray(), 64 * 1024)
            val out = ArrayList<Item>()
            while (true) {
                val entry = Archive.readNextHeader(a)
                if (entry == 0L) break
                val path = entryPath(entry)?.let(::safePath)
                val type = ArchiveEntry.filetype(entry)
                if (path != null && (type == ArchiveEntry.AE_IFREG || type == ArchiveEntry.AE_IFDIR)) {
                    out += Item(path, type == ArchiveEntry.AE_IFDIR, if (type == ArchiveEntry.AE_IFREG) ArchiveEntry.size(entry) else 0L)
                }
                Archive.readDataSkip(a)
            }
            return out
        } finally {
            runCatching { Archive.readFree(a) }
        }
    }

    fun extractOne(archive: File, path: String, dest: File) {
        dest.parentFile?.mkdirs()
        extractInto(archive, dest.parentFile ?: error("No folder"), {}, only = path, renameTo = dest)
        check(dest.isFile) { "Not found in the archive" }
    }

    fun children(items: List<Item>, dir: String): List<Item> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val found = LinkedHashMap<String, Item>()
        for (it in items) {
            if (!it.path.startsWith(prefix) || it.path == dir) continue
            val rest = it.path.removePrefix(prefix)
            val first = rest.substringBefore('/')
            val childPath = prefix + first
            if (rest.contains('/')) found.getOrPut(childPath) { Item(childPath, true, 0) }
            else found[childPath] = it
        }
        val (dirs, files) = found.values.partition { it.isDir }
        val byName = compareBy<Item, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        return dirs.sortedWith(byName) + files.sortedWith(byName)
    }

    private fun entryPath(entry: Long): String? =
        runCatching { ArchiveEntry.pathnameUtf8(entry) }.getOrNull()
            ?: ArchiveEntry.pathname(entry)?.toString(Charsets.ISO_8859_1)

    private fun extractInto(
        src: File,
        dest: File,
        onFile: (Int) -> Unit,
        only: String? = null,
        renameTo: File? = null,
        progress: Progress? = null,
    ) {
        val a = Archive.readNew()
        try {
            Archive.readSupportFilterAll(a)
            Archive.readSupportFormatAll(a)
            Archive.readOpenFileName(a, src.path.toByteArray(), 64 * 1024)
            val buffer = ByteBuffer.allocateDirect(64 * 1024)
            val chunk = ByteArray(buffer.capacity())
            var done = 0
            while (true) {
                val entry = Archive.readNextHeader(a)
                if (entry == 0L) break
                val path = entryPath(entry)?.let(::safePath)
                val type = ArchiveEntry.filetype(entry)
                if (path == null || (type != ArchiveEntry.AE_IFREG && type != ArchiveEntry.AE_IFDIR) || (only != null && path != only)) {
                    Archive.readDataSkip(a)
                    continue
                }
                val out = if (only != null && renameTo != null) renameTo else File(dest, path)
                if (type == ArchiveEntry.AE_IFDIR) {
                    out.mkdirs()
                    Archive.readDataSkip(a)
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().buffered().use { os ->
                    while (true) {
                        buffer.clear()
                        Archive.readData(a, buffer)
                        buffer.flip()
                        val n = buffer.remaining()
                        if (n == 0) break
                        buffer.get(chunk, 0, n)
                        os.write(chunk, 0, n)
                        progress?.add(n.toLong())
                    }
                }
                onFile(++done)
                if (only != null) return
            }
            check(done > 0 || dest.listFiles().orEmpty().isNotEmpty()) { "Nothing to extract" }
        } finally {
            runCatching { Archive.readFree(a) }
        }
    }
}
