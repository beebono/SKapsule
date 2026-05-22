package com.skarm.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Stages LWJGL 3.4.1 (AngelAuraMC Android build) onto internal storage.
 */
object LwjglInstaller {

    private const val TAG = "LwjglInstaller"
    private const val DIR_NAME = "lwjgl"
    private const val STAMP_NAME = ".version"
    private const val NATIVES_ASSET = "lwjgl/lwjgl-3.4.1-android-natives-arm64.zip"
    private const val MODULES_ASSET = "lwjgl/lwjgl-3.4.1-android-modules.zip"
    private const val VERSION = "3.4.1-aam-2026-05-21"

    fun homeDir(context: Context): File = File(context.filesDir, DIR_NAME)
    fun libDir(context: Context): File = File(homeDir(context), "lib")
    fun jarsDir(context: Context): File = File(homeDir(context), "jars")

    fun isInstalled(context: Context): Boolean {
        val stamp = File(homeDir(context), STAMP_NAME)
        if (!stamp.isFile) return false
        return stamp.readText().trim() == VERSION
    }

    /** Returns a JVM-style classpath of all staged jars, colon-separated. */
    fun classpath(context: Context): String =
        jarsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".jar") }
            ?.sortedBy { it.name }
            ?.joinToString(":") { it.absolutePath }
            ?: ""

    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        val home = homeDir(context)
        if (home.exists()) home.deleteRecursively()
        libDir(context).mkdirs()
        jarsDir(context).mkdirs()

        onProgress("Unpacking LWJGL natives…")
        extractFlatZip(context, NATIVES_ASSET, libDir(context)) { name ->
            if (name.endsWith(".so")) name.substringAfterLast('/') else null
        }

        onProgress("Unpacking LWJGL modules…")
        extractFlatZip(context, MODULES_ASSET, jarsDir(context)) { name ->
            // Modules zip nests jars under per-module dirs...
            // Flatten and skip license txts.
            if (name.endsWith(".jar")) name.substringAfterLast('/') else null
        }

        File(home, STAMP_NAME).writeText(VERSION)
        onProgress("LWJGL ready.")
        Log.i(TAG, "LWJGL $VERSION staged at $home; jars=${jarsDir(context).list()?.size}, natives=${libDir(context).list()?.size}")
    }

    private fun extractFlatZip(
        context: Context,
        assetPath: String,
        into: File,
        nameFor: (String) -> String?,
    ) {
        context.assets.open(assetPath).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val outName = nameFor(entry.name)
                    if (outName == null) { zip.closeEntry(); continue }

                    val target = File(into, outName)
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    target.setReadable(true, false)
                    if (outName.endsWith(".so")) target.setExecutable(true, false)
                    zip.closeEntry()
                }
            }
        }
    }
}
