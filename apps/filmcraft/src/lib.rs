//! Safe platform startup helpers shared by the app's native entry points.

/// Start FilmCraft from Android's native activity lifecycle.
#[cfg(target_os = "android")]
pub fn run_android(android_app: android_activity::AndroidApp) {
    filmcraft_ui_egui::crash::install(None);
    use android_activity::WindowManagerFlags;
    // NativeActivity can otherwise hide the system decorations. Keep status/navigation bars
    // visible; targetSdk 35+ lays out edge-to-edge, so the UI below also follows content_rect().
    android_app.set_window_flags(WindowManagerFlags::FORCE_NOT_FULLSCREEN, WindowManagerFlags::FULLSCREEN);
    let safe_area_app = android_app.clone();
    let options = eframe::NativeOptions { android_app: Some(android_app), ..Default::default() };
    let started = eframe::run_native(
        "FilmCraft",
        options,
        Box::new(|cc| {
            let mut session = filmcraft_engine::Session::default();
            if let Err(error) = session.execute("file.openDemoProject", serde_json::json!({})) {
                log::error!("could not open the demo project: {error}");
            }
            let mut app = filmcraft_ui_egui::FilmcraftApp::new(session);
            app.hooks.safe_area_insets = Some(Box::new(move |size, pixels_per_point| {
                if !pixels_per_point.is_finite() || pixels_per_point <= 0.0 || !size.x.is_finite() || !size.y.is_finite() {
                    return [0.0; 4];
                }
                let width = size.x * pixels_per_point;
                let height = size.y * pixels_per_point;
                if !width.is_finite() || !height.is_finite() || width <= 0.0 || height <= 0.0 {
                    return [0.0; 4];
                }
                let rect = safe_area_app.content_rect();
                if rect.right <= rect.left || rect.bottom <= rect.top {
                    return [0.0; 4];
                }
                [
                    (rect.left.max(0) as f32 / pixels_per_point).min(size.x),
                    (rect.top.max(0) as f32 / pixels_per_point).min(size.y),
                    (width - rect.right as f32).max(0.0) / pixels_per_point,
                    (height - rect.bottom as f32).max(0.0) / pixels_per_point,
                ]
            }));
            if let Some(render_state) = cc.wgpu_render_state.clone() {
                app.set_wgpu(render_state);
            }
            Ok(Box::new(app))
        }),
    );
    if let Err(error) = started {
        let message = format!("FilmCraft could not start its Android window: {error}");
        log::error!("{message}");
        filmcraft_ui_egui::crash::record(&message);
    }
}
