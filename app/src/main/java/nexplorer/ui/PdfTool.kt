package nexplorer.ui

import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import nexplorer.doc.LockState
import nexplorer.doc.PasswordNeeded
import nexplorer.doc.PdfLock
import nexplorer.files.FileKind
import nexplorer.files.Volume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PickedPdf(val file: File, val state: LockState, val password: String?)

@Composable
fun PdfTool(
    title: String,
    volumes: List<Volume>,
    onClose: () -> Unit,
    accept: (LockState) -> String?,
    content: @Composable (PickedPdf, back: () -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<File?>(null) }
    var wrong by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<PickedPdf?>(null) }

    ToolScreen {
        val p = picked
        if (p != null) {
            content(p) { picked = null }
        } else {
            FilePicker(
                kind = FileKind.PDF,
                title = title,
                noneFound = "No PDFs found",
                volumes = volumes,
                onPick = { file ->
                    scope.launch {
                        val state = withContext(Dispatchers.IO) { PdfLock.state(file) }
                        val problem = accept(state)
                        when {
                            problem != null -> Toast.makeText(ctx, problem, Toast.LENGTH_SHORT).show()
                            state == LockState.OpenPassword -> { wrong = false; pending = file }
                            else -> picked = PickedPdf(file, state, null)
                        }
                    }
                },
                onBack = onClose,
            )
        }
    }

    pending?.let { file ->
        PasswordDialog(
            fileName = file.name,
            wrong = wrong,
            onPassword = { pw ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { PdfLock.open(file.path, pw).destroy() }.exceptionOrNull() !is PasswordNeeded
                    }
                    if (ok) { pending = null; picked = PickedPdf(file, LockState.OpenPassword, pw) } else wrong = true
                }
            },
            onDismiss = { pending = null },
        )
    }
}
