# The ABIs the AAR ships its JNI shim for. Keep equal to `shimAbis` in
# ../../../build.gradle.kts and to the Gradle plugin's SwiftTuiAndroidAbi table;
# the plugin's unit tests fail when the three differ.
APP_ABI := arm64-v8a x86_64
APP_PLATFORM := android-28
APP_STL := c++_static

