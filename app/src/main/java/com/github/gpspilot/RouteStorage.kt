package com.github.gpspilot

import android.content.Context
import android.net.Uri
import i
import java.io.File
import java.io.InputStream
import java.util.*


fun Context.saveRoute(uri: Uri): File? {
    val time = Date()
    val file = createNewFile(time)
    val copied = file?.let { copyUriToFile(uri, it) } ?: false
    return file.takeIf { copied }
}


/**
 * Creates empty route [File] and returns it for further operations.
 * If error occurred during route creation - `null` we be returned.
 * If route folder isn't exist - new one will be created.
 */
private fun Context.createNewFile(time: Date): File? {
    val dir = routeFolder
    if (dir?.mkdirs() == true) i { "Route dir created." }
    return dir?.append(time.routeFileName)
}

private inline val Date.routeFileName: String
    get() = format("yyyy-MM-dd'T'HH:mm:ss.SSS") + ".gpx"

private inline val Context.routeFolder: File?
    get() = getExternalFilesDir(null)?.append("routes")

private fun Context.copyUriToFile(uri: Uri, file: File): Boolean {
    return try {
        contentResolver.openInputStream(uri)?.use { input: InputStream ->
            file.apply {
                createNewFile()
                outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        true
    } catch (e: Exception) {
        e logE { "Can't copy file: $file." }
        false
    }
}