package nexplorer.wordpdf

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import nexplorer.files.FileKind
import nexplorer.files.Volume
import nexplorer.scan.PageSizes
import nexplorer.scan.SavePdfScreen
import nexplorer.ui.FilePicker
import nexplorer.ui.ToolScreen
import java.io.File

@Composable
fun WordPdfFlow(volumes: List<Volume>, onSaved: (File) -> Unit, onClose: () -> Unit) {
    var docx by remember { mutableStateOf<File?>(null) }

    ToolScreen {
        val picked = docx
        if (picked == null) {
            FilePicker(
                kind = FileKind.DOCX,
                title = "Word to PDF",
                noneFound = "No Word files found",
                volumes = volumes,
                onPick = { docx = it },
                onBack = onClose,
            )
        } else {
            SavePdfScreen(
                volumes = volumes,
                defaultName = "${picked.nameWithoutExtension}.pdf",
                sizeOptions = PageSizes.ALL - PageSizes.FIT,
                note = picked.name,
                savingLabel = { done -> if (done == 0) "Saving" else "Saving page $done" },
                write = { file, size, progress -> WordPdf.write(picked, size, file, progress) },
                onSaved = onSaved,
                onBack = { docx = null },
            )
        }
    }
}
