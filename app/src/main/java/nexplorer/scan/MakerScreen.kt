package nexplorer.scan

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val previews = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
fun PagePreview(page: ScanPage, maxSide: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val key = "${page.source}|${page.rotation}|${page.mirrored}|${page.filter}|${page.crop.hashCode()}|$maxSide"
    var bmp by remember(key) { mutableStateOf(previews.get(key)) }
    LaunchedEffect(key) {
        if (bmp == null) bmp = withContext(Dispatchers.IO) { ScanImaging.render(ctx, page, maxSide) }?.also { previews.put(key, it) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b == null) CircularProgressIndicator(Modifier.size(28.dp), color = AccentBlue, strokeWidth = 2.dp)
        else Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
fun MakerScreen(
    pages: List<ScanPage>,
    startPage: Int,
    onPages: (List<ScanPage>) -> Unit,
    onPageShown: (Int) -> Unit,
    onImport: () -> Unit,
    onCamera: () -> Unit,
    onRetake: (Int) -> Unit,
    onReorder: () -> Unit,
    onCrop: (Int) -> Unit,
    onNext: () -> Unit,
    onDiscard: () -> Unit,
) {
    var askDiscard by remember { mutableStateOf(false) }
    BackHandler { askDiscard = true }
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = startPage.coerceIn(0, pages.size)) { pages.size + 1 }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect(onPageShown) }
    val current = pager.currentPage.takeIf { it < pages.size }
    var showFilters by remember { mutableStateOf(false) }

    fun edit(f: (ScanPage) -> ScanPage) {
        val i = current ?: return
        onPages(pages.toMutableList().also { it[i] = f(it[i]) })
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { askDiscard = true }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onNext, enabled = pages.isNotEmpty()) {
                Text("Next", color = if (pages.isEmpty()) Color.Gray else AccentBlue, fontSize = 16.sp)
            }
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            if (i < pages.size) {
                PagePreview(pages[i], 1600, Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp))
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    AddButton(Icons.AutoMirrored.Outlined.NoteAdd, "Import", onImport)
                    Spacer(Modifier.height(20.dp))
                    AddButton(Icons.Outlined.PhotoCamera, "Take a photo", onCamera)
                }
            }
        }

        Text(
            if (current != null) "${current + 1} / ${pages.size}" else "Add pages",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )

        if (showFilters && current != null) {
            FilterStrip(
                page = pages[current],
                onPick = { f -> edit { it.copy(filter = f) } },
                onApplyAll = { onPages(pages.map { it.copy(filter = pages[current].filter) }) },
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val on = current != null
            Tool(Icons.Outlined.GridView, "Reorder", Modifier.weight(1f), pages.size > 1, onClick = onReorder)
            Tool(Icons.Outlined.PhotoCamera, "Retake", Modifier.weight(1f), on) { current?.let(onRetake) }
            Tool(Icons.Outlined.Rotate90DegreesCw, "Rotate", Modifier.weight(1f), on) { edit { it.copy(rotation = (it.rotation + 90) % 360) } }
            Tool(Icons.Outlined.Flip, "Mirror", Modifier.weight(1f), on) { edit { it.copy(mirrored = !it.mirrored) } }
            Tool(Icons.Outlined.AutoFixHigh, "Filters", Modifier.weight(1f), on, active = showFilters) { showFilters = !showFilters }
            Tool(Icons.Outlined.Crop, "Crop", Modifier.weight(1f), on) { current?.let(onCrop) }
        }
    }

    LaunchedEffect(startPage) { if (startPage != pager.currentPage) scope.launch { pager.scrollToPage(startPage.coerceIn(0, pages.size)) } }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Discard this PDF?") },
            text = { Text("The pages you've added won't be saved.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = { TextButton(onClick = { askDiscard = false; onDiscard() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("Keep editing", color = AccentBlue) } },
        )
    }
}

@Composable
private fun AddButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(160.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(40.dp), tint = AccentBlue)
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        active -> AccentBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = color)
        Text(label, fontSize = 10.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun FilterStrip(page: ScanPage, onPick: (Filter) -> Unit, onApplyAll: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Filter.entries) { f ->
                val selected = f == page.filter
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(f) }) {
                    PagePreview(
                        page.copy(filter = f),
                        240,
                        Modifier.size(64.dp, 80.dp).clip(RoundedCornerShape(6.dp))
                            .border(if (selected) 2.dp else 0.5.dp, if (selected) AccentBlue else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                    )
                    Text(f.label, fontSize = 11.sp, color = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        TextButton(onClick = onApplyAll, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) {
            Text("Apply to all", color = AccentBlue)
        }
    }
}
