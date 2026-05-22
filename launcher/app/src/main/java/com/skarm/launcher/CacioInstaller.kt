package com.skarm.launcher

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Stages caciocavallo (cacio-shared + cacio-tta) onto internal storage. These
 * provide a non-X11 AWT toolkit (CTCToolkit/CTCGraphicsEnvironment) so SK's AWT
 * calls — cursors, conversation/menu dialogs, clipboard — work without a display.
 * The jars go on the game JVM's bootclasspath (java.desktop must see the toolkit),
 * and cacio-tta doubles as the -javaagent that preloads them; see the JVM wiring
 * in sklauncher.c.
 *
 * Built from the caciocavallo17 submodule via scripts/build-cacio.sh. Always
 * re-staged (like sk-bootstrap.jar) so a rebuilt jar propagates on next launch.
 */
object CacioInstaller {

    private const val TAG = "CacioInstaller"
    private const val DIR_NAME = "cacio"
    private val JARS = listOf("cacio-shared.jar", "cacio-tta.jar")

    fun homeDir(context: Context): File = File(context.filesDir, DIR_NAME)
    fun sharedJar(context: Context): File = File(homeDir(context), "cacio-shared.jar")
    fun ttaJar(context: Context): File = File(homeDir(context), "cacio-tta.jar")

    /** Re-stages the cacio jars from assets and returns the install dir. */
    fun stage(context: Context): File {
        val home = homeDir(context)
        home.mkdirs()
        for (name in JARS) {
            val target = File(home, name)
            context.assets.open("$DIR_NAME/$name").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            target.setReadable(true, false)
        }
        Log.i(TAG, "Staged cacio jars at $home (${JARS.joinToString()})")
        return home
    }
}
