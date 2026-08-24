# libaapt2.so

Statically-linked, Android-native `aapt2` builds, one per ABI, invoked via
`ProcessBuilder` from `apktool.module.Apktool` (see `getAapt2Binary`). Named
`lib*.so` and placed under `jniLibs` (not `assets`) so the OS extracts them
into `nativeLibraryDir` with exec permission at install time - that only
works with `android:extractNativeLibs="true"` (AndroidManifest.xml) and
`packagingOptions.jniLibs.useLegacyPackaging true` (app/build.gradle), both
already set for this project.

Source: https://github.com/anirudhmalik/apktool-android
(`brut.apktool/apktool-android/src/main/jniLibs/`), Apache-2.0. Built via the
`aapt2-build/` recipe in that repo (NDK r27b, target Android 30, fully static
- no `DT_NEEDED` entries, so no `libc++_shared.so`/`libz.so` dlopen failures).

Covers `arm64-v8a`, `armeabi-v7a`, `x86_64`. No build exists yet for 32-bit
`x86` (real-world 32-bit-only Android devices are essentially extinct) -
repackaging will fail with a clear IOException on such a device instead of
misbehaving silently.
