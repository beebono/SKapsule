// SK launcher native side.

#include <jni.h>
#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

// Avoid pulling in jni.h for one define
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

#define LOG_TAG "sklauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// --- shared state -------------------------------------------------------------

static struct {
    ANativeWindow *window;
    EGLDisplay display;
    EGLConfig  config;
    EGLContext context;
    EGLSurface surface;
    int width, height;
    bool gl4es_initialized;
    // Set when the window surface changes (first bring-up / Android resume) so SK's
    // render thread re-binds it on its next frame — only that thread can make the
    // EGL context current. Display+context outlive surface loss to keep GL resources.
    atomic_int surface_dirty;
} gfx = {
    .display = EGL_NO_DISPLAY,
    .context = EGL_NO_CONTEXT,
    .surface = EGL_NO_SURFACE,
};

// Serializes surface lifecycle (UI thread create/destroy) against the render
// thread's bind/swap, so a background teardown can't free a surface mid-eglSwapBuffers.
static pthread_mutex_t egl_lock = PTHREAD_MUTEX_INITIALIZER;

static struct {
    pthread_t thread;
    atomic_bool started;
    char jre_home[1024];
    char classpath[8192];
    char lib_path[2048];
    char app_files[1024];
    char cacio_dir[1024];
    char frenchpress_jar[1024];  // prepended to SK classpath (-Dfrenchpress.jar); empty = off
    char cred_file[1024];        // FRENCHPRESS_CRED_FILE (Steam refresh-token store)
    char steam_user[256];        // FRENCHPRESS_STEAM_USER (first login only); empty = web
    char steam_pass[256];        // FRENCHPRESS_STEAM_PASS
} jvm = { .started = ATOMIC_VAR_INIT(false) };

// --- input event ring buffer --------------------------------------------------
// Filled on the Android UI thread (NativeBridge JNI calls), drained on SK's
// main loop thread (GLFW.glfwPollEvents -> nativeDrainInput). Each event is a
// 4-int record [type, a, b, c]; the type codes mirror GLFW.java.
#define EV_CURSOR_POS   1
#define EV_MOUSE_BUTTON 2
#define EV_SCROLL       3
#define EV_KEY          4
#define EV_CHAR         5

#define INPUT_Q_CAP 2048   // power-of-two-ish; must exceed a frame's worth of events
static struct {
    pthread_mutex_t lock;
    int32_t buf[INPUT_Q_CAP][4];
    int head;   // next read
    int tail;   // next write
} input_q = { .lock = PTHREAD_MUTEX_INITIALIZER };

static void input_push(int32_t type, int32_t a, int32_t b, int32_t c) {
    pthread_mutex_lock(&input_q.lock);
    int next = (input_q.tail + 1) % INPUT_Q_CAP;
    if (next != input_q.head) {           // drop silently if full
        input_q.buf[input_q.tail][0] = type;
        input_q.buf[input_q.tail][1] = a;
        input_q.buf[input_q.tail][2] = b;
        input_q.buf[input_q.tail][3] = c;
        input_q.tail = next;
    }
    pthread_mutex_unlock(&input_q.lock);
}

// --- gamepad state (polled, not queued) ---------------------------------------
// SK reads this each frame via GLFW.glfwGetGamepadState. Android writes button/
// axis changes from its UI thread. Values are already normalized to the GLFW
// standard gamepad layout on the Android side (15 buttons, 6 axes; trigger axes
// rest=-1..pressed=+1), so this is a dumb mailbox.
#define GP_BUTTONS 15
#define GP_AXES    6
static struct {
    pthread_mutex_t lock;
    atomic_bool present;
    int8_t buttons[GP_BUTTONS];
    float  axes[GP_AXES];
} gamepad = { .lock = PTHREAD_MUTEX_INITIALIZER };

// --- EGL helpers --------------------------------------------------------------

static const char *egl_err_str(EGLint e) {
    switch (e) {
        case EGL_SUCCESS:             return "EGL_SUCCESS";
        case EGL_NOT_INITIALIZED:     return "EGL_NOT_INITIALIZED";
        case EGL_BAD_ACCESS:          return "EGL_BAD_ACCESS";
        case EGL_BAD_ALLOC:           return "EGL_BAD_ALLOC";
        case EGL_BAD_ATTRIBUTE:       return "EGL_BAD_ATTRIBUTE";
        case EGL_BAD_CONFIG:          return "EGL_BAD_CONFIG";
        case EGL_BAD_CONTEXT:         return "EGL_BAD_CONTEXT";
        case EGL_BAD_CURRENT_SURFACE: return "EGL_BAD_CURRENT_SURFACE";
        case EGL_BAD_DISPLAY:         return "EGL_BAD_DISPLAY";
        case EGL_BAD_MATCH:           return "EGL_BAD_MATCH";
        case EGL_BAD_NATIVE_PIXMAP:   return "EGL_BAD_NATIVE_PIXMAP";
        case EGL_BAD_NATIVE_WINDOW:   return "EGL_BAD_NATIVE_WINDOW";
        case EGL_BAD_PARAMETER:       return "EGL_BAD_PARAMETER";
        case EGL_BAD_SURFACE:         return "EGL_BAD_SURFACE";
        case EGL_CONTEXT_LOST:        return "EGL_CONTEXT_LOST";
        default:                      return "EGL_UNKNOWN";
    }
}

