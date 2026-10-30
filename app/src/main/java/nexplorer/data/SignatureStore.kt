package nexplorer.data

import android.content.Context
import nexplorer.reader.Signature
import nexplorer.reader.Signatures
import java.io.File

class SignatureStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "signatures")

    fun list(): List<Signature> = dir.listFiles { f -> f.name.endsWith(".sig") }.orEmpty()
        .sortedBy { it.name }
        .mapNotNull { f -> runCatching { Signatures.decode(f.nameWithoutExtension, f.readText()) }.getOrNull() }

    fun save(s: Signature) {
        dir.mkdirs()
        val tmp = File(dir, "${s.id}.tmp")
        tmp.writeText(Signatures.encode(s))
        if (!tmp.renameTo(File(dir, "${s.id}.sig"))) {
            tmp.copyTo(File(dir, "${s.id}.sig"), overwrite = true)
            tmp.delete()
        }
    }

    fun delete(id: String) {
        File(dir, "$id.sig").delete()
    }

    companion object {
        fun newId(): String = System.currentTimeMillis().toString().padStart(15, '0')
    }
}
