#include "gpu_guard.h"
#include "ctxbridges/egl_loader.h"
#include <android/log.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define GPU_LOG(...) __android_log_print(ANDROID_LOG_INFO, "FCL-QuestGPU", __VA_ARGS__)
#define GPU_WARN(...) __android_log_print(ANDROID_LOG_WARN, "FCL-QuestGPU", __VA_ARGS__)
#define POLL_NS UINT64_C(5000000)
#define WAIT_BUDGET_NS UINT64_C(500000000)

static pthread_mutex_t registry_mutex = PTHREAD_MUTEX_INITIALIZER;
static quest_gpu_guard *registered_guards;

static bool extension_present(const char *list, const char *name) {
    if (!list || !name || !*name) return false;
    const size_t length = strlen(name);
    for (const char *p = list; (p = strstr(p, name)); p += length)
        if ((p == list || p[-1] == ' ') && (p[length] == '\0' || p[length] == ' ')) return true;
    return false;
}

bool quest_gpu_guard_enabled(void) {
    const char *flag = getenv("FCL_QUEST_GPU_GUARD");
    const char *provider = getenv("POJAVEXEC_EGL");
    if (!flag || strcmp(flag, "1") || !provider) return false;
    const char *name = strrchr(provider, '/');
    return !strcmp(name ? name + 1 : provider, "libmobileglues.so");
}

EGLContext quest_gpu_create_context(EGLDisplay display, EGLConfig config,
                                   EGLContext share, const EGLint *attributes) {
    if (!quest_gpu_guard_enabled() || !eglGetProcAddress_p)
        return eglCreateContext_p(display, config, share, attributes);
    const char *(*query_string)(EGLDisplay, EGLint) = (void *) eglGetProcAddress_p("eglQueryString");
    EGLenum (*query_api)(void) = (void *) eglGetProcAddress_p("eglQueryAPI");
    EGLBoolean (*query_context)(EGLDisplay, EGLContext, EGLint, EGLint *) =
            (void *) eglGetProcAddress_p("eglQueryContext");
    if (!query_string || !query_api || query_api() != EGL_OPENGL_ES_API ||
        !extension_present(query_string(display, EGL_EXTENSIONS), "EGL_IMG_context_priority"))
        return eglCreateContext_p(display, config, share, attributes);

    EGLint adapted[259];
    size_t count = 0;
    bool priority = false;
    if (attributes) {
        for (; count < 256 && attributes[count] != EGL_NONE; count += 2) {
            adapted[count] = attributes[count];
            adapted[count + 1] = attributes[count + 1];
            if (adapted[count] == EGL_CONTEXT_PRIORITY_LEVEL_IMG) {
                adapted[count + 1] = EGL_CONTEXT_PRIORITY_LOW_IMG;
                priority = true;
            }
        }
        if (count == 256) return eglCreateContext_p(display, config, share, attributes);
    }
    if (!priority) {
        adapted[count++] = EGL_CONTEXT_PRIORITY_LEVEL_IMG;
        adapted[count++] = EGL_CONTEXT_PRIORITY_LOW_IMG;
    }
    adapted[count] = EGL_NONE;
    EGLContext context = eglCreateContext_p(display, config, share, adapted);
    if (context == EGL_NO_CONTEXT) {
        EGLint error = eglGetError_p ? eglGetError_p() : EGL_SUCCESS;
        GPU_WARN("Low-priority GLES context declined (0x%x); retrying original attributes", error);
        return eglCreateContext_p(display, config, share, attributes);
    }
    EGLint granted = 0;
    if (query_context && query_context(display, context, EGL_CONTEXT_PRIORITY_LEVEL_IMG, &granted))
        GPU_LOG("GLES priority requested LOW (0x%x), granted 0x%x", EGL_CONTEXT_PRIORITY_LOW_IMG, granted);
    else {
        if (eglGetError_p) eglGetError_p();
        GPU_LOG("GLES priority requested LOW; actual priority unavailable");
    }
    return context;
}

static uint64_t now_ns(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value)) return 0;
    return (uint64_t) value.tv_sec * UINT64_C(1000000000) + (uint64_t) value.tv_nsec;
}

static void yield_time(quest_gpu_guard *guard, uint64_t duration) {
    while (duration && !atomic_load(&guard->cancelled)) {
        uint64_t slice = duration > UINT64_C(1000000) ? UINT64_C(1000000) : duration;
        struct timespec request = {0, (long) slice};
        while (nanosleep(&request, &request) && errno == EINTR)
            if (atomic_load(&guard->cancelled)) return;
        duration -= slice;
    }
}

