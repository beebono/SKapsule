package com.skarm.launcher

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Stages frenchpress (the com.threerings.froth.* Steam-login shim) onto internal
 * storage. The jar is placed BEFORE projectx-pcode.jar on SK's runtime classloader
 * (SkBootstrap, via -Dfrenchpress.jar) so its SteamAPI/froth classes shadow the
 * froth-foamy ones bundled in SK.
 *
 * frenchpress is activated only in Steam mode (GameActivity passes its jar path then,
 * via -Dfrenchpress.jar). Web mode runs WITHOUT it on the classpath, so an explicit
 * Play(Web) always does web login even when a Steam refresh token is stored on disk —
 * frenchpress's stored token has priority over the empty-username web fallback, so
 * loading it for Web would silently re-use Steam. The jar is still staged on first use.
 *
 * Built from the frenchpress submodule via scripts/build-frenchpress-android.sh.
 * Always re-staged (like cacio/sk-bootstrap) so a rebuilt jar propagates next launch.
 */
object FrenchpressInstaller {

    private const val TAG = "FrenchpressInstaller"
    private const val DIR_NAME = "frenchpress"
    private const val JAR_NAME = "frenchpress.jar"

    fun homeDir(context: Context): File = File(context.filesDir, DIR_NAME)
    fun jar(context: Context): File = File(homeDir(context), JAR_NAME)

    /**
     * The Steam refresh-token store (frenchpress FileCredentialStore). Lives under
     * the JVM's user.home (filesDir/home, see sklauncher.c) — outside the getdown sk/
     * tree so updates can't wipe it. Pointed at via FRENCHPRESS_CRED_FILE. Its presence
     * is how the launcher decides whether a first-login credential prompt is needed.
     */
    fun credFile(context: Context): File =
        File(context.filesDir, "home/$DIR_NAME/steam.creds")

    /** Re-stages frenchpress.jar from assets and returns the install dir. */
    fun stage(context: Context): File {
        val home = homeDir(context)
        home.mkdirs()
        val target = jar(context)
        context.assets.open("$DIR_NAME/$JAR_NAME").use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        target.setReadable(true, false)
        Log.i(TAG, "Staged frenchpress jar at $target")
        return home
    }
}