// Brings up the EGL surface for the current window. Display + context are created
// once and intentionally kept alive across surface loss (Android backgrounding) so
// SK's GL resources survive; only the window surface is (re)created here. Runs on
// the UI thread; SK's render thread binds the result via surface_dirty.
static bool egl_bring_up(void) {
    if (!gfx.window) { LOGE("egl_bring_up: no ANativeWindow"); return false; }

    pthread_mutex_lock(&egl_lock);
    bool ok = false;

    if (gfx.display == EGL_NO_DISPLAY) {
        gfx.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (gfx.display == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed"); goto out; }

        EGLint major = 0, minor = 0;
        if (!eglInitialize(gfx.display, &major, &minor)) {
            LOGE("eglInitialize failed: %s", egl_err_str(eglGetError())); goto out;
        }
        LOGI("EGL %d.%d initialized (vendor=%s)",
             major, minor, eglQueryString(gfx.display, EGL_VENDOR));

        const EGLint attribs[] = {
            EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE,        8,
            EGL_GREEN_SIZE,      8,
            EGL_BLUE_SIZE,       8,
            EGL_ALPHA_SIZE,      8,
            EGL_DEPTH_SIZE,      24,
            EGL_STENCIL_SIZE,    8,
            EGL_NONE
        };
        EGLint num_configs = 0;
        if (!eglChooseConfig(gfx.display, attribs, &gfx.config, 1, &num_configs) || num_configs < 1) {
            LOGE("eglChooseConfig failed: %s", egl_err_str(eglGetError())); goto out;
        }
    }

    if (gfx.context == EGL_NO_CONTEXT) {
        const EGLint ctx_attribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
        gfx.context = eglCreateContext(gfx.display, gfx.config, EGL_NO_CONTEXT, ctx_attribs);
        if (gfx.context == EGL_NO_CONTEXT) {
            LOGE("eglCreateContext failed: %s", egl_err_str(eglGetError())); goto out;
        }
    }

    if (gfx.surface == EGL_NO_SURFACE) {
        EGLint native_visual_id = 0;
        eglGetConfigAttrib(gfx.display, gfx.config, EGL_NATIVE_VISUAL_ID, &native_visual_id);
        ANativeWindow_setBuffersGeometry(gfx.window, 0, 0, native_visual_id);

        gfx.surface = eglCreateWindowSurface(gfx.display, gfx.config, gfx.window, NULL);
        if (gfx.surface == EGL_NO_SURFACE) {
            LOGE("eglCreateWindowSurface failed: %s", egl_err_str(eglGetError())); goto out;
        }
    }

    // Hand the (new) surface to SK's render thread to make current on its next frame.
    atomic_store(&gfx.surface_dirty, 1);
    ok = true;
out:
    pthread_mutex_unlock(&egl_lock);
    return ok;
}

static bool egl_make_current_on_caller_thread(void) {
    if (!eglMakeCurrent(gfx.display, gfx.surface, gfx.surface, gfx.context)) {
        LOGE("eglMakeCurrent (caller thread) failed: %s", egl_err_str(eglGetError()));
        return false;
    }
    // SK calls glfwMakeContextCurrent several times at startup (and we bind twice per
    // call around gl4es bring-up), so log the make-current banner + invariant GL identity
    // only once. Multitask-resume re-binds are logged separately (render_thread_rebind_if_dirty).
    static bool logged_once = false;
    if (!logged_once) {
        logged_once = true;
        LOGI("EGL current on tid=%ld; ctx=%p surface=%p",
             (long)pthread_self(), gfx.context, gfx.surface);
        LOGI("native GL_VERSION:  %s", glGetString(GL_VERSION));
        LOGI("native GL_VENDOR:   %s", glGetString(GL_VENDOR));
        LOGI("native GL_RENDERER: %s", glGetString(GL_RENDERER));
    }
    return true;
}

// Releases only the window surface on backgrounding (Android destroys the native
// window). The EGLDisplay/EGLContext (and gl4es state) are intentionally KEPT so
// SK's GL resources survive; on resume egl_bring_up() makes a fresh surface against
// the same context. eglDestroySurface is deferred by EGL if the surface is still
// current on the render thread; the EGLSurface holds its own window ref, so we
// destroy it before dropping ours.
static void egl_release_surface(void) {
    pthread_mutex_lock(&egl_lock);
    if (gfx.display != EGL_NO_DISPLAY && gfx.surface != EGL_NO_SURFACE) {
        eglDestroySurface(gfx.display, gfx.surface);
    }
    gfx.surface = EGL_NO_SURFACE;
    if (gfx.window) {
        ANativeWindow_release(gfx.window);
        gfx.window = NULL;
    }
    gfx.width = gfx.height = 0;
    pthread_mutex_unlock(&egl_lock);
}

// Called on SK's render thread (the only thread that may make the context current).
// When the surface changed (first bring-up or resume), bind it before rendering.
static void render_thread_rebind_if_dirty(void) {
    if (!atomic_exchange(&gfx.surface_dirty, 0)) return;
    pthread_mutex_lock(&egl_lock);
    if (gfx.surface != EGL_NO_SURFACE &&
        !eglMakeCurrent(gfx.display, gfx.surface, gfx.surface, gfx.context)) {
        LOGW("rebind eglMakeCurrent failed: %s", egl_err_str(eglGetError()));
        atomic_store(&gfx.surface_dirty, 1); // try again next frame
    } else if (gfx.surface != EGL_NO_SURFACE) {
        LOGI("EGL surface (re)bound on render tid=%ld surface=%p",
             (long)pthread_self(), gfx.surface);
    }
    pthread_mutex_unlock(&egl_lock);
}

// --- gl4es activation ---------------------------------------------------------

static void gl4es_bring_up_once(void) {
    if (gfx.gl4es_initialized) return;

    // GL4ES envvars
    setenv("LIBGL_NOBANNER",       "1", 0);

    void *gl4es = dlopen("libgl4es.so", RTLD_NOW | RTLD_GLOBAL);
    if (!gl4es) { LOGW("dlopen libgl4es.so failed: %s", dlerror()); return; }

    void (*initialize_gl4es)(void) = dlsym(gl4es, "initialize_gl4es");
    const GLubyte *(*gl4es_glGetString)(GLenum) = dlsym(gl4es, "glGetString");
    if (!initialize_gl4es || !gl4es_glGetString) {
        LOGE("dlsym from libgl4es.so failed: %s", dlerror()); return;
    }

    initialize_gl4es();
    LOGI("gl4es GL_VERSION:  %s", gl4es_glGetString(GL_VERSION));
    LOGI("gl4es GL_VENDOR:   %s", gl4es_glGetString(GL_VENDOR));
    LOGI("gl4es GL_RENDERER: %s", gl4es_glGetString(GL_RENDERER));
    LOGI("gl4es initialized on tid=%ld", (long)pthread_self());
    gfx.gl4es_initialized = true;
}

// --- JVM bootstrap (pthread) --------------------------------------------------

typedef jint (JNICALL *JNI_CreateJavaVM_t)(JavaVM **, void **, void *);

JNIEXPORT void JNICALL
Java_com_skarm_launcher_bootstrap_SkBootstrap_registerGlfwNatives(JNIEnv *env, jclass thiz,
                                                                  jclass glfwClass);

// Prevent stdout/err from getting sent into the abyss
typedef struct {
    int read_fd;
    int level;
    const char *tag;
} pump_arg_t;

// Known-benign third-party noise dropped from sk-stdout/sk-stderr. We can't silence
// these at the source (closed SK jar, binary getdown-pro.jar, the LWJGL fork's Pojav
// hook), so we filter the redirect instead. Substring match; keep patterns specific so
// real errors still get through. See docs/current-status CONFIRMED BENIGN LOG NOISE.
static const char *const BENIGN_NOISE[] = {
    "no pojavexec in java.library.path",            // LWJGL fork Pojav hook; load fails, MemoryUtil fine after
    "Unable to parse VM version",                   // getdown regex rejects '25.0.3-internal', proceeds anyway
    "Found joystick! [name=null]",                  // gamepad detected w/ null name; input works
    "::staticFieldBase will be removed",            // cacio CTCPreloadAgent JDK25 deprecation warnings (4 lines)
    "terminally deprecated method in sun.misc.Unsafe",
    "CTCPreloadAgent",
    "SLF4J",                                        // slf4j-api (via frenchpress/ktor) no-provider warning, NOPs
};

static bool line_is_benign(const char *line) {
    for (size_t i = 0; i < sizeof BENIGN_NOISE / sizeof BENIGN_NOISE[0]; i++) {
        if (strstr(line, BENIGN_NOISE[i])) return true;
    }
    return false;
}

static void *stdio_pump(void *arg) {
    pump_arg_t *p = (pump_arg_t *)arg;
    char line[2048];
    int line_len = 0;
    char buf[1024];
    bool dropping_stack = false;   // swallow the pojavexec exception's trailing frames
    while (1) {
        ssize_t n = read(p->read_fd, buf, sizeof buf);
        if (n <= 0) break;
        for (ssize_t i = 0; i < n; i++) {
            char c = buf[i];
            if (c == '\n' || line_len == (int)sizeof line - 1) {
                line[line_len] = '\0';
                if (line_len > 0) {
                    if (line_is_benign(line)) {
                        // Drop it; if it's the pojavexec error, also drop the indented
                        // stack frames that follow (until the next non-frame line).
                        dropping_stack = strstr(line, "no pojavexec") != NULL;
                    } else if (dropping_stack && line[0] == '\t') {
                        // skip "\tat ..." frame belonging to the suppressed benign error
                    } else {
                        dropping_stack = false;
                        __android_log_write(p->level, p->tag, line);
                    }
                }
                line_len = 0;
            } else if (c != '\r') {
                line[line_len++] = c;
            }
        }
    }
    return NULL;
}

static void redirect_stdio_to_log(void) {
    static bool done = false;
    if (done) return;
    done = true;

    int outp[2], errp[2];
    if (pipe(outp) || pipe(errp)) { LOGE("pipe() failed for stdio redirect"); return; }
    dup2(outp[1], STDOUT_FILENO); close(outp[1]);
    dup2(errp[1], STDERR_FILENO); close(errp[1]);
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IOLBF, 0);

    pump_arg_t *out_arg = malloc(sizeof *out_arg);
    *out_arg = (pump_arg_t){ .read_fd = outp[0], .level = ANDROID_LOG_INFO,  .tag = "sk-stdout" };
    pump_arg_t *err_arg = malloc(sizeof *err_arg);
    *err_arg = (pump_arg_t){ .read_fd = errp[0], .level = ANDROID_LOG_ERROR, .tag = "sk-stderr" };

    pthread_t to, te;
    pthread_create(&to, NULL, stdio_pump, out_arg); pthread_detach(to);
    pthread_create(&te, NULL, stdio_pump, err_arg); pthread_detach(te);

    LOGI("stdout/stderr redirected to sk-stdout / sk-stderr tags");
}

