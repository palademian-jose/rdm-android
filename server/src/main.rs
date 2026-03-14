use actix_cors::Cors;
use actix_files::Files;
use actix_web::{web, App, HttpResponse, HttpServer, Responder};
use actix_ws::Message;
use futures_util::StreamExt;
use std::collections::HashMap;
use std::env;
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::{error, info, warn};

mod auth;
mod anomalies;
mod database;
mod devices;
mod recordings;
mod screenshots;
mod unified_events;
mod websocket;

#[derive(Clone)]
pub struct WsSender {
    tx: mpsc::UnboundedSender<String>,
}

#[derive(Clone)]
struct AppState {
    devices: Arc<std::sync::RwLock<Vec<devices::Device>>>,
    db: Arc<database::Database>,
    ws_senders: Arc<std::sync::RwLock<HashMap<String, WsSender>>>,
    client_senders: Arc<std::sync::RwLock<HashMap<String, WsSender>>>, // client_id -> WsSender
}

impl AppState {
    pub fn add_ws_sender(&self, device_id: String, sender: WsSender) {
        self.ws_senders.write().unwrap().insert(device_id, sender);
    }

    pub fn remove_ws_sender(&self, device_id: &str) {
        self.ws_senders.write().unwrap().remove(device_id);
    }

    pub fn add_client_sender(&self, client_id: String, sender: WsSender) {
        self.client_senders
            .write()
            .unwrap()
            .insert(client_id, sender);
    }

    pub fn remove_client_sender(&self, client_id: &str) {
        self.client_senders.write().unwrap().remove(client_id);
    }

