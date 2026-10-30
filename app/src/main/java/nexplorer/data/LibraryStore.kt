package nexplorer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.libraryStore by preferencesDataStore(name = "library")

class LibraryStore(context: Context) {
    private val store = context.applicationContext.libraryStore
    private val hiddenKey = stringPreferencesKey("hidden_paths")

    val hidden: Flow<Set<String>> = store.data.map { decode(it[hiddenKey]).toSet() }

    suspend fun hide(path: String) {
        store.edit { p ->
            val cur = decode(p[hiddenKey])
            if (path !in cur) p[hiddenKey] = encode(cur + path)
        }
    }

    suspend fun unhideAll() {
        store.edit { it.remove(hiddenKey) }
    }

    private val openedKey = stringPreferencesKey("opened_paths")

    val opened: Flow<Set<String>> = store.data.map { decode(it[openedKey]).toSet() }

    suspend fun setOpened(paths: Collection<String>, opened: Boolean) {
        store.edit { p ->
            val cur = decode(p[openedKey]).toSet()
            val next = if (opened) cur + paths else cur - paths.toSet()
            if (next != cur) p[openedKey] = encode(next.toList())
        }
    }

    suspend fun openedMoved(old: String, new: String) {
        store.edit { p -> p[openedKey] = encode(OpenedPaths.moved(decode(p[openedKey]).toSet(), old, new).toList()) }
    }

    suspend fun openedRemoved(path: String) {
        store.edit { p -> p[openedKey] = encode(OpenedPaths.removed(decode(p[openedKey]).toSet(), path).toList()) }
    }

    private fun decode(s: String?) = s?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
    private fun encode(list: List<String>) = list.joinToString("\n")
}

object OpenedPaths {
    private fun under(path: String, dir: String) = path == dir || path.startsWith("$dir/")

    fun moved(set: Set<String>, old: String, new: String): Set<String> =
        set.mapTo(LinkedHashSet()) { if (under(it, old)) new + it.removePrefix(old) else it }

    fun removed(set: Set<String>, path: String): Set<String> = set.filterNotTo(LinkedHashSet()) { under(it, path) }
}
