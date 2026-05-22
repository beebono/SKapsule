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

    // --- minimum viable surface ---
    public static boolean glfwInit() {
        return true;
    }

    public static void glfwTerminate() { /* no-op */ }

    public static void glfwInitHint(int hint, int value) { /* no-op */ }

    public static void glfwPollEvents() { /* no-op until we wire input */ }

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

    // --- callbacks: store and forget for now ---
    public static GLFWErrorCallback glfwSetErrorCallback(GLFWErrorCallbackI cb) { return null; }
    public static GLFWKeyCallback glfwSetKeyCallback(long window, GLFWKeyCallbackI cb) { return null; }
    public static GLFWCharCallback glfwSetCharCallback(long window, GLFWCharCallbackI cb) { return null; }
    public static GLFWCharModsCallback glfwSetCharModsCallback(long window, GLFWCharModsCallbackI cb) { return null; }
    public static GLFWMouseButtonCallback glfwSetMouseButtonCallback(long window, GLFWMouseButtonCallbackI cb) { return null; }
    public static GLFWCursorPosCallback glfwSetCursorPosCallback(long window, GLFWCursorPosCallbackI cb) { return null; }
    public static GLFWCursorEnterCallback glfwSetCursorEnterCallback(long window, GLFWCursorEnterCallbackI cb) { return null; }
    public static GLFWScrollCallback glfwSetScrollCallback(long window, GLFWScrollCallbackI cb) { return null; }
    public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(long window, GLFWFramebufferSizeCallbackI cb) { return null; }
    public static GLFWWindowSizeCallback glfwSetWindowSizeCallback(long window, GLFWWindowSizeCallbackI cb) { return null; }
    public static GLFWWindowPosCallback glfwSetWindowPosCallback(long window, GLFWWindowPosCallbackI cb) { return null; }
    public static GLFWWindowFocusCallback glfwSetWindowFocusCallback(long window, GLFWWindowFocusCallbackI cb) { return null; }
    public static GLFWWindowCloseCallback glfwSetWindowCloseCallback(long window, GLFWWindowCloseCallbackI cb) { return null; }
    public static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI cb) { return null; }
    public static GLFWWindowMaximizeCallback glfwSetWindowMaximizeCallback(long window, GLFWWindowMaximizeCallbackI cb) { return null; }
    public static GLFWWindowRefreshCallback glfwSetWindowRefreshCallback(long window, GLFWWindowRefreshCallbackI cb) { return null; }
    public static GLFWDropCallback glfwSetDropCallback(long window, GLFWDropCallbackI cb) { return null; }
    public static GLFWMonitorCallback glfwSetMonitorCallback(GLFWMonitorCallbackI cb) { return null; }

    // --- input state ---
    public static int glfwGetKey(long window, int key) { return 0 /* GLFW_RELEASE */; }
    public static int glfwGetMouseButton(long window, int button) { return 0; }
    public static void glfwGetCursorPos(long window, double[] xs, double[] ys) {
        if (xs != null && xs.length > 0) xs[0] = 0;
        if (ys != null && ys.length > 0) ys[0] = 0;
    }
    public static void glfwSetCursorPos(long window, double x, double y) { /* no-op */ }
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

    // --- joystick / gamepad: We WILL need these, but for now... ---
    public static boolean glfwJoystickPresent(int jid) { return false; }
    public static boolean glfwJoystickIsGamepad(int jid) { return false; }
    public static java.nio.FloatBuffer  glfwGetJoystickAxes(int jid)    { return null; }
    public static java.nio.ByteBuffer   glfwGetJoystickButtons(int jid) { return null; }
    public static java.nio.ByteBuffer   glfwGetJoystickHats(int jid)    { return null; }
    public static String glfwGetJoystickName(int jid) { return null; }
    public static String glfwGetJoystickGUID(int jid) { return null; }
    public static String glfwGetGamepadName(int jid) { return null; }
    public static boolean glfwGetGamepadState(int jid, GLFWGamepadState state) { return false; }
    public static void glfwSetJoystickUserPointer(int jid, long pointer) { /* no-op */ }
    public static long glfwGetJoystickUserPointer(int jid) { return 0L; }
    public static int glfwUpdateGamepadMappings(java.nio.ByteBuffer mapping) { return 0; }
    public static int glfwUpdateGamepadMappings(CharSequence mapping) { return 0; }
    public static GLFWJoystickCallback glfwSetJoystickCallback(GLFWJoystickCallbackI cb) { return null; }
}
