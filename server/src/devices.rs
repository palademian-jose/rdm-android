use actix_web::{web, HttpResponse, Responder, get, post};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub model: String,
    pub platform: String,
    pub status: String,
    pub last_seen: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub foreground_app: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse {
    pub success: bool,
    pub message: String,
    pub data: Option<Value>,
}

#[derive(Debug, Deserialize)]
pub struct CommandRequest {
    pub command: String,
    pub sudo: Option<bool>,
}

pub async fn get_all_devices(app_state: web::Data<crate::AppState>) -> impl Responder {
    let devices = app_state.devices.read().unwrap().clone();

    // Also try to load from database for persistence
    if let Ok(db_devices) = app_state.db.get_all_devices().await {
        // Merge in-memory and database devices
        let mut all_devices = devices;
        for db_device in db_devices {
            if !all_devices.iter().any(|d| d.id == db_device.id) {
                all_devices.push(Device {
                    id: db_device.id,
                    name: db_device.name,
                    model: db_device.model,
                    platform: db_device.android_version,
                    status: "offline".to_string(),
                    last_seen: db_device.last_seen,
                    foreground_app: None,
                });
            }
        }
        return HttpResponse::Ok().json(ApiResponse {
            success: true,
            message: format!("Found {} device(s)", all_devices.len()),
            data: Some(json!({
                "devices": all_devices
            })),
        });
    }

    let message = if devices.is_empty() {
        "No devices connected yet".to_string()
    } else {
        format!("Found {} device(s)", devices.len())
    };

    HttpResponse::Ok().json(ApiResponse {
        success: true,
        message,
        data: Some(json!({
            "devices": devices
        })),
    })
}

pub async fn get_device_by_id(
    path: web::Path<String>,
    app_state: web::Data<crate::AppState>
) -> impl Responder {
    let device_id = path.into_inner();
    let devices = app_state.devices.read().unwrap();

    if let Some(device) = devices.iter().find(|d| d.id == device_id) {
        HttpResponse::Ok().json(ApiResponse {
            success: true,
            message: "Device found".to_string(),
            data: Some(serde_json::to_value(device).unwrap()),
        })
    } else {
        HttpResponse::NotFound().json(ApiResponse {
            success: false,
            message: "Device not found".to_string(),
            data: None,
        })
    }
}

pub async fn get_device_logs(
    path: web::Path<String>,
    query: web::Query<std::collections::HashMap<String, String>>,
    app_state: web::Data<crate::AppState>
) -> impl Responder {
    let device_id = path.into_inner();
    let limit = query.get("limit").and_then(|l| l.parse().ok()).unwrap_or(100);

    if let Ok(logs) = app_state.db.get_logs(Some(&device_id), Some(limit), None).await {
        HttpResponse::Ok().json(ApiResponse {
            success: true,
            message: format!("Found {} log(s)", logs.len()),
            data: Some(json!({
                "logs": logs
            })),
        })
    } else {
        HttpResponse::InternalServerError().json(ApiResponse {
            success: false,
            message: "Failed to fetch logs".to_string(),
            data: None,
        })
    }
}

pub async fn send_command(
    path: web::Path<String>,
    cmd_req: web::Json<CommandRequest>,
    app_state: web::Data<crate::AppState>
) -> impl Responder {
    let device_id = path.into_inner();
    let sudo = cmd_req.sudo.unwrap_or(false);

    // Check if device is online
    let devices = app_state.devices.read().unwrap();
    let device_online = devices.iter().any(|d| d.id == device_id && d.status == "online");
    drop(devices);

    if !device_online {
        return HttpResponse::BadRequest().json(ApiResponse {
            success: false,
            message: "Device is not online".to_string(),
            data: None,
        });
    }

    // Save command to database
    let command_id = match app_state.db.save_command(&device_id, &cmd_req.command, sudo).await {
        Ok(id) => id,
        Err(_) => {
            return HttpResponse::InternalServerError().json(ApiResponse {
                success: false,
                message: "Failed to save command".to_string(),
                data: None,
            });
        }
    };

    // Send command to device via WebSocket
    let sent = app_state.send_command_to_device(&device_id, &cmd_req.command, &command_id, sudo).await;

    if !sent {
        // Device connected but failed to send
        return HttpResponse::InternalServerError().json(ApiResponse {
            success: false,
            message: "Failed to send command to device".to_string(),
            data: None,
        });
    }

    HttpResponse::Ok().json(ApiResponse {
        success: true,
        message: "Command sent".to_string(),
        data: Some(json!({
            "command_id": command_id,
            "device_id": device_id,
            "command": cmd_req.command,
            "sudo": sudo
        })),
    })
}
