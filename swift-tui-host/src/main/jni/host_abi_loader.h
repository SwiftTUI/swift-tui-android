#ifndef SWIFT_TUI_HOST_ABI_LOADER_H
#define SWIFT_TUI_HOST_ABI_LOADER_H

#include "swift_tui_android.h"
#include <cstring>
#include <string>

// Resolution is performed once before JNI methods can run. Keep the table and
// validation independent of JNI so a compiled caller can qualify real libraries.
struct HostFunctions {
  decltype(&swift_tui_android_install_executor) install = nullptr;
  decltype(&swift_tui_android_create_host) create = nullptr;
  decltype(&swift_tui_android_start) start = nullptr;
  decltype(&swift_tui_android_tick) tick = nullptr;
  decltype(&swift_tui_android_stop) stop = nullptr;
  decltype(&swift_tui_android_destroy) destroy = nullptr;
  decltype(&swift_tui_android_resize) resize = nullptr;
  decltype(&swift_tui_android_send_input) sendInput = nullptr;
  decltype(&swift_tui_android_copy_latest_frame) copyFrame = nullptr;
  decltype(&swift_tui_android_copy_clipboard_text) copyClipboard = nullptr;
  decltype(&swift_tui_android_declare_capabilities) capabilities = nullptr;
  decltype(&swift_tui_android_request_resync) resync = nullptr;
  decltype(&swift_tui_android_accessibility_action) accessibility = nullptr;
  decltype(&swift_tui_android_diag) diagnostics = nullptr;
  bool legacy = false;

  using Resolver = void* (*)(void*, const char*);

  std::string load(void* library, Resolver resolve) {
    *this = HostFunctions{};
#define REQUIRED(member, name) \
    member = reinterpret_cast<decltype(member)>(resolve(library, name)); \
    if (!member) return std::string("android.abi.missingRequired: ") + name
    REQUIRED(install, "swift_tui_android_install_executor");
    REQUIRED(create, "swift_tui_android_create_host");
    REQUIRED(start, "swift_tui_android_start");
    REQUIRED(tick, "swift_tui_android_tick");
    REQUIRED(stop, "swift_tui_android_stop");
    REQUIRED(destroy, "swift_tui_android_destroy");
    REQUIRED(resize, "swift_tui_android_resize");
    REQUIRED(sendInput, "swift_tui_android_send_input");
    REQUIRED(copyFrame, "swift_tui_android_copy_latest_frame");
#undef REQUIRED
    auto query = reinterpret_cast<decltype(&swift_tui_android_get_abi_info)>(
      resolve(library, "swift_tui_android_get_abi_info"));
    uint32_t operations = UINT32_MAX;
    legacy = query == nullptr;
    if (query) {
      swift_tui_android_abi_info info{};
      const int32_t bytes = query(reinterpret_cast<uint8_t*>(&info), sizeof(info));
      if (bytes < static_cast<int32_t>(sizeof(info)) || info.struct_bytes < sizeof(info))
        return "android.abi.shortInfo: expected at least 32 bytes";
      if (info.major != SWIFT_TUI_ANDROID_ABI_MAJOR)
        return "android.abi.incompatibleMajor: expected 1";
      if (info.handle_bits != 64 || info.count_bits != 32 || info.scalar_bits != 64)
        return "android.abi.argumentWidth: expected handle=64 count=32 scalar=64";
      operations = info.operations;
    }
#define OPTIONAL(member, name, bit) \
    member = reinterpret_cast<decltype(member)>(resolve(library, name)); \
    if (!legacy && (operations & bit) && !member) \
      return std::string("android.abi.missingAdvertised: ") + name; \
    if (!(operations & bit)) member = nullptr
    OPTIONAL(capabilities, "swift_tui_android_declare_capabilities", SWIFT_TUI_ANDROID_OP_CAPABILITIES);
    OPTIONAL(resync, "swift_tui_android_request_resync", SWIFT_TUI_ANDROID_OP_RESYNC);
    OPTIONAL(accessibility, "swift_tui_android_accessibility_action", SWIFT_TUI_ANDROID_OP_ACCESSIBILITY);
    OPTIONAL(copyClipboard, "swift_tui_android_copy_clipboard_text", SWIFT_TUI_ANDROID_OP_CLIPBOARD);
    OPTIONAL(diagnostics, "swift_tui_android_diag", SWIFT_TUI_ANDROID_OP_DIAGNOSTICS);
#undef OPTIONAL
    return {};
  }
};

#endif
