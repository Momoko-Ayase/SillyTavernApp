package moe.momokko.sillytavernapp

import android.content.Context
import java.io.File

object AssetExtractor {
    private const val PREFS = "ST_PREFS"
    private const val KEY_UPDATE = "apk_last_update"

    /** Returns the absolute path to the extracted nodejs-project dir. */
    fun ensureProject(context: Context): String {
        val dir = File(context.filesDir, "nodejs-project")
        if (wasApkUpdated(context) || !dir.exists()) {
            if (dir.exists()) dir.deleteRecursively()
            copyAsset(context, "nodejs-project", dir)
            saveUpdateTime(context)
        }
        return dir.absolutePath
    }

    private fun copyAsset(context: Context, fromPath: String, to: File) {
        val am = context.assets
        val children = am.list(fromPath) ?: arrayOf()
        if (children.isEmpty()) {
            // An empty list means either a file or an empty directory — AssetManager
            // gives no isDirectory. Opening a directory throws, so fall back to mkdirs.
            try {
                am.open(fromPath).use { input ->
                    to.outputStream().use { out -> input.copyTo(out) }
                }
            } catch (_: java.io.FileNotFoundException) {
                to.mkdirs()
            }
        } else {
            to.mkdirs()
            for (child in children) copyAsset(context, "$fromPath/$child", File(to, child))
        }
    }

    private fun currentUpdateTime(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    private fun wasApkUpdated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_UPDATE, 0L) != currentUpdateTime(context)
    }

    private fun saveUpdateTime(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_UPDATE, currentUpdateTime(context)).apply()
    }
}
