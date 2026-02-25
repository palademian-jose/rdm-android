use actix_web::{web, App, HttpResponse, HttpServer, Responder};
use actix_ws::Message;
use actix_cors::Cors;
use actix_files::Files;
use tracing::{info, error};
use std::sync::Arc;
use futures_util::stream::StreamExt;
use std::env;
use tokio::sync::mpsc;
use std::collections::HashMap;

mod auth;
mod websocket;
mod database;
mod devices;

#[derive(Clone)]
pub struct WsSender {
    tx: mpsc::UnboundedSender<String>,
}

#[derive(Clone)]
struct AppState {
    devices: Arc<std::sync::RwLock<Vec<devices::Device>>>,
    db: Arc<database::Database>,
    ws_senders: Arc<std::sync::RwLock<HashMap<String, WsSender>>>,
}

impl AppState {
    pub fn add_ws_sender(&self, device_id: String, sender: WsSender) {
        self.ws_senders.write().unwrap().insert(device_id, sender);
    }

    pub fn remove_ws_sender(&self, device_id: &str) {
        self.ws_senders.write().unwrap().remove(device_id);
    }

    pub async fn send_command_to_device(&self, device_id: &str, command: &str, command_id: &str, sudo: bool) -> bool {
        let senders = self.ws_senders.read().unwrap().clone();
        if let Some(sender) = senders.get(device_id) {
            let message = serde_json::json!({
                "type": "command",
                "id": command_id,
                "command": command,
                "sudo": sudo
            });
            sender.tx.send(message.to_string()).is_ok()
        } else {
            false
        }
    }
}

async fn health() -> impl Responder {
    HttpResponse::Ok().json(serde_json::json!({
        "status": "ok",
        "service": "rdm-server",
        "version": "0.1.0"
    }))
}

