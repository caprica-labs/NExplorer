package nexplorer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import nexplorer.files.ExplorerSort
import nexplorer.reader.AppTheme
import nexplorer.reader.Bookmark
import nexplorer.reader.Bookmarks
import nexplorer.reader.ReaderTheme
import nexplorer.reader.ViewSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.readerStore by preferencesDataStore(name = "reader")

class ReaderPrefs(context: Context) {
    private val store = context.applicationContext.readerStore
    private val themeKey = stringPreferencesKey("theme")
    private val viewKey = stringPreferencesKey("view")

    private fun positionKey(path: String) = floatPreferencesKey("p_${path.hashCode()}")
    private fun bookmarksKey(path: String) = stringPreferencesKey("b_${path.hashCode()}")

    val theme: Flow<ReaderTheme> = store.data.map { ReaderTheme.fromStored(it[themeKey], appDark = false) }

    suspend fun setTheme(t: ReaderTheme) {
        store.edit { it[themeKey] = t.name }
    }

    suspend fun migrateTheme(appDark: Boolean) {
        store.edit { p ->
            if (p[themeKey] == "FollowApp") p[themeKey] = ReaderTheme.fromStored("FollowApp", appDark).name
        }
    }

    val view: Flow<ViewSettings> = store.data.map { ViewSettings.decode(it[viewKey]) }

    suspend fun setView(s: ViewSettings) {
        store.edit { it[viewKey] = s.encode() }
    }

    private val appThemeKey = stringPreferencesKey("app_theme")

    val appTheme: Flow<AppTheme> = store.data.map { p ->
        p[appThemeKey]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: AppTheme.FollowSystem
    }

    suspend fun setAppTheme(t: AppTheme) {
        store.edit { it[appThemeKey] = t.name }
    }

    private val explorerSortKey = stringPreferencesKey("explorer_sort")

    val explorerSort: Flow<ExplorerSort> = store.data.map { ExplorerSort.decode(it[explorerSortKey]) }

    suspend fun setExplorerSort(s: ExplorerSort) {
        store.edit { it[explorerSortKey] = s.encode() }
    }

    private val recentKey = stringPreferencesKey("recent")
    private val recentsKey = stringPreferencesKey("recents")

    val recent: Flow<String?> = store.data.map { it[recentKey] }

    val recents: Flow<List<String>> = store.data.map { p -> p[recentsKey]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty() }

    suspend fun setRecent(path: String, inList: Boolean = true) {
        store.edit { p ->
            p[recentKey] = path
            if (inList) {
                val list = p[recentsKey]?.split('\n')?.filter { it.isNotEmpty() && it != path }.orEmpty()
                p[recentsKey] = (listOf(path) + list).take(RECENTS).joinToString("\n")
            }
        }
    }

    suspend fun moved(old: String, new: String) {
        store.edit { p ->
            p[positionKey(old)]?.let { p[positionKey(new)] = it; p.remove(positionKey(old)) }
            p[bookmarksKey(old)]?.let { p[bookmarksKey(new)] = it; p.remove(bookmarksKey(old)) }
            fun renamed(r: String) = if (r == old) new else if (r.startsWith("$old/")) new + r.removePrefix(old) else r
            p[recentKey]?.let { p[recentKey] = renamed(it) }
            p[recentsKey]?.let { list -> p[recentsKey] = list.split('\n').joinToString("\n") { renamed(it) } }
        }
    }

    private val recycleBinKey = booleanPreferencesKey("recycle_bin")

    val recycleBin: Flow<Boolean> = store.data.map { it[recycleBinKey] ?: false }

    suspend fun setRecycleBin(on: Boolean) {
        store.edit { it[recycleBinKey] = on }
    }

    suspend fun position(path: String): Float = store.data.first()[positionKey(path)] ?: 0f

    suspend fun savePosition(path: String, fraction: Float) {
        store.edit { it[positionKey(path)] = fraction }
    }

    fun bookmarks(path: String): Flow<List<Bookmark>> = store.data.map { Bookmarks.decode(it[bookmarksKey(path)]) }

    suspend fun setBookmarks(path: String, list: List<Bookmark>) {
        store.edit { if (list.isEmpty()) it.remove(bookmarksKey(path)) else it[bookmarksKey(path)] = Bookmarks.encode(list) }
    }

    suspend fun copyBookmarks(from: String, to: String) {
        store.edit { p -> p[bookmarksKey(from)]?.let { p[bookmarksKey(to)] = it } }
    }

    private companion object {
        const val RECENTS = 50
    }
}
