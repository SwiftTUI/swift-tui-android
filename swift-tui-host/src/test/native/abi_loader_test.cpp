#include "host_abi_loader.h"
#include <algorithm>
#include <dlfcn.h>
#include <cassert>
#include <iostream>

static int lookups = 0;
static void* countedResolve(void* library, const char* name) {
  ++lookups;
  return dlsym(library, name);
}

int main(int argc, char** argv) {
  // argv: library, expected load result ("ok+open-link" also expects open link
  // to resolve), and whether take clipboard resolves.
  assert(argc == 4);
  const bool expectsTake = std::string(argv[3]) == "take";
  void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (!library) { std::cerr << dlerror() << '\n'; return 1; }
  HostFunctions functions;
  const auto error = functions.load(library, countedResolve);
  if (std::string(argv[2]).rfind("ok", 0) != 0) {
    assert(error.find(argv[2]) == 0);
    std::cout << error << '\n';
  } else {
    assert(error.empty());
    assert(!functions.capabilities && !functions.resync && !functions.accessibility);
    // "ok" admits the library without the optional operation; "ok+open-link"
    // also requires the loader to have resolved it.
    if (std::string(argv[2]) == "ok+open-link") {
      assert(functions.copyOpenLink && functions.copyOpenLink(INT64_C(0x100000002), nullptr, 0) == 7);
    } else {
      assert(!functions.copyOpenLink);
    }
    // Take clipboard follows the same rule as the other optional operations: the
    // advertised bit decides, not the symbol; a legacy library has no bits, so
    // there the symbol decides.
    assert((functions.takeClipboard != nullptr) == expectsTake);
    if (functions.takeClipboard) assert(functions.takeClipboard(INT64_C(0x100000002), nullptr, 0) == -1);
    const int resolved = lookups;
    functions.install();
    const auto handle = functions.create();
    assert(handle == INT64_C(0x100000002));
    functions.start(handle);
    for (int tick = 0; tick < 10000; ++tick) assert(functions.tick(handle) == 1);
    functions.stop(handle);
    functions.destroy(handle);
    assert(lookups == resolved);
    auto query = reinterpret_cast<decltype(&swift_tui_android_get_abi_info)>(
      dlsym(library, "swift_tui_android_get_abi_info"));
    if (query) {
      assert(query(nullptr, 0) == 32);
      uint8_t bytes[40];
      for (int capacity = -1; capacity <= 40; ++capacity) {
        std::memset(bytes, 0xA5, sizeof(bytes));
        assert(query(bytes, capacity) == 32);
        for (int i = std::max(0, std::min(capacity, 32)); i < 40; ++i) assert(bytes[i] == 0xA5);
      }
    }
    std::cout << (functions.legacy ? "legacy" : "ABI 1") << ": 10000 ticks, "
              << resolved << " startup lookups, no tick lookups\n";
  }
  dlclose(library);
}
