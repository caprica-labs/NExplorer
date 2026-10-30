package nexplorer.ui.home

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import nexplorer.files.Share
import java.io.File

fun zipForSharing(ctx: Context, folder: File): File {
    val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
    val pictures = folder.listFiles().orEmpty().filter { it.isFile && Share.mimeFor(it.name).startsWith("image/") }.sortedBy { it.name }
    return File(dir, "${folder.name}.zip").also { Share.zip(pictures, it) }
}

fun shareFiles(ctx: Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris: List<Uri> = runCatching {
        files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }
    }.getOrElse {
        Toast.makeText(ctx, "These files can't be shared", Toast.LENGTH_SHORT).show()
        return
    }
    val type = Share.commonType(files.map { Share.mimeFor(it.name) })
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.apply {
        setType(type)
        clipData = ClipData.newRawUri(null, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val title = if (files.size == 1) "Share ${files[0].name}" else "Share ${files.size} files"
    ctx.startActivity(Intent.createChooser(send, title))
}

fun mimeOf(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: Share.mimeFor(file.name).takeIf { it != "application/octet-stream" }
        ?: "*/*"

fun openWithApp(ctx: Context, file: File, choose: Boolean): Boolean {
    val uri = runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file) }.getOrElse {
        Toast.makeText(ctx, "This file can't be opened by other apps", Toast.LENGTH_SHORT).show()
        return false
    }
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    return try {
        ctx.startActivity(if (choose) Intent.createChooser(view, "Open with") else view)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app on this phone can open this file", Toast.LENGTH_SHORT).show()
        false
    }
}
