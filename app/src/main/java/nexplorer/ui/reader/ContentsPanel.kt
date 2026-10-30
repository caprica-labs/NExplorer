package nexplorer.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.reader.Bookmark
import nexplorer.reader.Bookmarks
import nexplorer.reader.TocEntry
import nexplorer.ui.theme.AccentBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentsSheet(toc: List<TocEntry>?, current: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    val here = toc?.let { Bookmarks.currentEntry(it, current) } ?: -1
    Sheet("Table of contents", onDismiss) {
        when {
            toc == null -> Empty("Loading…")
            toc.isEmpty() -> Empty("This file has no table of contents")
            else -> {
                val list = rememberLazyListState(initialFirstVisibleItemIndex = (here - 2).coerceAtLeast(0))
                LazyColumn(state = list) {
                    itemsIndexed(toc) { i, e ->
                        Line(
                            text = e.title.ifEmpty { "(untitled)" },
                            page = e.page.takeIf { it >= 0 }?.let { it + 1 },
                            indent = e.level.coerceAtMost(4),
                            current = i == here,
                            bold = e.level == 0,
                            onClick = if (e.page >= 0) ({ onGo(e.page) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksSheet(
    list: List<Bookmark>,
    pageCount: Int,
    current: Int,
    onGo: (Int) -> Unit,
    onEdit: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet("Bookmarks", onDismiss) {
        if (list.isEmpty()) {
            Empty("No bookmarks yet. Tap the ribbon at the top to bookmark the page you're on.")
        } else {
            LazyColumn {
                itemsIndexed(list) { _, b ->
                    val page = Bookmarks.pageOf(b, pageCount)
                    Line(
                        text = b.name.ifBlank { "Page ${page + 1}" },
                        page = page + 1,
                        indent = 0,
                        current = page == current,
                        bold = false,
                        onClick = { onGo(page) },
                        onLongClick = { onEdit(b) },
                    )
                }
            }
            Text(
                "Long-press a bookmark to rename or delete it",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp),
            )
        }
    }
}

/** Rename or delete one bookmark. */
@Composable
internal fun BookmarkDialog(b: Bookmark, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) =
    NameDialog("Bookmark", b.name, onRename, onDelete, onDismiss)

@Composable
internal fun NameDialog(title: String, start: String, onSave: (String) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentBlue, cursorColor = AccentBlue, focusedLabelColor = AccentBlue),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }) { Text("Save", color = AccentBlue) } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Line(
    text: String,
    page: Int?,
    indent: Int,
    current: Boolean,
    bold: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
) {
    val color = when {
        current -> AccentBlue
        onClick == null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (current) Modifier.background(AccentBlue.copy(alpha = 0.10f)) else Modifier)
            .then(
                if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier,
            )
            .padding(start = 20.dp + 16.dp * indent, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = color,
            fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        if (page != null) {
            Spacer(Modifier.width(12.dp))
            Text("$page", color = if (current) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}