static void preload_dir(const char *dir, int max_passes) {
    DIR *d = opendir(dir);
    if (!d) { LOGW("preload_dir: opendir %s: %s", dir, strerror(errno)); return; }

    char files[64][128];
    int n = 0;
    struct dirent *e;
    while ((e = readdir(d)) && n < 64) {
        size_t len = strlen(e->d_name);
        if (len < 4 || strcmp(e->d_name + len - 3, ".so") != 0) continue;
        snprintf(files[n++], sizeof files[0], "%s", e->d_name);
    }
    closedir(d);

    bool loaded[64] = { false };
    for (int pass = 0; pass < max_passes; pass++) {
        int progress = 0;
        for (int i = 0; i < n; i++) {
            if (loaded[i]) continue;
            char path[1500];
            snprintf(path, sizeof path, "%s/%s", dir, files[i]);
            if (dlopen(path, RTLD_NOW | RTLD_GLOBAL)) {
                loaded[i] = true;
                progress++;
            }
        }
        if (!progress) break;
    }
    int loaded_count = 0;
    for (int i = 0; i < n; i++) if (loaded[i]) loaded_count++;
    LOGI("preload %s: %d/%d", dir, loaded_count, n);
    for (int i = 0; i < n; i++) {
        if (!loaded[i]) {
            char path[1500];
            snprintf(path, sizeof path, "%s/%s", dir, files[i]);
            (void)dlopen(path, RTLD_NOW);
            LOGW("  unresolved: %s (%s)", files[i], dlerror());
        }
    }
}

