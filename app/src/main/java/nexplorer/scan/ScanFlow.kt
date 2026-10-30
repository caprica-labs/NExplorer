package nexplorer.scan

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import nexplorer.files.Volume
import nexplorer.ui.ToolScreen
import java.io.File

enum class ScanStart { Scanner, Images }

/** Screens of the PDF maker flow. */
private sealed interface Route {
    data class Camera(val replace: Int? = null) : Route
    data object Gallery : Route
    data object Maker : Route
    data object Reorder : Route
    data class Crop(val page: Int) : Route
    data object Save : Route
}

@Composable
fun ScanFlow(start: ScanStart, volumes: List<Volume>, onSaved: (File) -> Unit, onClose: () -> Unit) {
    var pages by remember { mutableStateOf(listOf<ScanPage>()) }
    var nextId by remember { mutableLongStateOf(1L) }
    var shown by remember { mutableIntStateOf(0) }
    var route by remember { mutableStateOf<Route>(if (start == ScanStart.Scanner) Route.Camera() else Route.Gallery) }

    fun newPage(source: String) = ScanPage(nextId++, source)

    val ctx = LocalContext.current
    DisposableEffect(Unit) { onDispose { File(ctx.filesDir, "scans").deleteRecursively() } }

    fun backFromPicker() { if (pages.isEmpty()) onClose() else route = Route.Maker }

    ToolScreen(
        if (route is Route.Camera || route is Route.Crop) Color.Black else MaterialTheme.colorScheme.background,
    ) {
        when (val r = route) {
            is Route.Camera -> CameraScreen(
                onShot = { uri ->
                    val i = r.replace
                    if (i != null && i in pages.indices) {
                        pages = pages.toMutableList().also { it[i] = ScanPage(nextId++, uri, filter = it[i].filter) }
                        shown = i
                    } else {
                        pages = pages + newPage(uri)
                        shown = pages.lastIndex
                    }
                    route = Route.Maker
                },
                onBack = { if (r.replace != null) route = Route.Maker else backFromPicker() },
            )
            Route.Gallery -> GalleryScreen(
                onCamera = { route = Route.Camera() },
                onDone = { picked ->
                    val first = pages.size
                    pages = pages + picked.map(::newPage)
                    shown = first
                    route = Route.Maker
                },
                onBack = ::backFromPicker,
            )
            Route.Maker -> MakerScreen(
                pages = pages,
                startPage = shown,
                onPages = { pages = it },
                onPageShown = { shown = it },
                onImport = { route = Route.Gallery },
                onCamera = { route = Route.Camera() },
                onRetake = { route = Route.Camera(replace = it) },
                onReorder = { route = Route.Reorder },
                onCrop = { route = Route.Crop(it) },
                onNext = { route = Route.Save },
                onDiscard = onClose,
            )
            Route.Reorder -> ReorderScreen(
                pages = pages,
                onSave = { pages = it; route = Route.Maker },
                onBack = { route = Route.Maker },
            )
            is Route.Crop -> pages.getOrNull(r.page)?.let { page ->
                CropScreen(
                    page = page,
                    onDone = { q ->
                        pages = pages.toMutableList().also { it[r.page] = page.copy(crop = q) }
                        route = Route.Maker
                    },
                    onBack = { route = Route.Maker },
                )
            }
            Route.Save -> SaveScreen(pages, volumes, onSaved = onSaved, onBack = { route = Route.Maker })
        }
    }
}
