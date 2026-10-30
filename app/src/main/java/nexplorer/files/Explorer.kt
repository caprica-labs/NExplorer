package nexplorer.files

import java.io.File

enum class FileType { Folder, Document, Image, Text, Archive, Other }

object FileTypes {
    private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

    private val TEXT = setOf(
        "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yml", "yaml", "ini", "cfg", "conf",
        "properties", "toml", "srt", "vtt", "sh", "bat", "py", "kt", "kts", "java", "js", "ts", "css",
        "c", "h", "cpp", "gradle", "sql", "nfo",
    )

    private val ARCHIVES = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "cbz", "cbr", "cb7")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isImage(name: String) = ext(name) in IMAGES
    fun isText(name: String) = ext(name) in TEXT
    fun isArchive(name: String) = ext(name) in ARCHIVES

    private val MARKUP = setOf("html", "htm", "xhtml", "fb2")

    fun isWebPage(name: String) = ext(name) in setOf("html", "htm", "xhtml")

    fun isNotepadOrReader(name: String) = Formats.isReadable(name) && (isText(name) || ext(name) in MARKUP)

    fun of(name: String, isDir: Boolean): FileType = when {
        isDir -> FileType.Folder
        isImage(name) -> FileType.Image
        Formats.isReadable(name) && ext(name) != "txt" -> FileType.Document
        isText(name) -> FileType.Text
        isArchive(name) -> FileType.Archive
        else -> FileType.Other
    }
}

enum class SortBy(val label: String) { Name("Name"), Date("Date modified"), Size("Size"), Type("Type") }

data class ExplorerSort(val by: SortBy = SortBy.Name, val descending: Boolean = false) {
    fun encode() = "${by.name}:$descending"

    companion object {
        fun decode(s: String?): ExplorerSort {
            val parts = s?.split(':') ?: return ExplorerSort()
            val by = SortBy.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return ExplorerSort()
            return ExplorerSort(by, parts.getOrNull(1) == "true")
        }
    }
}

object Explorer {
    fun list(dir: File, sort: ExplorerSort): List<Entry> {
        val all = dir.listFiles().orEmpty().filterNot { FolderScanner.isHidden(it) || it.name == RecycleBin.FOLDER }.map { f ->
            val isDir = f.isDirectory
            Entry(f, isDir, if (isDir) f.list()?.size ?: 0 else 0, if (isDir) 0L else f.length(), f.lastModified())
        }
        return sorted(all, sort)
    }

    fun sorted(items: List<Entry>, sort: ExplorerSort): List<Entry> {
        val byName = compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.file.name }
        val order: Comparator<Entry> = when (sort.by) {
            SortBy.Name -> byName
            SortBy.Date -> compareBy<Entry> { it.modified }.then(byName)
            SortBy.Size -> compareBy<Entry> { if (it.isDir) it.readableCount.toLong() else it.size }.then(byName)
            SortBy.Type -> compareBy<Entry> { it.file.extension.lowercase() }.then(byName)
        }
        val reverse = if (sort.by == SortBy.Date || sort.by == SortBy.Size) !sort.descending else sort.descending
        val o = if (reverse) order.reversed() else order
        val (folders, files) = items.partition { it.isDir }
        return folders.sortedWith(o) + files.sortedWith(o)
    }

    fun newFile(dir: File, name: String): File {
        val n = name.trim()
        require(n.isNotEmpty() && '/' !in n && n != "." && n != "..") { "Invalid name" }
        val f = File(dir, n)
        check(!f.exists()) { "Name already used" }
        check(f.createNewFile()) { "Couldn't create the file" }
        return f
    }

    fun newFolder(dir: File, name: String): File {
        val n = name.trim()
        require(n.isNotEmpty() && '/' !in n && n != "." && n != "..") { "Invalid name" }
        val f = File(dir, n)
        check(!f.exists()) { "Name already used" }
        check(f.mkdir()) { "Couldn't create the folder" }
        return f
    }
}
