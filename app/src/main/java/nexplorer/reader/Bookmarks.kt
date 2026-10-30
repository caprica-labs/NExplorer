package nexplorer.reader

import nexplorer.doc.PositionMapper

data class Bookmark(val fraction: Float, val name: String)

data class TocEntry(val title: String, val level: Int, val page: Int)

object Bookmarks {
    fun encode(list: List<Bookmark>): String =
        list.joinToString("\n") { "${it.fraction}\t${it.name.replace(Regex("[\\t\\r\\n]+"), " ")}" }

    fun decode(s: String?): List<Bookmark> = s.orEmpty().lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab < 0) return@mapNotNull null
        val f = line.substring(0, tab).toFloatOrNull() ?: return@mapNotNull null
        Bookmark(f.coerceIn(0f, 1f), line.substring(tab + 1))
    }.toList()

    fun pageOf(b: Bookmark, pageCount: Int): Int = PositionMapper.toPage(b.fraction, pageCount)

    fun isMarked(list: List<Bookmark>, page: Int, pageCount: Int): Boolean = list.any { pageOf(it, pageCount) == page }

    fun toggle(list: List<Bookmark>, page: Int, pageCount: Int): List<Bookmark> =
        if (isMarked(list, page, pageCount)) {
            list.filterNot { pageOf(it, pageCount) == page }
        } else {
            (list + Bookmark(PositionMapper.toFraction(page, pageCount), "Page ${page + 1}")).sortedBy { it.fraction }
        }

    fun currentEntry(toc: List<TocEntry>, page: Int): Int = toc.indexOfLast { it.page in 0..page }

    fun remap(toc: List<TocEntry>, fromCount: Int, toCount: Int): List<TocEntry> = toc.map {
        if (it.page < 0) it else it.copy(page = PositionMapper.toPage(PositionMapper.toFraction(it.page, fromCount), toCount))
    }
}
