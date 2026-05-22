package org.lwjgl.glfw;

import org.lwjgl.PointerBuffer;

import java.nio.IntBuffer;

/**
 * Pure-Java shadow of LWJGL's GLFW entry class. Sits on the SK URLClassLoader
 * BEFORE the AngelAuraMC lwjgl-glfw.jar so it shadows the real class.
 *
 * On Android there is no native libglfw.so. The upstream class would
 * Library.loadNative("libpojavexec.so" / "libglfw.so") in its &lt;clinit&gt; and
 * throw UnsatisfiedLinkError. We shadow only this entry point — other GLFW
 * types (GLFWVidMode, GLFWErrorCallback, etc.) keep coming from upstream.
 *
 * The current implementation covers JUST what SK needs to get past prefs
 * initialisation. This stub will probably grow. Each new method is added on demand.
 *
 * Returning null/0 from queries like glfwGetVideoModes is intentional and
 * is what SK's getAvailableDisplayModes() handles gracefully (empty array).
 */
public final class GLFW {

    private GLFW() {}

    // --- GLFW init hints (constants used by SK's ensureGlfwInit) ---
    public static final int GLFW_JOYSTICK_HAT_BUTTONS = 0x50001;
    public static final int GLFW_COCOA_CHDIR_RESOURCES = 0x51001;
    public static final int GLFW_COCOA_MENUBAR = 0x51002;
    public static final int GLFW_X11_XCB_VULKAN_SURFACE = 0x52001;
    public static final int GLFW_PLATFORM = 0x50003;

    // --- joystick / gamepad constants (referenced by our own methods) ---
    public static final int GLFW_JOYSTICK_1 = 0;
    public static final int GLFW_JOYSTICK_LAST = 15;

    // --- minimum viable surface ---
    public static boolean glfwInit() {
        return true;
    }

    public static void glfwTerminate() { /* no-op */ }

    public static void glfwInitHint(int hint, int value) { /* no-op */ }

    // --- input event pump ---------------------------------------------------
    // Android input crosses the classloader boundary as primitive ints through
    // a native ring buffer (filled on the Android UI thread, drained here on
    // SK's main loop thread). Each event is a 4-int record: [type, a, b, c].
    // Draining here means the stored SK callbacks fire on the same thread that
    // calls glfwPollEvents — matching desktop GLFW semantics exactly.
    private static final int EV_CURSOR_POS   = 1; // a=x, b=y (framebuffer px, y-down)
    private static final int EV_MOUSE_BUTTON = 2; // a=button, b=action (1=press,0=release)
    private static final int EV_SCROLL       = 3; // a=delta (+/-1)
    private static final int EV_KEY          = 4; // a=key, b=action, c=mods
    private static final int EV_CHAR         = 5; // a=codepoint

    private static final int DRAIN_RECORDS = 256;
    private static final int[] drainBuf = new int[DRAIN_RECORDS * 4];
    private static native int nativeDrainInput(int[] out);

    public static void glfwPollEvents() {
        int n;
        do {
            n = nativeDrainInput(drainBuf);
            for (int i = 0; i < n; i++) {
                int base = i * 4;
                int type = drainBuf[base];
                int a = drainBuf[base + 1];
                int b = drainBuf[base + 2];
                int c = drainBuf[base + 3];
                switch (type) {
                    case EV_CURSOR_POS:
                        cursorX = a;
                        cursorY = b;
                        if (cursorPosCb != null) cursorPosCb.invoke(FAKE_WINDOW, cursorX, cursorY);
                        break;
                    case EV_MOUSE_BUTTON:
                        mouseButtons[a & 0x7] = (b != 0);
                        if (mouseButtonCb != null) mouseButtonCb.invoke(FAKE_WINDOW, a, b, 0);
                        break;
                    case EV_SCROLL:
                        if (scrollCb != null) scrollCb.invoke(FAKE_WINDOW, 0.0, a);
                        break;
                    case EV_KEY:
                        if (b != 0) pressedKeys.add(a); else pressedKeys.remove(a);
                        if (keyCb != null) keyCb.invoke(FAKE_WINDOW, a, a /*scancode*/, b, c);
                        break;
                    case EV_CHAR:
                        if (charCb != null) charCb.invoke(FAKE_WINDOW, a);
                        break;
                    default:
                        break;
                }
            }
        } while (n == DRAIN_RECORDS); // buffer was full; keep draining
    }

    public static void glfwWaitEvents() { /* no-op */ }

    public static void glfwWaitEventsTimeout(double timeout) { /* no-op */ }

