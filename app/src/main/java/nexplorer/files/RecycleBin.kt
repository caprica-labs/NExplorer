package nexplorer.files

import java.io.File

object RecycleBin {
    const val DAYS = 30
    const val FOLDER = ".NExplorer-bin"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    class Item(val holder: File, val file: File, val original: File, val deletedAt: Long)

    private fun binOf(root: File) = File(root, FOLDER)
    private fun noteOf(holder: File) = File(holder.path + ".info")

    private fun rootOf(f: File, roots: List<File>): File =
        roots.firstOrNull { r -> f.path == r.path || f.path.startsWith(r.path + "/") } ?: roots.first()

    fun put(target: File, roots: List<File>, progress: Progress? = null, now: Long = System.currentTimeMillis()) {
        check(target.exists()) { "${target.name} no longer exists" }
        val bin = binOf(rootOf(target, roots))
        val holder = File(bin, "$now-${System.nanoTime()}")
        check(holder.mkdirs()) { "Couldn't use the recycle bin" }
        runCatching { File(bin, ".nomedia").createNewFile() }
        val note = noteOf(holder)
        try {
            note.writeText("$now\n${target.path}")
            FileOps.moveAs(target, File(holder, target.name), progress)
        } catch (t: Throwable) {
            holder.deleteRecursively()
            note.delete()
            throw t
        }
    }

    fun items(roots: List<File>): List<Item> = roots.flatMap { root ->
        binOf(root).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { holder ->
            val note = runCatching { noteOf(holder).readLines() }.getOrNull() ?: return@mapNotNull null
            val file = holder.listFiles()?.singleOrNull() ?: return@mapNotNull null
            val original = note.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Item(holder, file, File(original), note[0].toLongOrNull() ?: 0L)
        }
    }.sortedByDescending { it.deletedAt }

    fun restore(item: Item, progress: Progress? = null): File {
        val dir = item.original.parentFile ?: error("No folder to put it back in")
        if (!dir.isDirectory) check(dir.mkdirs()) { "Couldn't recreate ${dir.name}" }
        val dest = File(dir, FileOps.uniqueName(dir, item.original.name))
        FileOps.moveAs(item.file, dest, progress)
        item.holder.deleteRecursively()
        noteOf(item.holder).delete()
        return dest
    }

    fun delete(item: Item, progress: Progress? = null): Boolean {
        val ok = FileOps.deleteFromDevice(item.holder, progress)
        if (ok) noteOf(item.holder).delete()
        return ok
    }

    fun count(item: Item): Int = FileOps.countAll(item.file)

    fun purge(roots: List<File>, now: Long = System.currentTimeMillis()) {
        val kept = items(roots)
        kept.filter { now - it.deletedAt > DAYS * DAY_MS }.forEach { delete(it) }
        val known = kept.map { it.holder.path }.toSet()
        roots.forEach { root ->
            binOf(root).listFiles().orEmpty().forEach { f ->
                val holder = if (f.name.endsWith(".info")) File(f.path.removeSuffix(".info")) else f
                val stray = f.name != ".nomedia" && holder.path !in known
                if (stray && now - f.lastModified() > DAY_MS) f.deleteRecursively()
            }
        }
    }
}
