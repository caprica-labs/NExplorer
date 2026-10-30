package nexplorer.doc

import com.artifex.mupdf.fitz.Document
import java.io.File

enum class Restriction(val label: String, val bits: Int) {
    Printing("Printing", 4 or 2048),
    Copying("Copying text and images", 16),
    Editing("Editing", 8 or 1024),
    Comments("Comments and forms", 32 or 256),
}

enum class LockState { None, OpenPassword, RestrictionsOnly }

object PdfLock {
    private const val ALL = 4 or 8 or 16 or 32 or 256 or 512 or 1024 or 2048

    private const val RESERVED = 0xFFFFF0C0.toInt()

    fun permissions(blocked: Set<Restriction>): Int =
        RESERVED or (ALL and blocked.fold(0) { acc, r -> acc or r.bits }.inv())

    fun usable(password: String) = !password.contains(',')

    fun options(open: String, owner: String, blocked: Set<Restriction>): String {
        require(usable(open) && usable(owner))
        val ownerPw = if (blocked.isEmpty()) open else owner
        return "garbage,compress,encrypt=aes-256,user-password=$open,owner-password=$ownerPw,permissions=${permissions(blocked)}"
    }

    fun open(path: String, password: String?): Document {
        val doc = Document.openDocument(path)
        if (doc.needsPassword()) {
            if (password == null || !doc.authenticatePassword(password)) {
                doc.destroy()
                throw PasswordNeeded(wrong = password != null)
            }
        }
        return doc
    }

    fun state(file: File): LockState = runCatching {
        val doc = Document.openDocument(file.path)
        try {
            when {
                doc.needsPassword() -> LockState.OpenPassword
                doc.asPDF()?.trailer?.get("Encrypt")?.isNull == false -> LockState.RestrictionsOnly
                else -> LockState.None
            }
        } finally {
            doc.destroy()
        }
    }.getOrDefault(LockState.None)

    fun lock(src: File, srcPassword: String?, out: File, open: String, owner: String, blocked: Set<Restriction>) {
        val doc = open(src.path, srcPassword)
        try {
            val pdf = doc.asPDF() ?: error("Not a PDF")
            out.parentFile?.mkdirs()
            pdf.save(out.path, options(open, owner, blocked))
        } finally {
            doc.destroy()
        }
    }

    fun unlock(src: File, password: String?, out: File) {
        val doc = open(src.path, password)
        try {
            val pdf = doc.asPDF() ?: error("Not a PDF")
            out.parentFile?.mkdirs()
            pdf.save(out.path, "decrypt,garbage,compress")
        } finally {
            doc.destroy()
        }
    }
}

class PasswordNeeded(val wrong: Boolean) : Exception(if (wrong) "Wrong password" else "This PDF is password protected")
