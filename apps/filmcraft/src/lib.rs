//! Safe platform startup helpers shared by the app's native entry points.

/// Start FilmCraft from Android's native activity lifecycle.
#[cfg(target_os = "android")]
pub fn run_android(android_app: android_activity::AndroidApp) {
    filmcraft_ui_egui::crash::install(None);
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
