package com.skarm.launcher

import android.view.Surface

/**
 * Single entry point into the native side.
 */
object NativeBridge {

    init {
        // Load gl4es first so libsklauncher's dlopen("libgl4es.so") just
        // returns the existing handle. System.loadLibrary picks the right
        // ABI variant from jniLibs/.
        System.loadLibrary("gl4es")
        System.loadLibrary("sklauncher")
    }

    external fun onSurfaceCreated(surface: Surface)
    external fun onSurfaceChanged(width: Int, height: Int)
    external fun onSurfaceDestroyed()

    /**
     * Spins up the JVM on its own pthread and returns immediately since JVM init happens async.
     * @param cacioDir directory holding the staged cacio jars (AWT bridge); empty to disable.
     * @param frenchpressJar path to frenchpress.jar; prepended to SK's classpath (Steam-login
     *   shim). Empty to disable.
     * @param credFile path to frenchpress's Steam refresh-token store (FRENCHPRESS_CRED_FILE).
     * @param steamUser,steamPass Steam credentials for a first login (env vars). Empty = web
     *   login (frenchpress treats an empty username as a web account).
     */
    external fun startJvm(
        jreHome: String,
        classpath: String,
        libPath: String,
        appFiles: String,
        cacioDir: String,
        frenchpressJar: String,
        credFile: String,
        steamUser: String,
        steamPass: String,
    )

    /** Launches Spiral Knights inside the running JVM. (Stub) */
    external fun launchGame(gameDir: String, loginMode: String)

    /**
     * Forwards a touch as a mouse event into the native input queue.
     * @param action 0=down, 1=move, 2=up
     * @param x,y framebuffer pixels (y-down, top-left origin)
     */
    external fun onTouchEvent(action: Int, x: Int, y: Int)

    /** Marks a controller connected/disconnected. Disconnect zeroes held state. */
    external fun onGamepadConnected(connected: Boolean)

    /** Sets a button in the GLFW standard layout (index 0..14). */
    external fun onGamepadButton(index: Int, pressed: Boolean)

    /** Sets an axis in the GLFW standard layout (index 0..5), value already normalized. */
    external fun onGamepadAxis(index: Int, value: Float)

    /** A key transition. key = GLFW keycode; action 1=press, 2=repeat, 0=release. */
    external fun onKeyEvent(key: Int, action: Int, mods: Int)

    /** A typed character (Unicode codepoint) for text entry. */
    external fun onCharInput(codepoint: Int)
}
