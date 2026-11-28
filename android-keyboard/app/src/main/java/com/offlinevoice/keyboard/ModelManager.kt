package com.offlinevoice.keyboard

import android.content.Context
import java.io.File

/**
 * The Whisper model ships inside the APK. whisper.cpp needs a real file path,
 * so it is copied out of the APK once, on first use.
 */
object ModelManager {
    private const val ASSET_DIR = "models"

    val modelName: String get() = BuildConfig.MODEL_FILE

    fun ensureModelFile(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val target = File(dir, modelName)
        val assetPath = "$ASSET_DIR/$modelName"
        val expectedSize = context.assets.openFd(assetPath).use { it.length }
        if (target.exists() && target.length() == expectedSize) return target

        // Remove models left over from older versions of the app.
        dir.listFiles()?.filter { it.name != modelName }?.forEach { it.delete() }

        val tmp = File(dir, "$modelName.part")
        context.assets.open(assetPath).use { input ->
            tmp.outputStream().use { input.copyTo(it, bufferSize = 1 shl 20) }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Could not save the speech model")
        }
        return target
    }
}
