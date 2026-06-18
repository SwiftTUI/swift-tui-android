#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstdint>
#include <mutex>

namespace {

constexpr const char* kLogTag = "SwiftTUIJNI";
// The consumer's Swift host product, standardized to a canonical file name by
// the SwiftTUI Android Gradle convention plugin as it copies the built `.so`
// into jniLibs. The library never references an app-specific product name.
constexpr const char* kHostLibrary = "libswift_tui_app_host.so";
constexpr const char* kJniClassName = "sh/swifttui/android/host/SwiftTUIJni";

using VoidFunction = void (*)();
using CreateHost = int64_t (*)();
using HandleFunction = void (*)(int64_t);
using TickFunction = int32_t (*)(int64_t);
using DiagFunction = int64_t (*)();
using ResizeFunction = void (*)(int64_t, int32_t, int32_t, double, double);
using SendInputFunction = void (*)(int64_t, const uint8_t*, int32_t);
using CopyLatestFrameFunction = int32_t (*)(int64_t, uint8_t*, int32_t);
using CopyClipboardTextFunction = int32_t (*)(int64_t, uint8_t*, int32_t);

std::once_flag gLoadOnce;
void* gHostHandle = nullptr;

void loadHostLibrary() {
  gHostHandle = dlopen(kHostLibrary, RTLD_NOW | RTLD_LOCAL);
  if (gHostHandle == nullptr) {
    __android_log_print(
      ANDROID_LOG_ERROR,
      kLogTag,
      "dlopen(%s) failed: %s",
      kHostLibrary,
      dlerror()
    );
  }
}

void* resolveSymbol(const char* name) {
  std::call_once(gLoadOnce, loadHostLibrary);
  if (gHostHandle == nullptr) {
    return nullptr;
  }

  dlerror();
  void* symbol = dlsym(gHostHandle, name);
  const char* error = dlerror();
  if (error != nullptr) {
    __android_log_print(
      ANDROID_LOG_ERROR,
      kLogTag,
      "dlsym(%s) failed: %s",
      name,
      error
    );
    return nullptr;
  }
  return symbol;
}

template <typename Function>
Function swiftSymbol(const char* name) {
  return reinterpret_cast<Function>(resolveSymbol(name));
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
        swiftSymbol<VoidFunction>("swift_tui_android_install_executor")) {
    install();
  }

  auto createHost = swiftSymbol<CreateHost>("swift_tui_android_create_host");
  if (createHost == nullptr) {
    return 0;
  }
  return static_cast<jlong>(createHost());
}

void nativeStart(JNIEnv*, jobject, jlong handle) {
  auto start = swiftSymbol<HandleFunction>("swift_tui_android_start");
  if (start != nullptr) {
    start(static_cast<int64_t>(handle));
  }
}

jint nativeTick(JNIEnv*, jobject, jlong handle) {
  auto tick = swiftSymbol<TickFunction>("swift_tui_android_tick");
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
    if (auto diagFn = swiftSymbol<DiagFunction>("swift_tui_android_diag")) {
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
  auto stop = swiftSymbol<HandleFunction>("swift_tui_android_stop");
  if (stop != nullptr) {
    stop(static_cast<int64_t>(handle));
  }
}

void nativeDestroy(JNIEnv*, jobject, jlong handle) {
  auto destroy = swiftSymbol<HandleFunction>("swift_tui_android_destroy");
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
  auto resize = swiftSymbol<ResizeFunction>("swift_tui_android_resize");
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
  auto copyLatestFrame = swiftSymbol<CopyLatestFrameFunction>(
    "swift_tui_android_copy_latest_frame"
  );
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
  auto copyClipboardText = swiftSymbol<CopyClipboardTextFunction>(
    "swift_tui_android_copy_clipboard_text"
  );
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
  auto sendInput = swiftSymbol<SendInputFunction>("swift_tui_android_send_input");
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
