# FilmCraft Android entry point

This tiny `cdylib` exports Android's `android_main` symbol for `NativeActivity` and forwards the
`AndroidApp` handle to the safe startup function in `apps/filmcraft`. The one `#[unsafe(no_mangle)]`
attribute is required by Android's native activity loader; the entry function contains no unsafe
operation. The Gradle shell packages this library as `libfilmcraft_android.so`.

Build it through the Android shell instructions in [`android/README.md`](../../android/README.md).