async fn ws_device(
    device_id: web::Path<String>,
    app_state: web::Data<AppState>,
    req: actix_web::HttpRequest,
    body: web::Payload,
) -> Result<HttpResponse, actix_web::Error> {
    let device_id = device_id.into_inner();
    info!("WebSocket connection request for device: {}", device_id);

    let (response, mut session, mut msg_stream) = actix_ws::handle(&req, body)?;

    let app_state_clone = app_state.get_ref().clone();

    // Create channel for sending messages to this device
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();

    // Register the sender
    app_state.add_ws_sender(device_id.clone(), WsSender { tx });

    let device_id_cleanup = device_id.clone();
    let app_state_cleanup = app_state_clone.clone();

    // Spawn a task to handle the WebSocket connection
    actix_web::rt::spawn(async move {
        let mut authenticated = false;
        let device_id = device_id_cleanup.clone();

        loop {
            tokio::select! {
                // Handle incoming WebSocket messages
                msg_result = msg_stream.next() => {
                    match msg_result {
                        Some(Ok(msg)) => {
                            match msg {
                                Message::Text(text) => {
                                    if let Ok(ws_msg) = serde_json::from_str::<websocket::WsMessage>(&text) {
                                        match ws_msg {
                                            websocket::WsMessage::Auth { token } => {
                                                info!("Auth received from device: {}", device_id);
                                                authenticated = true;
                                            }
                                            websocket::WsMessage::DeviceInfo { device_id: d_id, info } => {
                                                info!("Device info received from: {}", d_id);

                                                if !authenticated {
                                                    error!("Device {} sending info without auth", d_id);
                                                    continue;
                                                }

                                                if let Ok(device_obj) = serde_json::from_value::<serde_json::Value>(info.clone()) {
                                                    let name = device_obj.get("name")
                                                        .and_then(|v| v.as_str())
                                                        .unwrap_or("Unknown")
                                                        .to_string();
                                                    let model = device_obj.get("model")
                                                        .and_then(|v| v.as_str())
                                                        .unwrap_or("Unknown")
                                                        .to_string();
                                                    let android_version = device_obj.get("android_version")
                                                        .and_then(|v| v.as_str())
                                                        .unwrap_or("Unknown")
                                                        .to_string();

                                                    let device = devices::Device {
                                                        id: d_id.clone(),
                                                        name: name.clone(),
                                                        model: model.clone(),
                                                        platform: android_version.clone(),
                                                        status: "online".to_string(),
                                                        last_seen: chrono::Utc::now().to_rfc3339(),
                                                        latitude: None,
                                                        longitude: None,
                                                        battery_level: None,
                                                        storage_free: None,
                                                        ram_free: None,
                                                    };

                                                    if let Ok(mut devices) = app_state_clone.devices.write() {
                                                        devices.retain(|d| d.id != d_id);
                                                        devices.push(device.clone());
                                                        info!("Device {} stored in state (total: {})", d_id, devices.len());
                                                    }

                                                    let db_device = database::Device {
                                                        id: d_id.clone(),
                                                        name: name.clone(),
                                                        model: model.clone(),
                                                        android_version: android_version.clone(),
                                                        api_level: 30,
                                                        architecture: "aarch64".to_string(),
                                                        device_info: info.to_string(),
                                                        user_data: "{}".to_string(),
                                                        last_seen: chrono::Utc::now().to_rfc3339(),
                                                        created_at: chrono::Utc::now().to_rfc3339(),
                                                        latitude: None,
                                                        longitude: None,
                                                        battery_level: None,
                                                        storage_free: None,
                                                        ram_free: None,
                                                    };

                                                    if let Err(e) = app_state_clone.db.save_device(&db_device).await {
                                                        error!("Failed to save device to DB: {:?}", e);
                                                    }
                                                }
                                            }
                                            websocket::WsMessage::Command { id, .. } => {
                                                info!("Unexpected command message from device {}: {}", device_id, id);
                                            }
                                            websocket::WsMessage::CommandResult { id, success, output, error } => {
                                                info!("Command result from {} - ID: {}, success: {}", device_id, id, success);
                                                let _ = app_state_clone.db.update_command(&id, Some(output), error, if success { "completed" } else { "failed" }).await;
                                            }
                                            websocket::WsMessage::Heartbeat { device_id: d_id, .. } => {
                                                info!("Heartbeat received from: {}", d_id);
                                                if let Ok(mut devices) = app_state_clone.devices.write() {
                                                    if let Some(device) = devices.iter_mut().find(|d| d.id == d_id) {
                                                        device.status = "online".to_string();
                                                        device.last_seen = chrono::Utc::now().to_rfc3339();
                                                    }
                                                }
                                            }
                                            websocket::WsMessage::Log { device_id: d_id, level, message, data } => {
                                                info!("Log received from {}: {} - {}", d_id, level, message);
                                                let log_entry = database::LogEntry {
                                                    id: uuid::Uuid::new_v4().to_string(),
                                                    device_id: d_id.clone(),
                                                    level: level.clone(),
                                                    message: message.clone(),
                                                    data,
                                                    timestamp: chrono::Utc::now().to_rfc3339(),
                                                };
                                                let _ = app_state_clone.db.save_log(&log_entry).await;
                                            }
                                            websocket::WsMessage::Error { .. } => {
                                                error!("Error message received from device: {}", device_id);
                                            }
                                        }
                                    }
                                }
                                Message::Close(reason) => {
                                    info!("WebSocket closed for device: {}, reason: {:?}", device_id, reason);
                                    break;
                                }
                                Message::Ping(bytes) => {
                                    let _ = session.pong(&bytes).await;
                                }
                                Message::Pong(_) => {}
                                Message::Continuation(_) => {}
                                Message::Binary(_) => {}
                                Message::Nop => {}
                            }
                        }
                        Some(Err(e)) => {
                            error!("WebSocket error for device {}: {:?}", device_id, e);
                            break;
                        }
                        None => {
                            info!("WebSocket stream ended for device: {}", device_id);
                            break;
                        }
                    }
                }
                // Handle outgoing messages (commands to send to device)
                Some(msg) = rx.recv() => {
                    if let Err(e) = session.text(msg).await {
                        error!("Failed to send message to device {}: {:?}", device_id, e);
                        break;
                    }
                }
            }
        }

        // Cleanup
        if let Ok(mut devices) = app_state_cleanup.devices.write() {
            if let Some(device) = devices.iter_mut().find(|d| d.id == device_id_cleanup) {
                device.status = "offline".to_string();
            }
        }
        app_state_cleanup.remove_ws_sender(&device_id_cleanup);
    });

    Ok(response)
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    dotenv::dotenv().ok();

    let host = env::var("RDM_HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
    let port = env::var("RDM_PORT")
        .unwrap_or_else(|_| "8443".to_string())
        .parse::<u16>()
        .unwrap_or(8443);

    let db_path = env::var("DATABASE_URL").unwrap_or_else(|_| "database/rdm.db".to_string());

    if let Some(parent) = std::path::Path::new(&db_path).parent() {
        std::fs::create_dir_all(parent).ok();
    }

    info!("🚀 RDM Server starting on {}:{}", host, port);

    // Initialize database
    let db = database::Database::new(&db_path).await;

    // Try to migrate, but don't fail if it doesn't work
    let _ = db.migrate().await;

    let app_state = AppState {
        devices: Arc::new(std::sync::RwLock::new(Vec::new())),
        db: Arc::new(db),
        ws_senders: Arc::new(std::sync::RwLock::new(HashMap::new())),
    };

    let bind_addr = format!("{}:{}", host, port);

    HttpServer::new(move || {
        let files = Files::new("/web", "./web")
            .index_file("dashboard.html");

        App::new()
            .wrap(Cors::permissive())
            .app_data(web::Data::new(app_state.clone()))
            .route("/health", web::get().to(health))
            .route("/api/auth/login", web::post().to(auth::login))
            .route("/api/devices", web::get().to(devices::get_all_devices))
            .route("/api/devices/{device_id}", web::get().to(devices::get_device_by_id))
            .route("/api/devices/{device_id}/logs", web::get().to(devices::get_device_logs))
            .route("/api/devices/{device_id}/commands", web::post().to(devices::send_command))
            .route("/ws/device/{device_id}", web::get().to(ws_device))
            .service(files)
    })
    .bind(&bind_addr)?
    .run()
    .await
}