void quest_gpu_guard_init(quest_gpu_guard *guard, EGLDisplay display, EGLContext context) {
    memset(guard, 0, sizeof(*guard));
    atomic_init(&guard->initialized, false);
    if (!quest_gpu_guard_enabled() || display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return;
    if (pthread_mutex_init(&guard->mutex, NULL)) return;
    atomic_init(&guard->cancelled, false);
    guard->display = display;
    guard->context = context;
    pthread_mutex_lock(&registry_mutex);
    guard->next = registered_guards;
    registered_guards = guard;
    atomic_store(&guard->initialized, true);
    pthread_mutex_unlock(&registry_mutex);
}

static void forget_fence(quest_gpu_guard *guard) {
    if (guard->pending != EGL_NO_SYNC_KHR && guard->destroy_sync) {
        if (!guard->destroy_sync(guard->display, guard->pending)) eglGetError();
        guard->pending = EGL_NO_SYNC_KHR;
    }
}

static bool prepare_fences(quest_gpu_guard *guard) {
    if (guard->checked) return guard->available;
    guard->checked = true;
    if (extension_present(eglQueryString(guard->display, EGL_EXTENSIONS), "EGL_KHR_fence_sync")) {
        guard->create_sync = (void *) eglGetProcAddress("eglCreateSyncKHR");
        guard->wait_sync = (void *) eglGetProcAddress("eglClientWaitSyncKHR");
        guard->destroy_sync = (void *) eglGetProcAddress("eglDestroySyncKHR");
        guard->available = guard->create_sync && guard->wait_sync && guard->destroy_sync;
    }
    GPU_LOG("Native GLES fence pacing: %s", guard->available ? "enabled" : "unavailable; retaining priority hint");
    return guard->available;
}

static EGLint wait_pending(quest_gpu_guard *guard, uint64_t deadline) {
    for (unsigned poll = 0; poll < WAIT_BUDGET_NS / POLL_NS; ++poll) {
        if (atomic_load(&guard->cancelled)) return EGL_TIMEOUT_EXPIRED_KHR;
        uint64_t now = now_ns();
        if (!now || now >= deadline) return EGL_TIMEOUT_EXPIRED_KHR;
        EGLTimeKHR remaining = deadline - now;
        EGLTimeKHR timeout = remaining < POLL_NS ? remaining : POLL_NS;
        EGLint result = guard->wait_sync(guard->display, guard->pending,
                                        EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, timeout);
        if (result != EGL_TIMEOUT_EXPIRED_KHR) return result;
    }
    return EGL_TIMEOUT_EXPIRED_KHR;
}

static bool complete_pending(quest_gpu_guard *guard, uint64_t deadline) {
    EGLint result = wait_pending(guard, deadline);
    if (result == EGL_CONDITION_SATISFIED_KHR) {
        forget_fence(guard);
        if (guard->timeout_reported) {
            GPU_LOG("GPU fence recovered; normal pacing resumed");
            guard->timeout_reported = false;
        }
        return true;
    }
    if (result == EGL_TIMEOUT_EXPIRED_KHR) {
        if (!atomic_load(&guard->cancelled)) {
            if (!guard->timeout_reported)
                GPU_WARN("GPU wait exceeded 500ms; retaining one fence and yielding (best-effort pacing)");
            guard->timeout_reported = true;
            yield_time(guard, UINT64_C(16000000));
        }
    } else {
        EGLint error = eglGetError();
        GPU_WARN("GPU fence wait failed (0x%x); pacing disabled for this context", error);
        forget_fence(guard);
        guard->available = false;
    }
    return false;
}

void quest_gpu_guard_frame(quest_gpu_guard *guard) {
    if (!atomic_load(&guard->initialized)) return;
    pthread_mutex_lock(&registry_mutex);
    if (!atomic_load(&guard->initialized) || eglGetCurrentDisplay() != guard->display ||
        eglGetCurrentContext() != guard->context) {
        pthread_mutex_unlock(&registry_mutex);
        return;
    }
    if (pthread_mutex_trylock(&guard->mutex)) {
        pthread_mutex_unlock(&registry_mutex);
        return;
    }
    pthread_mutex_unlock(&registry_mutex);
    if (atomic_load(&guard->cancelled) || !prepare_fences(guard)) goto done;
    uint64_t start = now_ns();
    if (!start) goto done;
    uint64_t deadline = start + WAIT_BUDGET_NS;
    if (guard->pending != EGL_NO_SYNC_KHR && !complete_pending(guard, deadline)) goto done;
    if (atomic_load(&guard->cancelled)) goto done;
    guard->pending = guard->create_sync(guard->display, EGL_SYNC_FENCE_KHR, NULL);
    if (guard->pending == EGL_NO_SYNC_KHR) {
        GPU_WARN("GPU fence creation failed (0x%x); pacing disabled for this context", eglGetError());
        guard->available = false;
        goto done;
    }
    if (!complete_pending(guard, deadline)) goto done;
    uint64_t finished = now_ns();
    uint64_t previous = guard->last_finished ? guard->last_finished : start;
    if (finished > previous && finished - previous > UINT64_C(25000000))
        yield_time(guard, UINT64_C(2000000));
done:
    guard->last_finished = now_ns();
    pthread_mutex_unlock(&guard->mutex);
}

static void release_registered(quest_gpu_guard **slot) {
    quest_gpu_guard *guard = *slot;
    *slot = guard->next;
    atomic_store(&guard->cancelled, true);
    pthread_mutex_lock(&guard->mutex);
    forget_fence(guard);
    pthread_mutex_unlock(&guard->mutex);
    pthread_mutex_destroy(&guard->mutex);
    atomic_store(&guard->initialized, false);
    guard->next = NULL;
}

void quest_gpu_guard_release(quest_gpu_guard *guard) {
    if (!atomic_load(&guard->initialized)) return;
    pthread_mutex_lock(&registry_mutex);
    for (quest_gpu_guard **slot = &registered_guards; *slot; slot = &(*slot)->next) {
        if (*slot == guard) {
            release_registered(slot);
            break;
        }
    }
    pthread_mutex_unlock(&registry_mutex);
}

void quest_gpu_guard_release_display(EGLDisplay display) {
    pthread_mutex_lock(&registry_mutex);
    quest_gpu_guard **slot = &registered_guards;
    while (*slot) {
        if ((*slot)->display == display) release_registered(slot);
        else slot = &(*slot)->next;
    }
    pthread_mutex_unlock(&registry_mutex);
}
