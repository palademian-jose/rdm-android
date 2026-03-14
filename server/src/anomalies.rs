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
pub struct AnomalyData {
    pub anomaly_type: String,
    pub source: String,
    pub message: String,
    pub severity: String,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnomalyLog {
    pub id: String,
    pub device_id: String,
    pub anomaly_type: String,
    pub source: String,
    pub message: String,
    pub severity: String,
    pub timestamp: i64,
    pub created_at: String,
}

/// Save anomaly log to file system organized by device_id and date
pub fn save_anomaly_log(device_id: &str, anomaly_data: &AnomalyData) -> Result<String, String> {
    // Create base directory structure: recordings/{device_id}/anomalies/
    let base_dir = format!("recordings/{}/anomalies", device_id);

    // Create directory if it doesn't exist
    if let Err(e) = fs::create_dir_all(&base_dir) {
        error!("Failed to create anomalies directory: {}", e);
        return Err(format!("Failed to create anomalies directory: {}", e));
    }

    // Convert timestamp to date for file organization
    let date: String = match Utc.timestamp_opt(anomaly_data.timestamp / 1000, 0) {
        chrono::LocalResult::Single(datetime) => datetime.format("%Y-%m-%d").to_string(),
        _ => Utc::now().format("%Y-%m-%d").to_string(),
    };

    // Create or append to daily anomaly log file
    let log_filename = format!("{}/anomalies_{}.jsonl", base_dir, date);

    // Create anomaly log entry
    let log_entry = AnomalyLog {
        id: uuid::Uuid::new_v4().to_string(),
        device_id: device_id.to_string(),
        anomaly_type: anomaly_data.anomaly_type.clone(),
        source: anomaly_data.source.clone(),
        message: anomaly_data.message.clone(),
        severity: anomaly_data.severity.clone(),
        timestamp: anomaly_data.timestamp,
        created_at: Utc::now().to_rfc3339(),
    };

    // Serialize to JSON and append to file
    let log_line = serde_json::to_string(&log_entry)
        .map_err(|e| format!("Failed to serialize anomaly log: {}", e))?;

    // Append to file (create if doesn't exist, append if it does)
    match fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_filename)
    {
        Ok(mut file) => {
            if let Err(e) = writeln!(file, "{}", log_line) {
                error!("Failed to write anomaly log: {}", e);
                return Err(format!("Failed to write anomaly log: {}", e));
            }
        }
        Err(e) => {
            error!("Failed to open anomaly log file: {}", e);
            return Err(format!("Failed to open anomaly log file: {}", e));
        }
    }

    info!(
        "Saved anomaly log for device {}: {} - {}",
        device_id, anomaly_data.anomaly_type, anomaly_data.message
    );

    Ok(log_filename)
}

/// List anomaly logs for a specific device
pub async fn list_anomalies(
    path: web::Path<String>,
    query: web::Query<std::collections::HashMap<String, String>>,
) -> impl Responder {
    let device_id = path.into_inner();
    let anomalies_dir = format!("recordings/{}/anomalies", device_id);

    // Get date filter from query parameters
    let date_filter = query.get("date").map(|d| d.as_str());

    if !Path::new(&anomalies_dir).exists() {
        return HttpResponse::NotFound().json(ApiResponse {
            success: false,
            message: format!("No anomalies found for device: {}", device_id),
            data: None,
        });
    }

    match fs::read_dir(&anomalies_dir) {
        Ok(entries) => {
            let mut all_anomalies = Vec::new();

            for entry in entries.flatten() {
                let file_name = entry.file_name().to_string_lossy().to_string();

                // Filter by date if specified
                if let Some(date) = date_filter {
                    if !file_name.contains(&format!("anomalies_{}.jsonl", date)) {
                        continue;
                    }
                }

                // Only process .jsonl files
                if file_name.ends_with(".jsonl") {
                    // Read and parse the file
                    if let Ok(content) = fs::read_to_string(entry.path()) {
                        for line in content.lines() {
                            if let Ok(anomaly) = serde_json::from_str::<AnomalyLog>(line) {
                                all_anomalies.push(anomaly);
                            }
                        }
                    }
                }
            }

            // Sort by timestamp descending (newest first)
            all_anomalies.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));

            HttpResponse::Ok().json(ApiResponse {
                success: true,
                message: format!("Found {} anomaly/anomalies", all_anomalies.len()),
                data: Some(json!({
                    "device_id": device_id,
                    "anomalies": all_anomalies,
                    "count": all_anomalies.len()
                })),
            })
        }
        Err(e) => {
            HttpResponse::InternalServerError().json(ApiResponse {
                success: false,
                message: format!("Failed to read anomalies directory: {}", e),
                data: None,
            })
        }
    }
}

/// Get anomaly statistics for a device
pub async fn get_anomaly_stats(
    path: web::Path<String>,
) -> impl Responder {
    let device_id = path.into_inner();
    let anomalies_dir = format!("recordings/{}/anomalies", device_id);

    if !Path::new(&anomalies_dir).exists() {
        return HttpResponse::Ok().json(ApiResponse {
            success: true,
            message: "No anomalies found".to_string(),
            data: Some(json!({
                "device_id": device_id,
                "total_count": 0,
                "by_type": {},
                "by_severity": {},
                "recent_anomalies": []
            })),
        });
    }

    match fs::read_dir(&anomalies_dir) {
        Ok(entries) => {
            let mut all_anomalies = Vec::new();
            let mut by_type = std::collections::HashMap::new();
            let mut by_severity = std::collections::HashMap::new();

            for entry in entries.flatten() {
                let file_name = entry.file_name().to_string_lossy().to_string();

                if file_name.ends_with(".jsonl") {
                    if let Ok(content) = fs::read_to_string(entry.path()) {
                        for line in content.lines() {
                            if let Ok(anomaly) = serde_json::from_str::<AnomalyLog>(line) {
                                // Count by type
                                *by_type.entry(anomaly.anomaly_type.clone()).or_insert(0) += 1;

                                // Count by severity
                                *by_severity.entry(anomaly.severity.clone()).or_insert(0) += 1;

                                all_anomalies.push(anomaly);
                            }
                        }
                    }
                }
            }

            // Sort by timestamp and get recent ones (last 10)
            all_anomalies.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));
            let recent_anomalies = all_anomalies.iter().take(10).map(|a| a.clone()).collect::<Vec<_>>();

            HttpResponse::Ok().json(ApiResponse {
                success: true,
                message: format!("Found {} total anomaly/anomalies", all_anomalies.len()),
                data: Some(json!({
                    "device_id": device_id,
                    "total_count": all_anomalies.len(),
                    "by_type": by_type,
                    "by_severity": by_severity,
                    "recent_anomalies": recent_anomalies
                })),
            })
        }
        Err(e) => {
            HttpResponse::InternalServerError().json(ApiResponse {
                success: false,
                message: format!("Failed to read anomalies directory: {}", e),
                data: None,
            })
        }
    }
}
