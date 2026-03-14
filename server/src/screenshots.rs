use actix_multipart::Multipart;
use actix_web::{web, HttpResponse, Responder};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::fs;
use tokio::io::AsyncWriteExt;
use tracing::{error, info};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse {
    pub success: bool,
    pub message: String,
    pub data: Option<serde_json::Value>,
}

#[derive(Debug, Deserialize)]
pub struct ScreenshotUploadQuery {
    pub device_id: String,
    pub package_name: String,
    pub timestamp: String,
    pub width: Option<u32>,
    pub height: Option<u32>,
}

pub async fn upload_screenshot(
    query: web::Query<ScreenshotUploadQuery>,
    mut payload: Multipart,
    app_state: web::Data<crate::AppState>,
) -> impl Responder {
    let device_id = query.device_id.clone();
    let package_name = query.package_name.clone();
    let timestamp = query.timestamp.clone();
    let width = query.width.unwrap_or(0);
    let height = query.height.unwrap_or(0);

    info!(
        "Received screenshot upload request from device: {}, package: {}, timestamp: {}, size: {}x{}",
        device_id, package_name, timestamp, width, height
    );

    // Create screenshots directory if it doesn't exist
    // Structure: recordings/{device_id}/screenshots/
    let screenshots_dir = format!("recordings/{}/screenshots", device_id);
    if let Err(e) = fs::create_dir_all(&screenshots_dir).await {
        error!("Failed to create screenshots directory: {}", e);
        return HttpResponse::InternalServerError().json(ApiResponse {
            success: false,
            message: format!("Failed to create screenshots directory: {}", e),
            data: None,
        });
    }

    let mut file_path = String::new();
    let mut file_size: u64 = 0;

    // Process multipart upload
    while let Some(field) = payload.next().await {
        match field {
            Ok(mut field) => {
                let content_disposition = field.content_disposition();
                let field_name = content_disposition.get_name().unwrap_or("unknown");

                if field_name == "screenshot" {
                    // Generate filename with metadata
                    let filename = format!("screenshot_{}_{}_{}.png", package_name, timestamp, uuid::Uuid::new_v4());
                    file_path = format!("{}/{}", screenshots_dir, filename);

                    // Create file and write content
                    match fs::File::create(&file_path).await {
                        Ok(mut file) => {
                            let mut total_bytes = 0u64;

                            // Read file data in chunks
                            while let Some(chunk_result) = field.next().await {
                                match chunk_result {
                                    Ok(chunk) => {
                                        let chunk_bytes = chunk.len();
                                        if let Err(e) = file.write_all(&chunk).await {
                                            error!("Failed to write chunk: {}", e);
                                            return HttpResponse::InternalServerError().json(ApiResponse {
                                                success: false,
                                                message: format!("Failed to write file: {}", e),
                                                data: None,
                                            });
                                        }
                                        total_bytes += chunk_bytes as u64;
                                    }
                                    Err(e) => {
                                        error!("Failed to read chunk: {}", e);
                                        return HttpResponse::InternalServerError().json(ApiResponse {
                                            success: false,
                                            message: format!("Failed to read file chunk: {}", e),
                                            data: None,
                                        });
                                    }
                                }
                            }

                            file_size = total_bytes;
                            info!("Saved screenshot to {}, size: {} bytes", file_path, file_size);
                        }
                        Err(e) => {
                            error!("Failed to create file: {}", e);
                            return HttpResponse::InternalServerError().json(ApiResponse {
                                success: false,
                                message: format!("Failed to create file: {}", e),
                                data: None,
                            });
                        }
                    }
                } else {
                    // Skip other fields
                    while let Some(_chunk) = field.next().await {}
                }
            }
            Err(e) => {
                error!("Multipart error: {}", e);
                return HttpResponse::BadRequest().json(ApiResponse {
                    success: false,
                    message: format!("Multipart error: {}", e),
                    data: None,
                });
            }
        }
    }

    if file_path.is_empty() {
        return HttpResponse::BadRequest().json(ApiResponse {
            success: false,
            message: "No screenshot file found in upload".to_string(),
            data: None,
        });
    }

    // Save screenshot metadata to JSON file
    let metadata_path = file_path.replace(".png", ".metadata.json");
    let metadata_json = json!({
        "device_id": device_id,
        "package_name": package_name,
        "timestamp": timestamp,
        "width": width,
        "height": height,
        "file_path": file_path,
        "file_size": file_size,
        "uploaded_at": chrono::Utc::now().to_rfc3339()
    });

    if let Err(e) = fs::write(&metadata_path, metadata_json.to_string()).await {
        error!("Failed to write metadata file: {}", e);
    }

    HttpResponse::Ok().json(ApiResponse {
        success: true,
        message: "Screenshot uploaded successfully".to_string(),
        data: Some(json!({
            "file_path": file_path,
            "file_size": file_size,
            "device_id": device_id,
            "package_name": package_name,
            "timestamp": timestamp,
            "width": width,
            "height": height
        })),
    })
}

