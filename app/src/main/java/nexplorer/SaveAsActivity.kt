package nexplorer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import nexplorer.data.ReaderPrefs
import nexplorer.files.FileOps
import nexplorer.files.Volumes
import nexplorer.reader.AppTheme
import nexplorer.scan.SavePdfScreen
import nexplorer.scan.ScanNames
import nexplorer.ui.home.formatSize
import nexplorer.ui.theme.NExplorerTheme
import java.io.File

class SaveAsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = Shared.from(this, intent)
        if (shared.isEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        enableEdgeToEdge()
        val prefs = ReaderPrefs(this)
        setContent {
            val appTheme by prefs.appTheme.collectAsState(initial = AppTheme.FollowSystem)
            val dark = when (appTheme) {
                AppTheme.FollowSystem -> isSystemInDarkTheme()
                AppTheme.Light -> false
                AppTheme.Dark -> true
            }
            val view = LocalView.current
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            NExplorerTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionGate {
                        val ctx = LocalContext.current
                        val volumes = remember { Volumes.list(ctx) }
                        val one = shared.singleOrNull()
                        Box(Modifier.safeDrawingPadding()) {
                            SavePdfScreen(
                                volumes = volumes,
                                defaultName = one?.name ?: "",
                                sizeOptions = emptyList(),
                                note = if (one != null) {
                                    one.size?.let(::formatSize) ?: if (one.uri == null) "Text" else ""
                                } else {
                                    severalNote(shared)
                                },
                                savingLabel = { done -> if (one != null) "Saving" else "Saving ${(done + 1).coerceAtMost(shared.size)} of ${shared.size}" },
                                write = { target, _, progress ->
                                    if (one != null) {
                                        one.writeTo(ctx, target)
                                    } else {
                                        shared.forEachIndexed { i, s ->
                                            progress(i)
                                            s.writeTo(ctx, File(target, FileOps.uniqueName(target, s.name)))
                                        }
                                    }
                                },
                                onSaved = {
                                    val folder = if (one != null) it.parent else it.path
                                    Toast.makeText(ctx, "Saved to $folder", Toast.LENGTH_SHORT).show()
                                    finish()
                                },
                                onBack = ::finish,
                                extension = "",
                                title = "Save As",
                                showName = one != null,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun severalNote(items: List<Shared>): String {
    val total = items.mapNotNull { it.size }.sum()
    val head = "${items.size} files" + if (total > 0) "  ·  ${formatSize(total)}" else ""
    val names = items.take(20).joinToString("\n") { it.name }
    val more = if (items.size > 20) "\nand ${items.size - 20} more" else ""
    return "$head\n\n$names$more"
}

private class Shared(val name: String, val size: Long?, val uri: Uri?, val text: String?) {
    fun writeTo(ctx: android.content.Context, file: java.io.File) {
        if (uri != null) {
            val input = ctx.contentResolver.openInputStream(uri) ?: error("Couldn't read the shared file")
            input.use { i -> file.outputStream().use { i.copyTo(it) } }
        } else {
            file.writeText(text.orEmpty())
        }
    }

    companion object {
        fun from(ctx: android.content.Context, intent: Intent?): List<Shared> {
            if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
                val uris = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                return uris.orEmpty().map { file(ctx, it, null) }
            }
            if (intent?.action != Intent.ACTION_SEND) return emptyList()
            val uri = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) return listOf(file(ctx, uri, intent.type))
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return emptyList()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            val title = subject?.trim()?.replace('/', '_')?.takeIf { it.isNotEmpty() } ?: "Shared text"
            return listOf(Shared(ScanNames.withExtension(title, "txt"), null, null, text))
        }

        private fun file(ctx: android.content.Context, uri: Uri, type: String?): Shared {
            var display: String? = null
            var size: Long? = null
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        display = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                        size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                    }
                }
            }
            val mime = ctx.contentResolver.getType(uri) ?: type
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            val fallback = display ?: uri.lastPathSegment?.substringAfterLast('/')
            return Shared(SharedNames.of(fallback, ext, "Shared file"), size, uri, null)
        }
    }
}

object SharedNames {
    fun of(name: String?, ext: String?, fallback: String): String {
        val n = name?.trim()?.replace('/', '_')?.takeIf { it.isNotEmpty() } ?: fallback
        val hasExt = n.lastIndexOf('.') > 0 && !n.endsWith('.')
        return if (hasExt || ext.isNullOrEmpty()) n else "$n.$ext"
    }
}
