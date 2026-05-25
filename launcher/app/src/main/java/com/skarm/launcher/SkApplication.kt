package com.skarm.launcher

import android.app.Application
import android.util.Log

/**
 * Process-wide setup. Application.onCreate runs once in *every* process, so this
 * installs the crash handler in both the launcher and the :game JVM host — any
 * uncaught exception in either auto-saves a logcat dump that the launcher offers
 * to share on next start (see LauncherActivity / LogExporter).
 */
class SkApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    /**
     * Captures a diagnostic log when a thread dies from an uncaught exception,
     * then lets the platform's default handling run (so Android still shows the
     * crash and tears the process down). Chaining is the subtle part: the JVM
     * captured the previous default handler before we replaced it.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", error)
            try {
                LogExporter.captureCrash(this)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to generate crash log", t)
            }
            previous?.uncaughtException(thread, error) ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private companion object {
        const val TAG = "SkApplication"
    }
}