    public static void glfwPostEmptyEvent() { /* no-op */ }

    public static long glfwGetPrimaryMonitor() {
        // Non-zero handle so SK knows "we have a monitor", but we don't enum.
        return 1L;
    }

    public static PointerBuffer glfwGetMonitors() {
        // Returning null is legal per LWJGL docs; SK handles it.
        return null;
    }

    public static long glfwGetWindowMonitor(long window) {
        // 0 = windowed mode (not fullscreen). SK's isFullscreen returns false.
        return 0L;
    }

    public static void glfwGetMonitorPos(long monitor, int[] x, int[] y) {
        if (x != null && x.length > 0) x[0] = 0;
        if (y != null && y.length > 0) y[0] = 0;
    }
    public static void glfwGetMonitorWorkarea(long monitor, int[] x, int[] y, int[] w, int[] h) {
        if (x != null && x.length > 0) x[0] = 0;
        if (y != null && y.length > 0) y[0] = 0;
        if (w != null && w.length > 0) w[0] = width;
        if (h != null && h.length > 0) h[0] = height;
    }
    public static void glfwGetMonitorPhysicalSize(long monitor, int[] mmW, int[] mmH) {
        // 96 DPI assumption: 1280px @ 96dpi ~= 339mm.
        if (mmW != null && mmW.length > 0) mmW[0] = width * 254 / 960;
        if (mmH != null && mmH.length > 0) mmH[0] = height * 254 / 960;
    }
    public static void glfwGetMonitorContentScale(long monitor, float[] xs, float[] ys) {
        if (xs != null && xs.length > 0) xs[0] = 1.0f;
        if (ys != null && ys.length > 0) ys[0] = 1.0f;
    }
    public static String glfwGetMonitorName(long monitor) { return "android-surface"; }
    public static GLFWGammaRamp glfwGetGammaRamp(long monitor) { return null; }
    public static void glfwSetGamma(long monitor, float gamma) { /* no-op */ }
    public static void glfwSetGammaRamp(long monitor, GLFWGammaRamp ramp) { /* no-op */ }
    public static long glfwGetMonitorUserPointer(long monitor) { return 0L; }
    public static void glfwSetMonitorUserPointer(long monitor, long pointer) { /* no-op */ }

    public static GLFWVidMode glfwGetVideoMode(long monitor) {
        // SK falls back to native screen size if this is null.
        return null;
    }

    public static GLFWVidMode.Buffer glfwGetVideoModes(long monitor) {
        // SK's getAvailableDisplayModes returns an empty array when this is null.
        return null;
    }

    // --- window hints ---
    public static void glfwDefaultWindowHints() { /* no-op */ }
    public static void glfwWindowHint(int hint, int value) { /* no-op */ }
    public static void glfwWindowHint(int hint, boolean value) { /* no-op */ }
    public static void glfwWindowHintString(int hint, CharSequence value) { /* no-op */ }
    public static void glfwWindowHintString(int hint, java.nio.ByteBuffer value) { /* no-op */ }

    // --- our single fake window. The handle is just a sentinel non-zero long. ---
    private static final long FAKE_WINDOW = 0xBEEF7AC0; // any non-zero sentinel
    private static volatile boolean shouldClose = false;
    // Just above SK's minimum to be safe if the minimum increases in the future
    private static volatile int width = 1280;
    private static volatile int height = 720;

    // --- live input state (read back by SK via glfwGet* during callbacks/poll) ---
    private static volatile double cursorX = 0, cursorY = 0;
    private static final boolean[] mouseButtons = new boolean[8];
    private static final java.util.Set<Integer> pressedKeys =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Stored input callbacks. SK installs these via glfwSet*Callback; we invoke
    // them from glfwPollEvents on the main thread. Kept as the *I functional
    // interfaces (what SK passes); the concrete return types are unused (SK
    // ignores the "previous callback" return value).
    private static GLFWKeyCallbackI         keyCb;
    private static GLFWCharCallbackI        charCb;
    private static GLFWMouseButtonCallbackI mouseButtonCb;
    private static GLFWCursorPosCallbackI   cursorPosCb;
    private static GLFWScrollCallbackI      scrollCb;
    private static GLFWWindowFocusCallbackI focusCb;

    public static long glfwCreateWindow(int w, int h, CharSequence title, long monitor, long share) {
        // SK sends what it WANTS the window to be but like, we don't always 
        // get what we want. In this case its what Android and EGL want.
        int[] dims = nativeGetSurfaceSize();
        if (dims != null && dims.length >= 2 && dims[0] > 0 && dims[1] > 0) {
            width = dims[0];
            height = dims[1];
        } else {
            width = w;
            height = h;
        }
        return FAKE_WINDOW;
    }

