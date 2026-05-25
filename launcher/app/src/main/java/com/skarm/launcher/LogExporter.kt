package com.skarm.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-scoped diagnostic logging with no ADB/USB required.
 *
 * Android only lets an app read its *own* UID's logcat output (since API 16),
 * which is exactly what we want: [capture] runs `logcat -d` from inside the app
 * and gets every line the launcher, the :game JVM, and SK itself emitted —
 * including the JVM's stdout/stderr, which sklauncher.c funnels into logcat
 * under the sk-stdout / sk-stderr tags. The dump is written to a file and handed
 * to the system share sheet ([share]) so a remote user can send it to us.
 *
 * Files live in cacheDir/logs, which is per-app (shared by both processes) and
 * served to the share target by the FileProvider declared in the manifest.
 */
object LogExporter {

    private const val TAG = "LogExporter"
    private const val LOG_SUBDIR = "logs"
    private const val MANUAL_PREFIX = "log"
    private const val CRASH_PREFIX = "crash"
    private const val KEEP_FILES = 8          // prune older dumps beyond this
    private const val LAUNCH_MARKER = ".launch_attempted"

    private val authority = { ctx: Context -> "${ctx.packageName}.fileprovider" }

    /** Directory holding captured dumps; created on demand. */
    private fun logDir(context: Context): File =
        File(context.cacheDir, LOG_SUBDIR).apply { mkdirs() }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /**
     * Dumps this app's logcat to a freshly-named file under cacheDir/logs and
     * returns it, or null if the dump produced nothing / failed. [prefix]
     * distinguishes manual exports from auto-saved crashes.
     */
    fun capture(context: Context, prefix: String = MANUAL_PREFIX): File? = try {
        val out = File(logDir(context), "$prefix-${timestamp()}.txt")
        // -d: dump the buffer and exit (don't stream). -v threadtime: timestamp +
        // pid/tid per line. Reads only our UID's lines — no permission needed.
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime")
            .redirectErrorStream(true)
            .start()
        out.outputStream().use { sink -> process.inputStream.copyTo(sink) }
        process.waitFor()
        prune(context)
        if (out.length() > 0L) out else { out.delete(); null }
    } catch (t: Throwable) {
        Log.e(TAG, "logcat capture failed", t)
        null
    }

    /** Fires the system share sheet for a captured [file]. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_logs_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.share_logs_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Copies a captured [file] into a user-chosen [dest] (a content:// URI from
     * the Storage Access Framework's CreateDocument picker). The system owns the
     * write, so no storage permission is needed. Returns true on success.
     */
    fun copyToUri(context: Context, file: File, dest: Uri): Boolean = try {
        context.contentResolver.openOutputStream(dest)?.use { sink ->
            file.inputStream().use { it.copyTo(sink) }
        } ?: error("openOutputStream returned null for $dest")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "save-to-document failed", t)
        false
    }

    /** Capture + share in one step, toasting if there's nothing to send. */
    fun captureAndShare(context: Context) {
        val file = capture(context)
        if (file == null) {
            Toast.makeText(context, R.string.share_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        share(context, file)
    }

    // --- launch-attempt marker (cross-process via a file in filesDir) ---

    /**
     * Records that a JVM launch was attempted, so the launcher knows there's
     * something worth sharing. Written by the :game process; read by the
     * launcher process — a marker file is the reliable cross-process channel
     * (multi-process SharedPreferences is deprecated/unreliable).
     */
    fun markLaunchAttempted(context: Context) {
        runCatching { File(context.filesDir, LAUNCH_MARKER).createNewFile() }
    }

    fun wasLaunchAttempted(context: Context): Boolean =
        File(context.filesDir, LAUNCH_MARKER).exists()

    // --- crash dumps (auto-saved by SkApplication's crash handler) ---

    /** Captures a dump tagged as a crash; used by the uncaught-exception handler. */
    fun captureCrash(context: Context): File? = capture(context, CRASH_PREFIX)

    /** Newest auto-saved crash dump, if any, for the launcher to offer on next start. */
    fun latestCrash(context: Context): File? =
        logDir(context).listFiles { f -> f.name.startsWith("$CRASH_PREFIX-") }
            ?.maxByOrNull { it.lastModified() }

    /** Keep only the most recent [KEEP_FILES] dumps to bound cache growth. */
    private fun prune(context: Context) {
        logDir(context).listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_FILES)
            ?.forEach { it.delete() }
    }
}
