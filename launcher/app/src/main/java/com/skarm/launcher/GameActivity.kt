package com.skarm.launcher

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.skarm.launcher.databinding.ActivityGameBinding
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * In-game host. Owns the SurfaceView that the EGL/GLES2 context will be
 * created on. Hosts SK via the embedded JRE 25; rendering is routed through
 * libgl4es.so. Single visible UI element is a small "Exit" button.
 */
class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityGameBinding
    private lateinit var surface: SurfaceView
    private var jvmKicked = false

    private lateinit var inputManager: InputManager
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshGamepadPresence()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepadPresence()
        override fun onInputDeviceChanged(deviceId: Int) = refreshGamepadPresence()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        surface = binding.gameSurface
        surface.holder.addCallback(this)
        wireTouchInput()

        inputManager = getSystemService(INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(deviceListener, null)
        refreshGamepadPresence()

        binding.btnExit.setOnClickListener { confirmExit() }

        // TODO: read EXTRA_LOGIN_MODE; pass through to the JVM bootstrap once
        // we're invoking SK rather than smoke-testing.
    }

    /**
     * Routes touches on the game surface to the native input queue as plain
     * mouse events (UI/cursor navigation; gameplay movement is the gamepad's
     * job). Only the primary pointer is tracked — SK's UI is a single cursor.
     * MotionEvent coords are in the SurfaceView's pixel space, which equals the
     * EGL framebuffer, so they pass through unscaled.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireTouchInput() {
        surface.setOnTouchListener { _, event ->
            val x = event.x.toInt()
            val y = event.y.toInt()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> NativeBridge.onTouchEvent(TOUCH_DOWN, x, y)
                MotionEvent.ACTION_MOVE -> NativeBridge.onTouchEvent(TOUCH_MOVE, x, y)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> NativeBridge.onTouchEvent(TOUCH_UP, x, y)
                else -> return@setOnTouchListener false
            }
            true
        }
    }

    // --- gamepad ---

    /** True if any connected input device exposes a gamepad/joystick source. */
    private fun refreshGamepadPresence() {
        val present = InputDevice.getDeviceIds().any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            val s = dev.sources
            (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        }
        NativeBridge.onGamepadConnected(present)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromPad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_DPAD)
        if (fromPad) {
            // L2/R2 reported as buttons (some pads) -> drive the trigger axes.
            val triggerAxis = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L2 -> GP_AXIS_LTRIGGER
                KeyEvent.KEYCODE_BUTTON_R2 -> GP_AXIS_RTRIGGER
                else -> -1
            }
            if (triggerAxis >= 0) {
                if (event.action == KeyEvent.ACTION_DOWN) NativeBridge.onGamepadAxis(triggerAxis, 1f)
                else if (event.action == KeyEvent.ACTION_UP) NativeBridge.onGamepadAxis(triggerAxis, -1f)
                return true
            }
            val idx = gamepadButtonIndex(event.keyCode)
            if (idx >= 0) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) NativeBridge.onGamepadButton(idx, true)
                    KeyEvent.ACTION_UP -> NativeBridge.onGamepadButton(idx, false)
                }
                return true
            }
        }
        if (handleKeyboard(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Routes physical/soft/adb keyboard input to SK. A mapped key emits a GLFW
     * key transition; printable presses also emit the typed character. Order
     * matters: the key event goes first so SK's consumed-flag coordination can
     * suppress the char when a bound gameplay key (e.g. W) eats the key press,
     * while leaving text-entry chars to insert normally. Returns true only for
     * keys we actually translated, so Back/volume/etc. keep default behavior.
     */
    private fun handleKeyboard(event: KeyEvent): Boolean {
        // Don't double-handle gamepad/dpad sources (handled above).
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_DPAD)) return false

        val glfwKey = glfwKeyCode(event.keyCode)
        val ch = event.unicodeChar
        val printable = ch != 0 && (ch.toChar().isLetterOrDigit() || !ch.toChar().isISOControl())
        if (glfwKey < 0 && !printable) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (glfwKey >= 0) {
                    NativeBridge.onKeyEvent(glfwKey, if (event.repeatCount == 0) 1 else 2, 0)
                }
                if (printable) NativeBridge.onCharInput(ch)
            }
            KeyEvent.ACTION_UP -> {
                if (glfwKey >= 0) NativeBridge.onKeyEvent(glfwKey, 0, 0)
            }
            KeyEvent.ACTION_MULTIPLE -> {
                // Batched characters (rare; some IMEs/adb paths). Emit each.
                event.characters?.forEach { NativeBridge.onCharInput(it.code) }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            event.action == MotionEvent.ACTION_MOVE) {
            processJoystick(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** Reads the standard axes off a joystick MotionEvent and pushes normalized values. */
    private fun processJoystick(e: MotionEvent) {
        NativeBridge.onGamepadAxis(GP_AXIS_LEFT_X, deadzone(e.getAxisValue(MotionEvent.AXIS_X)))
        NativeBridge.onGamepadAxis(GP_AXIS_LEFT_Y, deadzone(e.getAxisValue(MotionEvent.AXIS_Y)))
        // Right stick is Z/RZ on the vast majority of Android controllers.
        NativeBridge.onGamepadAxis(GP_AXIS_RIGHT_X, deadzone(e.getAxisValue(MotionEvent.AXIS_Z)))
        NativeBridge.onGamepadAxis(GP_AXIS_RIGHT_Y, deadzone(e.getAxisValue(MotionEvent.AXIS_RZ)))
        // Triggers: Android reports 0..1 (LTRIGGER/RTRIGGER or BRAKE/GAS); GLFW wants
        // -1 at rest .. +1 fully pressed.
        val lt = max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        NativeBridge.onGamepadAxis(GP_AXIS_LTRIGGER, lt * 2f - 1f)
        NativeBridge.onGamepadAxis(GP_AXIS_RTRIGGER, rt * 2f - 1f)
        // D-pad delivered as a hat axis on many controllers -> the four dpad buttons.
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        NativeBridge.onGamepadButton(GP_BTN_DPAD_LEFT, hx < -0.5f)
        NativeBridge.onGamepadButton(GP_BTN_DPAD_RIGHT, hx > 0.5f)
        NativeBridge.onGamepadButton(GP_BTN_DPAD_UP, hy < -0.5f)
        NativeBridge.onGamepadButton(GP_BTN_DPAD_DOWN, hy > 0.5f)
    }

    private fun deadzone(v: Float): Float = if (abs(v) < AXIS_DEADZONE) 0f else v

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
            // Re-stage the cacio AWT bridge so a rebuilt jar always propagates.
            val cacioDir = CacioInstaller.stage(this).absolutePath
            NativeBridge.startJvm(
                jreHome = JreInstaller.homeDir(this).absolutePath,
                classpath = classpath,
                libPath = libPath,
                appFiles = filesDir.absolutePath,
                cacioDir = cacioDir,
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeBridge.onSurfaceDestroyed()
    }

    override fun onDestroy() {
        if (::inputManager.isInitialized) {
            inputManager.unregisterInputDeviceListener(deviceListener)
        }
        super.onDestroy()
    }

    private companion object {
        // Mirrors the action codes in NativeBridge.onTouchEvent / sklauncher.c
        const val TOUCH_DOWN = 0
        const val TOUCH_MOVE = 1
        const val TOUCH_UP = 2

        // GLFW standard gamepad layout — button indices.
        const val GP_BTN_A = 0
        const val GP_BTN_B = 1
        const val GP_BTN_X = 2
        const val GP_BTN_Y = 3
        const val GP_BTN_LEFT_BUMPER = 4
        const val GP_BTN_RIGHT_BUMPER = 5
        const val GP_BTN_BACK = 6
        const val GP_BTN_START = 7
        const val GP_BTN_GUIDE = 8
        const val GP_BTN_LEFT_THUMB = 9
        const val GP_BTN_RIGHT_THUMB = 10
        const val GP_BTN_DPAD_UP = 11
        const val GP_BTN_DPAD_RIGHT = 12
        const val GP_BTN_DPAD_DOWN = 13
        const val GP_BTN_DPAD_LEFT = 14

        // GLFW standard gamepad layout — axis indices.
        const val GP_AXIS_LEFT_X = 0
        const val GP_AXIS_LEFT_Y = 1
        const val GP_AXIS_RIGHT_X = 2
        const val GP_AXIS_RIGHT_Y = 3
        const val GP_AXIS_LTRIGGER = 4
        const val GP_AXIS_RTRIGGER = 5

        const val AXIS_DEADZONE = 0.15f

        /** Maps an Android gamepad keycode to a GLFW button index, or -1. */
        fun gamepadButtonIndex(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> GP_BTN_A
            KeyEvent.KEYCODE_BUTTON_B -> GP_BTN_B
            KeyEvent.KEYCODE_BUTTON_X -> GP_BTN_X
            KeyEvent.KEYCODE_BUTTON_Y -> GP_BTN_Y
            KeyEvent.KEYCODE_BUTTON_L1 -> GP_BTN_LEFT_BUMPER
            KeyEvent.KEYCODE_BUTTON_R1 -> GP_BTN_RIGHT_BUMPER
            KeyEvent.KEYCODE_BUTTON_SELECT -> GP_BTN_BACK
            KeyEvent.KEYCODE_BUTTON_START -> GP_BTN_START
            KeyEvent.KEYCODE_BUTTON_MODE -> GP_BTN_GUIDE
            KeyEvent.KEYCODE_BUTTON_THUMBL -> GP_BTN_LEFT_THUMB
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GP_BTN_RIGHT_THUMB
            KeyEvent.KEYCODE_DPAD_UP -> GP_BTN_DPAD_UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> GP_BTN_DPAD_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> GP_BTN_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> GP_BTN_DPAD_LEFT
            else -> -1
        }

        /**
         * Maps an Android keycode to a GLFW keycode, or -1 if unmapped. GLFW
         * letter/digit keycodes are uppercase-ASCII; named keys use GLFW's 256+
         * range. Covers gameplay + text-editing keys; printable chars themselves
         * arrive separately via unicodeChar so layout/shift is handled for us.
         */
        fun glfwKeyCode(keyCode: Int): Int = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                'A'.code + (keyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                '0'.code + (keyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_SPACE -> 32
            KeyEvent.KEYCODE_APOSTROPHE -> 39
            KeyEvent.KEYCODE_COMMA -> 44
            KeyEvent.KEYCODE_MINUS -> 45
            KeyEvent.KEYCODE_PERIOD -> 46
            KeyEvent.KEYCODE_SLASH -> 47
            KeyEvent.KEYCODE_SEMICOLON -> 59
            KeyEvent.KEYCODE_EQUALS -> 61
            KeyEvent.KEYCODE_LEFT_BRACKET -> 91
            KeyEvent.KEYCODE_BACKSLASH -> 92
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 93
            KeyEvent.KEYCODE_GRAVE -> 96
            KeyEvent.KEYCODE_ESCAPE -> 256
            KeyEvent.KEYCODE_ENTER -> 257
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 257
            KeyEvent.KEYCODE_TAB -> 258
            KeyEvent.KEYCODE_DEL -> 259          // GLFW_KEY_BACKSPACE
            KeyEvent.KEYCODE_INSERT -> 260
            KeyEvent.KEYCODE_FORWARD_DEL -> 261  // GLFW_KEY_DELETE
            KeyEvent.KEYCODE_DPAD_RIGHT -> 262
            KeyEvent.KEYCODE_DPAD_LEFT -> 263
            KeyEvent.KEYCODE_DPAD_DOWN -> 264
            KeyEvent.KEYCODE_DPAD_UP -> 265
            KeyEvent.KEYCODE_PAGE_UP -> 266
            KeyEvent.KEYCODE_PAGE_DOWN -> 267
            KeyEvent.KEYCODE_MOVE_HOME -> 268
            KeyEvent.KEYCODE_MOVE_END -> 269
            KeyEvent.KEYCODE_SHIFT_LEFT -> 340
            KeyEvent.KEYCODE_CTRL_LEFT -> 341
            KeyEvent.KEYCODE_ALT_LEFT -> 342
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 344
            KeyEvent.KEYCODE_CTRL_RIGHT -> 345
            KeyEvent.KEYCODE_ALT_RIGHT -> 346
            else -> -1
        }
    }
}
