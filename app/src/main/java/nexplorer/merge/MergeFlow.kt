package nexplorer.merge

import android.graphics.Bitmap
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.files.FileKind
import nexplorer.files.Volume
import nexplorer.scan.Lists
import nexplorer.scan.NumberBadge
import nexplorer.scan.SavePdfScreen
import nexplorer.scan.ScanNames
import nexplorer.ui.FilePicker
import nexplorer.ui.ReorderGrid
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.key
import nexplorer.ui.PasswordDialog
import nexplorer.ui.ToolScreen
import java.io.File

private enum class Route { Pick, Order, Save }

@Composable
fun MergeFlow(volumes: List<Volume>, onSaved: (File) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var route by remember { mutableStateOf(Route.Pick) }
    var picks by remember { mutableStateOf(listOf<File>()) }
    val infos = remember { mutableStateMapOf<String, PdfInfo>() }
    val passwords = remember { mutableStateMapOf<String, String>() }

    ToolScreen {
        when (route) {
            Route.Pick -> FilePicker(
                kind = FileKind.PDF,
                title = "Merge PDFs",
                noneFound = "No PDFs found",
                volumes = volumes,
                picks = picks,
                onPick = { picks = Lists.togglePick(picks, it) },
                onBack = onClose,
                action = {
                    TextButton(onClick = { route = Route.Order }, enabled = picks.size >= 2) {
                        Text(
                            if (picks.isEmpty()) "Next" else "Next (${picks.size})",
                            color = if (picks.size >= 2) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            Route.Order -> OrderScreen(
                files = picks,
                infos = infos,
                passwords = passwords,
                onOrder = { picks = it },
                onNext = {
                    val bad = picks.firstOrNull { infos[it.path]?.problem != null }
                    if (bad != null) {
                        Toast.makeText(ctx, "${bad.name}: ${infos[bad.path]?.problem}", Toast.LENGTH_LONG).show()
                    } else {
                        route = Route.Save
                    }
                },
                onBack = { route = Route.Pick },
            )
            Route.Save -> SavePdfScreen(
                volumes = volumes,
                defaultName = ScanNames.default(prefix = "Merged"),
                sizeOptions = emptyList(),
                note = "${picks.size} files · ${picks.sumOf { infos[it.path]?.pages ?: 0 }} pages",
                savingLabel = { done -> "Adding file ${(done + 1).coerceAtMost(picks.size)} of ${picks.size}" },
                write = { file, _, progress -> PdfMerge.write(picks, passwords.toMap(), file, progress) },
                onSaved = onSaved,
                onBack = { route = Route.Order },
            )
        }
    }
}

@Composable
private fun OrderScreen(
    files: List<File>,
    infos: MutableMap<String, PdfInfo>,
    passwords: MutableMap<String, String>,
    onOrder: (List<File>) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<File?>(null) }
    asking?.let { f ->
        PasswordDialog(
            fileName = f.name,
            wrong = infos[f.path]?.problem?.startsWith("Wrong") == true,
            onPassword = { pw ->
                asking = null
                scope.launch {
                    val info = withContext(Dispatchers.IO) { PdfMerge.info(f, pw) }
                    if (!info.locked) passwords[f.path] = pw
                    infos[f.path] = info
                    if (info.locked) asking = f
                }
            },
            onDismiss = { asking = null },
        )
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Order", fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onNext, enabled = files.size >= 2) {
                Text("Next", color = if (files.size >= 2) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            }
        }
        Text(
            "Hold a file and drag it to a new place.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ReorderGrid(
            items = files,
            key = { it.path },
            onMove = { from, to -> onOrder(Lists.move(files, from, to)) },
            onTap = { i -> files.getOrNull(i)?.takeIf { infos[it.path]?.locked == true }?.let { asking = it } },
            modifier = Modifier.fillMaxSize().padding(8.dp),
        ) { file, i, lifted ->
            LaunchedEffect(file.path) {
                if (file.path !in infos) infos[file.path] = withContext(Dispatchers.IO) { PdfMerge.info(file, passwords[file.path]) }
            }
            val info = infos[file.path]
            Column(Modifier.padding(6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .clip(RoundedCornerShape(6.dp))
                        .border(if (lifted) 2.dp else 0.5.dp, if (lifted) AccentBlue else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    key(passwords[file.path]) { FirstPage(file, passwords[file.path], Modifier.fillMaxSize()) }
                    NumberBadge(i + 1, Modifier.align(Alignment.BottomCenter).padding(6.dp))
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)).clickable { onOrder(files - file) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
                Text(file.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(
                    when {
                        info == null -> " "
                        info.problem != null -> info.problem
                        info.pages == 1 -> "1 page"
                        else -> "${info.pages} pages"
                    },
                    fontSize = 11.sp,
                    color = if (info?.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FirstPage(file: File, password: String?, modifier: Modifier) {
    var bmp by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(file.path) { mutableStateOf(true) }
    LaunchedEffect(file.path) {
        bmp = withContext(Dispatchers.IO) { PdfMerge.firstPage(file, password, 300, 400) }
        loading = false
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        when {
            b != null -> Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            loading -> CircularProgressIndicator(Modifier.size(24.dp), color = AccentBlue, strokeWidth = 2.dp)
        }
    }
}
