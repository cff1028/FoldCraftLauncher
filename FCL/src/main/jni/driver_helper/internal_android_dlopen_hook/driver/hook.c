//
// linkerhook：拦截 libvulkan 加载器的 android_dlopen_ext，懒创建自定义驱动命名空间。
// 驱动库名经 DRIVER_LIB 环境变量注入（Turnip 为 libvulkan_freedreno.so，
// panvk 为 libvulkan_panfrost.so 等），目录经 DRIVER_PATH 注入
//

#include <string.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdio.h>

#include <androidnsbypass/nsbypass_t.h>
#include <androidnsbypass/nsbypass.h>

#include "log.h"

static android_dlopen_ext_t platformOpen;
static pthread_once_t platformOpenOnce = PTHREAD_ONCE_INIT;
static pthread_mutex_t namespaceLock = PTHREAD_MUTEX_INITIALIZER;
static struct android_namespace_t* driverNs;
static uint64_t (*atrace_get_enabled_tags_p)();

static void resolvePlatformOpen(void) {
    // This library exports the same symbol. Resolve the public NDK entry from libdl itself.
    void* libdl = dlopen("libdl.so", RTLD_NOW | RTLD_LOCAL);
    if (libdl != NULL) {
        platformOpen = (android_dlopen_ext_t)dlsym(libdl, "android_dlopen_ext");
        dlclose(libdl); // libdl is a DT_NEEDED dependency for the lifetime of this library.
    }
    if (platformOpen == NULL) FCL_LOG("DriverSupp: libdl is missing android_dlopen_ext");
}

static void* openLibrary(const char* filename, int flags, const android_dlextinfo* extinfo,
                         const void* caller) {
    pthread_once(&platformOpenOnce, resolvePlatformOpen);
    if (platformOpen == NULL) return NULL;

    const char* slash = filename == NULL ? NULL : strrchr(filename, '/');
    const char* name = slash == NULL ? filename : slash + 1;
    if (name == NULL || strncmp(name, "vulkan.", sizeof("vulkan.") - 1) != 0) {
        return platformOpen(filename, flags, extinfo);
    }

    pthread_mutex_lock(&namespaceLock);
    if (driverNs == NULL) {
        struct android_namespace_t* parent = NULL;
        if (extinfo != NULL && (extinfo->flags & ANDROID_DLEXT_USE_NAMESPACE) != 0) {
            parent = extinfo->library_namespace;
        }
        driverNs = private_create_namespace("custom-driver-NS", NULL, getenv("DRIVER_PATH"),
                                            ANDROID_NAMESPACE_TYPE_SHARED_ISOLATED, NULL, parent, caller);
    }
    struct android_namespace_t* ns = driverNs;
    pthread_mutex_unlock(&namespaceLock);
    if (ns == NULL) {
        FCL_LOG("DriverSupp: custom driver namespace unavailable");
        return NULL;
    }

    const char* driverLib = getenv("DRIVER_LIB");
    if (driverLib == NULL || *driverLib == 0) driverLib = "libvulkan_freedreno.so";
    const android_dlextinfo driverInfo = {
        .flags = ANDROID_DLEXT_USE_NAMESPACE,
        .library_namespace = ns
    };
    // Open the selected file directly, preserving dlopen/dlclose ownership on repeated requests.
    void* driver = platformOpen(driverLib, RTLD_LOCAL | RTLD_NOW, &driverInfo);
    if (driver != NULL) {
        FCL_LOG("DriverSupp: opened Vulkan driver library %s", driverLib);
    } else {
        const char* error = dlerror();
        FCL_LOG("DriverSupp: failed to open Vulkan driver library %s: %s",
                driverLib, error != NULL ? error : "unknown error");
    }
    return driver;
}

__attribute__((visibility("default"), used)) void *android_dlopen_ext(const char *filename, int flags, const android_dlextinfo *extinfo) {
    return openLibrary(filename, flags, extinfo, __builtin_return_address(0));
}

__attribute__((visibility("default"), used)) void *android_load_sphal_library(const char *filename, int flags) {
    // 参考 AOSP（https://cs.android.com/android/platform/superproject/+/android-latest-release:system/core/libvndksupport/linker.cpp;drc=5248c5d72ad2a14f3426b7872b8867f97818650f;l=42）
    const char *sphal_namespaces[3] = {
            "sphal", "vendor", "default"
    };

    struct android_namespace_t* androidNamespace;
    for(int i = 0; i < 3; i++) {
        androidNamespace = private_get_exported_namespace(sphal_namespaces[i]);
        if(androidNamespace != NULL) break;
    }
    android_dlextinfo info = {0};
    info.flags = ANDROID_DLEXT_USE_NAMESPACE;
    info.library_namespace = androidNamespace;
    return openLibrary(filename, flags, &info, &openLibrary);
}

// Android 部分版本不导出该函数，需手动从 libcutils 解析
// 参考 AOSP（https://cs.android.com/android/platform/superproject/+/android-latest-release:system/core/libcutils/include/cutils/trace.h;drc=0c44d8d68d56c7aecb828d8d87fba7dcb114f3d9;l=32-82）
__attribute__((visibility("default"), used)) uint64_t atrace_get_enabled_tags() {
    if (!atrace_get_enabled_tags_p) {
        dlerror(); // no stale pls
        void* cutilsHandle = private_dlopen("libcutils.so", RTLD_LOCAL | RTLD_LAZY, &dlopen);
        if (!cutilsHandle) {
            FCL_LOG("Unable to load libcutils in liblinkerhook, are we in an escape namespace? Assuming atrace tags to be ATRACE_TAG_NEVER. dlopen error: %s", dlerror());
            return 0; // ATRACE_TAG_NEVER
        }
        atrace_get_enabled_tags_p = private_dlsym(
                cutilsHandle,
                "atrace_get_enabled_tags",
                &dlopen
        );
        if (!atrace_get_enabled_tags_p) {
            FCL_LOG("Unable to get atrace_get_enabled_tags from libcutils, dlsym error: %s", dlerror());
            return 0; // ATRACE_TAG_NEVER
        }
    }
    uint64_t result = atrace_get_enabled_tags_p();
    return result;
}