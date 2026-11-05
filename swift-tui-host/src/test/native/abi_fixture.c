#include "swift_tui_android.h"
#include <string.h>

#ifndef ABI_MAJOR
#define ABI_MAJOR 1
#endif
#ifndef COUNT_BITS
#define COUNT_BITS 32
#endif
#ifndef INFO_BYTES
#define INFO_BYTES 32
#endif
#ifndef OPERATIONS
#define OPERATIONS 0
#endif

#ifndef LEGACY
int32_t swift_tui_android_get_abi_info(uint8_t *out, int32_t capacity) {
  const swift_tui_android_abi_info info = {INFO_BYTES, ABI_MAJOR, 0, 64, COUNT_BITS, 64, OPERATIONS, 0};
  if (out && capacity > 0) {
    int32_t count = capacity < INFO_BYTES ? capacity : INFO_BYTES;
    memcpy(out, &info, (size_t)count);
  }
  return INFO_BYTES;
}
#endif

void swift_tui_android_install_executor(void) {}
#ifndef OMIT_FACTORY
int64_t swift_tui_android_create_host(void) { return INT64_C(0x100000002); }
#endif
#ifndef OMIT_START
void swift_tui_android_start(int64_t handle) { (void)handle; }
#endif
int32_t swift_tui_android_tick(int64_t handle) { return handle == INT64_C(0x100000002) ? 1 : -1; }
void swift_tui_android_stop(int64_t handle) { (void)handle; }
void swift_tui_android_destroy(int64_t handle) { (void)handle; }
void swift_tui_android_resize(int64_t h, int32_t c, int32_t r, double w, double t) {
  (void)h; (void)c; (void)r; (void)w; (void)t;
}
void swift_tui_android_send_input(int64_t h, const uint8_t *b, int32_t c) {
  (void)h; (void)b; (void)c;
}
int32_t swift_tui_android_copy_latest_frame(int64_t h, uint8_t *b, int32_t c) {
  (void)h; (void)b; (void)c; return 0;
}
