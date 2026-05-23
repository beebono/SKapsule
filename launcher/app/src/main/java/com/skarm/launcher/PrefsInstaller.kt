package com.skarm.launcher

import android.util.Log
import java.io.File

/**
 * Seeds default SK preferences on a fresh install.
 *
 * SK persists options via java.util.prefs in a node named "projectx" (confirmed by
 * reading the file SK itself writes). We pre-write a few keys before the JVM starts
 * so a clean install comes up sensibly:
 *  - compatibility_mode=true + render_quality=LOW: gl4es looks best here, and LOW
 *    also hides the character-shadow artifact.
 *  - cull_transients_prod=true: SK's own default for prod; keep it.
 *  - anonymous_logon=false: don't auto-enter Guest Mode, so the player can log in
 *    with a web account from the install instead of sitting through the Guest intro,
 *    logging out, then logging back in.
 * We only seed when the node's prefs.xml doesn't exist yet, so a returning player's
 * own choices are never overwritten.
 */
object PrefsInstaller {

    private const val TAG = "PrefsInstaller"

    // Exact format SK/java.util.prefs reads (matches what FileSystemPreferences
    // writes, incl. its alphabetical-by-key ordering).
    private val DEFAULT_PROJECTX_PREFS = """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">
        <map MAP_XML_VERSION="1.0">
          <entry key="anonymous_logon" value="false"/>
          <entry key="compatibility_mode" value="true"/>
          <entry key="cull_transients_prod" value="true"/>
          <entry key="render_quality" value="LOW"/>
        </map>
    """.trimIndent() + "\n"

    /**
     * @param userPrefsRoot the java.util.prefs user-root node dir (the dir that
     *   directly contains per-node subdirs like "projectx"). Must match
     *   `-Djava.util.prefs.userRoot` in sklauncher.c plus the JDK's appended
     *   `.java/.userPrefs` segment.
     */
    fun seedDefaults(userPrefsRoot: File) {
        val prefsXml = File(userPrefsRoot, "projectx/prefs.xml")
        if (prefsXml.exists()) return // returning player — never clobber their settings
        try {
            prefsXml.parentFile?.mkdirs()
            prefsXml.writeText(DEFAULT_PROJECTX_PREFS)
            Log.i(TAG, "Seeded default projectx prefs (compatibility_mode, render_quality=LOW, " +
                "cull_transients_prod, anonymous_logon=false)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to seed default projectx prefs", t)
        }
    }
}
