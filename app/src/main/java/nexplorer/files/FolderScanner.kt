package nexplorer.files

import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class Entry(val file: File, val isDir: Boolean, val readableCount: Int, val size: Long = 0, val modified: Long = 0)

class FileKind(val key: String, val accepts: (String) -> Boolean) {
    companion object {
        /** Everything the reader opens. */
        val READABLE = FileKind("readable", Formats::isReadable)
        val TXT = FileKind("txt") { it.endsWith(".txt", ignoreCase = true) }
        val PDF = FileKind("pdf") { it.endsWith(".pdf", ignoreCase = true) }
        val DOCX = FileKind("word") { it.endsWith(".docx", ignoreCase = true) || it.endsWith(".doc", ignoreCase = true) }
        /** Every file (Network Share). */
        val ANY = FileKind("any") { true }
    }
}

object FolderScanner {
    fun shouldSkip(dir: File): Boolean {
        if (dir.name.startsWith(".")) return true
        val p = dir.path.replace('\\', '/')
        return p.endsWith("/Android/data") || p.endsWith("/Android/obb")
    }

    @Volatile
    var hidden: Set<String> = emptySet()
        set(value) {
            field = value.map(::norm).toSet()
            invalidate()
        }

    private fun norm(p: String) = p.replace('\\', '/').trimEnd('/')

    fun isHidden(f: File): Boolean {
        val h = hidden
        if (h.isEmpty()) return false
        val p = norm(f.path)
        return h.any { p == it || p.startsWith("$it/") }
    }

    private val counts = ConcurrentHashMap<String, Int>()

    fun invalidate() = counts.clear()

    fun countReadable(dir: File, kind: FileKind = FileKind.READABLE): Int =
        counts.getOrPut("${kind.key}|${dir.path}") { walkCount(dir, kind) }

    private fun walkCount(dir: File, kind: FileKind): Int {
        val list = dir.listFiles() ?: return 0
        var n = 0
        for (f in list) {
            if (isHidden(f)) continue
            if (f.isDirectory) {
                if (!shouldSkip(f)) n += countReadable(f, kind)
            } else if (kind.accepts(f.name)) {
                n++
            }
        }
        return n
    }

    fun children(dir: File, kind: FileKind = FileKind.READABLE): List<Entry> {
        val list = dir.listFiles()?.filterNot(::isHidden) ?: return emptyList()
        val folders = list.filter { it.isDirectory && !shouldSkip(it) }
            .map { Entry(it, true, countReadable(it, kind)) }
            .filter { it.readableCount > 0 }
            .sortedBy { it.file.name.lowercase() }
        val files = list.filter { it.isFile && kind.accepts(it.name) }
            .map { Entry(it, false, 0) }
            .sortedBy { it.file.name.lowercase() }
        return folders + files
    }
}
