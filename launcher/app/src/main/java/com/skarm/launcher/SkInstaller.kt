package com.skarm.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bootstraps the Spiral Knights getdown layout under filesDir/sk/:
 */
object SkInstaller {

    private const val TAG = "SkInstaller"
    private const val DIR_NAME = "sk"
    private const val GETDOWN_PRO_ASSET = "sk/getdown-pro.jar"
    private const val SK_BOOTSTRAP_ASSET = "sk/sk-bootstrap.jar"
    private const val MANIFEST_URL = "http://gamemedia2.spiralknights.com/spiral/latest/getdown.txt"

    fun homeDir(context: Context): File = File(context.filesDir, DIR_NAME)
    fun codeDir(context: Context): File = File(homeDir(context), "code")
    fun tmpDir(context: Context): File = File(homeDir(context), "tmp")
    fun getdownJar(context: Context): File = File(codeDir(context), "getdown-pro.jar")
    fun bootstrapJar(context: Context): File = File(codeDir(context), "sk-bootstrap.jar")
    fun manifest(context: Context): File = File(homeDir(context), "getdown.txt")

    fun bootstrapClasspath(context: Context): String = listOf(
        bootstrapJar(context).absolutePath,
        getdownJar(context).absolutePath,
    ).joinToString(":")

    fun isBootstrapped(context: Context): Boolean =
        getdownJar(context).isFile && bootstrapJar(context).isFile && manifest(context).isFile

    fun bootstrap(context: Context, onProgress: (String) -> Unit = {}) {
        codeDir(context).mkdirs()
        tmpDir(context).mkdirs() // java.io.tmpdir target for the FCL JVM

        if (!getdownJar(context).isFile) {
            onProgress("Staging getdown-pro.jar…")
            context.assets.open(GETDOWN_PRO_ASSET).use { input ->
                getdownJar(context).outputStream().use { out -> input.copyTo(out) }
            }
            Log.i(TAG, "Staged getdown-pro.jar (${getdownJar(context).length()} bytes)")
        }

        // Always re-stage sk-bootstrap.jar so updates are easy when needed
        onProgress("Staging sk-bootstrap.jar…")
        context.assets.open(SK_BOOTSTRAP_ASSET).use { input ->
            bootstrapJar(context).outputStream().use { out -> input.copyTo(out) }
        }
        Log.i(TAG, "Staged sk-bootstrap.jar (${bootstrapJar(context).length()} bytes)")

        if (!manifest(context).isFile) {
            onProgress("Fetching SK manifest…")
            downloadManifest(context)
        }
    }

    private fun downloadManifest(context: Context) {
        val url = URL(MANIFEST_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                error("manifest fetch failed: HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                manifest(context).outputStream().use { out -> input.copyTo(out) }
            }
            Log.i(TAG, "Fetched getdown.txt (${manifest(context).length()} bytes) from $MANIFEST_URL")
        } finally {
            conn.disconnect()
        }
    }
}
