use actix_web::{web, HttpResponse, Responder};
use chrono::{TimeZone, Utc};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::fs;
use std::io::Write;
use std::path::Path;
use tracing::{error, info};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse {
    pub success: bool,
    pub message: String,
    pub data: Option<serde_json::Value>,
}

#[derive(Debug, Deserialize)]
pub struct UnifiedEvent {
    pub timestamp: i64,
    pub source: String,
    pub event_type: String,
    pub package_name: Option<String>,
    pub severity: String,
    pub description: String,
    pub details: serde_json::Value,
}

#[derive(Debug, Deserialize)]
pub struct UnifiedEventsBatch {
    pub event_count: i32,
    pub events: Vec<UnifiedEvent>,
}

/// Save unified event to organized storage
pub fn save_unified_event(
    device_id: &str,
    event: &UnifiedEvent,
) -> Result<String, String> {
    // Create organized directory structure
    let base_dir = format!("recordings/{}/unified_events", device_id);

    // Create subdirectories by source type
    let source_dir = format!("{}/{}", base_dir, event.source.to_lowercase());
    if let Err(e) = fs::create_dir_all(&source_dir) {
        error!("Failed to create source directory: {}", e);
        return Err(format!("Failed to create source directory: {}", e));
    }

    // Convert timestamp to date for daily log files
    let date: String = match Utc.timestamp_opt(event.timestamp / 1000, 0) {
        chrono::LocalResult::Single(datetime) => datetime.format("%Y-%m-%d").to_string(),
        _ => Utc::now().format("%Y-%m-%d").to_string(),
    };

    // Create daily log file
    let log_filename = format!("{}/events_{}.jsonl", source_dir, date);

    // Create enhanced log entry
    let log_entry = json!({
        "timestamp": event.timestamp,
        "device_id": device_id,
        "source": event.source,
        "event_type": event.event_type,
        "package_name": event.package_name,
        "severity": event.severity,
        "description": event.description,
        "details": event.details
    });

    // Append to file
    match fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_filename)
    {
        Ok(mut file) => {
            if let Err(e) = writeln!(file, "{}", log_entry) {
                error!("Failed to write event log: {}", e);
                return Err(format!("Failed to write event log: {}", e));
            }
        }
        Err(e) => {
            error!("Failed to open event log file: {}", e);
            return Err(format!("Failed to open event log file: {}", e));
        }
    }

    info!(
        "Unified event saved for device {}: {} - {}",
        device_id, event.source, event.event_type
    );

    Ok(log_filename)
}

/// Handle unified event from device
pub async fn handle_unified_event(
    path: web::Path<String>,
    event: web::Json<UnifiedEvent>,
) -> impl Responder {
    let device_id = path.into_inner();
    let event_data = event.into_inner();

    info!(
        "Received unified event from device {}: {} - {}",
        device_id, event_data.source, event_data.event_type
    );

    match save_unified_event(&device_id, &event_data) {
        Ok(_) => HttpResponse::Ok().json(ApiResponse {
            success: true,
            message: "Event logged successfully".to_string(),
            data: Some(json!({
                "device_id": device_id,
                "event_type": event_data.event_type,
                "timestamp": event_data.timestamp
            })),
        }),
        Err(e) => HttpResponse::InternalServerError().json(ApiResponse {
            success: false,
            message: format!("Failed to log event: {}", e),
            data: None,
        }),
    }
}

/// Handle batch of unified events from device
pub async fn handle_unified_events_batch(
    path: web::Path<String>,
    batch: web::Json<serde_json::Value>,
) -> impl Responder {
    let device_id = path.into_inner();
    let batch_data = batch.into_inner();

    info!(
        "Received batch of {} events from device {}",
        batch_data["event_count"],
        device_id
    );

    // Parse events array
    let events_array = match batch_data["events"].as_array() {
        Some(arr) => arr.clone(),
        None => {
            return HttpResponse::BadRequest().json(ApiResponse {
                success: false,
                message: "Invalid events array".to_string(),
                data: None,
            })
        }
    };

    let mut saved_count = 0;
    let mut failed_count = 0;

    for event_value in &events_array {
        match serde_json::from_value::<UnifiedEvent>(event_value.clone()) {
            Ok(event) => {
                match save_unified_event(&device_id, &event) {
                    Ok(_) => saved_count += 1,
                    Err(_) => failed_count += 1,
                }
            }
            Err(e) => {
                error!("Failed to parse event: {}", e);
                failed_count += 1;
            }
        }
    }

    HttpResponse::Ok().json(ApiResponse {
        success: true,
        message: format!(
            "Processed {} events (saved: {}, failed: {})",
            events_array.len(),
            saved_count,
            failed_count
        ),
        data: Some(json!({
            "device_id": device_id,
            "total_events": events_array.len(),
            "saved_count": saved_count,
            "failed_count": failed_count
        })),
    })
}

