package nexplorer.files

enum class FormatKind { FIXED, REFLOWABLE, DOCX }

object Formats {
    private val fixed = setOf("pdf", "xps", "oxps", "cbz", "cbr", "pptx", "ppsx")
    private val reflowable = setOf("epub", "fb2", "mobi", "html", "htm", "xhtml", "txt")

    val extensions: Set<String> = fixed + reflowable + "docx" + "doc"

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isReadable(name: String): Boolean = ext(name) in extensions

    fun kindOf(name: String): FormatKind? = when (ext(name)) {
        in fixed -> FormatKind.FIXED
        in reflowable -> FormatKind.REFLOWABLE
        "docx", "doc" -> FormatKind.DOCX
        else -> null
    }
}
