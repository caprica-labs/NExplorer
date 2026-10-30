package nexplorer.scan

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.ui.Placeholder
import nexplorer.ui.theme.AccentBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryImage(val uri: String, val bucketId: String, val bucket: String)

private fun loadImages(ctx: Context): List<GalleryImage> {
    val out = ArrayList<GalleryImage>()
    val cols = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )
    runCatching {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(cols[0])
            val bid = c.getColumnIndexOrThrow(cols[1])
            val bname = c.getColumnIndexOrThrow(cols[2])
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(id))
                out.add(GalleryImage(uri.toString(), c.getString(bid) ?: "", c.getString(bname) ?: "Other"))
            }
        }
    }
    return out
}

private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
fun Thumbnail(uri: String, modifier: Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf(thumbs.get(uri)) }
    LaunchedEffect(uri) {
        if (bmp != null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            val u = Uri.parse(uri)
            val camera = u.scheme == "content" && ctx.contentResolver.getType(u) in setOf("image/jpeg", "image/heic", "image/heif")
            val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && camera) {
                runCatching { ctx.contentResolver.loadThumbnail(u, Size(320, 320), null) }.getOrNull()
            } else null
            (system ?: ScanImaging.loadOriginal(ctx, uri, 320))?.also { thumbs.put(uri, it) }
        }
    }
    Box(modifier.background(Color(0x22888888))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun GalleryScreen(onCamera: () -> Unit, onDone: (List<String>) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var images by remember { mutableStateOf<List<GalleryImage>?>(null) }
    var bucket by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var picks by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) { images = withContext(Dispatchers.IO) { loadImages(ctx) } }

    val all = images.orEmpty()
    val buckets = remember(all) {
        all.groupBy { it.bucketId }.map { (id, list) -> Triple(id, list.first().bucket, list.size) }.sortedBy { it.second.lowercase() }
    }
    val shown = if (bucket == null) all else all.filter { it.bucketId == bucket }
    val bucketName = buckets.firstOrNull { it.first == bucket }?.second ?: "All photos"

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { menu = true }) {
                    Text(bucketName, color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ArrowDropDown, "Choose folder", tint = MaterialTheme.colorScheme.onBackground)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("All photos (${all.size})") }, onClick = { bucket = null; menu = false })
                    buckets.forEach { (id, name, n) ->
                        DropdownMenuItem(text = { Text("$name ($n)") }, onClick = { bucket = id; menu = false })
                    }
                }
            }
            TextButton(onClick = { onDone(picks) }, enabled = picks.isNotEmpty()) {
                Text(if (picks.isEmpty()) "Next" else "Next (${picks.size})", color = if (picks.isEmpty()) Color.Gray else AccentBlue)
            }
        }
        if (images == null) {
            Placeholder("Loading photos")
        } else {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize()) {
                item {
                    Box(
                        Modifier.aspectRatio(1f).padding(1.dp).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onCamera),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, "Take a photo", modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(shown, key = { it.uri }) { img ->
                    val n = picks.indexOf(img.uri) + 1
                    Box(Modifier.aspectRatio(1f).padding(1.dp).clickable { picks = Lists.togglePick(picks, img.uri) }) {
                        Thumbnail(img.uri, Modifier.fillMaxSize())
                        if (n > 0) Box(Modifier.fillMaxSize().border(3.dp, AccentBlue))
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape)
                                .background(if (n > 0) AccentBlue else Color.Black.copy(alpha = 0.25f))
                                .border(1.5.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (n > 0) Text("$n", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