static void preload_libpath(const char *lib_path) {
    char buf[2400];
    strncpy(buf, lib_path, sizeof buf - 1);
    buf[sizeof buf - 1] = '\0';
    char *saveptr = NULL;
    for (char *tok = strtok_r(buf, ":", &saveptr); tok;
         tok = strtok_r(NULL, ":", &saveptr)) {
        preload_dir(tok, 6);
    }
}

static void log_pending_exception(JNIEnv *env, const char *prefix) {
    if (!(*env)->ExceptionCheck(env)) return;
    jthrowable t = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    if (!t) { LOGE("%s: <unreadable exception>", prefix); return; }
    jclass thr_class = (*env)->GetObjectClass(env, t);
    jmethodID to_string = (*env)->GetMethodID(env, thr_class, "toString",
                                              "()Ljava/lang/String;");
    jstring desc = (*env)->CallObjectMethod(env, t, to_string);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); desc = NULL; }
    if (desc) {
        const char *c = (*env)->GetStringUTFChars(env, desc, NULL);
        LOGE("%s: %s", prefix, c);
        (*env)->ReleaseStringUTFChars(env, desc, c);
        (*env)->DeleteLocalRef(env, desc);
    } else {
        LOGE("%s: <toString() unavailable>", prefix);
    }
    (*env)->DeleteLocalRef(env, thr_class);
    (*env)->DeleteLocalRef(env, t);
}

