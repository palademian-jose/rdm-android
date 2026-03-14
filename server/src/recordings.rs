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
pub struct UploadQuery {
    pub device_id: String,
    pub package_name: String,
    pub timestamp: String,
}

pub async fn upload_recording(
    query: web::Query<UploadQuery>,
    mut payload: Multipart,
    _app_state: web::Data<crate::AppState>,
) -> impl Responder {
    let device_id = query.device_id.clone();
    let package_name = query.package_name.clone();
    let timestamp = query.timestamp.clone();

    info!(
        "Received recording upload request from device: {}, package: {}, timestamp: {}",
        device_id, package_name, timestamp
    );

    // Create recordings directory if it doesn't exist
    // Structure: recordings/{device_id}/recordings/
    let recordings_dir = format!("recordings/{}/recordings", device_id);
    if let Err(e) = fs::create_dir_all(&recordings_dir).await {
        error!("Failed to create recordings directory: {}", e);
        return HttpResponse::InternalServerError().json(ApiResponse {
            success: false,
            message: format!("Failed to create recordings directory: {}", e),
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

                if field_name == "video" {
                    // Generate filename
                    let filename = format!("recording_{}_{}_{}.mp4", package_name, timestamp, uuid::Uuid::new_v4());
                    file_path = format!("{}/{}", recordings_dir, filename);

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
                            info!("Saved recording to {}, size: {} bytes", file_path, file_size);
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
            message: "No video file found in upload".to_string(),
            data: None,
        });
    }

    // Save recording metadata to database (optional, for future reference)
    // TODO: Add recordings table to database schema

    HttpResponse::Ok().json(ApiResponse {
        success: true,
        message: "Recording uploaded successfully".to_string(),
        data: Some(json!({
            "file_path": file_path,
            "file_size": file_size,
            "device_id": device_id,
            "package_name": package_name,
            "timestamp": timestamp
        })),
    })
}

pub async fn list_recordings(
    path: web::Path<String>,
    _app_state: web::Data<crate::AppState>,
) -> impl Responder {
    let device_id = path.into_inner();
    let recordings_dir = format!("recordings/{}/recordings", device_id);

    match fs::read_dir(&recordings_dir).await {
        Ok(mut entries) => {
            let mut recordings = Vec::new();

            while let Some(entry) = entries.next_entry().await.unwrap_or(None) {
                if let Ok(metadata) = entry.metadata().await {
                    if metadata.is_file() {
                        recordings.push(json!({
                            "filename": entry.file_name().to_string_lossy().to_string(),
                            "size": metadata.len(),
                            "created": metadata.created().ok().map(|t| t.duration_since(std::time::UNIX_EPOCH).unwrap().as_secs()).unwrap_or(0)
                        }));
                    }
                }
            }

            HttpResponse::Ok().json(ApiResponse {
                success: true,
                message: format!("Found {} recording(s)", recordings.len()),
                data: Some(json!({
                    "device_id": device_id,
                    "recordings": recordings
                })),
            })
        }
        Err(e) => {
            HttpResponse::NotFound().json(ApiResponse {
                success: false,
                message: format!("No recordings found for device: {}", e),
                data: None,
            })
        }
    }
}
