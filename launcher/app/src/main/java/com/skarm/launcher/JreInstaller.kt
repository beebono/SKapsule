package com.skarm.launcher

import android.content.Context
import android.os.Build
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Extracts the bundled JRE 25 (FCL-Team multiarch build) onto internal storage.
 */
object JreInstaller {

    private const val TAG = "JreInstaller"
    private const val DIR_NAME = "jre25"
    private const val STAMP_NAME = ".version"
    private const val ASSET_DIR = "jre25"

    fun homeDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** Path to libjvm.so once installed. */
    fun libjvmPath(context: Context): File =
        File(homeDir(context), "lib/server/libjvm.so")

    fun isInstalled(context: Context): Boolean {
        val home = homeDir(context)
        val stamp = File(home, STAMP_NAME)
        if (!stamp.isFile) return false
        if (!libjvmPath(context).isFile) return false
        val have = stamp.readText().trim()
        val want = bundledVersion(context)
        return have == want
    }

    private fun bundledVersion(context: Context): String =
        context.assets.open("$ASSET_DIR/version").use { it.bufferedReader().readText().trim() }

    private fun archAssetName(): String {
        // arm64-v8a only. abiFilters prevents installs on non-arm64 devices,
        // so if we somehow get here on a different ABI, it's a real problem.
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        if (abi != "arm64-v8a") {
            error("Unsupported ABI: $abi (only arm64-v8a is supported)")
        }
        return "bin-arm64.tar.xz"
    }

    /**
     * Installs (or reinstalls) the JRE. Safe to call from a background thread.
     * Progress lines are reported via [onProgress] for the UI to surface.
     */
    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        val home = homeDir(context)
        if (home.exists()) {
            onProgress("Clearing previous runtime…")
            home.deleteRecursively()
        }
        home.mkdirs()

        onProgress("Unpacking JRE base…")
        extractAsset(context, "$ASSET_DIR/universal.tar.xz", home)

        val archAsset = archAssetName()
        onProgress("Unpacking JRE native ($archAsset)…")
        extractAsset(context, "$ASSET_DIR/$archAsset", home)

        markExecutable(File(home, "bin"))
        markExecutable(File(home, "lib/server/libjvm.so"))
        markExecutable(File(home, "lib/jspawnhelper"))

        onProgress("Staging libGL.so…")
        stageLibGL(context, home)

        File(home, STAMP_NAME).writeText(bundledVersion(context))
        onProgress("Runtime ready.")
        Log.i(TAG, "JRE 25 installed at $home; libjvm exists=${libjvmPath(context).exists()}")
    }

    private fun extractAsset(context: Context, assetPath: String, into: File) {
        context.assets.open(assetPath).use { raw ->
            BufferedInputStream(raw).use { buf ->
                XZInputStream(buf).use { xz ->
                    TarArchiveInputStream(xz).use { tar -> extractTar(tar, into) }
                }
            }
        }
    }

    private fun extractTar(tar: TarArchiveInputStream, into: File) {
        val rootPath = into.canonicalPath + File.separator
        while (true) {
            val entry = tar.nextEntry ?: break
            // Strip the leading "./" that some tar producers prepend.
            val name = entry.name.removePrefix("./").removePrefix("/")
            if (name.isEmpty()) continue

            val target = File(into, name).canonicalFile
            if (!target.path.startsWith(rootPath)) {
                error("tar entry escapes target dir: ${entry.name}")
            }

            if (entry.isDirectory) {
                target.mkdirs()
                continue
            }
            if (entry.isSymbolicLink) {
                // Java doesn't ship symlink creation in stdlib without nio...
                java.nio.file.Files.createSymbolicLink(
                    target.toPath(),
                    java.nio.file.Paths.get(entry.linkName)
                )
                continue
            }

            target.parentFile?.mkdirs()
            FileOutputStream(target).use { copy(tar, it) }

            // Preserve the executable bit from the archive's unix mode.
            if (entry.mode and 0b001_001_001 != 0) {
                target.setExecutable(true, false)
            }
        }
    }

    private fun stageLibGL(context: Context, home: File) {
        val source = File(context.applicationInfo.nativeLibraryDir, "libgl4es.so")
        if (!source.isFile) {
            error("libgl4es.so not found in app nativeLibraryDir at ${source.path}")
        }
        val target = File(home, "lib/libGL.so")
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        target.setReadable(true, false)
        target.setExecutable(true, false)
    }

    private fun markExecutable(path: File) {
        if (!path.exists()) return
        if (path.isDirectory) {
            path.listFiles()?.forEach { markExecutable(it) }
        } else {
            path.setExecutable(true, false)
        }
    }

    private fun copy(input: InputStream, output: FileOutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
        }
    }
}
