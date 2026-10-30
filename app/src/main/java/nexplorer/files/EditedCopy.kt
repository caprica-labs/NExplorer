package nexplorer.files

import java.io.File

object EditedCopy {
    const val SUFFIX = "_EditedByNExplorer"

    fun isEditedCopy(file: File): Boolean = file.nameWithoutExtension.endsWith(SUFFIX)

    fun target(original: File, folder: File): File {
        val base = original.nameWithoutExtension
        val ext = original.extension.ifEmpty { "pdf" }
        var f = File(folder, "$base$SUFFIX.$ext")
        var n = 2
        while (f.exists()) f = File(folder, "$base (${n++})$SUFFIX.$ext")
        return f
    }
}
