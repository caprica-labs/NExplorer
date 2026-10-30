package nexplorer.lock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.doc.LockState
import nexplorer.doc.PdfLock
import nexplorer.doc.Restriction
import nexplorer.files.Volume
import nexplorer.scan.SavePdfScreen
import nexplorer.ui.OptionLabel
import nexplorer.ui.PasswordField
import nexplorer.ui.PdfTool
import nexplorer.ui.theme.AccentBlue
import java.io.File

@Composable
fun LockFlow(volumes: List<Volume>, onSaved: (File) -> Unit, onClose: () -> Unit) {
    PdfTool("Lock PDF", volumes, onClose, accept = { null }) { p, back ->
        var open by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var owner by remember { mutableStateOf("") }
        var blocked by remember { mutableStateOf(setOf<Restriction>()) }

        val problem = when {
            open.isEmpty() && blocked.isEmpty() -> "Set an open password, or choose what to restrict."
            !PdfLock.usable(open) || !PdfLock.usable(owner) -> "Passwords can't contain a comma."
            open != confirm -> "The passwords don't match."
            blocked.isNotEmpty() && owner.isEmpty() -> "Restrictions need an owner password."
            blocked.isNotEmpty() && owner == open -> "The owner password must differ from the open password."
            else -> null
        }

        SavePdfScreen(
            volumes = volumes,
            defaultName = "${p.file.nameWithoutExtension}_locked.pdf",
            sizeOptions = emptyList(),
            note = "AES-256 encryption. A forgotten open password can't be recovered.",
            savingLabel = { "Locking" },
            write = { file, _, _ -> PdfLock.lock(p.file, p.password, file, open, owner, blocked) },
            onSaved = onSaved,
            onBack = back,
            ready = problem == null,
        ) {
            OptionLabel("Open password")
            PasswordField(open, { open = it }, "Password (leave empty for restrictions only)")
            PasswordField(confirm, { confirm = it }, "Confirm password", Modifier.padding(top = 8.dp), isError = confirm.isNotEmpty() && confirm != open)

            OptionLabel("Restrict")
            Restriction.entries.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { blocked = if (r in blocked) blocked - r else blocked + r },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = r in blocked,
                        onCheckedChange = { blocked = if (it) blocked + r else blocked - r },
                        colors = CheckboxDefaults.colors(checkedColor = AccentBlue),
                    )
                    Text(r.label)
                }
            }
            if (blocked.isNotEmpty()) {
                OptionLabel("Owner password")
                PasswordField(owner, { owner = it }, "Lifts the restrictions", imeAction = androidx.compose.ui.text.input.ImeAction.Done)
            }
            if (problem != null && (open.isNotEmpty() || confirm.isNotEmpty() || blocked.isNotEmpty())) {
                Text(problem, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun UnlockFlow(volumes: List<Volume>, onSaved: (File) -> Unit, onClose: () -> Unit) {
    PdfTool(
        "Unlock PDF", volumes, onClose,
        accept = { if (it == LockState.None) "This PDF isn't locked" else null },
    ) { p, back ->
        SavePdfScreen(
            volumes = volumes,
            defaultName = "${p.file.nameWithoutExtension}_unlocked.pdf",
            sizeOptions = emptyList(),
            note = if (p.state == LockState.OpenPassword) {
                "The copy will open without a password and have no restrictions."
            } else {
                "This PDF has restrictions only; the copy will have none."
            },
            savingLabel = { "Unlocking" },
            write = { file, _, _ -> PdfLock.unlock(p.file, p.password, file) },
            onSaved = onSaved,
            onBack = back,
        )
    }
}
