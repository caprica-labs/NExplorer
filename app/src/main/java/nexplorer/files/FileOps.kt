package nexplorer.files

import java.io.File

fun interface Progress {
    fun add(n: Long)
}

object FileOps {
    fun deleteFromDevice(target: File, progress: Progress? = null): Boolean {
        if (!target.exists()) return true
        if (progress == null) {
            target.deleteRecursively()
        } else {
            target.walkBottomUp().forEach { f ->
                val counted = f.isFile
                f.delete()
                if (counted) progress.add(1)
            }
        }
        return !target.exists()
    }

    fun countAll(target: File): Int =
        if (target.isFile) 1 else target.walkTopDown().count { it.isFile }

    fun sizeOf(target: File): Long =
        if (target.isFile) target.length() else target.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    enum class OnConflict { Replace, KeepBoth }

    fun rename(target: File, newName: String): File {
        val name = newName.trim()
        require(name.isNotEmpty() && '/' !in name && name != "." && name != "..") { "Invalid name" }
        val dest = File(target.parentFile, name)
        if (dest == target) return target
        check(!dest.exists()) { "Name already used" }
        check(target.renameTo(dest)) { "Couldn't rename" }
        return dest
    }

    fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 && !File(dir, name).isDirectory }
        val base = if (dot != null) name.substring(0, dot) else name
        val ext = if (dot != null) name.substring(dot) else ""
        var n = 2
        while (File(dir, "$base ($n)$ext").exists()) n++
        return "$base ($n)$ext"
    }

    fun wouldClash(src: File, destDir: File): Boolean {
        val dest = File(destDir, src.name)
        return dest.exists() && dest.canonicalPath != src.canonicalPath
    }

    fun isInside(dir: File, candidate: File): Boolean {
        val d = dir.canonicalPath
        val c = candidate.canonicalPath
        return c == d || c.startsWith(d + File.separator)
    }

    fun copyTo(src: File, destDir: File, onConflict: OnConflict, progress: Progress? = null): File {
        require(!(src.isDirectory && isInside(src, destDir))) { "Can't copy a folder into itself" }
        val dest = target(src, destDir, onConflict)
        if (dest.canonicalPath == src.canonicalPath) return src
        placeAt(dest, src) { copyTree(src, it, progress) }
        return dest
    }

    fun moveTo(src: File, destDir: File, onConflict: OnConflict, progress: Progress? = null): File {
        require(!(src.isDirectory && isInside(src, destDir))) { "Can't move a folder into itself" }
        if (File(destDir, src.name).canonicalPath == src.canonicalPath) return src
        val dest = target(src, destDir, onConflict)
        moveAs(src, dest, progress)
        return dest
    }

    fun moveAs(src: File, dest: File, progress: Progress? = null) {
        val size = if (progress != null) sizeOf(src) else 0L
        var copied = false
        placeAt(dest, src) {
            if (src.renameTo(it)) {
                progress?.add(size)
            } else {
                copyTree(src, it, progress)
                copied = true
            }
        }
        if (copied) src.deleteRecursively()
    }

    private fun copyTree(src: File, dest: File, progress: Progress?) {
        if (src.isDirectory) {
            check(dest.mkdirs()) { "Couldn't create ${dest.name}" }
            val inside = src.listFiles() ?: error("Couldn't read ${src.name}")
            inside.forEach { copyTree(it, File(dest, it.name), progress) }
            return
        }
        check(!dest.exists()) { "${dest.name} already exists" }
        src.inputStream().use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    progress?.add(n.toLong())
                }
            }
        }
    }

    private fun placeAt(dest: File, src: File, put: (File) -> Unit) {
        require(!(dest.exists() && isInside(dest, src))) { "Can't replace a folder with something inside it" }
        val old = if (dest.exists()) File(dest.parentFile, ".${dest.name}.nx-old-${System.nanoTime()}") else null
        if (old != null) check(dest.renameTo(old)) { "Couldn't replace ${dest.name}" }
        try {
            put(dest)
        } catch (e: Throwable) {
            dest.deleteRecursively()
            if (old != null) old.renameTo(dest)
            throw e
        }
        old?.deleteRecursively()
    }

    private fun target(src: File, destDir: File, onConflict: OnConflict): File = when (onConflict) {
        OnConflict.Replace -> File(destDir, src.name)
        OnConflict.KeepBoth -> File(destDir, uniqueName(destDir, src.name))
    }
}
