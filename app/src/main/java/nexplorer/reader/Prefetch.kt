package nexplorer.reader

object Prefetch {
    const val RADIUS = 10

    /** Pages each side for PDFs. */
    const val PDF_RADIUS = 5

    fun order(current: Int, count: Int, radius: Int = RADIUS): List<Int> {
        val out = ArrayList<Int>(radius * 2 + 1)
        if (current in 0 until count) out.add(current)
        for (d in 1..radius) {
            if (current + d < count) out.add(current + d)
            if (current - d >= 0) out.add(current - d)
        }
        return out
    }

    fun pagesThatFit(cacheBytes: Long, bytesPerPage: Long, radius: Int = RADIUS): Int =
        (cacheBytes * 3 / 4 / bytesPerPage.coerceAtLeast(1)).toInt().coerceIn(1, radius * 2 + 1)
}
