#include <vulkan/vulkan.h>

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <limits.h>
#include <fcntl.h>
#include <pthread.h>
#include <android/api-level.h>
#include "../adrenotools/include/adrenotools/driver.h"

// GPU information queries for GPUInformation.java (renderer name, vendor id, Vulkan version,
// device extensions), optionally through a custom Adreno driver loaded with adrenotools.
//
// Each query opens a Vulkan loader, creates a throwaway instance, reads the first physical
// device and tears everything down again. Rewritten for correctness; the previous version:
//  - read uninitialized pointers when a custom driver's folder was missing, when
//    AdrenotoolsManager.getLibraryName() failed, or when the context was null — and passed them
//    to adrenotools_open_libvulkan() (undefined behaviour: crash or a random driver path);
//  - left a pending Java exception after getLibraryName() (a driver without meta.json makes
//    it throw), which aborts the process on the next JNI call;
//  - leaked the instance and the loader handle on every failure path, never released
//    GetStringUTFChars results, and leaked every asprintf()/malloc() buffer;
//  - checked a stale VkResult after the second vkEnumerateDeviceExtensionProperties call;
//  - required vkEnumerateInstanceVersion, which Vulkan 1.0 loaders don't export;
//  - kept all state in unsynchronised globals although queries come from several threads
//    (UI, HUD, container launch).

#define LOG_TAG "Winlator_GPUInfo"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SYSTEM_VULKAN "/system/lib64/libvulkan.so"

// One query at a time: the Vulkan loader/driver state is process-wide.
static pthread_mutex_t gpu_query_lock = PTHREAD_MUTEX_INITIALIZER;

// Strings handed to adrenotools_open_libvulkan(). Kept in static storage (not freed after the
// call) because adrenotools' hook setup may keep pointers to them; reused by the next query,
// which only happens after the previous driver handle has been closed (under the lock).
static char s_driver_path[PATH_MAX];
static char s_library_name[PATH_MAX];
static char s_native_lib_dir[PATH_MAX];
static char s_tmp_dir[PATH_MAX];

typedef struct {
    void *handle;
    VkInstance instance;
    VkPhysicalDevice device;
    PFN_vkDestroyInstance destroyInstance;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties;
    PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties;
} GpuQuery;

// ---------- JNI helpers ----------

static int clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 1;
    }
    return 0;
}

// Copies a Java string into buf. Returns 1 on success (non-empty string that fits).
static int copy_jstring(JNIEnv *env, jstring value, char *buf, size_t size) {
    if (!value) return 0;
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (!utf) {
        clear_exception(env);
        return 0;
    }
    size_t len = strlen(utf);
    int ok = len > 0 && len < size;
    if (ok) memcpy(buf, utf, len + 1);
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return ok;
}

static int fill_native_lib_dir(JNIEnv *env, jobject context) {
    int ok = 0;
    jclass appUtils = (*env)->FindClass(env, "com/winlator/cmod/core/AppUtils");
    if (!appUtils || clear_exception(env)) return 0;
    jmethodID getNativeLibDir = (*env)->GetStaticMethodID(env, appUtils, "getNativeLibDir",
                                                          "(Landroid/content/Context;)Ljava/lang/String;");
    if (getNativeLibDir && !clear_exception(env)) {
        jstring dir = (jstring)(*env)->CallStaticObjectMethod(env, appUtils, getNativeLibDir, context);
        if (!clear_exception(env)) ok = copy_jstring(env, dir, s_native_lib_dir, sizeof(s_native_lib_dir));
        if (dir) (*env)->DeleteLocalRef(env, dir);
    }
    (*env)->DeleteLocalRef(env, appUtils);
    return ok;
}

