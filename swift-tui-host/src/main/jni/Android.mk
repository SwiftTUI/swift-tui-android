LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := swift_tui_jni
LOCAL_SRC_FILES := swift_tui_jni.cpp
LOCAL_CPPFLAGS += -std=c++17 -Wall -Wextra -Werror
LOCAL_LDLIBS := -ldl -llog
# Align LOAD segments at 16 KB so the shim is compatible with Android 15+ 16 KB
# page-size devices (required for Google Play since 2025-11-01). NDK r27's
# ndk-build does not default to this; the Swift runtime/host `.so`s are already
# 16 KB-aligned by the Swift Android SDK.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)

