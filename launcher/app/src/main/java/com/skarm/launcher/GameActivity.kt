package com.skarm.launcher

import android.app.AlertDialog
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.skarm.launcher.databinding.ActivityGameBinding
import java.io.File

/**
 * In-game host. Owns the SurfaceView that the EGL/GLES2 context will be
 * created on. Hosts SK via the embedded JRE 25; rendering is routed through
 * libgl4es.so. Single visible UI element is a small "Exit" button.
 */
class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityGameBinding
    private lateinit var surface: SurfaceView
    private var jvmKicked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        surface = binding.gameSurface
        surface.holder.addCallback(this)

        binding.btnExit.setOnClickListener { confirmExit() }

        // TODO: read EXTRA_LOGIN_MODE; pass through to the JVM bootstrap once
        // we're invoking SK rather than smoke-testing.
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setMessage(R.string.exit_confirm_message)
            .setPositiveButton(R.string.exit_confirm_yes) { _, _ -> finishAndRemoveTask() }
            .setNegativeButton(R.string.exit_confirm_no, null)
            .show()
    }

    // --- SurfaceHolder.Callback ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeBridge.onSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeBridge.onSurfaceChanged(width, height)
        if (!jvmKicked) {
            jvmKicked = true
            // java.library.path = JRE's lib (libjvm, libGL via gl4es) + LWJGL natives
            val libPath = listOf(
                File(JreInstaller.homeDir(this), "lib").absolutePath,
                LwjglInstaller.libDir(this).absolutePath,
            ).joinToString(":")
            // Classpath: SK bootstrap (getdown + sk-bootstrap) first, then LWJGL.
            val classpath = listOf(
                SkInstaller.bootstrapClasspath(this),
                LwjglInstaller.classpath(this),
            ).filter { it.isNotEmpty() }.joinToString(":")
            NativeBridge.startJvm(
                jreHome = JreInstaller.homeDir(this).absolutePath,
                classpath = classpath,
                libPath = libPath,
                appFiles = filesDir.absolutePath,
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeBridge.onSurfaceDestroyed()
    }
}