static int fill_driver_path(JNIEnv *env, jobject context, const char *driver_name) {
    int ok = 0;
    jclass contextWrapper = (*env)->FindClass(env, "android/content/ContextWrapper");
    if (!contextWrapper || clear_exception(env)) return 0;
    jmethodID getFilesDir = (*env)->GetMethodID(env, contextWrapper, "getFilesDir", "()Ljava/io/File;");
    jobject filesDir = getFilesDir && !clear_exception(env)
            ? (*env)->CallObjectMethod(env, context, getFilesDir) : NULL;
    if (filesDir && !clear_exception(env)) {
        jclass fileClass = (*env)->GetObjectClass(env, filesDir);
        jmethodID getAbsolutePath = (*env)->GetMethodID(env, fileClass, "getAbsolutePath", "()Ljava/lang/String;");
        jstring absolutePath = getAbsolutePath && !clear_exception(env)
                ? (jstring)(*env)->CallObjectMethod(env, filesDir, getAbsolutePath) : NULL;
        char files_dir[PATH_MAX];
        if (!clear_exception(env) && copy_jstring(env, absolutePath, files_dir, sizeof(files_dir))) {
            int n = snprintf(s_driver_path, sizeof(s_driver_path), "%s/contents/adrenotools/%s/", files_dir, driver_name);
            ok = n > 0 && (size_t)n < sizeof(s_driver_path);
        }
        if (absolutePath) (*env)->DeleteLocalRef(env, absolutePath);
        (*env)->DeleteLocalRef(env, fileClass);
        (*env)->DeleteLocalRef(env, filesDir);
    } else {
        clear_exception(env);
    }
    (*env)->DeleteLocalRef(env, contextWrapper);
    return ok;
}

// AdrenotoolsManager.getLibraryName() returns "" when meta.json is unreadable and throws when
// it's missing entirely (JSONObject(null)); both count as "no usable driver".
static int fill_library_name(JNIEnv *env, jobject context, const char *driver_name) {
    int ok = 0;
    jclass manager = (*env)->FindClass(env, "com/winlator/cmod/contents/AdrenotoolsManager");
    if (!manager || clear_exception(env)) return 0;
    jmethodID constructor = (*env)->GetMethodID(env, manager, "<init>", "(Landroid/content/Context;)V");
    jmethodID getLibraryName = (*env)->GetMethodID(env, manager, "getLibraryName", "(Ljava/lang/String;)Ljava/lang/String;");
    if (constructor && getLibraryName && !clear_exception(env)) {
        jobject managerObj = (*env)->NewObject(env, manager, constructor, context);
        if (managerObj && !clear_exception(env)) {
            jstring driverName = (*env)->NewStringUTF(env, driver_name);
            jstring libraryName = driverName
                    ? (jstring)(*env)->CallObjectMethod(env, managerObj, getLibraryName, driverName) : NULL;
            if (!clear_exception(env)) ok = copy_jstring(env, libraryName, s_library_name, sizeof(s_library_name));
            if (libraryName) (*env)->DeleteLocalRef(env, libraryName);
            if (driverName) (*env)->DeleteLocalRef(env, driverName);
            (*env)->DeleteLocalRef(env, managerObj);
        } else {
            clear_exception(env);
        }
    } else {
        clear_exception(env);
    }
    (*env)->DeleteLocalRef(env, manager);
    return ok;
}

// ---------- Loader ----------

// "System" / no driver → the platform loader. A custom driver is used only if everything it
// needs resolves; otherwise the query fails (the caller reports "Unknown") rather than silently
// answering for a different driver.
static void *open_vulkan(JNIEnv *env, jobject context, const char *driver_name) {
    if (!driver_name || strcmp(driver_name, "System") == 0)
        return dlopen(SYSTEM_VULKAN, RTLD_LOCAL | RTLD_NOW);

    if (!context) {
        LOGE("custom driver '%s' requested without a context", driver_name);
        return NULL;
    }
    if (!fill_driver_path(env, context, driver_name) || access(s_driver_path, F_OK) != 0) {
        LOGE("driver '%s' is not installed", driver_name);
        return NULL;
    }
    if (!fill_library_name(env, context, driver_name)) {
        LOGE("driver '%s' has no usable meta.json/libraryName", driver_name);
        return NULL;
    }
    if (!fill_native_lib_dir(env, context)) {
        LOGE("could not resolve the native library dir");
        return NULL;
    }
    int n = snprintf(s_tmp_dir, sizeof(s_tmp_dir), "%stemp", s_driver_path);
    if (n <= 0 || (size_t)n >= sizeof(s_tmp_dir)) return NULL;
    mkdir(s_tmp_dir, S_IRWXU | S_IRWXG);

    void *handle = adrenotools_open_libvulkan(RTLD_LOCAL | RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM,
                                              s_tmp_dir, s_native_lib_dir, s_driver_path,
                                              s_library_name, NULL, NULL);
    if (!handle) LOGE("adrenotools_open_libvulkan failed for '%s'", driver_name);
    return handle;
}

