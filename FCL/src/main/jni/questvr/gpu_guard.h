#ifndef FCL_QUEST_GPU_GUARD_H
#define FCL_QUEST_GPU_GUARD_H

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>

typedef struct quest_gpu_guard {
    EGLDisplay display;
    EGLContext context;
    EGLSyncKHR pending;
    PFNEGLCREATESYNCKHRPROC create_sync;
    PFNEGLCLIENTWAITSYNCKHRPROC wait_sync;
    PFNEGLDESTROYSYNCKHRPROC destroy_sync;
    pthread_mutex_t mutex;
    atomic_bool cancelled;
    atomic_bool initialized;
    bool checked;
    bool available;
    bool timeout_reported;
    uint64_t last_finished;
    struct quest_gpu_guard *next;
} quest_gpu_guard;

bool quest_gpu_guard_enabled(void);
EGLContext quest_gpu_create_context(EGLDisplay display, EGLConfig config,
                                   EGLContext share, const EGLint *attributes);
void quest_gpu_guard_init(quest_gpu_guard *guard, EGLDisplay display, EGLContext context);
void quest_gpu_guard_frame(quest_gpu_guard *guard);
void quest_gpu_guard_release(quest_gpu_guard *guard);
void quest_gpu_guard_release_display(EGLDisplay display);

#endif