static void *jvm_thread_main(void *arg) {
    (void)arg;
    redirect_stdio_to_log();
    LOGI("JVM thread: home=%s tid=%ld", jvm.jre_home, (long)pthread_self());

    // Export the native search path so (a) jspawnhelper, exec'd as a standalone
    // binary, can resolve its NEEDED libc++_shared.so from the app's
    // nativeLibraryDir, and (b) cacio's CTCScreen.<clinit> getenv("LD_LIBRARY_PATH")
    // is non-null (else it NPEs probing for the unused libpojavexec_awt.so).
    // lib_path already carries jre25/lib:lwjgl/lib:<nativeLibraryDir> from Kotlin.
    setenv("LD_LIBRARY_PATH", jvm.lib_path, 1);

    // frenchpress (Steam-login shim) env. Must be set before JNI_CreateJavaVM so
    // System.getenv sees them. CRED_FILE points the FileCredentialStore at our
    // user.home; STEAM_USER/PASS are present only on a first Steam login — absent
    // for web login, which frenchpress reads as an empty-username web account.
    if (jvm.cred_file[0])  setenv("FRENCHPRESS_CRED_FILE",  jvm.cred_file,  1);
    if (jvm.steam_user[0]) setenv("FRENCHPRESS_STEAM_USER", jvm.steam_user, 1);
    if (jvm.steam_pass[0]) setenv("FRENCHPRESS_STEAM_PASS", jvm.steam_pass, 1);

    for (int i = 0; i < 50 && gfx.surface == EGL_NO_SURFACE; i++) {
        usleep(20 * 1000);
    }
    if (gfx.surface == EGL_NO_SURFACE) {
        LOGE("JVM thread: EGL never came up; aborting");
        return NULL;
    }

    char libjli_path[1100];
    char libjvm_path[1100];
    snprintf(libjli_path, sizeof libjli_path, "%s/lib/libjli.so",       jvm.jre_home);
    snprintf(libjvm_path, sizeof libjvm_path, "%s/lib/server/libjvm.so", jvm.jre_home);

    void *jli = dlopen(libjli_path, RTLD_NOW | RTLD_GLOBAL);
    if (!jli) { LOGE("dlopen libjli.so failed: %s", dlerror()); return NULL; }

    void *jvm_lib = dlopen(libjvm_path, RTLD_NOW | RTLD_GLOBAL);
    if (!jvm_lib) { LOGE("dlopen libjvm.so failed: %s", dlerror()); return NULL; }

    JNI_CreateJavaVM_t JNI_CreateJavaVM_p = dlsym(jvm_lib, "JNI_CreateJavaVM");
    if (!JNI_CreateJavaVM_p) { LOGE("dlsym JNI_CreateJavaVM failed: %s", dlerror()); return NULL; }

    preload_libpath(jvm.lib_path);
    char serverdir[1300];
    snprintf(serverdir, sizeof serverdir, "%s/lib/server", jvm.jre_home);
    preload_dir(serverdir, 6);

    char opt_home[1200];
    char opt_libpath[2200];
    char opt_classpath[8400];
    char opt_appdir[1400];
    char opt_rsrcdir[1400];
    char opt_crucibledir[1400];
    char opt_tmpdir[1400];
    char opt_userhome[1400];
    char opt_prefs_user[1400];
    char opt_prefs_sys[1400];
    snprintf(opt_home,      sizeof opt_home,      "-Djava.home=%s",         jvm.jre_home);
    snprintf(opt_libpath,   sizeof opt_libpath,   "-Djava.library.path=%s", jvm.lib_path);
    snprintf(opt_classpath, sizeof opt_classpath, "-Djava.class.path=%s",   jvm.classpath);
    snprintf(opt_appdir,      sizeof opt_appdir,      "-Dappdir=%s/sk",                jvm.app_files);
    snprintf(opt_rsrcdir,     sizeof opt_rsrcdir,     "-Dresource_dir=%s/sk/rsrc",     jvm.app_files);
    snprintf(opt_crucibledir, sizeof opt_crucibledir, "-Dcrucible.dir=%s/sk/crucible", jvm.app_files);
    // Redirect Linux-style /tmp to writable for Android to fix font loading
    snprintf(opt_tmpdir,      sizeof opt_tmpdir,      "-Djava.io.tmpdir=%s/sk/tmp",    jvm.app_files);
    // Writable HOME (outside the getdown-managed sk/ tree so updates never wipe it).
    // Without a writable user.home + prefs roots, java.util.prefs FileSystemPreferences
    // can't create/lock its dir -> the recurring "Could not lock User prefs" warnings
    // and SK's settings (clyde ResourceUtil._prefs) never persist. user.home also backs
    // frenchpress's Steam creds path (user.home/.local/share/frenchpress/steam.creds).
    // Dirs are pre-created in Kotlin (GameActivity) to match these paths.
    snprintf(opt_userhome,   sizeof opt_userhome,   "-Duser.home=%s/home",                       jvm.app_files);
    snprintf(opt_prefs_user, sizeof opt_prefs_user, "-Djava.util.prefs.userRoot=%s/home/.userPrefs",   jvm.app_files);
    snprintf(opt_prefs_sys,  sizeof opt_prefs_sys,  "-Djava.util.prefs.systemRoot=%s/home/.systemPrefs", jvm.app_files);

    // frenchpress Steam-login shim (optional): jar prepended to SK's classpath via
    // SkBootstrap (-Dfrenchpress.jar) so its froth/SteamAPI classes shadow projectx's.
    bool frenchpress = jvm.frenchpress_jar[0] != '\0';
    char opt_fp_jar[1100];
    if (frenchpress) {
        snprintf(opt_fp_jar, sizeof opt_fp_jar, "-Dfrenchpress.jar=%s", jvm.frenchpress_jar);
    }

    // caciocavallo AWT bridge (optional): non-headless toolkit so SK's cursor/
    // dialog/clipboard AWT calls work without X11. Enabled when cacio_dir is set.
    bool cacio = jvm.cacio_dir[0] != '\0';
    char opt_cacio_bootcp[2400];
    char opt_cacio_agent[1200];
    char opt_cacio_screensize[64];
    if (cacio) {
        snprintf(opt_cacio_bootcp, sizeof opt_cacio_bootcp,
                 "-Xbootclasspath/a:%s/cacio-shared.jar:%s/cacio-tta.jar",
                 jvm.cacio_dir, jvm.cacio_dir);
        snprintf(opt_cacio_agent, sizeof opt_cacio_agent,
                 "-javaagent:%s/cacio-tta.jar", jvm.cacio_dir);
        int sw = gfx.width  > 0 ? gfx.width  : 1280;
        int sh = gfx.height > 0 ? gfx.height : 720;
        snprintf(opt_cacio_screensize, sizeof opt_cacio_screensize,
                 "-Dcacio.managed.screensize=%dx%d", sw, sh);
    }

    JavaVMOption options[64];
    int nopt = 0;
    #define ADD_OPT(s) do { \
        if (nopt < (int)(sizeof options / sizeof options[0])) { \
            options[nopt].optionString = (char *)(s); \
            options[nopt].extraInfo = NULL; \
            nopt++; \
        } \
    } while (0)

    ADD_OPT(opt_home);
    ADD_OPT(opt_libpath);
    ADD_OPT(opt_classpath);
    ADD_OPT(opt_appdir);
    ADD_OPT(opt_rsrcdir);
    ADD_OPT(opt_crucibledir);
    ADD_OPT(opt_tmpdir);
    ADD_OPT(opt_userhome);
    ADD_OPT(opt_prefs_user);
    ADD_OPT(opt_prefs_sys);
    ADD_OPT("-Dorg.lwjgl.util.NoChecks=true");
    // disable_steam_api is misleadingly named: it only stops froth-foamy from loading
    // the native libsteam_api.so (which we never ship on Android) — froth still issues
    // its Steam calls and falls back gracefully. Kept ALWAYS; frenchpress's shimmed
    // SteamAPI.init() runs regardless and drives login via JavaSteam when creds are set.
    ADD_OPT("-Dcom.threerings.froth.disable_steam_api=true");
    if (frenchpress) {
        ADD_OPT(opt_fp_jar);
        // Force the headless (env-var) credential prompt; otherwise frenchpress would
        // pick SwingCredentialPrompt on the non-headless cacio AWT and render into an
        // invisible buffer. With no FRENCHPRESS_STEAM_USER this yields a web account.
        ADD_OPT("-Dfrenchpress.credentialPrompt=co.frenchpress.HeadlessEnvPrompt");
    }
    ADD_OPT("-Dno_log_redir=true");
    ADD_OPT("-Dsilent=launch");
    ADD_OPT("--add-opens=java.base/java.lang=ALL-UNNAMED");
    ADD_OPT("--add-opens=java.base/java.util=ALL-UNNAMED");
    ADD_OPT("--enable-native-access=ALL-UNNAMED");
    ADD_OPT("-XX:-CreateCoredumpOnCrash");
    ADD_OPT("-XX:+SuppressFatalErrorMessage");

    if (cacio) {
        ADD_OPT(opt_cacio_bootcp);
        ADD_OPT(opt_cacio_agent);   // cacio-tta PreMain-Class preloads the toolkit
        ADD_OPT("-Djava.awt.headless=false");
        ADD_OPT("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit");
        ADD_OPT("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment");
        ADD_OPT(opt_cacio_screensize);
        ADD_OPT("-Dcacio.font.fontmanager=sun.awt.X11FontManager");
        ADD_OPT("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler");
        // java.desktop internals cacio reflects into (from the cacio pom argLine).
        ADD_OPT("--add-exports=java.desktop/java.awt=ALL-UNNAMED");
        ADD_OPT("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED");
        ADD_OPT("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
        ADD_OPT("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED");
        ADD_OPT("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED");
        ADD_OPT("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED");
        ADD_OPT("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED");
        ADD_OPT("--add-exports=java.desktop/sun.java2d.pipe=ALL-UNNAMED");
        ADD_OPT("--add-exports=java.desktop/sun.font=ALL-UNNAMED");
        ADD_OPT("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED");
        ADD_OPT("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
        ADD_OPT("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED");
        ADD_OPT("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
        // cacio FontManagerUtil setAccessible's sun.font.FontManagerFactory.instance:
        // needs opens (not just exports). (java.base/sun.security.action dropped: that
        // package isn't in JRE 25, so the export warned and did nothing.)
        ADD_OPT("--add-opens=java.desktop/sun.font=ALL-UNNAMED");
        LOGI("cacio AWT bridge enabled (dir=%s)", jvm.cacio_dir);
    }
    #undef ADD_OPT

    JavaVMInitArgs args = {
        .version            = JNI_VERSION_1_8,
        .options            = options,
        .nOptions           = nopt,
        .ignoreUnrecognized = JNI_FALSE,
    };

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    LOGI("JVM thread: JNI_CreateJavaVM ...");
    jint rc = JNI_CreateJavaVM_p(&vm, (void **)&env, &args);
    if (rc != JNI_OK) { LOGE("JNI_CreateJavaVM failed: rc=%d", rc); return NULL; }
    LOGI("JVM thread: JNI_CreateJavaVM OK");

    {
        jclass skBoot = (*env)->FindClass(env, "com/skarm/launcher/bootstrap/SkBootstrap");
        if (!skBoot) {
            log_pending_exception(env, "FindClass SkBootstrap (for native registration)");
        } else {
            JNINativeMethod m[] = {
                { "registerGlfwNatives", "(Ljava/lang/Class;)V",
                  (void *)Java_com_skarm_launcher_bootstrap_SkBootstrap_registerGlfwNatives },
            };
            jint rrc = (*env)->RegisterNatives(env, skBoot, m, 1);
            if (rrc != 0) {
                LOGE("RegisterNatives on SkBootstrap failed: rc=%d", rrc);
            } else {
                LOGI("RegisterNatives on SkBootstrap OK");
            }
            (*env)->DeleteLocalRef(env, skBoot);
        }
    }

    jclass system = (*env)->FindClass(env, "java/lang/System");
    if (!system) { LOGE("FindClass java/lang/System failed"); goto done; }
    jmethodID getprop = (*env)->GetStaticMethodID(env, system, "getProperty",
                                                  "(Ljava/lang/String;)Ljava/lang/String;");
    if (!getprop) { LOGE("GetStaticMethodID getProperty failed"); goto done; }

    const char *keys[] = { "java.version", "java.vm.name", "java.home", "os.arch" };
    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
        jstring key = (*env)->NewStringUTF(env, keys[i]);
        jstring val = (jstring)(*env)->CallStaticObjectMethod(env, system, getprop, key);
        if (val) {
            const char *c = (*env)->GetStringUTFChars(env, val, NULL);
            LOGI("JVM: %s = %s", keys[i], c);
            (*env)->ReleaseStringUTFChars(env, val, c);
            (*env)->DeleteLocalRef(env, val);
        } else {
            LOGW("JVM: %s = (null)", keys[i]);
        }
        (*env)->DeleteLocalRef(env, key);
    }

    {
        jclass version = (*env)->FindClass(env, "org/lwjgl/Version");
        if (!version) {
            log_pending_exception(env, "FindClass org/lwjgl/Version");
        } else {
            jmethodID gv = (*env)->GetStaticMethodID(env, version, "getVersion",
                                                    "()Ljava/lang/String;");
            jstring ver = (jstring)(*env)->CallStaticObjectMethod(env, version, gv);
            if (ver) {
                const char *c = (*env)->GetStringUTFChars(env, ver, NULL);
                LOGI("LWJGL: %s", c);
                (*env)->ReleaseStringUTFChars(env, ver, c);
                (*env)->DeleteLocalRef(env, ver);
            }
            (*env)->DeleteLocalRef(env, version);
        }
    }

    {
        jclass boot = (*env)->FindClass(env, "com/skarm/launcher/bootstrap/SkBootstrap");
        if (!boot) {
            log_pending_exception(env, "FindClass SkBootstrap");
            goto done;
        }
        jmethodID main = (*env)->GetStaticMethodID(env, boot, "main", "([Ljava/lang/String;)V");
        if (!main) {
            log_pending_exception(env, "SkBootstrap.main lookup");
            goto done;
        }

        char appdir[1300];
        snprintf(appdir, sizeof appdir, "%s/sk", jvm.app_files);
        jstring appdir_j = (*env)->NewStringUTF(env, appdir);
        jclass strcls = (*env)->FindClass(env, "java/lang/String");
        jobjectArray args = (*env)->NewObjectArray(env, 1, strcls, appdir_j);

        LOGI("invoking SkBootstrap.main(%s)", appdir);
        (*env)->CallStaticVoidMethod(env, boot, main, args);
        if ((*env)->ExceptionCheck(env)) {
            log_pending_exception(env, "SkBootstrap.main");
        } else {
            LOGI("SkBootstrap.main returned");
        }

        (*env)->DeleteLocalRef(env, args);
        (*env)->DeleteLocalRef(env, strcls);
        (*env)->DeleteLocalRef(env, appdir_j);
        (*env)->DeleteLocalRef(env, boot);
    }

done:
    // Leave the VM running for possible multitasking
    return NULL;
}

static void copy_arg(char *dst, size_t dst_sz, const char *src) {
    strncpy(dst, src, dst_sz - 1);
    dst[dst_sz - 1] = '\0';
}

static void start_jvm_thread_once(const char *jre_home, const char *classpath,
                                  const char *lib_path, const char *app_files,
                                  const char *cacio_dir, const char *frenchpress_jar,
                                  const char *cred_file, const char *steam_user,
                                  const char *steam_pass) {
    bool expected = false;
    if (!atomic_compare_exchange_strong(&jvm.started, &expected, true)) {
        LOGW("JVM thread already started");
        return;
    }
    copy_arg(jvm.jre_home,  sizeof(jvm.jre_home),  jre_home);
    copy_arg(jvm.classpath, sizeof(jvm.classpath), classpath);
    copy_arg(jvm.lib_path,  sizeof(jvm.lib_path),  lib_path);
    copy_arg(jvm.app_files, sizeof(jvm.app_files), app_files);
    copy_arg(jvm.cacio_dir, sizeof(jvm.cacio_dir), cacio_dir);
    copy_arg(jvm.frenchpress_jar, sizeof(jvm.frenchpress_jar), frenchpress_jar);
    copy_arg(jvm.cred_file,  sizeof(jvm.cred_file),  cred_file);
    copy_arg(jvm.steam_user, sizeof(jvm.steam_user), steam_user);
    copy_arg(jvm.steam_pass, sizeof(jvm.steam_pass), steam_pass);

    int rc = pthread_create(&jvm.thread, NULL, jvm_thread_main, NULL);
    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        atomic_store(&jvm.started, false);
    }
}

