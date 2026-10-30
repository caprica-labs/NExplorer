package nexplorer.reader

object TextMeasure {
    fun mixed(table: (Char) -> Float?, fallback: (String) -> Float): (String) -> Float = { s ->
        var total = 0f
        var run = StringBuilder()
        for (c in s) {
            val w = table(c)
            if (w != null) {
                if (run.isNotEmpty()) { total += fallback(run.toString()); run = StringBuilder() }
                total += w
            } else {
                run.append(c)
            }
        }
        if (run.isNotEmpty()) total += fallback(run.toString())
        total
    }
}