    private static native int[] nativeGetSurfaceSize();
    public static void glfwDestroyWindow(long window) { /* no-op */ }
    public static void glfwShowWindow(long window) { /* no-op */ }
    public static void glfwHideWindow(long window) { /* no-op */ }
    public static void glfwFocusWindow(long window) { /* no-op */ }
    public static void glfwRequestWindowAttention(long window) { /* no-op */ }
    public static void glfwSetWindowTitle(long window, CharSequence title) { /* no-op */ }
    public static void glfwSetWindowTitle(long window, java.nio.ByteBuffer title) { /* no-op */ }
    public static void glfwSetWindowSize(long window, int w, int h) { width = w; height = h; }
    public static void glfwSetWindowPos(long window, int x, int y) { /* no-op */ }
    public static void glfwSetWindowMonitor(long window, long monitor, int x, int y, int w, int h, int refresh) {
        width = w; height = h;
    }
    public static void glfwSetWindowIcon(long window, GLFWImage.Buffer images) { /* no-op */ }
    public static void glfwSetWindowIcon(long window, GLFWImage image) { /* no-op */ }
    public static void glfwSetWindowAspectRatio(long window, int numer, int denom) { /* no-op */ }
    public static void glfwSetWindowSizeLimits(long window, int minW, int minH, int maxW, int maxH) { /* no-op */ }
    public static void glfwIconifyWindow(long window) { /* no-op */ }
    public static void glfwRestoreWindow(long window) { /* no-op */ }
    public static void glfwMaximizeWindow(long window) { /* no-op */ }
    // Pretend to be a normal, focused, visible, non-minimised window
    // SK discriminates against our calls otherwise and refuses to render
    public static int glfwGetWindowAttrib(long window, int attrib) {
        switch (attrib) {
            case 0x00020001: return 1; // GLFW_FOCUSED
            case 0x00020002: return 0; // GLFW_ICONIFIED
            case 0x00020003: return 0; // GLFW_RESIZABLE
            case 0x00020004: return 1; // GLFW_VISIBLE
            case 0x00020005: return 0; // GLFW_DECORATED
            case 0x00020006: return 0; // GLFW_AUTO_ICONIFY
            case 0x00020007: return 0; // GLFW_FLOATING
            case 0x00020008: return 0; // GLFW_MAXIMIZED
            case 0x00020009: return 0; // GLFW_CENTER_CURSOR
            case 0x0002000A: return 0; // GLFW_TRANSPARENT_FRAMEBUFFER
            case 0x0002000B: return 1; // GLFW_HOVERED
            case 0x0002000C: return 0; // GLFW_FOCUS_ON_SHOW
            case 0x00022002: return 2; // GLFW_CONTEXT_VERSION_MAJOR
            case 0x00022003: return 1; // GLFW_CONTEXT_VERSION_MINOR
            case 0x00022004: return 0; // GLFW_CONTEXT_REVISION
            case 0x00022008: return 0; // GLFW_OPENGL_PROFILE = ANY
            default:         return 0;
        }
    }
    public static void glfwSetWindowAttrib(long window, int attrib, int value) { /* no-op */ }
    public static long glfwGetWindowUserPointer(long window) { return 0L; }
    public static void glfwSetWindowUserPointer(long window, long pointer) { /* no-op */ }

    public static boolean glfwWindowShouldClose(long window) { return shouldClose; }
    public static void glfwSetWindowShouldClose(long window, boolean v) { shouldClose = v; }

    public static void glfwGetFramebufferSize(long window, IntBuffer w, IntBuffer h) {
        if (w != null) w.put(0, width);
        if (h != null) h.put(0, height);
    }
    public static void glfwGetFramebufferSize(long window, int[] w, int[] h) {
        if (w != null && w.length > 0) w[0] = width;
        if (h != null && h.length > 0) h[0] = height;
    }
    public static void glfwGetWindowSize(long window, IntBuffer w, IntBuffer h) {
        glfwGetFramebufferSize(window, w, h);
    }
    public static void glfwGetWindowSize(long window, int[] w, int[] h) {
        glfwGetFramebufferSize(window, w, h);
    }
    public static void glfwGetWindowPos(long window, int[] x, int[] y) {
        if (x != null && x.length > 0) x[0] = 0;
        if (y != null && y.length > 0) y[0] = 0;
    }
    public static void glfwGetWindowContentScale(long window, float[] xs, float[] ys) {
        if (xs != null && xs.length > 0) xs[0] = 1.0f;
        if (ys != null && ys.length > 0) ys[0] = 1.0f;
    }

