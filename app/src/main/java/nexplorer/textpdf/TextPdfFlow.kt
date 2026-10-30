package nexplorer.textpdf

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import nexplorer.files.FileKind
import nexplorer.files.Volume
import nexplorer.scan.PageSizes
import nexplorer.scan.SavePdfScreen
import nexplorer.scan.ScanNames
import nexplorer.ui.FilePicker
import nexplorer.ui.theme.NExplorerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nexplorer.ui.ToolScreen
import java.io.File

enum class TextStart { Write, Import }

@Composable
fun TextStartDialog(onPick: (TextStart) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text to PDF") },
        text = {
            Column {
                Choice(Icons.Outlined.Edit, "Write text") { onPick(TextStart.Write) }
                Choice(Icons.Outlined.FileOpen, "Import a .txt file") { onPick(TextStart.Import) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface)
    }
}

private sealed interface Route {
    data object Pick : Route
    data object Write : Route
    data object Save : Route
}

@Composable
fun TextPdfFlow(start: TextStart, volumes: List<Volume>, fileDark: Boolean, onSaved: (File) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<Route>(if (start == TextStart.Import) Route.Pick else Route.Write) }
    var source by remember { mutableStateOf<File?>(null) }
    var initial by remember { mutableStateOf(RichText.EMPTY) }
    var text by remember { mutableStateOf(RichText.EMPTY) }

    ToolScreen(
        when {
            route != Route.Write -> MaterialTheme.colorScheme.background
            fileDark -> Color(0xFF121212)
            else -> Color.White
        },
    ) {
        when (route) {
            Route.Pick -> FilePicker(
                kind = FileKind.TXT,
                title = "Import text",
                noneFound = "No .txt files found",
                volumes = volumes,
                onPick = { file ->
                    scope.launch {
                        val loaded = withContext(Dispatchers.IO) { runCatching { TextFiles.read(file) } }
                        loaded.onSuccess {
                            source = file
                            initial = RichText.plain(it)
                            route = Route.Write
                        }.onFailure {
                            Toast.makeText(ctx, "Couldn't read ${file.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onBack = onClose,
            )
            Route.Write -> NExplorerTheme(dark = fileDark) { FileThemedBars(fileDark); Surface(color = MaterialTheme.colorScheme.background) { NotepadScreen(
                title = source?.name ?: "Write text",
                initial = if (text != RichText.EMPTY) text else initial,
                baseline = initial,
                onDone = { text = it; route = Route.Save },
                onBack = { text = RichText.EMPTY; if (start == TextStart.Import) route = Route.Pick else onClose() },
            ) } }
            Route.Save -> SavePdfScreen(
                volumes = volumes,
                defaultName = source?.nameWithoutExtension?.let { "$it.pdf" } ?: ScanNames.default(),
                sizeOptions = PageSizes.ALL - PageSizes.FIT,
                note = "${text.text.length} characters",
                savingLabel = { done -> if (done == 0) "Saving" else "Saving page $done" },
                write = { file, size, progress -> TextPdf.write(text, size, file, progress) },
                onSaved = onSaved,
                onBack = { route = Route.Write },
            )
        }
    }
}

@Composable
private fun FileThemedBars(dark: Boolean) {
    val view = LocalView.current
    DisposableEffect(dark) {
        val window = (view.context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
        val bars = WindowCompat.getInsetsController(window, view)
        val was = bars.isAppearanceLightStatusBars to bars.isAppearanceLightNavigationBars
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
        onDispose {
            bars.isAppearanceLightStatusBars = was.first
            bars.isAppearanceLightNavigationBars = was.second
        }
    }
}
