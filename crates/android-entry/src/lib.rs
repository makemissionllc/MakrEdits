#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

#[cfg(target_os = "android")]
// SAFETY: Android locates this Rust-ABI entry point by the unmangled `android_main` symbol.
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub fn android_main(android_app: android_activity::AndroidApp) {
    filmcraft::run_android(android_app);
}
