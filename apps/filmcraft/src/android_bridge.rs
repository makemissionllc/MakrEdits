//! File based bridge to the Android Activity. Android's SAF and runtime permission APIs are
//! asynchronous Java APIs; this keeps them outside the Rust engine and avoids JNI/unsafe code.

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use serde_json::Value;

#[derive(Clone)]
pub struct AndroidBridge {
    directory: PathBuf,
    safe_area_cache: Arc<Mutex<SafeAreaCache>>,
}

#[derive(Default)]
struct SafeAreaCache {
    initialized: bool,
    modified: Option<SystemTime>,
    pixels: Option<[u32; 4]>,
}

impl AndroidBridge {
    pub fn new(directory: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&directory).map_err(|error| format!("could not create Android app storage: {error}"))?;
        Ok(Self { directory, safe_area_cache: Arc::new(Mutex::new(SafeAreaCache::default())) })
    }

    /// Read the latest Android WindowInsets values (physical pixels), caching file contents until
    /// the Java side publishes a new insets file. Missing or malformed state remains recoverable.
    pub fn safe_area_insets_px(&self) -> Option<[u32; 4]> {
        let path = self.directory.join("safe-area.json");
        let modified = fs::metadata(&path).and_then(|metadata| metadata.modified()).ok();
        let mut cache = self.safe_area_cache.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if cache.initialized && modified == cache.modified {
            return cache.pixels;
        }
        cache.modified = modified;
        let pixels = modified.and_then(|_| read_bounded(&path, 1024).ok()).and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok()).and_then(|value| {
            let inset = |name: &str| value.get(name)?.as_u64().and_then(|value| u32::try_from(value).ok());
            Some([inset("left")?, inset("top")?, inset("right")?, inset("bottom")?])
        });
        cache.initialized = modified.is_none() || pixels.is_some();
        cache.pixels = pixels;
        pixels
    }

    pub fn request_picker(&self, request: &Value) -> Result<(), String> {
        self.write_json("picker-request.json", request)
    }

    pub fn request_microphone(&self) -> Result<(), String> {
        self.write_json("microphone-request.json", &serde_json::json!({"kind":"microphone"}))
    }

    pub fn start_export_notifications(&self) -> Result<(), String> {
        self.write_json("notification-start.json", &serde_json::json!({"kind":"export"}))
    }

    pub fn report_exports(&self, progress: &Value) -> Result<(), String> {
        self.write_json("export-progress.json", progress)
    }

    pub fn private_export_path(&self, requested: &str) -> Result<String, String> {
        let name = Path::new(requested).file_name().and_then(|name| name.to_str()).unwrap_or("Export.mp4");
        let safe_name: String = name.chars().map(|ch| if ch.is_alphanumeric() || " -_().".contains(ch) { ch } else { '_' }).collect();
        let safe_name = if safe_name.trim().is_empty() || matches!(safe_name.as_str(), "." | "..") { "Export.mp4" } else { &safe_name };
        let directory = self.directory.join("exports");
        fs::create_dir_all(&directory).map_err(|error| format!("could not create private export storage: {error}"))?;
        Ok(directory.join(safe_name).to_string_lossy().into_owned())
    }

    pub fn take_events(&self) -> Result<Vec<Value>, String> {
        let mut events = Vec::new();
        for (file, kind) in [
            ("picker-result.json", "picker"),
            ("microphone-result.json", "microphone_permission"),
            ("notification-result.json", "notification_permission"),
            ("export-error.json", "android_error"),
        ] {
            if let Some(mut event) = self.take_json(file)? {
                if let Some(object) = event.as_object_mut() {
                    object.insert("kind".into(), Value::String(kind.into()));
                    events.push(event);
                }
            }
        }
        Ok(events)
    }

    fn write_json(&self, name: &str, value: &Value) -> Result<(), String> {
        let destination = self.directory.join(name);
        let temporary = self.directory.join(format!("{name}.rust-tmp"));
        let bytes = serde_json::to_vec(value).map_err(|error| format!("could not encode Android request: {error}"))?;
        fs::write(&temporary, bytes).map_err(|error| format!("could not write Android request: {error}"))?;
        fs::rename(&temporary, &destination).map_err(|error| {
            let _ = fs::remove_file(&temporary);
            format!("could not publish Android request: {error}")
        })
    }

    fn take_json(&self, name: &str) -> Result<Option<Value>, String> {
        let path = self.directory.join(name);
        let bytes = match read_bounded(&path, 4 * 1024 * 1024) {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(format!("could not read Android response: {error}")),
        };
        let _ = fs::remove_file(&path);
        if bytes.len() > 4 * 1024 * 1024 {
            return Err("Android response was larger than 4 MiB".into());
        }
        serde_json::from_slice(&bytes).map(Some).map_err(|error| format!("Android returned invalid response data: {error}"))
    }
}

/// Limit allocation before parsing even when a bridge file is malformed or replaced mid-read.
fn read_bounded(path: &Path, limit: usize) -> std::io::Result<Vec<u8>> {
    let file = fs::File::open(path)?;
    let mut bytes = Vec::new();
    file.take(limit.saturating_add(1) as u64).read_to_end(&mut bytes)?;
    if bytes.len() > limit {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Android bridge response exceeded its size limit"));
    }
    Ok(bytes)
}

pub fn safe_directory(path: Option<PathBuf>) -> Result<PathBuf, String> {
    let Some(path) = path else { return Err("Android did not provide app-private storage".into()) };
    let bridge = path.join("filmcraft-bridge");
    if !Path::new(&bridge).is_absolute() {
        return Err("Android app storage path was not absolute".into());
    }
    Ok(bridge)
}
