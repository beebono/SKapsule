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
} gfx = {
    .display = EGL_NO_DISPLAY,
    .context = EGL_NO_CONTEXT,
    .surface = EGL_NO_SURFACE,
};

static struct {
    pthread_t thread;
    atomic_bool started;
    char jre_home[1024];
    char classpath[8192];
    char lib_path[2048];
    char app_files[1024];
} jvm = { .started = ATOMIC_VAR_INIT(false) };

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

static bool egl_bring_up(void) {
    if (!gfx.window) { LOGE("egl_bring_up: no ANativeWindow"); return false; }
    if (gfx.surface != EGL_NO_SURFACE) return true;

    gfx.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (gfx.display == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed"); return false; }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(gfx.display, &major, &minor)) {
        LOGE("eglInitialize failed: %s", egl_err_str(eglGetError())); return false;
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
        LOGE("eglChooseConfig failed: %s", egl_err_str(eglGetError())); return false;
    }

    EGLint native_visual_id = 0;
    eglGetConfigAttrib(gfx.display, gfx.config, EGL_NATIVE_VISUAL_ID, &native_visual_id);
    ANativeWindow_setBuffersGeometry(gfx.window, 0, 0, native_visual_id);

    const EGLint ctx_attribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    gfx.context = eglCreateContext(gfx.display, gfx.config, EGL_NO_CONTEXT, ctx_attribs);
    if (gfx.context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: %s", egl_err_str(eglGetError())); return false;
    }

    gfx.surface = eglCreateWindowSurface(gfx.display, gfx.config, gfx.window, NULL);
    if (gfx.surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: %s", egl_err_str(eglGetError())); return false;
    }

    return true;
}

static bool egl_make_current_on_caller_thread(void) {
    if (!eglMakeCurrent(gfx.display, gfx.surface, gfx.surface, gfx.context)) {
        LOGE("eglMakeCurrent (caller thread) failed: %s", egl_err_str(eglGetError()));
        return false;
    }
    LOGI("EGL current on tid=%ld; ctx=%p surface=%p",
         (long)pthread_self(), gfx.context, gfx.surface);
    LOGI("native GL_VERSION:  %s", glGetString(GL_VERSION));
    LOGI("native GL_VENDOR:   %s", glGetString(GL_VENDOR));
    LOGI("native GL_RENDERER: %s", glGetString(GL_RENDERER));
    return true;
}

