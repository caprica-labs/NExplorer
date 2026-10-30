package nexplorer.textpdf

import java.io.File
import java.nio.ByteBuffer

object TextFiles {
    fun read(file: File): String = decode(file.readBytes())

    fun decode(bytes: ByteArray): String {
        val text = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    fun isUtf8(bytes: ByteArray): Boolean {
        if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())) return true
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, start, bytes.size - start)) }.isSuccess
    }

    data class Layout(val charset: java.nio.charset.Charset = Charsets.UTF_8, val bom: Boolean = false, val crlf: Boolean = false)

    fun layoutOf(bytes: ByteArray): Layout {
        val (charset, bom) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        val crlf = String(bytes, bom, minOf(bytes.size - bom, 64 * 1024), charset).contains("\r\n")
        return Layout(charset, bom > 0, crlf)
    }

    fun encode(text: String, layout: Layout): ByteArray {
        val body = (if (layout.crlf) text.replace("\n", "\r\n") else text).toByteArray(layout.charset)
        val bom = when {
            !layout.bom -> ByteArray(0)
            layout.charset == Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            layout.charset == Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        }
        return bom + body
    }
}
