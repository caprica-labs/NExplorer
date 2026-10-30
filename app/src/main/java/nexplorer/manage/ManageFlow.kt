package nexplorer.manage

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.files.Volume
import nexplorer.scan.Lists
import nexplorer.scan.NumberBadge
import nexplorer.scan.SavePdfScreen
import nexplorer.ui.Placeholder
import nexplorer.ui.PdfTool
import nexplorer.ui.ReorderGrid
import nexplorer.ui.theme.AccentBlue
import java.io.File

private sealed interface Saving {
    data class Edited(val pages: List<Int>) : Saving
    data class Extract(val pages: List<Int>) : Saving
}

@Composable
fun ManageFlow(volumes: List<Volume>, onSaved: (File) -> Unit, onClose: () -> Unit) {
    PdfTool("Manage PDF", volumes, onClose, accept = { null }) { p, back ->
        val src = p.file
        var saving by remember { mutableStateOf<Saving?>(null) }
        var order by remember { mutableStateOf<List<Int>?>(null) }
        val save = saving
        if (save != null) {
            SavePdfScreen(
                volumes = volumes,
                defaultName = src.nameWithoutExtension + if (save is Saving.Extract) "_pages.pdf" else "_edited.pdf",
                sizeOptions = emptyList(),
                note = when (save) {
                    is Saving.Edited -> "${save.pages.size} pages"
                    is Saving.Extract -> "${save.pages.size} of the pages of ${src.name}"
                },
                savingLabel = { "Saving" },
                write = { file, _, _ ->
                    PdfPages.write(src, p.password, if (save is Saving.Edited) save.pages else (save as Saving.Extract).pages, file)
                },
                onSaved = onSaved,
                onBack = { saving = null },
            )
        } else {
            PagesScreen(
                file = src,
                password = p.password,
                start = order,
                onOrder = { order = it },
                onSave = { saving = Saving.Edited(it) },
                onExtract = { saving = Saving.Extract(it) },
                onBack = back,
            )
        }
    }
}

@Composable
private fun PagesScreen(
    file: File,
    password: String?,
    start: List<Int>?,
    onOrder: (List<Int>) -> Unit,
    onSave: (List<Int>) -> Unit,
    onExtract: (List<Int>) -> Unit,
    onBack: () -> Unit,
) {
    val thumbs = remember(file) { PageThumbs(file, password) }
    DisposableEffect(thumbs) { onDispose { thumbs.close() } }
    var count by remember(file) { mutableStateOf<Int?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(thumbs) {
        thumbs.open().fold({ count = it }, { error = it.message ?: "This file couldn't be opened" })
    }

    val original = remember(count) { count?.let { (0 until it).toList() } }
    var pages by remember(original) { mutableStateOf(start ?: original.orEmpty()) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var confirmLeave by remember { mutableStateOf(false) }
    val changed = original != null && pages != original

    fun setPages(p: List<Int>) { pages = p; onOrder(p) }
    fun back() { if (changed) confirmLeave = true else onBack() }
    BackHandler { if (selected.isNotEmpty()) selected = emptySet() else back() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(file.name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (selected.isNotEmpty()) {
                TextButton(onClick = { selected = emptySet() }) { Text("Clear", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                TextButton(onClick = { onSave(pages) }, enabled = changed) {
                    Text("Save", color = if (changed) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                }
            }
        }
        val err = error
        when {
            err != null -> Placeholder(err)
            original == null -> Placeholder("Opening")
            else -> {
                Text(
                    "Tap pages to select them. Hold a page and drag it to move it.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                ReorderGrid(
                    items = pages,
                    key = { it },
                    onMove = { from, to -> setPages(Lists.move(pages, from, to)) },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                    onTap = { i -> pages[i].let { p -> selected = if (p in selected) selected - p else selected + p } },
                ) { page, i, lifted ->
                    val isSelected = page in selected
                    Box(
                        Modifier
                            .padding(6.dp)
                            .aspectRatio(0.75f)
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                if (lifted || isSelected) 2.dp else 0.5.dp,
                                if (lifted || isSelected) AccentBlue else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(6.dp),
                            )
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        PageThumb(thumbs, page, Modifier.fillMaxSize())
                        if (isSelected) Box(Modifier.fillMaxSize().background(AccentBlue.copy(alpha = 0.15f)))
                        NumberBadge(i + 1, Modifier.align(Alignment.BottomCenter).padding(6.dp))
                        if (isSelected) {
                            Icon(
                                Icons.Outlined.CheckCircle, contentDescription = "Selected", tint = AccentBlue,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape).background(Color.White),
                            )
                        }
                    }
                }
                if (selected.isNotEmpty()) {
                    val chosen = pages.filter { it in selected }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${chosen.size} selected", modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { setPages(pages - selected); selected = emptySet() },
                            enabled = chosen.size < pages.size,
                        ) { Text("Delete", color = if (chosen.size < pages.size) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { onExtract(chosen) }) { Text("Extract", color = AccentBlue) }
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard your changes?") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun PageThumb(thumbs: PageThumbs, page: Int, modifier: Modifier) {
    var bmp by remember(page) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page) { bmp = thumbs.page(page, 300, 400) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else CircularProgressIndicator(Modifier.size(24.dp), color = AccentBlue, strokeWidth = 2.dp)
    }
}