static void JNICALL glfw_make_current_impl(JNIEnv *env, jclass thiz, jlong window) {
    static bool logged_once = false;
    if (!logged_once) {
        logged_once = true;
        LOGI("glfwMakeContextCurrent on tid=%ld (window=0x%llx)",
             (long)pthread_self(), (long long)(unsigned long long)window);
    }
    pthread_mutex_lock(&egl_lock);
    if (gfx.surface == EGL_NO_SURFACE) {
        LOGE("glfwMakeContextCurrent: no EGL surface yet");
        pthread_mutex_unlock(&egl_lock);
        return;
    }
    if (egl_make_current_on_caller_thread()) {
        gl4es_bring_up_once();
        // gl4es does a validity test, then tears the EGL context down... so like,
        // make sure we actually still have an EGL context to prevent render errors.
        egl_make_current_on_caller_thread();
        atomic_store(&gfx.surface_dirty, 0); // we just bound the current surface
    }
    pthread_mutex_unlock(&egl_lock);
}

static jintArray JNICALL glfw_get_surface_size_impl(JNIEnv *env, jclass thiz) {
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (!arr) return NULL;
    jint dims[2] = { gfx.width, gfx.height };
    (*env)->SetIntArrayRegion(env, arr, 0, 2, dims);
    return arr;
}