    public static void glfwMakeContextCurrent(long window) { nativeMakeContextCurrent(window); }
    public static long glfwGetCurrentContext() { return FAKE_WINDOW; }
    public static void glfwSwapBuffers(long window) { nativeSwapBuffers(window); }
    public static void glfwSwapInterval(int interval) { /* no-op */ }

    private static native void nativeMakeContextCurrent(long window);
    private static native void nativeSwapBuffers(long window);

    // --- callbacks: input ones are stored and driven from glfwPollEvents ---
    public static GLFWErrorCallback glfwSetErrorCallback(GLFWErrorCallbackI cb) { return null; }
    public static GLFWKeyCallback glfwSetKeyCallback(long window, GLFWKeyCallbackI cb) { keyCb = cb; return null; }
    public static GLFWCharCallback glfwSetCharCallback(long window, GLFWCharCallbackI cb) { charCb = cb; return null; }
    public static GLFWCharModsCallback glfwSetCharModsCallback(long window, GLFWCharModsCallbackI cb) { return null; }
    public static GLFWMouseButtonCallback glfwSetMouseButtonCallback(long window, GLFWMouseButtonCallbackI cb) { mouseButtonCb = cb; return null; }
    public static GLFWCursorPosCallback glfwSetCursorPosCallback(long window, GLFWCursorPosCallbackI cb) { cursorPosCb = cb; return null; }
    public static GLFWCursorEnterCallback glfwSetCursorEnterCallback(long window, GLFWCursorEnterCallbackI cb) { return null; }
    public static GLFWScrollCallback glfwSetScrollCallback(long window, GLFWScrollCallbackI cb) { scrollCb = cb; return null; }
    public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(long window, GLFWFramebufferSizeCallbackI cb) { return null; }
    public static GLFWWindowSizeCallback glfwSetWindowSizeCallback(long window, GLFWWindowSizeCallbackI cb) { return null; }
    public static GLFWWindowPosCallback glfwSetWindowPosCallback(long window, GLFWWindowPosCallbackI cb) { return null; }
    public static GLFWWindowFocusCallback glfwSetWindowFocusCallback(long window, GLFWWindowFocusCallbackI cb) { focusCb = cb; return null; }
    public static GLFWWindowCloseCallback glfwSetWindowCloseCallback(long window, GLFWWindowCloseCallbackI cb) { return null; }
    public static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI cb) { return null; }
    public static GLFWWindowMaximizeCallback glfwSetWindowMaximizeCallback(long window, GLFWWindowMaximizeCallbackI cb) { return null; }
    public static GLFWWindowRefreshCallback glfwSetWindowRefreshCallback(long window, GLFWWindowRefreshCallbackI cb) { return null; }
    public static GLFWDropCallback glfwSetDropCallback(long window, GLFWDropCallbackI cb) { return null; }
    public static GLFWMonitorCallback glfwSetMonitorCallback(GLFWMonitorCallbackI cb) { return null; }

    // --- input state (live; mirrors what the event pump last saw) ---
    public static int glfwGetKey(long window, int key) {
        return pressedKeys.contains(key) ? 1 /* GLFW_PRESS */ : 0 /* GLFW_RELEASE */;
    }
    public static int glfwGetMouseButton(long window, int button) {
        return (button >= 0 && button < mouseButtons.length && mouseButtons[button]) ? 1 : 0;
    }
    public static void glfwGetCursorPos(long window, double[] xs, double[] ys) {
        if (xs != null && xs.length > 0) xs[0] = cursorX;
        if (ys != null && ys.length > 0) ys[0] = cursorY;
    }
    public static void glfwSetCursorPos(long window, double x, double y) { cursorX = x; cursorY = y; }
    public static void glfwSetInputMode(long window, int mode, int value) { /* no-op */ }
    public static int glfwGetInputMode(long window, int mode) { return 0; }

    // --- cursors: hand back sentinel handles, assume touchscreen is available anyway ---
    private static long nextCursor = 0x100L;
    public static long glfwCreateCursor(GLFWImage image, int xhot, int yhot) { return ++nextCursor; }
    public static long glfwCreateStandardCursor(int shape) { return ++nextCursor; }
    public static void glfwDestroyCursor(long cursor) { /* no-op */ }
    public static void glfwSetCursor(long window, long cursor) { /* no-op */ }
    public static boolean glfwRawMouseMotionSupported() { return false; }

    // --- misc / time ---
    public static double glfwGetTime() { return System.nanoTime() / 1e9; }
    public static void glfwSetTime(double time) { /* no-op */ }
    public static long glfwGetTimerValue() { return System.nanoTime(); }
    public static long glfwGetTimerFrequency() { return 1_000_000_000L; }
    public static String glfwGetVersionString() { return "3.4.1 (sk-launcher shadow)"; }
    public static void glfwGetVersion(int[] major, int[] minor, int[] rev) {
        if (major != null && major.length > 0) major[0] = 3;
        if (minor != null && minor.length > 0) minor[0] = 4;
        if (rev != null && rev.length > 0)   rev[0]   = 1;
    }
    public static String glfwGetKeyName(int key, int scancode) { return null; }
    public static int glfwGetKeyScancode(int key) { return key; }

    // --- joystick / gamepad ---
    // We expose a single virtual controller on jid 0, backed by Android input
    // state stashed natively. SK polls these every frame in DisplayRoot.
    // Android already normalizes to the GLFW standard layout, so glfwGetGamepadState
    // just copies the native mailbox into the LWJGL struct.
    private static final int GP_BUTTONS = 15;
    private static final int GP_AXES = 6;
    private static final byte[] gpButtons = new byte[GP_BUTTONS];
    private static final float[] gpAxes = new float[GP_AXES];
    private static native boolean nativeGamepadPresent();
    private static native boolean nativeGetGamepadState(byte[] outButtons, float[] outAxes);

    public static boolean glfwJoystickPresent(int jid) {
        return jid == GLFW_JOYSTICK_1 && nativeGamepadPresent();
    }
    public static boolean glfwJoystickIsGamepad(int jid) {
        return jid == GLFW_JOYSTICK_1 && nativeGamepadPresent();
    }
    // Raw-joystick reads: SK uses these to *validate* controller bindings
    // (PseudoKeys.isValid), separate from the gamepad-state activation path.
    // Back them with the same native state so axis/button bindings validate.
    // Direct, native-order buffers refilled per call (called on the main thread).
    private static java.nio.FloatBuffer joyAxesBuf;
    private static java.nio.ByteBuffer  joyButtonsBuf;
    public static java.nio.FloatBuffer glfwGetJoystickAxes(int jid) {
        if (jid != GLFW_JOYSTICK_1 || !nativeGetGamepadState(gpButtons, gpAxes)) return null;
        if (joyAxesBuf == null) {
            joyAxesBuf = java.nio.ByteBuffer.allocateDirect(GP_AXES * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        }
        joyAxesBuf.clear();
        joyAxesBuf.put(gpAxes, 0, GP_AXES).flip();
        return joyAxesBuf;
    }
    public static java.nio.ByteBuffer glfwGetJoystickButtons(int jid) {
        if (jid != GLFW_JOYSTICK_1 || !nativeGetGamepadState(gpButtons, gpAxes)) return null;
        if (joyButtonsBuf == null) {
            joyButtonsBuf = java.nio.ByteBuffer.allocateDirect(GP_BUTTONS)
                .order(java.nio.ByteOrder.nativeOrder());
        }
        joyButtonsBuf.clear();
        joyButtonsBuf.put(gpButtons, 0, GP_BUTTONS).flip();
        return joyButtonsBuf;
    }
    public static java.nio.ByteBuffer   glfwGetJoystickHats(int jid)    { return null; }
    public static String glfwGetJoystickName(int jid) { return null; }
    public static String glfwGetJoystickGUID(int jid) { return null; }
    public static String glfwGetGamepadName(int jid) { return "android-gamepad"; }
    public static boolean glfwGetGamepadState(int jid, GLFWGamepadState state) {
        if (jid != GLFW_JOYSTICK_1 || state == null) return false;
        if (!nativeGetGamepadState(gpButtons, gpAxes)) return false;
        for (int i = 0; i < GP_BUTTONS; i++) state.buttons(i, gpButtons[i]);
        for (int i = 0; i < GP_AXES; i++) state.axes(i, gpAxes[i]);
        return true;
    }
    public static void glfwSetJoystickUserPointer(int jid, long pointer) { /* no-op */ }
    public static long glfwGetJoystickUserPointer(int jid) { return 0L; }
    public static int glfwUpdateGamepadMappings(java.nio.ByteBuffer mapping) { return 0; }
    public static int glfwUpdateGamepadMappings(CharSequence mapping) { return 0; }
    public static GLFWJoystickCallback glfwSetJoystickCallback(GLFWJoystickCallbackI cb) { return null; }
}
