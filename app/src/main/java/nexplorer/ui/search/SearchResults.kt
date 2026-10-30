package nexplorer.ui.search

import nexplorer.ui.dragToSelect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import nexplorer.files.Entry
import nexplorer.files.Volume
import nexplorer.ui.Placeholder
import nexplorer.ui.home.EntryRow
import nexplorer.ui.home.typeIcon
import nexplorer.ui.home.FileActionCallbacks
import nexplorer.ui.home.SelectionBar
import nexplorer.ui.home.SelectionDialogs
import nexplorer.ui.home.rememberSelectionState
import java.io.File

@Composable
fun SearchResults(
    results: List<File>,
    query: String,
    volumes: List<Volume>,
    onOpen: (file: File, listed: List<File>) -> Unit,
    actions: FileActionCallbacks,
) {
    val sel = rememberSelectionState()
    val entries = results.map { Entry(it, false, 0) }
    val chosen = entries.filter { it.file.path in sel.selected }

    LaunchedEffect(query) { sel.clear() }
    LaunchedEffect(results) { sel.keepOnly(results.map { it.path }) }
    BackHandler(enabled = sel.active) { sel.clear() }

    Column(Modifier.fillMaxSize()) {
        if (sel.active) SelectionBar(sel, chosen, results.map { it.path }, actions)
        if (results.isEmpty()) {
            Placeholder("No files found")
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                Modifier.dragToSelect(
                    listState,
                    keys = { results.map { it.path } },
                    selected = { sel.selected },
                    onSelect = { sel.selected = it },
                ),
                state = listState,
            ) {
                items(entries, key = { it.file.path }) { e ->
                    val path = e.file.path
                    EntryRow(
                        e,
                        selected = if (sel.active) path in sel.selected else null,
                        onClick = {
                            if (sel.active) sel.toggle(path) else onOpen(e.file, listOf(e.file))
                        },
                        onLongClick = null,
                        above = e.file.parent ?: "",
                        opened = path in actions.opened,
                        icon = typeIcon(e.file.name, false),
                        thumbnail = true,
                    )
                }
            }
        }
    }
    SelectionDialogs(sel, volumes, volumes.first().root, actions)
}
