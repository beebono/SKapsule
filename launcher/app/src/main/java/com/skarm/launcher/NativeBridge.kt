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
     */
    external fun startJvm(jreHome: String, classpath: String, libPath: String, appFiles: String)

    /** Launches Spiral Knights inside the running JVM. (Stub) */
    external fun launchGame(gameDir: String, loginMode: String)
}
