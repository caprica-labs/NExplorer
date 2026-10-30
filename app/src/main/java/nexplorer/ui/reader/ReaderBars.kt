package nexplorer.ui.reader

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import nexplorer.ui.theme.AccentBlue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class ReaderMenu(val label: String) {
    Contents("Table of contents"), Bookmarks("Bookmarks"), Share("Share"), Print("Print / Save as PDF"), Undo("Undo edit"), RemoveAll("Remove all edits"),
}

@Composable
internal fun TopBar(
    name: String,
    bookmarked: Boolean?,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    enabled: (ReaderMenu) -> Boolean,
    onMenu: (ReaderMenu) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (bookmarked != null) {
                IconButton(onClick = onBookmark) {
                    if (bookmarked) {
                        Icon(Icons.Filled.Bookmark, contentDescription = "Remove bookmark", tint = AccentBlue)
                    } else {
                        Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Bookmark this page")
                    }
                }
                Box {
                    var open by remember { mutableStateOf(false) }
                    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.Menu, contentDescription = "Menu") }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        ReaderMenu.entries.forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, enabled = enabled(m), onClick = { open = false; onMenu(m) })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

@Composable
internal fun BottomBar(editMode: Boolean, onEdit: () -> Unit, onViewMode: () -> Unit, onSearch: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            BarButton(Icons.Outlined.Edit, "Edit", Modifier.weight(1f), active = editMode, onClick = onEdit)
            BarButton(Icons.Outlined.ViewAgenda, "View mode", Modifier.weight(1f), onClick = onViewMode)
            BarButton(Icons.Outlined.Search, "Search", Modifier.weight(1f), onClick = onSearch)
        }
    }
}

@Composable
internal fun BarButton(icon: ImageVector, label: String, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
    val color = if (active) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color)
            Text(label, fontSize = 11.sp, color = color)
        }
    }
}

internal val HighlightYellow = Color(0xFFFFF59D)
internal val UnderlineGreen = Color(0xFFA5D6A7)

@Composable
internal fun ActionButton(label: String, swatch: Color? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (swatch != null) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(swatch))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 13.sp)
    }
}

internal const val LONG_FILE = 5

internal data class Progress(val label: String, val done: Int, val total: Int, val cancellable: Boolean)

@Composable
internal fun ProgressDialog(p: Progress, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(p.label, fontSize = 16.sp) },
        text = {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                color = AccentBlue,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {},
        dismissButton = { if (p.cancellable) TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

internal const val MAX_AUTO_SCROLL = 28f
