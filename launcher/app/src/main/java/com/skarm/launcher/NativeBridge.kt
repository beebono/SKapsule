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
     * @param binDir directory prepended to the JVM's PATH; holds the xdg-open shim symlink so
     *   SK's Runtime.exec("xdg-open <url>") resolves to it. Empty to leave PATH untouched.
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
        binDir: String,
        screenWidth: Int,
        screenHeight: Int,
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

    /**
     * Marks the on-screen touch controls (a virtual controller) active/inactive.
     * SK treats the pad as connected while either this or a physical pad is on,
     * so touch button/axis writes aren't dropped when no real controller exists.
     */
    external fun onVirtualGamepadConnected(connected: Boolean)

    /** Sets a button in the GLFW standard layout (index 0..14). */
    external fun onGamepadButton(index: Int, pressed: Boolean)

    /** Sets an axis in the GLFW standard layout (index 0..5), value already normalized. */
    external fun onGamepadAxis(index: Int, value: Float)

    /** A key transition. key = GLFW keycode; action 1=press, 2=repeat, 0=release. */
    external fun onKeyEvent(key: Int, action: Int, mods: Int)

    /** A typed character (Unicode codepoint) for text entry. */
    external fun onCharInput(codepoint: Int)

    // --- JVM/native -> Android boot-overlay callbacks --------------------------
    // Invoked FROM native (cached JavaVM -> FindClass NativeBridge -> static call),
    // not from Kotlin. The bootstrap jar and frenchpress run in SK's classloader
    // (parent = platform classloader) and can't see app classes, so getdown
    // progress and Steam-auth status reach the Activity through native, which
    // fans out to whichever Activity registered as the listener.

    /** Implemented by GameActivity to receive boot-phase status on the UI thread. */
    interface BootListener {
        fun onLaunchStatus(message: String)
        fun onRenderReady()
    }

    @Volatile
    private var bootListener: BootListener? = null

    fun setBootListener(listener: BootListener?) { bootListener = listener }

    /** Native -> a human-readable boot-phase status (getdown %, "Waiting for Steam…"). */
    @JvmStatic
    fun onLaunchStatus(message: String) {
        bootListener?.onLaunchStatus(message)
    }

    /** Native -> SK has produced its first frame; the overlay can be dismissed. */
    @JvmStatic
    fun onRenderReady() {
        bootListener?.onRenderReady()
    }

    // --- JVM/native -> Android Steam-login keep-alive --------------------------
    // frenchpress brackets its whole auth attempt with steamKeepAlive(true/false)
    // (via SkBootstrap.nativeSteamKeepAlive -> sklauncher.c -> here). While active,
    // a foreground service holds the :game process at a priority that survives the
    // user tabbing out to the Steam Mobile App to approve the sign-in. Routed
    // through the application Context, not the Activity, because the Activity's
    // surface is destroyed during exactly that tab-out.

    @Volatile
    private var appContext: android.content.Context? = null

    /** Called once per process from SkApplication.onCreate. */
    fun attachContext(ctx: android.content.Context) {
        appContext = ctx.applicationContext
    }

    /** Native -> start (active) or stop the sign-in keep-alive foreground service. */
    @JvmStatic
    fun steamKeepAlive(active: Boolean) {
        val ctx = appContext ?: return
        val intent = android.content.Intent(ctx, SteamAuthService::class.java)
        try {
            if (active) ctx.startForegroundService(intent) else ctx.stopService(intent)
        } catch (t: Throwable) {
            // A denied background-start (rare: auth somehow began off-foreground)
            // must not crash the login thread; degrade to the old kill-prone path.
            android.util.Log.w("NativeBridge", "steamKeepAlive($active) failed", t)
        }
    }

    // --- JVM/native -> Android Steam Guard (2FA) prompt ------------------------
    // Unlike the boot-overlay callbacks above (fire-and-forget), these are a
    // BLOCKING request/response: frenchpress's login thread (a HotSpot thread
    // attached to ART) calls promptForDeviceCode/promptForEmailCode and parks
    // until the user submits a code, which is handed back across `codeExchange`.
    //
    // JavaSteam only ever reaches these when no Steam-Mobile-App push approval
    // is available, so a code is mandatory and blocking here is correct — push
    // accounts log in silently and never trigger a prompt. See the 120s login
    // timeout in SteamSession.attempt() as the ultimate backstop.

    /** Implemented by GameActivity to surface the Steam Guard dialog on the UI thread. */
    interface CredentialListener {
        /** Ask for a Steam Guard authenticator (TOTP) code. */
        fun onPromptDeviceCode(prevWrong: Boolean)
        /** Ask for a Steam Guard email code sent to [email]. */
        fun onPromptEmailCode(email: String, prevWrong: Boolean)
        /** The auth attempt ended (success/fail/timeout); tear down any open dialog. */
        fun onPromptDismiss()
    }

    @Volatile
    private var credentialListener: CredentialListener? = null

    fun setCredentialListener(listener: CredentialListener?) { credentialListener = listener }

    // Single-slot rendezvous between the blocked login thread (taker) and the UI
    // thread (offerer). SynchronousQueue means a late submission with no waiter
    // simply no-ops rather than leaking a stale code into the next prompt.
    private val codeExchange = java.util.concurrent.SynchronousQueue<String>()

    /**
     * Native (login thread) -> request a device code and BLOCK until the user
     * submits one. Returns the code, or "" if no UI is registered (login then
     * fails fast rather than hanging) or the wait is interrupted.
     */
    @JvmStatic
    fun promptForDeviceCode(prevWrong: Boolean): String {
        val listener = credentialListener ?: return ""
        listener.onPromptDeviceCode(prevWrong)
        return awaitCode()
    }

    /** Native (login thread) -> request an email code and BLOCK. See {@link #promptForDeviceCode}. */
    @JvmStatic
    fun promptForEmailCode(email: String, prevWrong: Boolean): String {
        val listener = credentialListener ?: return ""
        listener.onPromptEmailCode(email, prevWrong)
        return awaitCode()
    }

    private fun awaitCode(): String = try {
        codeExchange.take()
    } catch (ie: InterruptedException) {
        Thread.currentThread().interrupt()
        ""
    }

    /**
     * UI thread -> hand the user's entered code (or "" to fall through/cancel)
     * to the parked login thread. No-ops if nothing is waiting.
     */
    fun submitCode(code: String) { codeExchange.offer(code) }
}
