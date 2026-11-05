#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include "host_abi_loader.h"

#include <cstdint>
#include <mutex>

namespace {

constexpr const char* kLogTag = "SwiftTUIJNI";
// The consumer's Swift host product, standardized to a canonical file name by
// the SwiftTUI Android Gradle convention plugin as it copies the built `.so`
// into jniLibs. The library never references an app-specific product name.
constexpr const char* kHostLibrary = "libswift_tui_app_host.so";
constexpr const char* kJniClassName = "sh/swifttui/android/host/SwiftTUIJni";

std::once_flag gLoadOnce;
void* gHostHandle = nullptr;
HostFunctions gHost;
std::string gLoadError;

void loadHostLibrary() {
  gHostHandle = dlopen(kHostLibrary, RTLD_NOW | RTLD_LOCAL);
  if (gHostHandle == nullptr) {
    const char* detail = dlerror();
    gLoadError = std::string("android.abi.loadLibrary: ") + (detail ? detail : kHostLibrary);
    return;
  }
  gLoadError = gHost.load(gHostHandle, dlsym);
}

// Native methods. Bound explicitly via RegisterNatives in JNI_OnLoad (below)
// rather than by implicit Java_<package>_<class>_<method> name mangling, so the
// C symbol names are independent of the Kotlin package and validated at load.

jlong nativeCreateHost(JNIEnv*, jobject) {
  // Install the host-driven Swift main-actor executor before any main-actor
  // work runs (it is a fatal error to install it later). Without this, the
  // embedded SwiftTUI run loop's @MainActor continuations — autonomous `.task`
  // loops, animation deadline wakes — never resume on Android, because there is
  // no OS run loop to drain the Swift main executor. Idempotent.
  if (auto install =
        gHost.install) {
    install();
  }

  auto createHost = gHost.create;
  if (createHost == nullptr) {
    return 0;
  }
  return static_cast<jlong>(createHost());
}

void nativeStart(JNIEnv*, jobject, jlong handle) {
  auto start = gHost.start;
  if (start != nullptr) {
    start(static_cast<int64_t>(handle));
  }
}

jint nativeTick(JNIEnv*, jobject, jlong handle) {
  auto tick = gHost.tick;
  if (tick == nullptr) {
    return 0;
  }
  jint status = static_cast<jint>(tick(static_cast<int64_t>(handle)));

  // Diagnostics: confirm the main executor installed and is being drained on
  // the first few ticks (so device logs show the fix took effect), and surface
  // any later failure. diag packs: bit0 installed, bits1..21 enqueued,
  // bits22..42 drained, bits43..52 pending.
  static int s_tickCount = 0;
  if (s_tickCount < 3 || status < 0) {
    int64_t diag = 0;
    if (auto diagFn = gHost.diagnostics) {
      diag = diagFn();
    }
    int installed = static_cast<int>(diag & 0x1);
    long enqueued = static_cast<long>((diag >> 1) & 0x1FFFFF);
    long drained = static_cast<long>((diag >> 22) & 0x1FFFFF);
    long pending = static_cast<long>((diag >> 43) & 0x3FF);
    __android_log_print(
      status < 0 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
      kLogTag,
      "main-executor tick #%d status=%d installed=%d enqueued=%ld drained=%ld pending=%ld",
      s_tickCount,
      status,
      installed,
      enqueued,
      drained,
      pending);
  }
  s_tickCount++;

  return status;
}

void nativeStop(JNIEnv*, jobject, jlong handle) {
  auto stop = gHost.stop;
  if (stop != nullptr) {
    stop(static_cast<int64_t>(handle));
  }
}

void nativeDestroy(JNIEnv*, jobject, jlong handle) {
  auto destroy = gHost.destroy;
  if (destroy != nullptr) {
    destroy(static_cast<int64_t>(handle));
  }
}

void nativeResize(
  JNIEnv*,
  jobject,
  jlong handle,
  jint columns,
  jint rows,
  jdouble cellPixelWidth,
  jdouble cellPixelHeight
) {
  auto resize = gHost.resize;
  if (resize != nullptr) {
    resize(
      static_cast<int64_t>(handle),
      static_cast<int32_t>(columns),
      static_cast<int32_t>(rows),
      static_cast<double>(cellPixelWidth),
      static_cast<double>(cellPixelHeight)
    );
  }
}

jint nativeCopyLatestFrame(
  JNIEnv* env,
  jobject,
  jlong handle,
  jbyteArray outBuffer,
  jint capacity
) {
  auto copyLatestFrame = gHost.copyFrame;
  if (copyLatestFrame == nullptr) {
    return 0;
  }

  if (outBuffer == nullptr || capacity <= 0) {
    return copyLatestFrame(static_cast<int64_t>(handle), nullptr, 0);
  }

  jsize arrayLength = env->GetArrayLength(outBuffer);
  jint boundedCapacity = capacity < arrayLength ? capacity : arrayLength;
  jbyte* bytes = env->GetByteArrayElements(outBuffer, nullptr);
  if (bytes == nullptr) {
    return 0;
  }

  jint needed = copyLatestFrame(
    static_cast<int64_t>(handle),
    reinterpret_cast<uint8_t*>(bytes),
    static_cast<int32_t>(boundedCapacity)
  );
  env->ReleaseByteArrayElements(outBuffer, bytes, 0);
  return needed;
}

jint nativeCopyClipboardText(
  JNIEnv* env,
  jobject,
  jlong handle,
  jbyteArray outBuffer,
  jint capacity
) {
  auto copyClipboardText = gHost.copyClipboard;
  if (copyClipboardText == nullptr) {
    return 0;
  }

  if (outBuffer == nullptr || capacity <= 0) {
    return copyClipboardText(static_cast<int64_t>(handle), nullptr, 0);
  }

  jsize arrayLength = env->GetArrayLength(outBuffer);
  jint boundedCapacity = capacity < arrayLength ? capacity : arrayLength;
  jbyte* bytes = env->GetByteArrayElements(outBuffer, nullptr);
  if (bytes == nullptr) {
    return 0;
  }

  jint needed = copyClipboardText(
    static_cast<int64_t>(handle),
    reinterpret_cast<uint8_t*>(bytes),
    static_cast<int32_t>(boundedCapacity)
  );
  env->ReleaseByteArrayElements(outBuffer, bytes, 0);
  return needed;
}

void nativeSendInput(
  JNIEnv* env,
  jobject,
  jlong handle,
  jbyteArray input,
  jint count
) {
  auto sendInput = gHost.sendInput;
  if (sendInput == nullptr || input == nullptr || count <= 0) {
    return;
  }

  jsize arrayLength = env->GetArrayLength(input);
  jint boundedCount = count < arrayLength ? count : arrayLength;
  jbyte* bytes = env->GetByteArrayElements(input, nullptr);
  if (bytes == nullptr) {
    return;
  }

  sendInput(
    static_cast<int64_t>(handle),
    reinterpret_cast<const uint8_t*>(bytes),
    static_cast<int32_t>(boundedCount)
  );
  env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
}

jint nativeDeclareCapabilities(
  JNIEnv* env,
  jobject,
  jlong handle,
  jbyteArray json,
  jint count
) {
  // Optional legacy operation; the cached table preserves full-frame defaults.
  auto declareCapabilities =
    gHost.capabilities;
  if (declareCapabilities == nullptr || json == nullptr || count <= 0) {
    return 0;
  }

  jsize arrayLength = env->GetArrayLength(json);
  jint boundedCount = count < arrayLength ? count : arrayLength;
  jbyte* bytes = env->GetByteArrayElements(json, nullptr);
  if (bytes == nullptr) {
    return 0;
  }

  jint accepted = declareCapabilities(
    static_cast<int64_t>(handle),
    reinterpret_cast<const uint8_t*>(bytes),
    static_cast<int32_t>(boundedCount)
  );
  env->ReleaseByteArrayElements(json, bytes, JNI_ABORT);
  return accepted;
}

jint nativeRequestResync(
  JNIEnv* env,
  jobject,
  jlong handle,
  jbyteArray json,
  jint count
) {
  // An absent optional repair operation returns unsupported to Kotlin.
  auto requestResync =
    gHost.resync;
  if (requestResync == nullptr || json == nullptr || count <= 0) {
    return 0;
  }

  jsize arrayLength = env->GetArrayLength(json);
  jint boundedCount = count < arrayLength ? count : arrayLength;
  jbyte* bytes = env->GetByteArrayElements(json, nullptr);
  if (bytes == nullptr) {
    return 0;
  }

  jint accepted = requestResync(
    static_cast<int64_t>(handle),
    reinterpret_cast<const uint8_t*>(bytes),
    static_cast<int32_t>(boundedCount)
  );
  env->ReleaseByteArrayElements(json, bytes, JNI_ABORT);
  return accepted;
}

jint nativeAccessibilityAction(
  JNIEnv* env,
  jobject,
  jlong handle,
  jbyteArray json,
  jint count
) {
  // Older runtime libraries return unsupported instead of interpreting actions as keys.
  auto accessibilityAction =
    gHost.accessibility;
  if (accessibilityAction == nullptr || json == nullptr || count <= 0) {
    return 0;
  }

  jsize arrayLength = env->GetArrayLength(json);
  jint boundedCount = count < arrayLength ? count : arrayLength;
  jbyte* bytes = env->GetByteArrayElements(json, nullptr);
  if (bytes == nullptr) {
    return 0;
  }

  jint accepted = accessibilityAction(
    static_cast<int64_t>(handle),
    reinterpret_cast<const uint8_t*>(bytes),
    static_cast<int32_t>(boundedCount)
  );
  env->ReleaseByteArrayElements(json, bytes, JNI_ABORT);
  return accepted;
}

const JNINativeMethod kMethods[] = {
  {"createHost", "()J", reinterpret_cast<void*>(nativeCreateHost)},
  {"start", "(J)V", reinterpret_cast<void*>(nativeStart)},
  {"tick", "(J)I", reinterpret_cast<void*>(nativeTick)},
  {"stop", "(J)V", reinterpret_cast<void*>(nativeStop)},
  {"destroy", "(J)V", reinterpret_cast<void*>(nativeDestroy)},
  {"resize", "(JIIDD)V", reinterpret_cast<void*>(nativeResize)},
  {"copyLatestFrame", "(J[BI)I", reinterpret_cast<void*>(nativeCopyLatestFrame)},
  {"copyClipboardText", "(J[BI)I", reinterpret_cast<void*>(nativeCopyClipboardText)},
  {"sendInput", "(J[BI)V", reinterpret_cast<void*>(nativeSendInput)},
  {"declareCapabilities", "(J[BI)I", reinterpret_cast<void*>(nativeDeclareCapabilities)},
  {"accessibilityAction", "(J[BI)I", reinterpret_cast<void*>(nativeAccessibilityAction)},
  {"requestResync", "(J[BI)I", reinterpret_cast<void*>(nativeRequestResync)},
};

}  // namespace

// Registering the natives here (rather than relying on implicit symbol binding)
// fails fast on a class/signature mismatch at load and keeps the bound members
// anchored under R8 — see consumer-rules.pro.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  std::call_once(gLoadOnce, loadHostLibrary);
  if (!gLoadError.empty()) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", gLoadError.c_str());
    jclass errorClass = env->FindClass("java/lang/UnsatisfiedLinkError");
    if (errorClass) {
      env->ThrowNew(errorClass, gLoadError.c_str());
      env->DeleteLocalRef(errorClass);
    }
    return JNI_ERR;
  }

  jclass clazz = env->FindClass(kJniClassName);
  if (clazz == nullptr) {
    __android_log_print(
      ANDROID_LOG_ERROR, kLogTag, "FindClass(%s) failed", kJniClassName);
    return JNI_ERR;
  }

  const jint methodCount = static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0]));
  if (env->RegisterNatives(clazz, kMethods, methodCount) != JNI_OK) {
    __android_log_print(
      ANDROID_LOG_ERROR, kLogTag, "RegisterNatives(%s) failed", kJniClassName);
    env->DeleteLocalRef(clazz);
    return JNI_ERR;
  }

  env->DeleteLocalRef(clazz);
  return JNI_VERSION_1_6;
}
