#include "stereo_sync.h"
#include "interop.h"
#include <GLES3/gl3.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define STEREO_TIMEOUT_NS UINT64_C(1000000000)

static struct {
    EGLContext (*context)(void);
    EGLDisplay (*display)(void);
    EGLenum (*api)(void);
    const GLubyte *(*string)(GLenum);
    GLsync (*fence)(GLenum, GLbitfield);
    GLenum (*wait)(GLsync, GLbitfield, GLuint64);
    void (*destroy)(GLsync);
} stereo_gl;

typedef struct stereo_session {
    XrInstance instance;
    XrSession session;
    EGLDisplay display;
    EGLContext context;
    bool running, busy, retired, disabled, checked;
    unsigned warnings;
    GLsync pending;
    struct stereo_session *next;
} stereo_session;

static stereo_session *stereo_sessions;
static pthread_mutex_t stereo_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t stereo_once = PTHREAD_ONCE_INIT;

static void stereo_load(void) {
    void *egl = dlopen("libEGL.so", RTLD_LOCAL | RTLD_NOW);
    void *gles = dlopen("libGLESv3.so", RTLD_LOCAL | RTLD_NOW);
    if (egl && gles) {
        stereo_gl.context = dlsym(egl, "eglGetCurrentContext");
        stereo_gl.display = dlsym(egl, "eglGetCurrentDisplay");
        stereo_gl.api = dlsym(egl, "eglQueryAPI");
        stereo_gl.string = dlsym(gles, "glGetString");
        stereo_gl.fence = dlsym(gles, "glFenceSync");
        stereo_gl.wait = dlsym(gles, "glClientWaitSync");
        stereo_gl.destroy = dlsym(gles, "glDeleteSync");
    }
}

static bool stereo_available(void) {
    return stereo_gl.context && stereo_gl.display && stereo_gl.api &&
           stereo_gl.string && stereo_gl.fence && stereo_gl.wait && stereo_gl.destroy;
}

static bool stereo_current(const stereo_session *entry) {
    return stereo_gl.context() == entry->context && stereo_gl.display() == entry->display &&
           stereo_gl.api() == EGL_OPENGL_ES_API;
}

static void stereo_dispose(stereo_session *entry) {
    if (entry->pending && stereo_current(entry)) stereo_gl.destroy(entry->pending);
    free(entry);
}

void quest_vr_stereo_session(XrInstance instance, XrSession session,
                             EGLDisplay display, EGLContext context) {
    const char *vr = getenv("FCL_VR");
    const char *setting = getenv("FCL_VR_STEREO_SYNC");
    if (!instance || !session || !vr || strcmp(vr, "1") ||
        (setting && strcmp(setting, "1")) || !quest_vr_is_mobileglues() ||
        display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return;
    pthread_once(&stereo_once, stereo_load);
    if (!stereo_available()) {
        __android_log_print(ANDROID_LOG_WARN, "FCL-QuestVR", "VR image synchronization unavailable: GLES entry points missing");
        return;
    }
    stereo_session *entry = calloc(1, sizeof(*entry));
    if (!entry) return;
    entry->instance = instance;
    entry->session = session;
    entry->display = display;
    entry->context = context;
    pthread_mutex_lock(&stereo_mutex);
    entry->next = stereo_sessions;
    stereo_sessions = entry;
    pthread_mutex_unlock(&stereo_mutex);
}

void quest_vr_stereo_running(XrSession session, bool running) {
    if (!session) return;
    pthread_mutex_lock(&stereo_mutex);
    for (stereo_session *entry = stereo_sessions; entry; entry = entry->next) {
        if (entry->session != session) continue;
        entry->running = running;
        if (running && !entry->busy) {
            entry->disabled = false;
            entry->checked = false;
            entry->warnings = 0;
        }
        break;
    }
    pthread_mutex_unlock(&stereo_mutex);
}

void quest_vr_stereo_forget(XrInstance instance, XrSession session) {
    if (!instance && !session) return;
    stereo_session *removed = NULL;
    pthread_mutex_lock(&stereo_mutex);
    for (stereo_session **slot = &stereo_sessions; *slot;) {
        stereo_session *entry = *slot;
        if ((instance && entry->instance == instance) || (session && entry->session == session)) {
            *slot = entry->next;
            entry->retired = true;
            if (!entry->busy) { entry->next = removed; removed = entry; }
        } else slot = &entry->next;
    }
    pthread_mutex_unlock(&stereo_mutex);
    while (removed) {
        stereo_session *next = removed->next;
        stereo_dispose(removed);
        removed = next;
    }
}

static bool stereo_complete(GLenum result) {
    return result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED;
}

void quest_vr_stereo_before_release(XrSession session) {
    if (!session) return;
    stereo_session *entry;
    pthread_mutex_lock(&stereo_mutex);
    for (entry = stereo_sessions; entry && entry->session != session; entry = entry->next) {}
    if (!entry || !entry->running || entry->busy || entry->disabled) {
        pthread_mutex_unlock(&stereo_mutex);
        return;
    }
    entry->busy = true;
    pthread_mutex_unlock(&stereo_mutex);

    unsigned warning = 0;
    const char *message = NULL;
    bool disable = false;
    if (!stereo_current(entry)) {
        warning = 1;
        message = "VR image synchronization skipped: session GLES context is not current";
        goto finished;
    }
    if (entry->pending) {
        stereo_gl.destroy(entry->pending);
        entry->pending = NULL;
    }
    if (!entry->checked) {
        const char *version = (const char *) stereo_gl.string(GL_VERSION);
        const char prefix[] = "OpenGL ES ";
        if (!version || strncmp(version, prefix, sizeof(prefix) - 1) ||
            version[sizeof(prefix) - 1] < '3' || version[sizeof(prefix) - 1] > '9') {
            disable = true;
            warning = 2;
            message = "VR image synchronization disabled: GLES 3 is unavailable";
            goto finished;
        }
        entry->checked = true;
    }
    entry->pending = stereo_gl.fence(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    if (!entry->pending) {
        disable = true;
        warning = 2;
        message = "VR image synchronization disabled: fence creation failed";
        goto finished;
    }
    if (!stereo_current(entry)) goto context_changed;
    GLenum result = stereo_gl.wait(entry->pending, 0, 0);
    if (!stereo_current(entry)) goto context_changed;
    if (result == GL_TIMEOUT_EXPIRED) {
        result = stereo_gl.wait(entry->pending, GL_SYNC_FLUSH_COMMANDS_BIT, STEREO_TIMEOUT_NS);
        if (!stereo_current(entry)) goto context_changed;
    }
    if (result == GL_TIMEOUT_EXPIRED) {
        warning = 4;
        message = "VR image synchronization timed out after 1s; image completion is not confirmed";
    } else if (!stereo_complete(result)) {
        disable = true;
        warning = 2;
        message = "VR image synchronization disabled for this session: GLES wait failed";
    }
    stereo_gl.destroy(entry->pending);
    entry->pending = NULL;
    goto finished;

context_changed:
    disable = true;
    warning = 2;
    message = "VR image synchronization disabled: GLES context changed during synchronization";
finished:
    pthread_mutex_lock(&stereo_mutex);
    entry->disabled |= disable;
    bool report = warning && !(entry->warnings & warning);
    entry->warnings |= warning;
    entry->busy = false;
    bool retired = entry->retired;
    pthread_mutex_unlock(&stereo_mutex);
    if (report) __android_log_print(ANDROID_LOG_WARN, "FCL-QuestVR", "%s", message);
    if (retired) stereo_dispose(entry);
}