static void JNICALL glfw_swap_buffers_impl(JNIEnv *env, jclass thiz, jlong window) {
    // Pick up a surface that changed while we were backgrounded (Android resume).
    render_thread_rebind_if_dirty();
    pthread_mutex_lock(&egl_lock);
    if (gfx.display != EGL_NO_DISPLAY && gfx.surface != EGL_NO_SURFACE) {
        if (!eglSwapBuffers(gfx.display, gfx.surface)) {
            EGLint err = eglGetError();
            if (err != EGL_BAD_SURFACE) {
                LOGW("eglSwapBuffers: %s", egl_err_str(err));
            }
        }
    }
    pthread_mutex_unlock(&egl_lock);
}

// Drain up to (out.length/4) event records into the caller's int[]. Returns the
// number of records written. Called from GLFW.glfwPollEvents on SK's main thread.
static jint JNICALL glfw_drain_input_impl(JNIEnv *env, jclass thiz, jintArray out) {
    if (!out) return 0;
    jsize cap = (*env)->GetArrayLength(env, out) / 4;
    if (cap <= 0) return 0;

    jint *elems = (*env)->GetIntArrayElements(env, out, NULL);
    if (!elems) return 0;

    int n = 0;
    pthread_mutex_lock(&input_q.lock);
    while (n < cap && input_q.head != input_q.tail) {
        int32_t *rec = input_q.buf[input_q.head];
        elems[n * 4 + 0] = rec[0];
        elems[n * 4 + 1] = rec[1];
        elems[n * 4 + 2] = rec[2];
        elems[n * 4 + 3] = rec[3];
        input_q.head = (input_q.head + 1) % INPUT_Q_CAP;
        n++;
    }
    pthread_mutex_unlock(&input_q.lock);

    (*env)->ReleaseIntArrayElements(env, out, elems, 0);
    return n;
}

static jboolean JNICALL glfw_gamepad_present_impl(JNIEnv *env, jclass thiz) {
    return atomic_load(&gamepad.present) ? JNI_TRUE : JNI_FALSE;
}

