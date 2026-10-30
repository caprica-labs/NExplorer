package nexplorer.doc

object PositionMapper {
    fun toFraction(page: Int, pageCount: Int): Float =
        if (pageCount <= 1) 0f else (page.toFloat() / (pageCount - 1)).coerceIn(0f, 1f)

    fun toPage(fraction: Float, pageCount: Int): Int =
        if (pageCount <= 1) 0 else Math.round(fraction.coerceIn(0f, 1f) * (pageCount - 1))
}