static void egl_tear_down(void) {
    if (gfx.display != EGL_NO_DISPLAY) {
        eglMakeCurrent(gfx.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (gfx.surface != EGL_NO_SURFACE) eglDestroySurface(gfx.display, gfx.surface);
        if (gfx.context != EGL_NO_CONTEXT) eglDestroyContext(gfx.display, gfx.context);
        eglTerminate(gfx.display);
    }
    gfx.display = EGL_NO_DISPLAY;
    gfx.context = EGL_NO_CONTEXT;
    gfx.surface = EGL_NO_SURFACE;
    gfx.gl4es_initialized = false;

    if (gfx.window) {
        ANativeWindow_release(gfx.window);
        gfx.window = NULL;
    }
    gfx.width = gfx.height = 0;
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

static void *stdio_pump(void *arg) {
    pump_arg_t *p = (pump_arg_t *)arg;
    char line[2048];
    int line_len = 0;
    char buf[1024];
    while (1) {
        ssize_t n = read(p->read_fd, buf, sizeof buf);
        if (n <= 0) break;
        for (ssize_t i = 0; i < n; i++) {
            char c = buf[i];
            if (c == '\n' || line_len == (int)sizeof line - 1) {
                line[line_len] = '\0';
                if (line_len > 0) __android_log_write(p->level, p->tag, line);
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
    snprintf(opt_home,      sizeof opt_home,      "-Djava.home=%s",         jvm.jre_home);
    snprintf(opt_libpath,   sizeof opt_libpath,   "-Djava.library.path=%s", jvm.lib_path);
    snprintf(opt_classpath, sizeof opt_classpath, "-Djava.class.path=%s",   jvm.classpath);
    snprintf(opt_appdir,      sizeof opt_appdir,      "-Dappdir=%s/sk",                jvm.app_files);
    snprintf(opt_rsrcdir,     sizeof opt_rsrcdir,     "-Dresource_dir=%s/sk/rsrc",     jvm.app_files);
    snprintf(opt_crucibledir, sizeof opt_crucibledir, "-Dcrucible.dir=%s/sk/crucible", jvm.app_files);
    // Redirect Linux-style /tmp to writable for Android to fix font loading
    snprintf(opt_tmpdir,      sizeof opt_tmpdir,      "-Djava.io.tmpdir=%s/sk/tmp",    jvm.app_files);

    JavaVMOption options[] = {
        { .optionString = opt_home,        .extraInfo = NULL },
        { .optionString = opt_libpath,     .extraInfo = NULL },
        { .optionString = opt_classpath,   .extraInfo = NULL },
        { .optionString = opt_appdir,      .extraInfo = NULL },
        { .optionString = opt_rsrcdir,     .extraInfo = NULL },
        { .optionString = opt_crucibledir, .extraInfo = NULL },
        { .optionString = opt_tmpdir,      .extraInfo = NULL },
        { .optionString = "-Dorg.lwjgl.util.NoChecks=true",                    .extraInfo = NULL },
        { .optionString = "-Dcom.threerings.froth.disable_steam_api=true",     .extraInfo = NULL },
        { .optionString = "-Dno_log_redir=true",                               .extraInfo = NULL },
        { .optionString = "--add-opens=java.base/java.lang=ALL-UNNAMED",       .extraInfo = NULL },
        { .optionString = "--add-opens=java.base/java.util=ALL-UNNAMED",       .extraInfo = NULL },
        { .optionString = "--enable-native-access=ALL-UNNAMED",                .extraInfo = NULL },
        { .optionString = "-XX:-CreateCoredumpOnCrash",                        .extraInfo = NULL },
        { .optionString = "-XX:+SuppressFatalErrorMessage",                    .extraInfo = NULL },
    };
    JavaVMInitArgs args = {
        .version            = JNI_VERSION_1_8,
        .options            = options,
        .nOptions           = sizeof(options) / sizeof(options[0]),
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
                                  const char *lib_path, const char *app_files) {
    bool expected = false;
    if (!atomic_compare_exchange_strong(&jvm.started, &expected, true)) {
        LOGW("JVM thread already started");
        return;
    }
    copy_arg(jvm.jre_home,  sizeof(jvm.jre_home),  jre_home);
    copy_arg(jvm.classpath, sizeof(jvm.classpath), classpath);
    copy_arg(jvm.lib_path,  sizeof(jvm.lib_path),  lib_path);
    copy_arg(jvm.app_files, sizeof(jvm.app_files), app_files);

    int rc = pthread_create(&jvm.thread, NULL, jvm_thread_main, NULL);
    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        atomic_store(&jvm.started, false);
    }
}

static void JNICALL glfw_make_current_impl(JNIEnv *env, jclass thiz, jlong window) {
    LOGI("glfwMakeContextCurrent on tid=%ld (window=0x%llx)",
         (long)pthread_self(), (long long)(unsigned long long)window);
    if (gfx.surface == EGL_NO_SURFACE) {
        LOGE("glfwMakeContextCurrent: no EGL surface yet");
        return;
    }
    if (!egl_make_current_on_caller_thread()) return;
    gl4es_bring_up_once();
    // gl4es does a validity test, then tears the EGL context down... so like,
    // make sure we actually still have an EGL context to prevent render errors.
    egl_make_current_on_caller_thread();
}

static jintArray JNICALL glfw_get_surface_size_impl(JNIEnv *env, jclass thiz) {
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (!arr) return NULL;
    jint dims[2] = { gfx.width, gfx.height };
    (*env)->SetIntArrayRegion(env, arr, 0, 2, dims);
    return arr;
}

static void JNICALL glfw_swap_buffers_impl(JNIEnv *env, jclass thiz, jlong window) {
    if (gfx.display == EGL_NO_DISPLAY || gfx.surface == EGL_NO_SURFACE) return;
    static atomic_int swap_count = ATOMIC_VAR_INIT(0);
    int n = atomic_fetch_add(&swap_count, 1);
    if (n == 0 || n == 1 || n == 10 || n == 60 || n == 600 || (n % 6000) == 0) {
        LOGI("glfwSwapBuffers #%d (tid=%ld)", n, (long)pthread_self());
    }
    if (!eglSwapBuffers(gfx.display, gfx.surface)) {
        EGLint err = eglGetError();
        if (err != EGL_BAD_SURFACE) {
            LOGW("eglSwapBuffers: %s", egl_err_str(err));
        }
    }
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_bootstrap_SkBootstrap_registerGlfwNatives(JNIEnv *env, jclass thiz,
                                                                  jclass glfwClass) {
    JNINativeMethod m[] = {
        { "nativeMakeContextCurrent", "(J)V",  (void *)glfw_make_current_impl },
        { "nativeSwapBuffers",        "(J)V",  (void *)glfw_swap_buffers_impl },
        { "nativeGetSurfaceSize",     "()[I",  (void *)glfw_get_surface_size_impl },
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
    egl_tear_down();
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_startJvm(JNIEnv *env, jobject thiz,
                                              jstring jreHome, jstring classpath,
                                              jstring libPath, jstring appFiles) {
    const char *home = (*env)->GetStringUTFChars(env, jreHome,   NULL);
    const char *cp   = (*env)->GetStringUTFChars(env, classpath, NULL);
    const char *lp   = (*env)->GetStringUTFChars(env, libPath,   NULL);
    const char *af   = (*env)->GetStringUTFChars(env, appFiles,  NULL);
    LOGI("startJvm requested");
    LOGI("  jreHome   = %s", home);
    LOGI("  classpath = %s", cp);
    LOGI("  libPath   = %s", lp);
    LOGI("  appFiles  = %s", af);
    start_jvm_thread_once(home, cp, lp, af);
    (*env)->ReleaseStringUTFChars(env, jreHome,   home);
    (*env)->ReleaseStringUTFChars(env, classpath, cp);
    (*env)->ReleaseStringUTFChars(env, libPath,   lp);
    (*env)->ReleaseStringUTFChars(env, appFiles,  af);
}

JNIEXPORT void JNICALL
Java_com_skarm_launcher_NativeBridge_launchGame(JNIEnv *env, jobject thiz,
                                                jstring gameDir, jstring loginMode) {
    LOGI("launchGame (stub)");
}