// Copy current gamepad state into the caller's byte[15] / float[6]. Returns
// false (and touches nothing) if no gamepad is connected.
static jboolean JNICALL glfw_get_gamepad_state_impl(JNIEnv *env, jclass thiz,
                                                    jbyteArray outButtons, jfloatArray outAxes) {
    if (!atomic_load(&gamepad.present) || !outButtons || !outAxes) return JNI_FALSE;
    int8_t btns[GP_BUTTONS];
    float  axes[GP_AXES];
    pthread_mutex_lock(&gamepad.lock);
    memcpy(btns, gamepad.buttons, sizeof btns);
    memcpy(axes, gamepad.axes,    sizeof axes);
    pthread_mutex_unlock(&gamepad.lock);
    (*env)->SetByteArrayRegion(env, outButtons, 0, GP_BUTTONS, (const jbyte *)btns);
    (*env)->SetFloatArrayRegion(env, outAxes,   0, GP_AXES,    axes);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_bootstrap_SkBootstrap_registerGlfwNatives(JNIEnv *env, jclass thiz,
                                                                  jclass glfwClass) {
    JNINativeMethod m[] = {
        { "nativeMakeContextCurrent", "(J)V",   (void *)glfw_make_current_impl },
        { "nativeSwapBuffers",        "(J)V",   (void *)glfw_swap_buffers_impl },
        { "nativeGetSurfaceSize",     "()[I",   (void *)glfw_get_surface_size_impl },
        { "nativeDrainInput",         "([I)I",  (void *)glfw_drain_input_impl },
        { "nativeGamepadPresent",     "()Z",    (void *)glfw_gamepad_present_impl },
        { "nativeGetGamepadState",    "([B[F)Z",(void *)glfw_get_gamepad_state_impl },
    };
    jint rc = (*env)->RegisterNatives(env, glfwClass, m, sizeof(m) / sizeof(m[0]));
    if (rc != 0) {
        LOGE("RegisterNatives on SK GLFW failed: rc=%d", rc);
    } else {
        LOGI("RegisterNatives on SK GLFW OK (%zu methods)", sizeof(m)/sizeof(m[0]));
    }
}

// --- JNI entries --------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_onSurfaceCreated(JNIEnv *env, jobject thiz, jobject surface) {
    if (gfx.window) {
        ANativeWindow_release(gfx.window);
        gfx.window = NULL;
    }
    gfx.window = ANativeWindow_fromSurface(env, surface);
    if (!gfx.window) { LOGE("ANativeWindow_fromSurface returned NULL"); return; }
    LOGI("onSurfaceCreated (window stashed)");
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_onSurfaceChanged(JNIEnv *env, jobject thiz,
                                                     jint width, jint height) {
    LOGI("onSurfaceChanged %dx%d", width, height);
    gfx.width = width;
    gfx.height = height;

    if (!egl_bring_up()) return;
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_onSurfaceDestroyed(JNIEnv *env, jobject thiz) {
    LOGI("onSurfaceDestroyed");
    egl_release_surface();
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_startJvm(JNIEnv *env, jobject thiz,
                                              jstring jreHome, jstring classpath,
                                              jstring libPath, jstring appFiles,
                                              jstring cacioDir, jstring frenchpressJar,
                                              jstring credFile, jstring steamUser,
                                              jstring steamPass) {
    const char *home = (*env)->GetStringUTFChars(env, jreHome,   NULL);
    const char *cp   = (*env)->GetStringUTFChars(env, classpath, NULL);
    const char *lp   = (*env)->GetStringUTFChars(env, libPath,   NULL);
    const char *af   = (*env)->GetStringUTFChars(env, appFiles,  NULL);
    const char *cd   = (*env)->GetStringUTFChars(env, cacioDir,  NULL);
    const char *fj   = (*env)->GetStringUTFChars(env, frenchpressJar, NULL);
    const char *cf   = (*env)->GetStringUTFChars(env, credFile,  NULL);
    const char *su   = (*env)->GetStringUTFChars(env, steamUser, NULL);
    const char *sp   = (*env)->GetStringUTFChars(env, steamPass, NULL);
    LOGI("startJvm requested");
    LOGI("  jreHome     = %s", home);
    LOGI("  classpath   = %s", cp);
    LOGI("  libPath     = %s", lp);
    LOGI("  appFiles    = %s", af);
    LOGI("  cacioDir    = %s", cd);
    LOGI("  frenchpress = %s", fj);
    LOGI("  credFile    = %s", cf);
    LOGI("  steamUser   = %s", su[0] ? "(set)" : "(none)");  // never log pass
    start_jvm_thread_once(home, cp, lp, af, cd, fj, cf, su, sp);
    (*env)->ReleaseStringUTFChars(env, jreHome,   home);
    (*env)->ReleaseStringUTFChars(env, classpath, cp);
    (*env)->ReleaseStringUTFChars(env, libPath,   lp);
    (*env)->ReleaseStringUTFChars(env, appFiles,  af);
    (*env)->ReleaseStringUTFChars(env, cacioDir,  cd);
    (*env)->ReleaseStringUTFChars(env, frenchpressJar, fj);
    (*env)->ReleaseStringUTFChars(env, credFile,  cf);
    (*env)->ReleaseStringUTFChars(env, steamUser, su);
    (*env)->ReleaseStringUTFChars(env, steamPass, sp);
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_launchGame(JNIEnv *env, jobject thiz,
                                                jstring gameDir, jstring loginMode) {
    LOGI("launchGame (stub)");
}

// Touch -> mouse. action: 0=down, 1=move, 2=up. x,y in framebuffer pixels
// (y-down, top-left origin), matching the EGL surface that SK renders into.
// We always emit a cursor move so the button callback (which reads
// glfwGetCursorPos) lands at the touch point, then the button on down/up.
#define TOUCH_DOWN 0
#define TOUCH_MOVE 1
#define TOUCH_UP   2
#define GLFW_MOUSE_BUTTON_LEFT 0
JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_onTouchEvent(JNIEnv *env, jobject thiz,
                                                  jint action, jint x, jint y) {
    input_push(EV_CURSOR_POS, x, y, 0);
    if (action == TOUCH_DOWN) {
        input_push(EV_MOUSE_BUTTON, GLFW_MOUSE_BUTTON_LEFT, 1 /*press*/, 0);
    } else if (action == TOUCH_UP) {
        input_push(EV_MOUSE_BUTTON, GLFW_MOUSE_BUTTON_LEFT, 0 /*release*/, 0);
    }
}

// --- gamepad: Android UI thread writes, SK main thread reads ------------------
JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_onGamepadConnected(JNIEnv *env, jobject thiz,
                                                        jboolean connected) {
    if (!connected) {
        // Zero state so a held button/axis doesn't stick after disconnect.
        pthread_mutex_lock(&gamepad.lock);
        memset(gamepad.buttons, 0, sizeof gamepad.buttons);
        memset(gamepad.axes,    0, sizeof gamepad.axes);
        pthread_mutex_unlock(&gamepad.lock);
    }
    atomic_store(&gamepad.present, connected ? true : false);
    LOGI("gamepad %s", connected ? "connected" : "disconnected");
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_onGamepadButton(JNIEnv *env, jobject thiz,
                                                     jint index, jboolean pressed) {
    if (index < 0 || index >= GP_BUTTONS) return;
    pthread_mutex_lock(&gamepad.lock);
    gamepad.buttons[index] = pressed ? 1 : 0;
    pthread_mutex_unlock(&gamepad.lock);
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_onGamepadAxis(JNIEnv *env, jobject thiz,
                                                   jint index, jfloat value) {
    if (index < 0 || index >= GP_AXES) return;
    pthread_mutex_lock(&gamepad.lock);
    gamepad.axes[index] = value;
    pthread_mutex_unlock(&gamepad.lock);
}

// --- keyboard: GLFW key transitions + typed characters -----------------------
JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_onKeyEvent(JNIEnv *env, jobject thiz,
                                                jint key, jint action, jint mods) {
    input_push(EV_KEY, key, action, mods);
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_onCharInput(JNIEnv *env, jobject thiz, jint codepoint) {
    input_push(EV_CHAR, codepoint, 0, 0);
}