    pub async fn send_command_to_device(
        &self,
        device_id: &str,
        command: &str,
        command_id: &str,
        sudo: bool,
    ) -> bool {
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

    pub async fn broadcast_to_clients(&self, message: &str) {
        let senders = self.client_senders.read().unwrap().clone();
        for (client_id, sender) in senders.iter() {
            if let Err(e) = sender.tx.send(message.to_string()) {
                error!("Failed to send message to client {}: {:?}", client_id, e);
            }
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
                                            websocket::WsMessage::Auth { token: _ } => {
                                                info!("Auth received from device: {}", device_id);
                                                authenticated = true;
                                            }
                                            websocket::WsMessage::DeviceInfo { device_id: d_id, info } => {
                                                info!("Device info received from: {}", d_id);

                                                // if !authenticated {
                                                //     error!("Device {} sending info without auth", d_id);
                                                //     continue;
                                                // }

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
                                                        foreground_app: None,
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
                                                let _ = app_state_clone.db.update_command(&id, Some(output.clone()), error.clone(), if success { "completed" } else { "failed" }).await;

                                                // Broadcast command result to all connected clients
                                                let broadcast_message = serde_json::json!({
                                                    "type": "command_result",
                                                    "command_id": id,
                                                    "device_id": device_id,
                                                    "success": success,
                                                    "output": output,
                                                    "error": error,
                                                    "timestamp": chrono::Utc::now().to_rfc3339()
                                                }).to_string();

                                                app_state_clone.broadcast_to_clients(&broadcast_message).await;
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
                                            websocket::WsMessage::ForegroundApp { device_id: d_id, data } => {
                                                if let Ok(mut devices) = app_state_clone.devices.write() {
                                                    if let Some(device) = devices.iter_mut().find(|d| d.id == d_id) {
                                                        let package_name = data.get("package_name")
                                                            .and_then(|v| v.as_str())
                                                            .unwrap_or("Unknown")
                                                            .to_string();

                                                        device.foreground_app = Some(package_name.clone());
                                                        info!("Foreground app updated for {}: {}", d_id, package_name);
                                                    }
                                                }
                                            }
                                            websocket::WsMessage::Anomaly { device_id: d_id, data } => {
                                                info!("Anomaly received from {}: {:?}", d_id, data);

                                                // Parse anomaly data
                                                if let Ok(anomaly_data) = serde_json::from_value::<anomalies::AnomalyData>(data.clone()) {
                                                    // Save anomaly log to file system
                                                    match anomalies::save_anomaly_log(&d_id, &anomaly_data) {
                                                        Ok(log_file) => {
                                                            info!("Anomaly log saved to: {}", log_file);

                                                            // Broadcast anomaly to all connected clients
                                                            let broadcast_message = serde_json::json!({
                                                                "type": "anomaly",
                                                                "device_id": d_id,
                                                                "data": data,
                                                                "timestamp": chrono::Utc::now().to_rfc3339()
                                                            }).to_string();

                                                            app_state_clone.broadcast_to_clients(&broadcast_message).await;
                                                        }
                                                        Err(e) => {
                                                            error!("Failed to save anomaly log: {}", e);
                                                        }
                                                    }
                                                } else {
                                                    error!("Failed to parse anomaly data");
                                                }
                                            }
                                            websocket::WsMessage::UnifiedEvent { device_id: d_id, data } => {
                                                info!("Unified event received from {}: {:?}", d_id, data);

                                                // Parse unified event data
                                                if let Ok(unified_event) = serde_json::from_value::<unified_events::UnifiedEvent>(data.clone()) {
                                                    // Save unified event to file system
                                                    match unified_events::save_unified_event(&d_id, &unified_event) {
                                                        Ok(log_file) => {
                                                            info!("Unified event saved to: {}", log_file);

                                                            // Broadcast unified event to all connected clients
                                                            let broadcast_message = serde_json::json!({
                                                                "type": "unified_event",
                                                                "device_id": d_id,
                                                                "data": data,
                                                                "timestamp": chrono::Utc::now().to_rfc3339()
                                                            }).to_string();

                                                            app_state_clone.broadcast_to_clients(&broadcast_message).await;
                                                        }
                                                        Err(e) => {
                                                            error!("Failed to save unified event: {}", e);
                                                        }
                                                    }
                                                } else {
                                                    error!("Failed to parse unified event data");
                                                }
                                            }
                                            websocket::WsMessage::UnifiedEventsBatch { device_id: d_id, data } => {
                                                info!("Unified events batch received from {}: {:?}", d_id, data);

                                                // Broadcast batch to all connected clients
                                                let broadcast_message = serde_json::json!({
                                                    "type": "unified_events_batch",
                                                    "device_id": d_id,
                                                    "data": data,
                                                    "timestamp": chrono::Utc::now().to_rfc3339()
                                                }).to_string();

                                                app_state_clone.broadcast_to_clients(&broadcast_message).await;
                                            }
                                            websocket::WsMessage::RecordingEvent { device_id: d_id, data } => {
                                                info!("Recording event received from {}: {:?}", d_id, data);

                                                // Broadcast recording event to all connected clients
                                                let broadcast_message = serde_json::json!({
                                                    "type": "recording_event",
                                                    "device_id": d_id,
                                                    "data": data,
                                                    "timestamp": chrono::Utc::now().to_rfc3339()
                                                }).to_string();

                                                app_state_clone.broadcast_to_clients(&broadcast_message).await;
                                            }
                                            websocket::WsMessage::Screenshot { device_id: d_id, data } => {
                                                info!("Screenshot event received from {}: {:?}", d_id, data);

                                                // Broadcast screenshot event to all connected clients
                                                let broadcast_message = serde_json::json!({
                                                    "type": "screenshot",
                                                    "device_id": d_id,
                                                    "data": data,
                                                    "timestamp": chrono::Utc::now().to_rfc3339()
                                                }).to_string();

                                                app_state_clone.broadcast_to_clients(&broadcast_message).await;
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
                            // Check if it's an overflow error
                            let error_msg = format!("{:?}", e);
                            if error_msg.contains("Overflow") || error_msg.contains("overflow") {
                                warn!("WebSocket buffer overflow for device {} - rate limiting detected", device_id);
                                // Don't break immediately, try to continue
                                continue;
                            } else {
                                error!("WebSocket error for device {}: {:?}", device_id, e);
                                break;
                            }
                        }
                        None => {
                            info!("WebSocket stream ended for device: {}", device_id);
                            break;
                        }
                    }
                }
                // Handle outgoing messages (commands to send to device)
                Some(msg) = rx.recv() => {
                    // Add small delay to prevent flooding
                    tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
                    if let Err(e) = session.text(msg).await {
                        let error_msg = format!("{:?}", e);
                        if error_msg.contains("Overflow") || error_msg.contains("overflow") {
                            warn!("Message buffer overflow for device {}, will retry", device_id);
                            // Don't break on overflow, just continue
                            continue;
                        } else {
                            error!("Failed to send message to device {}: {:?}", device_id, e);
                            break;
                        }
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

async fn ws_client(
    auth_token: web::Query<std::collections::HashMap<String, String>>,
    app_state: web::Data<AppState>,
    req: actix_web::HttpRequest,
    body: web::Payload,
) -> Result<HttpResponse, actix_web::Error> {
    info!("WebSocket connection request for client");

    let client_id = uuid::Uuid::new_v4().to_string();

    let (response, mut session, mut msg_stream) = actix_ws::handle(&req, body)?;

    let app_state_clone = app_state.get_ref().clone();
    let app_state_cleanup = app_state_clone.clone();

    // Create channel for sending messages to this client
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();

    // Register client sender
    app_state.add_client_sender(client_id.clone(), WsSender { tx });

    let client_id_cleanup = client_id.clone();

    // Verify authentication token if provided
    let is_authenticated = auth_token.get("token").is_some();

    if !is_authenticated {
        info!("Client connecting without authentication token (demo mode)");
    } else {
        info!("Client {} authenticated", client_id);
    }

    // Spawn a task to handle the WebSocket connection
    actix_web::rt::spawn(async move {
        info!("Client {} connected", client_id);

        loop {
            tokio::select! {
                // Handle incoming WebSocket messages from client
                msg_result = msg_stream.next() => {
                    match msg_result {
                        Some(Ok(msg)) => {
                            match msg {
                                Message::Text(text) => {
                                    if let Ok(ws_msg) = serde_json::from_str::<websocket::WsMessage>(&text) {
                                        match ws_msg {
                                            websocket::WsMessage::Auth { token: _ } => {
                                                info!("Auth received from client: {}", client_id);
                                            }
                                            websocket::WsMessage::Command { id: _, command, sudo: _ } => {
                                                // Client wants to send a command to a device
                                                // This will be handled via REST API, but we acknowledge receipt
                                                info!("Command request from client {}: {}", client_id, command);
                                            }
                                            _ => {}
                                        }
                                    }
                                }
                                Message::Close(reason) => {
                                    info!("WebSocket closed for client: {}, reason: {:?}", client_id, reason);
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
                            // Check if it's an overflow error
                            let error_msg = format!("{:?}", e);
                            if error_msg.contains("Overflow") || error_msg.contains("overflow") {
                                warn!("WebSocket buffer overflow for client {} - rate limiting detected", client_id);
                                // Don't break immediately, try to continue
                                continue;
                            } else {
                                error!("WebSocket error for client {}: {:?}", client_id, e);
                                break;
                            }
                        }
                        None => {
                            info!("WebSocket stream ended for client: {}", client_id);
                            break;
                        }
                    }
                }
                // Handle outgoing messages (command results, etc.) to send to client
                Some(msg) = rx.recv() => {
                    // Add small delay to prevent flooding
                    tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
                    if let Err(e) = session.text(msg).await {
                        let error_msg = format!("{:?}", e);
                        if error_msg.contains("Overflow") || error_msg.contains("overflow") {
                            warn!("Message buffer overflow for client {}, will retry", client_id);
                            // Don't break on overflow, just continue
                            continue;
                        } else {
                            error!("Failed to send message to client {}: {:?}", client_id, e);
                            break;
                        }
                    }
                }
            }
        }

        // Cleanup
        app_state_cleanup.remove_client_sender(&client_id_cleanup);
        info!("Client {} disconnected", client_id_cleanup);
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
        if let Err(e) = std::fs::create_dir_all(parent) {
            error!(
                "Failed to create database directory {}: {:?}",
                parent.display(),
                e
            );
        }
    }

    info!("🚀 RDM Server starting on {}:{}", host, port);

    // Initialize database
    let db = database::Database::new(&db_path).await;

    // Migrate database schema
    info!("Running database migrations...");
    match db.migrate().await {
        Ok(_) => info!("Database migrations completed successfully"),
        Err(e) => {
            error!("Database migration failed: {:?}", e);
            eprintln!("Database migration failed: {:?}", e);
        }
    }

    let app_state = AppState {
        devices: Arc::new(std::sync::RwLock::new(Vec::new())),
        db: Arc::new(db),
        ws_senders: Arc::new(std::sync::RwLock::new(HashMap::new())),
        client_senders: Arc::new(std::sync::RwLock::new(HashMap::new())),
    };

    let bind_addr = format!("{}:{}", host, port);

    HttpServer::new(move || {
        let files = Files::new("/web", "./web").index_file("dashboard.html");

        App::new()
            .wrap(Cors::permissive())
            .app_data(web::Data::new(app_state.clone()))
            .route("/health", web::get().to(health))
            .route("/api/auth/login", web::post().to(auth::login))
            .route("/api/devices", web::get().to(devices::get_all_devices))
            .route(
                "/api/devices/{device_id}",
                web::get().to(devices::get_device_by_id),
            )
            .route(
                "/api/devices/{device_id}/logs",
                web::get().to(devices::get_device_logs),
            )
            .route(
                "/api/devices/{device_id}/commands",
                web::post().to(devices::send_command),
            )
            .route(
                "/api/devices/{device_id}/commands",
                web::get().to(devices::get_device_commands),
            )
            .route(
                "/api/recordings/upload",
                web::post().to(recordings::upload_recording),
            )
            .route(
                "/api/recordings/{device_id}",
                web::get().to(recordings::list_recordings),
            )
            .route(
                "/api/screenshots/upload",
                web::post().to(screenshots::upload_screenshot),
            )
            .route(
                "/api/screenshots/{device_id}",
                web::get().to(screenshots::list_screenshots),
            )
            .route(
                "/api/screenshots/{device_id}/stats",
                web::get().to(screenshots::get_screenshot_stats),
            )
            .route(
                "/api/anomalies/{device_id}",
                web::get().to(anomalies::list_anomalies),
            )
            .route(
                "/api/anomalies/{device_id}/stats",
                web::get().to(anomalies::get_anomaly_stats),
            )
            .route(
                "/api/unified-events/{device_id}",
                web::post().to(unified_events::handle_unified_event),
            )
            .route(
                "/api/unified-events/{device_id}/batch",
                web::post().to(unified_events::handle_unified_events_batch),
            )
            .route(
                "/api/unified-events/{device_id}",
                web::get().to(unified_events::list_unified_events),
            )
            .route(
                "/api/unified-events/{device_id}/stats",
                web::get().to(unified_events::get_unified_event_stats),
            )
            .route("/ws/device/{device_id}", web::get().to(ws_device))
            .route("/ws/client", web::get().to(ws_client))
            .service(files)
    })
    .bind(&bind_addr)?
    .run()
    .await
}
