package com.offlinevoice.keyboard

import android.content.Context
import java.io.File

/**
 * The Whisper models ship inside the APK and are normally read from it in
 * place. Copying a model out to a file is kept only as a fallback.
 */
object ModelManager {
    private const val ASSET_DIR = "models"

    val englishModel: String get() = BuildConfig.MODEL_ENGLISH
    val multilingualModel: String get() = BuildConfig.MODEL_MULTILINGUAL
    private val allModels get() = setOf(englishModel, multilingualModel)

    fun assetPath(name: String) = "$ASSET_DIR/$name"

    /**
     * Removes a copied model file (older versions copied models out of the
     * APK; they are now read in place, so the copy only wastes space).
     */
    @Synchronized
    fun deleteCopy(context: Context, name: String) {
        val dir = File(context.filesDir, ASSET_DIR)
        File(dir, name).delete()
        File(dir, "$name.part").delete()
        dir.listFiles()?.filter { it.name !in allModels }?.forEach { it.delete() }
    }

    /** Fallback: copies the model out of the APK once, for file-based loading. */
    @Synchronized
    fun ensureModelFile(context: Context, name: String): File {
        require(name in allModels) { "Unknown model $name" }
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val target = File(dir, name)
        val assetPath = "$ASSET_DIR/$name"
        val expectedSize = context.assets.openFd(assetPath).use { it.length }
        if (target.exists() && target.length() == expectedSize) return target

        // Remove models left over from older versions of the app.
        dir.listFiles()?.filter { it.name !in allModels && !it.name.endsWith(".part") }?.forEach { it.delete() }

        val tmp = File(dir, "$name.part")
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