pub async fn list_screenshots(
    path: web::Path<String>,
    app_state: web::Data<crate::AppState>,
) -> impl Responder {
    let device_id = path.into_inner();
    let screenshots_dir = format!("recordings/{}/screenshots", device_id);

    match fs::read_dir(&screenshots_dir).await {
        Ok(mut entries) => {
            let mut screenshots = Vec::new();

            while let Some(entry) = entries.next_entry().await.unwrap_or(None) {
                if let Ok(metadata) = entry.metadata().await {
                    if metadata.is_file() && entry.file_name().to_string_lossy().ends_with(".png") {
                        // Try to read metadata file
                        let metadata_path = format!("{}/{}", screenshots_dir,
                            entry.file_name().to_string_lossy().replace(".png", ".metadata.json"));

                        let screenshot_info = if fs::metadata(&metadata_path).await.is_ok() {
                            // Read metadata file
                            match fs::read_to_string(&metadata_path).await {
                                Ok(metadata_content) => {
                                    match serde_json::from_str::<serde_json::Value>(&metadata_content) {
                                        Ok(metadata_json) => json!({
                                            "filename": entry.file_name(),
                                            "metadata": metadata_json
                                        }),
                                        Err(_) => json!({
                                            "filename": entry.file_name(),
                                            "size": metadata.len(),
                                            "created": metadata.created().ok().map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap().as_secs()).unwrap_or(0)
                                        })
                                    }
                                }
                                Err(_) => json!({
                                    "filename": entry.file_name(),
                                    "size": metadata.len(),
                                    "created": metadata.created().ok().map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap().as_secs()).unwrap_or(0)
                                })
                            }
                        } else {
                            json!({
                                "filename": entry.file_name(),
                                "size": metadata.len(),
                                "created": metadata.created().ok().map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap().as_secs()).unwrap_or(0)
                            })
                        };

                        screenshots.push(screenshot_info);
                    }
                }
            }

            // Sort by timestamp (from metadata) or file created time
            screenshots.sort_by(|a, b| {
                let a_time = a["metadata"]
                    .as_object()
                    .and_then(|m| m.get("timestamp"))
                    .and_then(|t| t.as_str())
                    .and_then(|t| t.parse::<i64>().ok())
                    .unwrap_or(0);

                let b_time = b["metadata"]
                    .as_object()
                    .and_then(|m| m.get("timestamp"))
                    .and_then(|t| t.as_str())
                    .and_then(|t| t.parse::<i64>().ok())
                    .unwrap_or(0);

                b_time.cmp(&a_time)
            });

            HttpResponse::Ok().json(ApiResponse {
                success: true,
                message: format!("Found {} screenshot(s)", screenshots.len()),
                data: Some(json!({
                    "device_id": device_id,
                    "screenshots": screenshots,
                    "count": screenshots.len()
                })),
            })
        }
        Err(e) => {
            HttpResponse::NotFound().json(ApiResponse {
                success: false,
                message: format!("No screenshots found for device: {}", e),
                data: None,
            })
        }
    }
}

pub async fn get_screenshot_stats(
    path: web::Path<String>,
) -> impl Responder {
    let device_id = path.into_inner();
    let screenshots_dir = format!("recordings/{}/screenshots", device_id);

    match fs::read_dir(&screenshots_dir).await {
        Ok(mut entries) => {
            let mut total_count = 0;
            let mut total_size = 0u64;
            let mut by_package = std::collections::HashMap::new();
            let mut recent_screenshots = Vec::new();

            while let Some(entry) = entries.next_entry().await.unwrap_or(None) {
                if let Ok(metadata) = entry.metadata().await {
                    if metadata.is_file() && entry.file_name().to_string_lossy().ends_with(".png") {
                        total_count += 1;
                        total_size += metadata.len();

                        // Try to read metadata
                        let metadata_path = format!("{}/{}", screenshots_dir,
                            entry.file_name().to_string_lossy().replace(".png", ".metadata.json"));

                        if let Ok(metadata_content) = fs::read_to_string(&metadata_path).await {
                            if let Ok(metadata_json) = serde_json::from_str::<serde_json::Value>(&metadata_content) {
                                let package_name = metadata_json["package_name"].as_str().unwrap_or("unknown");
                                *by_package.entry(package_name.to_string()).or_insert(0) += 1;

                                // Add to recent screenshots
                                if recent_screenshots.len() < 20 {
                                    recent_screenshots.push(metadata_json);
                                }
                            }
                        }
                    }
                }
            }

            HttpResponse::Ok().json(ApiResponse {
                success: true,
                message: format!("Found {} total screenshot(s)", total_count),
                data: Some(json!({
                    "device_id": device_id,
                    "total_count": total_count,
                    "total_size": total_size,
                    "by_package": by_package,
                    "recent_screenshots": recent_screenshots
                })),
            })
        }
        Err(e) => {
            HttpResponse::NotFound().json(ApiResponse {
                success: false,
                message: format!("No screenshots found for device: {}", e),
                data: None,
            })
        }
    }
}
