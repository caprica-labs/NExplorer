package nexplorer.compress

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import nexplorer.files.Volume
import nexplorer.scan.Dropdown
import nexplorer.scan.SavePdfScreen
import nexplorer.scan.SizeEstimate
import nexplorer.ui.OptionLabel
import nexplorer.ui.PdfTool
import nexplorer.ui.home.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

private class Result(val file: File?, val size: Long, val error: String?)

@Composable
fun CompressFlow(volumes: List<Volume>, onSaved: (File) -> Unit, onClose: () -> Unit) {
    PdfTool("Compress PDF", volumes, onClose, accept = { null }) { p, back ->
        CompressSave(p.file, p.password, volumes, onSaved, onBack = back)
    }
}

@Composable
private fun CompressSave(src: File, password: String?, volumes: List<Volume>, onSaved: (File) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val work = remember(src) { File(ctx.cacheDir, "compress").apply { deleteRecursively(); mkdirs() } }
    DisposableEffect(work) { onDispose { work.deleteRecursively() } }
    var level by remember { mutableStateOf(Level.Balanced) }
    val results = remember(src) { mutableStateMapOf<Level, Result>() }
    val done = remember(src) { Level.entries.associateWith { CompletableDeferred<Result>() } }
    val original = remember(src) { src.length() }

    LaunchedEffect(src) {
        while (true) {
            val l = level.takeIf { it !in results } ?: Level.entries.firstOrNull { it !in results } ?: break
            val r = withContext(Dispatchers.IO) {
                val out = File(work, "${l.name}.pdf")
                runCatching { PdfCompress.compress(src, password, l, out, cancelled = { !isActive }) }
                    .fold({ Result(out, out.length(), null) }, {
                        if (it is CancellationException) throw it
                        Result(null, 0, it.message ?: "Couldn't compress")
                    })
            }
            results[l] = r
            done.getValue(l).complete(r)
        }
    }

    fun label(l: Level): String {
        val r = results[l] ?: return "${l.label}  ·  working…"
        if (r.error != null) return "${l.label}  ·  ${r.error}"
        val after = if (r.size >= original * 0.98) "about the same" else SizeEstimate.label(r.size)
        return "${l.label}  ·  ${formatSize(original)} → $after"
    }

    SavePdfScreen(
        volumes = volumes,
        defaultName = "${src.nameWithoutExtension}_compressed.pdf",
        sizeOptions = emptyList(),
        note = "${src.name}  ·  ${formatSize(original)}",
        savingLabel = { "Compressing" },
        write = { file, _, _ ->
            val r = runBlocking { done.getValue(level).await() }
            val ready = r.file ?: error(r.error ?: "Couldn't compress")
            ready.copyTo(file, overwrite = false)
        },
        onSaved = onSaved,
        onBack = onBack,
    ) {
        OptionLabel("Compression")
        Dropdown(label = label(level), options = Level.entries.map(::label), onPick = { level = Level.entries[it] })
    }
}
