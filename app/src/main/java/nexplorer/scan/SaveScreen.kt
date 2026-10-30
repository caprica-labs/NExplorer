package nexplorer.scan

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.files.FileOps
import nexplorer.files.Volume
import nexplorer.ui.OptionLabel
import nexplorer.ui.home.FolderPickerDialog
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val QUALITIES = listOf(100, 75, 50, 25)

@Composable
fun SaveScreen(pages: List<ScanPage>, volumes: List<Volume>, onSaved: (File) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var quality by remember { mutableIntStateOf(100) }
    var sizes by remember { mutableStateOf<Map<Int, Long>?>(null) }
    var estimating by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(pages) {
        estimating = coroutineContext[Job]
        sizes = withContext(Dispatchers.IO) {
            runCatching { ScanImaging.estimate(ctx, pages, QUALITIES, cancelled = { !isActive }) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }
    }
    SavePdfScreen(
        volumes = volumes,
        defaultName = ScanNames.default(),
        sizeOptions = PageSizes.ALL,
        note = "${pages.size} ${if (pages.size == 1) "page" else "pages"}",
        savingLabel = { done -> "Saving page ${(done + 1).coerceAtMost(pages.size)} of ${pages.size}" },
        write = { file, size, progress ->
            estimating?.cancel()
            ScanImaging.writePdf(ctx, pages, size, quality, file, progress)
        },
        onSaved = onSaved,
        onBack = onBack,
    ) {
        OptionLabel("Image quality")
        fun sized(q: Int) = "$q%  ·  " + (sizes?.get(q)?.let(SizeEstimate::label) ?: "estimating…")
        Dropdown(label = sized(quality), options = QUALITIES.map(::sized), onPick = { quality = QUALITIES[it] })
    }
}

@Composable
fun SavePdfScreen(
    volumes: List<Volume>,
    defaultName: String,
    sizeOptions: List<PageSize>,
    note: String,
    savingLabel: (done: Int) -> String,
    write: (file: File, size: PageSize, onProgress: (Int) -> Unit) -> Unit,
    onSaved: (File) -> Unit,
    onBack: () -> Unit,
    ready: Boolean = true,
    extension: String = "pdf",
    title: String = "Save PDF",
    showName: Boolean = true,
    startFolder: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    BackHandler(enabled = !saving, onBack = onBack)

    var name by remember { mutableStateOf(defaultName) }
    var folder by remember {
        mutableStateOf(startFolder)
    }
    var picking by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(PageSizes.DEFAULT.takeIf { it in sizeOptions } ?: sizeOptions.firstOrNull() ?: PageSizes.DEFAULT) }

    fun save() {
        saving = true
        done = 0
        scope.launch {
            val wanted = ScanNames.withExtension(name, extension)
            val file = if (showName) File(folder, FileOps.uniqueName(folder, wanted)) else folder
            val result = withContext(Dispatchers.IO) { runCatching { write(file, size) { done = it } } }
            saving = false
            result.onSuccess {
                if (showName && file.name != wanted) {
                    Toast.makeText(ctx, "Saved as ${file.name} (that name was taken)", Toast.LENGTH_SHORT).show()
                }
                onSaved(file)
            }.onFailure {
                if (showName) file.delete()
                Toast.makeText(ctx, "Couldn't save: ${it.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !saving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(title, fontSize = 18.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (showName) {
                OptionLabel("Name")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c != '/' } },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentBlue, cursorColor = AccentBlue),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OptionLabel("Where to")
            Field(onClick = { picking = true }) {
                Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(folder.path, maxLines = 2, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
            }

            if (sizeOptions.isNotEmpty()) {
                OptionLabel("Page size")
                fun label(p: PageSize) = if (p == PageSizes.FIT) p.name else "${p.name}  ·  ${p.dims}"
                Dropdown(label = label(size), options = sizeOptions.map(::label), onPick = { size = sizeOptions[it] })
            }

            extra()

            Text(
                note,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Button(
            onClick = ::save,
            enabled = !saving && ready,
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.padding(end = 10.dp).width(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Text(savingLabel(done))
            } else {
                Text("Save")
            }
        }
    }

    if (picking) {
        FolderPickerDialog(
            title = "Save to",
            confirmLabel = "Save here",
            items = emptyList(),
            volumes = volumes,
            startDir = folder,
            onPick = { folder = it; picking = false },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Field(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun Dropdown(label: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Field(onClick = { open = true }) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}
