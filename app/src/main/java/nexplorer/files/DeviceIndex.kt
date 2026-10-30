package nexplorer.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

object FileSearch {
    fun filter(all: List<File>, query: String, limit: Int = 200): List<File> =
        filter(all, all.map { it.name.lowercase() }, query, limit)

    fun filter(all: List<File>, lower: List<String>, query: String, limit: Int = 200): List<File> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val prefix = ArrayList<Pair<String, File>>()
        val other = ArrayList<Pair<String, File>>()
        for (i in all.indices) {
            val name = lower[i]
            val at = name.indexOf(q)
            if (at < 0 || FolderScanner.isHidden(all[i])) continue
            (if (at == 0) prefix else other) += name to all[i]
        }
        return (prefix.sortedBy { it.first } + other.sortedBy { it.first }).take(limit).map { it.second }
    }
}

class DeviceIndex(private val cacheFile: File) {
    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files
    private var job: Job? = null
    private val lock = Any()

    private val addedMeanwhile = ArrayList<File>()
    private val removedMeanwhile = ArrayList<String>()

    @Volatile private var lower: Pair<List<File>, List<String>>? = null

    fun lowerNames(list: List<File>): List<String> {
        lower?.takeIf { it.first === list }?.let { return it.second }
        return list.map { it.name.lowercase() }.also { lower = list to it }
    }

    fun refresh(scope: CoroutineScope, roots: List<File>) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            if (_files.value.isEmpty() && cacheFile.exists()) {
                val cached = runCatching { cacheFile.readLines().filter { it.isNotEmpty() }.map(::File) }.getOrDefault(emptyList())
                synchronized(lock) { if (_files.value.isEmpty()) _files.value = cached }
            }
            synchronized(lock) {
                addedMeanwhile.clear()
                removedMeanwhile.clear()
            }
            val found = scan(roots) { isActive }
            ensureActive()
            val now = synchronized(lock) {
                var list = found
                if (removedMeanwhile.isNotEmpty()) list = list.filterNot { f -> removedMeanwhile.any { under(f.path, it) } }
                val have = list.mapTo(HashSet()) { it.path }
                list = list + addedMeanwhile.filter { it.path !in have }
                _files.value = list
                list
            }
            runCatching { cacheFile.writeText(now.joinToString("\n") { it.path }) }
        }
    }

    private fun under(path: String, p: String) = path == p || path.startsWith(p + File.separator)

    fun add(file: File) = synchronized(lock) {
        addedMeanwhile += file
        if (_files.value.none { it.path == file.path }) _files.value = _files.value + file
    }

    fun remove(target: File) = synchronized(lock) {
        val p = target.path
        removedMeanwhile += p
        addedMeanwhile.removeAll { under(it.path, p) }
        _files.value = _files.value.filterNot { under(it.path, p) }
    }

    companion object {
        fun scan(roots: List<File>, going: () -> Boolean = { true }): List<File> {
            val out = ArrayList<File>()
            roots.forEach { walk(it, out, going) }
            return out
        }

        private fun walk(dir: File, out: MutableList<File>, going: () -> Boolean) {
            if (!going()) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (FolderScanner.isHidden(f)) continue
                if (f.isDirectory) {
                    if (!FolderScanner.shouldSkip(f)) walk(f, out, going)
                } else {
                    out.add(f)
                }
            }
        }
    }
}
