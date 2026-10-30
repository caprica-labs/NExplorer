package nexplorer.ui.explorer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.textpdf.TextFiles
import nexplorer.ui.Placeholder
import nexplorer.ui.ToolScreen
import nexplorer.ui.home.openWithApp
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_EDIT_BYTES = 4L * 1024 * 1024

@Composable
fun TextEditorScreen(file: File, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<Pair<String, TextFiles.Layout>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var saved by remember { mutableStateOf("") }
    var askLeave by remember { mutableStateOf(false) }
    val dirty = loaded != null && value.text != saved

    LaunchedEffect(file) {
        var why: String? = null
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (file.length() > MAX_EDIT_BYTES) null else file.readBytes()
                when {
                    bytes == null -> { why = "This file is too big to edit here."; null }
                    !TextFiles.isUtf8(bytes) -> { why = "This file uses an older text encoding, so editing it here could ruin it."; null }
                    else -> TextFiles.decode(bytes) to TextFiles.layoutOf(bytes)
                }
            }
        }
        r.onSuccess { got ->
            if (got == null) {
                problem = why
            } else {
                loaded = got
                saved = got.first
                value = TextFieldValue(got.first)
            }
        }.onFailure { problem = "This file couldn't be read." }
    }

    fun save(then: () -> Unit = {}) {
        val layout = loaded?.second ?: return
        val text = value.text
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(file.parentFile, ".${file.name}.nexplorer-tmp")
                    tmp.writeBytes(TextFiles.encode(text, layout))
                    if (!tmp.renameTo(file)) {
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    }
                }.isSuccess
            }
            if (ok) {
                saved = text
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                then()
            } else {
                Toast.makeText(ctx, "Couldn't save this file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun leave() { if (dirty) askLeave = true else onClose() }
    BackHandler(onBack = ::leave)

    ToolScreen {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text(
                    (if (dirty) "• " else "") + file.name,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { save() }, enabled = dirty) {
                    Text("Save", color = if (dirty) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            val p = problem
            when {
                p != null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f)) { Placeholder(p) }
                    TextButton(onClick = { openWithApp(ctx, file, choose = true) }, modifier = Modifier.padding(24.dp)) {
                        Text("Open with another app", color = AccentBlue)
                    }
                }
                loaded == null -> Placeholder("Opening")
                else -> BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textDirection = TextDirection.Content,
                    ),
                    cursorBrush = SolidColor(AccentBlue),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("Save changes?") },
            text = { Text("${file.name} has changes that aren't saved.") },
            confirmButton = { TextButton(onClick = { askLeave = false; save(onClose) }) { Text("Save", color = AccentBlue) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { askLeave = false; onClose() }) { Text("Don't save", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { askLeave = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
        )
    }
}
