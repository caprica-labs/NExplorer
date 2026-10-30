package nexplorer.export

import android.media.MediaScannerConnection
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import nexplorer.files.Volume
import nexplorer.scan.Dropdown
import nexplorer.scan.SavePdfScreen
import nexplorer.ui.OptionLabel
import nexplorer.ui.PdfTool
import nexplorer.ui.Segments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val DOWNLOADS: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

@Composable
private fun pageCount(src: File, password: String?): Int? {
    var count by remember(src) { mutableStateOf<Int?>(null) }
    LaunchedEffect(src) { count = withContext(Dispatchers.IO) { runCatching { PdfExport.pageCount(src, password) }.getOrNull() } }
    return count
}

private fun progress(done: Int, count: Int?) = if (count == null) "Saving" else "Page ${(done + 1).coerceAtMost(count)} of $count"

@Composable
fun PdfToTextFlow(volumes: List<Volume>, onSaved: (File) -> Unit, onClose: () -> Unit) {
    PdfTool("PDF to Text", volumes, onClose, accept = { null }) { p, back ->
        val count = pageCount(p.file, p.password)
        SavePdfScreen(
            volumes = volumes,
            defaultName = "${p.file.nameWithoutExtension}.txt",
            sizeOptions = emptyList(),
            note = "The text of every page${count?.let { " ($it)" } ?: ""}, as plain text. Scanned pages have no text to take.",
            savingLabel = { progress(it, count) },
            write = { file, _, onPage -> PdfExport.toText(p.file, p.password, file, onPage) },
            onSaved = onSaved,
            onBack = back,
            extension = "txt",
            title = "Save text",
            startFolder = DOWNLOADS,
        )
    }
}

@Composable
fun PdfToWordFlow(volumes: List<Volume>, onSaved: (File) -> Unit, onClose: () -> Unit) {
    PdfTool("PDF to Word", volumes, onClose, accept = { null }) { p, back ->
        val count = pageCount(p.file, p.password)
        SavePdfScreen(
            volumes = volumes,
            defaultName = "${p.file.nameWithoutExtension}.docx",
            sizeOptions = emptyList(),
            note = "Text becomes editable paragraphs (bold, underline, colors and sizes kept); pages without text, like scans, go in as pictures.",
            savingLabel = { progress(it, count) },
            write = { file, _, onPage -> PdfExport.toWord(p.file, p.password, file, onPage) },
            onSaved = onSaved,
            onBack = back,
            extension = "docx",
            title = "Save Word document",
            startFolder = DOWNLOADS,
        )
    }
}

private val RESOLUTIONS = listOf(72 to "Screen", 150 to "Standard", 300 to "High")

@Composable
fun PdfToImageFlow(volumes: List<Volume>, onSaved: (File, Int) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    PdfTool("PDF to Image", volumes, onClose, accept = { null }) { p, back ->
        val count = pageCount(p.file, p.password)
        var format by remember { mutableStateOf(ImageFormat.Jpeg) }
        var dpi by remember { mutableStateOf(150) }
        val single = count == 1
        SavePdfScreen(
            volumes = volumes,
            defaultName = p.file.nameWithoutExtension,
            sizeOptions = emptyList(),
            note = when (count) {
                null -> "Counting pages…"
                1 -> "1 page → 1 picture."
                else -> "$count pages → $count pictures in a new folder with this name."
            },
            savingLabel = { progress(it, count) },
            write = { file, _, onPage ->
                val files = PdfExport.toImages(p.file, p.password, file, format, dpi, onPage)
                MediaScannerConnection.scanFile(ctx, files.map { it.path }.toTypedArray(), null, null)
            },
            onSaved = { onSaved(it, count ?: 1) },
            onBack = back,
            ready = count != null,
            extension = if (single) format.ext else "",
            title = "Save pictures",
            startFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        ) {
            OptionLabel("Format")
            Segments(ImageFormat.entries.map { it.label }, selected = format.ordinal) { format = ImageFormat.entries[it] }
            OptionLabel("Resolution")
            fun label(r: Pair<Int, String>) = "${r.second}  ·  ${r.first} dpi"
            Dropdown(
                label = label(RESOLUTIONS.first { it.first == dpi }),
                options = RESOLUTIONS.map(::label),
                onPick = { dpi = RESOLUTIONS[it].first },
            )
        }
    }
}
