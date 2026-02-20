use actix_web::{web, HttpResponse, Responder};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use chrono::{DateTime, Utc};

// Mock data for devices
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub model: String,
    pub platform: String,
    pub status: String,
    pub last_seen: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse {
    pub success: bool,
    pub message: String,
    pub data: Option<serde_json::Value>,
}

pub async fn get_all_devices() -> impl Responder {
    let devices: Vec<Device> = vec![];
    HttpResponse::Ok().json(ApiResponse {
        success: true,
        message: "No devices connected yet".to_string(),
        data: Some(serde_json::json!({
            "devices": devices
        })),
    })
}

pub async fn get_device_by_id(path: web::Path<String>) -> impl Responder {
    let device_id = path.into_inner();

    let device = Device {
        id: device_id.clone(),
        name: "Test Device".to_string(),
        model: "Test Model".to_string(),
        platform: "Android 13".to_string(),
        status: "online".to_string(),
        last_seen: Utc::now().to_rfc3339(),
    };

    HttpResponse::Ok().json(ApiResponse {
        success: true,
        message: "Device found".to_string(),
        data: Some(serde_json::to_value(&device).unwrap()),
    })
}
