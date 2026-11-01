package nexplorer.download

import java.net.URI
import java.net.URLDecoder
import java.util.Calendar

object DownloadNames {
    const val FALLBACK = "download"
    private const val MAX = 150

    fun pick(disposition: String?, url: String, ext: String?): String {
        val name = clean(fromDisposition(disposition) ?: fromUrl(url) ?: FALLBACK)
        val hasExt = name.lastIndexOf('.') > 0
        return if (hasExt || ext.isNullOrEmpty()) name else limit("$name.$ext")
    }

    fun fromDisposition(header: String?): String? {
        if (header == null) return null
        val star = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)
        if (star != null) {
            val charset = star.groupValues[1].ifBlank { "UTF-8" }
            runCatching { URLDecoder.decode(star.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val plain = Regex("""filename\s*=\s*("((?:[^"\\]|\\.)*)"|[^;]+)""", RegexOption.IGNORE_CASE).find(header) ?: return null
        val quoted = plain.groupValues[2]
        val value = if (plain.groupValues[1].startsWith('"')) quoted.replace(Regex("""\\(.)"""), "$1") else plain.groupValues[1].trim()
        return value.takeIf { it.isNotBlank() }
    }

    fun fromUrl(url: String): String? {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
        val last = path.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return null
        return runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last).takeIf { it.isNotBlank() }
    }

    fun clean(raw: String): String {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        val safe = leaf.map { c -> if (c < ' ' || c == '\u007f' || c in "\\/:*?\"<>|") '_' else c }.joinToString("")
        val trimmed = safe.trim().trim('.').trim()
        return limit(trimmed.ifEmpty { FALLBACK })
    }

    private fun limit(name: String): String {
        if (name.length <= MAX) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 12 }
        return if (dot == null) name.take(MAX) else name.take(MAX - (name.length - dot)) + name.substring(dot)
    }

    fun rangeTotal(header: String?): Long? = header?.substringAfterLast('/')?.trim()?.toLongOrNull()

    fun normalize(link: String): String? {
        val t = link.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in t) t else "https://$t"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host?.contains('.') != true) return null
        return withScheme
    }

    fun secure(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    fun linkIn(text: String?): String? =
        text?.let { Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(it)?.value?.trimEnd('.', ',', ')', ']', '>', '"', '\'') }
}

object DownloadPlan {
    fun segments(total: Long, threads: Int, minSize: Long): List<LongRange> {
        if (total <= 0) return emptyList()
        val n = threads.coerceAtLeast(1).toLong().coerceAtMost((total / minSize.coerceAtLeast(1)).coerceAtLeast(1))
        val size = total / n
        return (0 until n).map { i ->
            val start = i * size
            val end = if (i == n - 1) total - 1 else start + size - 1
            start..end
        }
    }

    fun nextAt(now: Long, minuteOfDay: Int, cal: Calendar = Calendar.getInstance()): Long {
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        cal.set(Calendar.MINUTE, minuteOfDay % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun clock(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}