// ---------- Query lifecycle ----------

static void query_end(GpuQuery *q) {
    if (q->instance && q->destroyInstance) q->destroyInstance(q->instance, NULL);
    if (q->handle) dlclose(q->handle);
    memset(q, 0, sizeof(*q));
}

static int query_begin(GpuQuery *q, JNIEnv *env, jstring jdriverName, jobject context) {
    memset(q, 0, sizeof(*q));

    char driver_name[PATH_MAX];
    int has_driver = copy_jstring(env, jdriverName, driver_name, sizeof(driver_name));
    q->handle = open_vulkan(env, context, has_driver ? driver_name : NULL);
    if (!q->handle) return 0;

    PFN_vkGetInstanceProcAddr gip = (PFN_vkGetInstanceProcAddr)dlsym(q->handle, "vkGetInstanceProcAddr");
    PFN_vkCreateInstance createInstance = (PFN_vkCreateInstance)dlsym(q->handle, "vkCreateInstance");
    // Vulkan 1.1+ only; a 1.0 loader simply doesn't export it.
    PFN_vkEnumerateInstanceVersion enumerateInstanceVersion =
            (PFN_vkEnumerateInstanceVersion)dlsym(q->handle, "vkEnumerateInstanceVersion");
    if (!gip || !createInstance) {
        query_end(q);
        return 0;
    }

    VkApplicationInfo app_info = {0};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "Winlator";
    app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.pEngineName = "Winlator";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.apiVersion = VK_API_VERSION_1_0;
    if (android_get_device_api_level() <= 32 && enumerateInstanceVersion)
        enumerateInstanceVersion(&app_info.apiVersion);

    VkInstanceCreateInfo create_info = {0};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;

    if (createInstance(&create_info, NULL, &q->instance) != VK_SUCCESS) {
        q->instance = VK_NULL_HANDLE;
        query_end(q);
        return 0;
    }

    q->destroyInstance = (PFN_vkDestroyInstance)gip(q->instance, "vkDestroyInstance");
    q->getPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)gip(q->instance, "vkGetPhysicalDeviceProperties");
    q->enumerateDeviceExtensionProperties =
            (PFN_vkEnumerateDeviceExtensionProperties)gip(q->instance, "vkEnumerateDeviceExtensionProperties");
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices =
            (PFN_vkEnumeratePhysicalDevices)gip(q->instance, "vkEnumeratePhysicalDevices");
    if (!q->destroyInstance || !q->getPhysicalDeviceProperties ||
        !q->enumerateDeviceExtensionProperties || !enumeratePhysicalDevices) {
        query_end(q);
        return 0;
    }

    uint32_t count = 0;
    if (enumeratePhysicalDevices(q->instance, &count, NULL) != VK_SUCCESS || count < 1) {
        query_end(q);
        return 0;
    }
    VkPhysicalDevice *devices = malloc(sizeof(VkPhysicalDevice) * count);
    if (!devices) {
        query_end(q);
        return 0;
    }
    VkResult result = enumeratePhysicalDevices(q->instance, &count, devices);
    if ((result == VK_SUCCESS || result == VK_INCOMPLETE) && count > 0) q->device = devices[0];
    free(devices);

    if (q->device == VK_NULL_HANDLE) {
        query_end(q);
        return 0;
    }
    return 1;
}