/// List unified events for a device
pub async fn list_unified_events(
    path: web::Path<String>,
    query: web::Query<std::collections::HashMap<String, String>>,
) -> impl Responder {
    let device_id = path.into_inner();
    let unified_events_dir = format!("recordings/{}/unified_events", device_id);

    // Get filter parameters
    let source_filter = query.get("source");
    let date_filter = query.get("date");
    let severity_filter = query.get("severity");

    if !Path::new(&unified_events_dir).exists() {
        return HttpResponse::NotFound().json(ApiResponse {
            success: false,
            message: format!("No unified events found for device: {}", device_id),
            data: None,
        });
    }

    match fs::read_dir(&unified_events_dir) {
        Ok(entries) => {
            let mut all_events = Vec::new();

            for entry in entries.flatten() {
                let source_name = entry.file_name().to_string_lossy().to_string();

                // Apply source filter
                if let Some(source) = source_filter {
                    if !source_name.contains(&source.to_lowercase()) {
                        continue;
                    }
                }

                // Read events from source directory
                let source_dir = entry.path();
                if let Ok(file_entries) = fs::read_dir(&source_dir) {
                    for file_entry in file_entries.flatten() {
                        let file_name = file_entry.file_name().to_string_lossy().to_string();

                        // Apply date filter
                        if let Some(date) = date_filter {
                            if !file_name.contains(&format!("events_{}.jsonl", date)) {
                                continue;
                            }
                        }

                        if file_name.ends_with(".jsonl") {
                            if let Ok(content) = fs::read_to_string(file_entry.path()) {
                                for line in content.lines() {
                                    if let Ok(event) = serde_json::from_str::<serde_json::Value>(line) {
                                        // Apply severity filter
                                        if let Some(severity) = severity_filter {
                                            if event["severity"] != *severity {
                                                continue;
                                            }
                                        }
                                        all_events.push(event);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sort by timestamp descending
            all_events.sort_by(|a, b| {
                let a_time = b["timestamp"].as_i64().unwrap_or(0);
                let b_time = a["timestamp"].as_i64().unwrap_or(0);
                a_time.cmp(&b_time)
            });

            HttpResponse::Ok().json(ApiResponse {
                success: true,
                message: format!("Found {} event(s)", all_events.len()),
                data: Some(json!({
                    "device_id": device_id,
                    "events": all_events,
                    "count": all_events.len()
                })),
            })
        }
        Err(e) => {
            HttpResponse::InternalServerError().json(ApiResponse {
                success: false,
                message: format!("Failed to read events directory: {}", e),
                data: None,
            })
        }
    }
}

/// Get unified event statistics for a device
pub async fn get_unified_event_stats(path: web::Path<String>) -> impl Responder {
    let device_id = path.into_inner();
    let unified_events_dir = format!("recordings/{}/unified_events", device_id);

    if !Path::new(&unified_events_dir).exists() {
        return HttpResponse::Ok().json(ApiResponse {
            success: true,
            message: "No unified events found".to_string(),
            data: Some(json!({
                "device_id": device_id,
                "total_count": 0,
                "by_source": {},
                "by_severity": {},
                "by_type": {},
                "recent_events": []
            })),
        });
    }

    match fs::read_dir(&unified_events_dir) {
        Ok(entries) => {
            let mut all_events = Vec::new();
            let mut by_source = std::collections::HashMap::new();
            let mut by_severity = std::collections::HashMap::new();
            let mut by_type = std::collections::HashMap::new();

            for entry in entries.flatten() {
                let source_name = entry.file_name().to_string_lossy().to_string();
                let source_dir = entry.path();

                if let Ok(file_entries) = fs::read_dir(&source_dir) {
                    for file_entry in file_entries.flatten() {
                        let file_name = file_entry.file_name().to_string_lossy().to_string();

                        if file_name.ends_with(".jsonl") {
                            if let Ok(content) = fs::read_to_string(file_entry.path()) {
                                for line in content.lines() {
                                    if let Ok(event) = serde_json::from_str::<serde_json::Value>(line) {
                                        // Count by source
                                        *by_source
                                            .entry(event["source"].as_str().unwrap_or("unknown").to_string())
                                            .or_insert(0) += 1;

                                        // Count by severity
                                        *by_severity
                                            .entry(event["severity"].as_str().unwrap_or("unknown").to_string())
                                            .or_insert(0) += 1;

                                        // Count by type
                                        *by_type
                                            .entry(event["event_type"].as_str().unwrap_or("unknown").to_string())
                                            .or_insert(0) += 1;

                                        all_events.push(event);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sort by timestamp and get recent ones (last 20)
            all_events.sort_by(|a, b| {
                let a_time = b["timestamp"].as_i64().unwrap_or(0);
                let b_time = a["timestamp"].as_i64().unwrap_or(0);
                a_time.cmp(&b_time)
            });
            let recent_events = all_events.iter().take(20).cloned().collect::<Vec<_>>();

            HttpResponse::Ok().json(ApiResponse {
                success: true,
                message: format!("Found {} total event(s)", all_events.len()),
                data: Some(json!({
                    "device_id": device_id,
                    "total_count": all_events.len(),
                    "by_source": by_source,
                    "by_severity": by_severity,
                    "by_type": by_type,
                    "recent_events": recent_events
                })),
            })
        }
        Err(e) => {
            HttpResponse::InternalServerError().json(ApiResponse {
                success: false,
                message: format!("Failed to read events directory: {}", e),
                data: None,
            })
        }
    }
}
