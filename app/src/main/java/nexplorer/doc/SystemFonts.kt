package nexplorer.doc

import android.util.Log
import com.artifex.mupdf.fitz.Font
import java.io.File

object SystemFonts : Font.FontLoader {
    private val dir = File("/system/fonts")
    private val available: Set<String> by lazy { dir.list()?.toSet().orEmpty() }
    private val loaded = HashMap<String, Font?>()

    fun install() = Font.installFontLoader(this)

    override fun loadFont(name: String?, script: String?, bold: Boolean, italic: Boolean): Font? {
        if (script == null || script == "undefined") return null
        val (file, index) = FontFiles.pick(script, bold, available) ?: return null
        return synchronized(loaded) {
            loaded.getOrPut("$file#$index") {
                runCatching { Font(File(dir, file).path, index) }
                    .onFailure { Log.w("NExplorer", "Couldn't load $file", it) }
                    .getOrNull()
            }
        }
    }
}

object FontFiles {
    private val CJK = mapOf(
        "JP" to 0, "KR" to 1, "SC" to 2, "TC" to 3,
        "Han" to 2, "Bopomofo" to 3, "Hiragana" to 0, "Katakana" to 0, "Hangul" to 1,
    )

    private val NAMES = mapOf("Arabic" to listOf("NotoNaskhArabic"), "Syriac" to listOf("NotoSansSyriacEstrangela"))

    private val SYMBOLS = listOf("NotoSansSymbols-Regular-Subsetted.ttf", "NotoSansSymbols-Regular-Subsetted2.ttf")
    private val SYMBOL_SCRIPTS = setOf("Latin", "Greek", "Cyrillic", "Common", "Inherited")

    fun pick(script: String, bold: Boolean, available: Set<String>): Pair<String, Int>? {
        CJK[script]?.let { i -> return "NotoSansCJK-Regular.ttc".takeIf { it in available }?.let { it to i } }
        if (script in SYMBOL_SCRIPTS) return SYMBOLS.firstOrNull { it in available }?.let { it to 0 }
        val s = script.replace("_", "").replace(" ", "")
        for (family in NAMES[script] ?: listOf("NotoSans$s", "NotoSerif$s")) {
            val files = available.filter { it.startsWith("$family-") }.sorted()
            if (files.isEmpty()) continue
            val pick = files.firstOrNull { bold && "-Bold." in it }
                ?: files.firstOrNull { "-Regular." in it }
                ?: files.firstOrNull { "-VF." in it }
                ?: files.first()
            return pick to 0
        }
        return null
    }
}