static jobjectArray empty_string_array(JNIEnv *env) {
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray array = (*env)->NewObjectArray(env, 0, stringClass, NULL);
    (*env)->DeleteLocalRef(env, stringClass);
    return array;
}

// ---------- JNI entry points ----------

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_GPUInformation_getVulkanVersion(JNIEnv *env, jclass clazz, jstring driverName, jobject context) {
    (void)clazz;
    char version[32] = "Unknown";
    GpuQuery q;
    pthread_mutex_lock(&gpu_query_lock);
    if (query_begin(&q, env, driverName, context)) {
        VkPhysicalDeviceProperties props = {0};
        q.getPhysicalDeviceProperties(q.device, &props);
        snprintf(version, sizeof(version), "%u.%u.%u",
                 VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
                 VK_VERSION_PATCH(props.apiVersion));
        query_end(&q);
    } else {
        LOGD("getVulkanVersion: query failed");
    }
    pthread_mutex_unlock(&gpu_query_lock);
    return (*env)->NewStringUTF(env, version);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_core_GPUInformation_getVendorID(JNIEnv *env, jclass clazz, jstring driverName, jobject context) {
    (void)clazz;
    jint vendorID = 0;
    GpuQuery q;
    pthread_mutex_lock(&gpu_query_lock);
    if (query_begin(&q, env, driverName, context)) {
        VkPhysicalDeviceProperties props = {0};
        q.getPhysicalDeviceProperties(q.device, &props);
        vendorID = (jint)props.vendorID;
        query_end(&q);
    } else {
        LOGD("getVendorID: query failed");
    }
    pthread_mutex_unlock(&gpu_query_lock);
    return vendorID;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_core_GPUInformation_getRenderer(JNIEnv *env, jclass clazz, jstring driverName, jobject context) {
    (void)clazz;
    char renderer[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE] = "Unknown";
    GpuQuery q;
    pthread_mutex_lock(&gpu_query_lock);
    if (query_begin(&q, env, driverName, context)) {
        VkPhysicalDeviceProperties props = {0};
        q.getPhysicalDeviceProperties(q.device, &props);
        snprintf(renderer, sizeof(renderer), "%s", props.deviceName);
        query_end(&q);
    } else {
        LOGD("getRenderer: query failed");
    }
    pthread_mutex_unlock(&gpu_query_lock);
    return (*env)->NewStringUTF(env, renderer);
}

JNIEXPORT jobjectArray JNICALL
Java_com_winlator_cmod_core_GPUInformation_enumerateExtensions(JNIEnv *env, jclass clazz, jstring driverName, jobject context) {
    (void)clazz;
    VkExtensionProperties *properties = NULL;
    uint32_t count = 0;
    GpuQuery q;

    pthread_mutex_lock(&gpu_query_lock);
    if (query_begin(&q, env, driverName, context)) {
        if (q.enumerateDeviceExtensionProperties(q.device, NULL, &count, NULL) == VK_SUCCESS && count > 0) {
            properties = malloc(sizeof(VkExtensionProperties) * count);
            if (properties) {
                VkResult result = q.enumerateDeviceExtensionProperties(q.device, NULL, &count, properties);
                if (result != VK_SUCCESS && result != VK_INCOMPLETE) count = 0;
            } else {
                count = 0;
            }
        } else {
            count = 0;
        }
        query_end(&q);
    } else {
        LOGD("enumerateExtensions: query failed");
    }
    pthread_mutex_unlock(&gpu_query_lock);

    if (count == 0 || !properties) {
        free(properties);
        return empty_string_array(env);
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray extensions = (*env)->NewObjectArray(env, (jsize)count, stringClass, NULL);
    (*env)->DeleteLocalRef(env, stringClass);
    for (uint32_t i = 0; extensions && i < count; i++) {
        jstring name = (*env)->NewStringUTF(env, properties[i].extensionName);
        (*env)->SetObjectArrayElement(env, extensions, (jsize)i, name);
        (*env)->DeleteLocalRef(env, name);
    }
    free(properties);
    return extensions ? extensions : empty_string_array(env);
}
