# Run FilmCraft on an Android tablet

This Android Studio project packages the Rust `filmcraft-android-entry` library for 64-bit ARM
devices. It uses Android `NativeActivity` to start eframe. Audio and native file dialogs are omitted
on Android for this first window-only build.

## One-time setup

1. In Android Studio, install Android SDK Platform 37 and an Android NDK using **Tools → SDK
   Manager → SDK Tools**.
2. Install the Rust Android target and Cargo's Android NDK wrapper:

   ```sh
   rustup target add aarch64-linux-android
   cargo install cargo-ndk
   ```

3. Enable **Developer options** and **USB debugging** on the tablet, connect it, and accept the
   computer authorization prompt.

## Build, install, and open

From the repository root:

```sh
cd android
./gradlew :app:installDebug
adb shell monkey -p com.makemission.makredits 1
```

The Gradle build runs `cargo ndk` to build the Rust library for `arm64-v8a`, then packages and
installs the APK. To build an APK without installing it, run `./gradlew :app:assembleDebug`; it is
written to `android/app/build/outputs/apk/debug/app-debug.apk`.

This is only a first window build. Audio, file pickers, media import, touch-oriented controls,
video decoding and tablet layout work are not included yet.
