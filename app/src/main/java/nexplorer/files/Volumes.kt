package nexplorer.files

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class Volume(val label: String, val root: File)

object Volumes {
    fun list(ctx: Context): List<Volume> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val internal = Volume("Internal storage", Environment.getExternalStorageDirectory())
        val others = runCatching {
            sm.storageVolumes
                .filter { !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { v ->
                    val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        v.directory
                    } else {
                        runCatching { v.javaClass.getMethod("getPathFile").invoke(v) as File }.getOrNull()
                    }
                    dir?.takeIf { it.canRead() }?.let { Volume(v.getDescription(ctx) ?: it.name, it) }
                }
        }.getOrDefault(emptyList())
        return listOf(internal) + others
    }
}
