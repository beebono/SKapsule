package com.skarm.launcher

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Process
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.skarm.launcher.databinding.ActivityGameBinding
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * In-game host. Owns the SurfaceView that the EGL/GLES2 context will be
 * created on. Hosts SK via the embedded JRE 25; rendering is routed through
 * libgl4es.so. Single visible UI element is a small "Exit" button.
 */
class GameActivity : AppCompatActivity(), SurfaceHolder.Callback,
    NativeBridge.BootListener, NativeBridge.CredentialListener {

    private lateinit var binding: ActivityGameBinding
    private lateinit var surface: SurfaceView
    private var jvmKicked = false
    private lateinit var loginMode: LauncherActivity.LoginMode
    private var steamUser: String = ""
    private var steamPass: String = ""
    private var dismissing = false
    private var guardDialog: AlertDialog? = null

    private lateinit var inputManager: InputManager
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshGamepadPresence()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepadPresence()
        override fun onInputDeviceChanged(deviceId: Int) = refreshGamepadPresence()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableImmersiveMode()

        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        surface = binding.gameSurface
        surface.holder.addCallback(this)
        wireTouchInput()

        // Receive boot-phase status (getdown progress, Steam auth) routed from the
        // JVM via native. The overlay is visible by default (covers the black gap)
        // and shows "Starting Java runtime…" until the first status arrives.
        NativeBridge.setBootListener(this)
        // Steam Guard (2FA) prompts route here too; the dialog blocks frenchpress's
        // login thread until a code is submitted (or "" on cancel).
        NativeBridge.setCredentialListener(this)

        inputManager = getSystemService(INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(deviceListener, null)
        refreshGamepadPresence()

        binding.btnKeyboard.setOnClickListener { toggleSoftKeyboard() }

        // Android Back must go through the exit-confirm dialog, not silently finish
        // the activity (which would strand the JVM + audio in the :game process).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        loginMode = runCatching {
            LauncherActivity.LoginMode.valueOf(
                intent.getStringExtra(LauncherActivity.EXTRA_LOGIN_MODE).orEmpty()
            )
        }.getOrDefault(LauncherActivity.LoginMode.Web)
        // Steam credentials, present only on a first Steam login (subsequent launches
        // resume from frenchpress's stored refresh token). Empty in Web mode — which
        // frenchpress reads as "web account" and falls through to normal web login.
        // Threaded into the JVM as FRENCHPRESS_STEAM_USER/PASS env vars (sklauncher.c).
        steamUser = intent.getStringExtra(LauncherActivity.EXTRA_STEAM_USER).orEmpty()
        steamPass = intent.getStringExtra(LauncherActivity.EXTRA_STEAM_PASS).orEmpty()
    }

    /**
     * Re-assert immersive mode on coming back into focus.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    /**
     * Hides the status + navigation bars until a swipe shows them temporarily.
     */
    private fun enableImmersiveMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
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
            .setPositiveButton(R.string.exit_confirm_yes) { _, _ -> shutdownGame() }
            .setNegativeButton(R.string.exit_confirm_no, null)
            .show()
    }

    /**
     * Fully tears down the game. GameActivity runs in its own ":game" process with
     * the embedded JVM (and OpenAL audio) on a non-daemon thread, behind a one-shot
     * native init that can't be restarted in-process. So a confirmed exit kills the
     * process: audio stops and the next launch starts clean. The launcher lives in a
     * separate process and is unaffected. (Home/recents still background us normally,
     * preserving multitasking — only an explicit Exit/Back-confirm kills.)
     */
    private fun shutdownGame() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    /** Pops (or dismisses) the soft keyboard, routed to SK via [ImeBridgeView]. */
    private fun toggleSoftKeyboard() {
        val ime = binding.imeBridge
        val controller = WindowCompat.getInsetsController(window, ime)
        val visible = ViewCompat.getRootWindowInsets(ime)
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        if (visible) {
            controller.hide(WindowInsetsCompat.Type.ime())
        } else {
            ime.requestFocus()
            controller.show(WindowInsetsCompat.Type.ime())
        }
    }

    // --- SurfaceHolder.Callback ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeBridge.onSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeBridge.onSurfaceChanged(width, height)
        if (!jvmKicked) {
            jvmKicked = true
            Log.i(TAG, "Launching SK (loginMode=$loginMode)")
            // java.library.path / LD_LIBRARY_PATH = JRE's lib (libjvm, libGL via
            // gl4es) + LWJGL natives + the app's extracted nativeLibraryDir. The
            // last entry lets the exec'd jspawnhelper find libc++_shared.so and
            // keeps cacio's getenv("LD_LIBRARY_PATH") non-null (see sklauncher.c).
            val libPath = listOf(
                File(JreInstaller.homeDir(this), "lib").absolutePath,
                LwjglInstaller.libDir(this).absolutePath,
                applicationInfo.nativeLibraryDir,
            ).joinToString(":")
            // Classpath: SK bootstrap (getdown + sk-bootstrap) first, then LWJGL.
            val classpath = listOf(
                SkInstaller.bootstrapClasspath(this),
                LwjglInstaller.classpath(this),
            ).filter { it.isNotEmpty() }.joinToString(":")
            // Re-stage the cacio AWT bridge so a rebuilt jar always propagates.
            val cacioDir = CacioInstaller.stage(this).absolutePath
            // Activate frenchpress (Steam-login shim) only in Steam mode. An explicit
            // Play(Web) must do web login even if a Steam refresh token is stored on
            // disk — and frenchpress's CredentialStore token has priority over the
            // empty-username web fallback, so it would re-use the token if loaded. Not
            // putting it on the classpath for Web sidesteps that, non-destructively
            // (the token is preserved for the next Play(Steam)).
            val steam = loginMode == LauncherActivity.LoginMode.Steam
            val frenchpressJar: String
            val credFile: String
            if (steam) {
                FrenchpressInstaller.stage(this)  // re-stage so a rebuilt jar propagates
                frenchpressJar = FrenchpressInstaller.jar(this).absolutePath
                credFile = FrenchpressInstaller.credFile(this).absolutePath
            } else {
                frenchpressJar = ""
                credFile = ""
            }
            // Writable HOME for the JVM (user.home + java.util.prefs roots, set in
            // sklauncher.c). Kept outside the getdown-managed sk/ tree so updates
            // never wipe persisted settings. Pre-create so FileSystemPreferences
            // (whose failure mode is a missing parent dir) can lock/flush.
            val home = File(filesDir, "home")
            File(home, ".userPrefs").mkdirs()
            File(home, ".systemPrefs").mkdirs()
            // Seed default SK prefs (Compatibility + LOW, cull_transients, and
            // anonymous_logon=false for web-account login) into SK's "projectx" node
            // on first launch only. Path = userRoot (home/.userPrefs, see
            // sklauncher.c) + the JDK's appended .java/.userPrefs.
            PrefsInstaller.seedDefaults(File(home, ".userPrefs/.java/.userPrefs"))
            NativeBridge.startJvm(
                jreHome = JreInstaller.homeDir(this).absolutePath,
                classpath = classpath,
                libPath = libPath,
                appFiles = filesDir.absolutePath,
                cacioDir = cacioDir,
                frenchpressJar = frenchpressJar,
                credFile = credFile,
                steamUser = steamUser,
                steamPass = steamPass,
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
        NativeBridge.setBootListener(null)
        NativeBridge.setCredentialListener(null)
        // Unblock any login thread parked on a dialog we're about to drop.
        NativeBridge.submitCode("")
        guardDialog?.dismiss()
        guardDialog = null
        super.onDestroy()
    }

    // --- NativeBridge.BootListener (called from a JVM/native thread) ---

    /**
     * A boot-phase status update (getdown progress, "Waiting for Steam…"). Arrives
     * off the UI thread, so it's marshalled onto it before touching views. Ignored
     * once the overlay has been dismissed, so a late log line can't resurrect it.
     */
    override fun onLaunchStatus(message: String) {
        runOnUiThread {
            if (binding.bootOverlay.visibility == View.VISIBLE) {
                binding.bootStatus.text = message
            }
        }
    }

    /**
     * SK produced its first frame: dismiss the boot overlay so the game shows
     * through. Marshalled onto the UI thread.
     */
    override fun onRenderReady() {
        runOnUiThread {
            // Slide overlay out to left and mark GONE when done to prevent touch stealing.
            if (dismissing || binding.bootOverlay.visibility != View.VISIBLE) return@runOnUiThread
            dismissing = true
            binding.bootOverlay.animate()
                               .translationX(-binding.bootOverlay.width.toFloat())
                               .setDuration(resources.getInteger(android.R.integer.config_mediumAnimTime).toLong())
                               .withEndAction{ binding.bootOverlay.visibility = View.GONE }
        }
    }

    // --- NativeBridge.CredentialListener (called from the JVM login thread) ---

    /**
     * Steam needs a typed authenticator (TOTP) code — reached only when no Steam
     * Mobile App push approval is available, so a code is mandatory here. The
     * login thread is parked in NativeBridge.promptForDeviceCode; we collect the
     * code and hand it back via submitCode(). Marshalled onto the UI thread.
     */
    override fun onPromptDeviceCode(prevWrong: Boolean) {
        runOnUiThread {
            showGuardDialog(
                title = getString(R.string.steam_guard_title),
                message = getString(
                    if (prevWrong) R.string.steam_guard_device_retry
                    else R.string.steam_guard_device_message
                ),
                numeric = true,
            )
        }
    }

    /** Steam needs an email Steam Guard code sent to [email]. See {@link #onPromptDeviceCode}. */
    override fun onPromptEmailCode(email: String, prevWrong: Boolean) {
        runOnUiThread {
            showGuardDialog(
                title = getString(R.string.steam_guard_title),
                message = getString(
                    if (prevWrong) R.string.steam_guard_email_retry
                    else R.string.steam_guard_email_message, email
                ),
                numeric = false, // email codes are alphanumeric
            )
        }
    }

    /** Auth finished (any outcome) — drop a lingering dialog and release the latch. */
    override fun onPromptDismiss() {
        runOnUiThread {
            guardDialog?.dismiss()
            guardDialog = null
        }
    }

    /**
     * Builds the single-field Steam Guard dialog. Both OK and cancel/back MUST
     * reach submitCode() exactly once so the parked login thread never hangs:
     * OK submits the trimmed code, cancel submits "" (login then fails fast
     * rather than waiting out the 120s timeout). Replaces any prior dialog so a
     * retry prompt doesn't stack.
     */
    private fun showGuardDialog(title: String, message: String, numeric: Boolean) {
        guardDialog?.dismiss()
        val input = EditText(this).apply {
            inputType = if (numeric) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
            hint = getString(R.string.steam_guard_hint)
        }
        guardDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setCancelable(true)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                NativeBridge.submitCode(input.text.toString().trim())
                guardDialog = null
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                NativeBridge.submitCode("")
                guardDialog = null
            }
            .setOnCancelListener { // back button / tap-outside
                NativeBridge.submitCode("")
                guardDialog = null
            }
            .show()
    }

    private companion object {
        const val TAG = "GameActivity"

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
