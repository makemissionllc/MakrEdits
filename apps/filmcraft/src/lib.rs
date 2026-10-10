//! Safe platform startup helpers shared by the app's native entry points.

#[cfg(target_os = "android")]
#[path = "audio_in.rs"]
mod android_audio_in;
#[cfg(target_os = "android")]
mod android_bridge;

/// Start FilmCraft from Android's native activity lifecycle.
#[cfg(target_os = "android")]
pub fn run_android(android_app: android_activity::AndroidApp) {
    filmcraft_ui_egui::crash::install(None);
    use android_activity::WindowManagerFlags;
    let bridge = android_bridge::safe_directory(android_app.internal_data_path()).and_then(android_bridge::AndroidBridge::new);
    // Keep system bars visible while Java publishes their WindowInsets for the UI safe area.
    android_app.set_window_flags(WindowManagerFlags::FORCE_NOT_FULLSCREEN, WindowManagerFlags::FULLSCREEN);
    let safe_area_bridge = bridge.as_ref().ok().cloned();
    let options = eframe::NativeOptions { android_app: Some(android_app), ..Default::default() };
    let started = eframe::run_native(
        "FilmCraft",
        options,
        Box::new(move |cc| {
            let mut session = filmcraft_engine::Session::default();
            if let Err(error) = session.execute("file.openDemoProject", serde_json::json!({})) {
                log::error!("could not open the demo project: {error}");
            }
            session.voiceover.input = Some(Box::new(android_audio_in::CpalIn::new(&session.prefs.audio_hardware.device_class)));
            let mut app = filmcraft_ui_egui::FilmcraftApp::new(session);
            match bridge {
                Ok(bridge) => {
                    let picker_bridge = bridge.clone();
                    app.hooks.android_picker_request = Some(Box::new(move |request| picker_bridge.request_picker(request)));
                    let event_bridge = bridge.clone();
                    app.hooks.android_take_events = Some(Box::new(move || event_bridge.take_events()));
                    let microphone_bridge = bridge.clone();
                    app.hooks.android_request_microphone = Some(Box::new(move || microphone_bridge.request_microphone()));
                    let notification_bridge = bridge.clone();
                    app.hooks.android_export_started = Some(Box::new(move || notification_bridge.start_export_notifications()));
                    let export_bridge = bridge.clone();
                    app.hooks.android_export_progress = Some(Box::new(move |progress| export_bridge.report_exports(progress)));
                    app.hooks.android_private_export_path = Some(Box::new(move |path| bridge.private_export_path(path)));
                }
                Err(error) => {
                    app.ui.status = format!("Android file picker and export support could not start: {error}");
                    log::error!("Android bridge unavailable: {error}");
                }
            }
            app.hooks.safe_area_insets = Some(Box::new(move |size, pixels_per_point| {
                if !pixels_per_point.is_finite() || pixels_per_point <= 0.0 || !size.x.is_finite() || !size.y.is_finite() {
                    return [0.0; 4];
                }
                safe_area_bridge
                    .as_ref()
                    .and_then(android_bridge::AndroidBridge::safe_area_insets_px)
                    .map(|insets| [insets[0] as f32, insets[1] as f32, insets[2] as f32, insets[3] as f32].map(|pixels| pixels / pixels_per_point))
                    .unwrap_or([0.0, 24.0, 0.0, 24.0])
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
