use anyhow::{anyhow, Result};
use futures_util::{SinkExt, StreamExt};
use reqwest::{Client, ClientBuilder, StatusCode};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};
use tokio_tungstenite::{
    connect_async, tungstenite::client::IntoClientRequest,
    tungstenite::protocol::Message as WsMessage,
};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Clone)]
pub struct ApiClient {
    client: Client,
    base_url: String,
    token: Option<String>,
    ws_url: Option<String>,
    ws_sender: Option<Arc<Mutex<UnboundedSender<WsMessage>>>>,
    pending_commands: Arc<Mutex<HashMap<String, PendingCommand>>>,
}

#[derive(Debug, Clone)]
struct PendingCommand {
    sender: UnboundedSender<String>,
    created_at: std::time::Instant,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub model: String,
    #[serde(rename = "platform", alias = "android_version")]
    pub platform: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub api_level: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub architecture: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_info: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_data: Option<String>,
    pub last_seen: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub created_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub foreground_app: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Command {
    pub id: String,
    pub device_id: String,
    pub command: String,
    pub sudo: bool,
    pub output: Option<String>,
    pub error: Option<String>,
    pub status: String,
    pub created_at: String,
    pub completed_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogEntry {
    pub id: String,
    pub device_id: String,
    pub level: String,
    pub message: String,
    pub data: Option<String>,
    pub timestamp: String,
}

#[derive(Debug, Serialize)]
struct AuthRequest {
    username: String,
    password: String,
}

#[derive(Debug, Deserialize)]
struct AuthResponse {
    token: String,
    device_id: String,
}

#[derive(Debug, Serialize)]
struct CommandRequest {
    device_id: String,
    command: String,
    sudo: bool,
}

impl ApiClient {
    pub fn new(base_url: &str) -> Result<Self> {
        let client = ClientBuilder::new()
            .timeout(Duration::from_secs(30))
            .danger_accept_invalid_certs(true) // For development - remove in production
            .build()?;

        // Extract WebSocket URL from base URL
        let ws_url = if base_url.starts_with("http://") {
            base_url.replacen("http://", "ws://", 1)
        } else if base_url.starts_with("https://") {
            base_url.replacen("https://", "wss://", 1)
        } else {
            format!("ws://{}", base_url)
        };

        Ok(Self {
            client,
            base_url: base_url.trim_end_matches('/').to_string(),
            token: None,
            ws_url: Some(ws_url),
            ws_sender: None,
            pending_commands: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    pub fn set_token(&mut self, token: &str) {
        self.token = Some(token.to_string());
    }

    pub fn set_ws_sender(&mut self, sender: Arc<Mutex<UnboundedSender<WsMessage>>>) {
        self.ws_sender = Some(sender);
    }

    fn auth_header(&self) -> Option<String> {
        self.token.as_ref().map(|t| format!("Bearer {}", t))
    }

    pub async fn connect_websocket(&mut self) -> Result<()> {
        let token = self.token.as_ref().ok_or_else(|| anyhow!("Not authenticated"))?;
        let ws_url = format!(
            "{}/ws/client?token={}",
            self.ws_url
                .as_ref()
                .ok_or_else(|| anyhow!("No WebSocket URL"))?,
            token
        );
        info!("Connecting to WebSocket: {}", ws_url);

        let request = ws_url.into_client_request()?;
        let (ws_stream, _) = connect_async(request).await?;

        let (mut ws_sender, mut ws_receiver) = ws_stream.split();

        // Create channel and store sender on this client
        let (tx, mut rx) = unbounded_channel::<WsMessage>();
        let sender_arc = Arc::new(Mutex::new(tx));
        self.ws_sender = Some(sender_arc.clone());

        let pending_commands = self.pending_commands.clone();

        // Task to send messages to WebSocket
        let ws_sender_task = async move {
            while let Some(msg) = rx.recv().await {
                debug!("Sending WebSocket message: {:?}", msg);
                if let Err(e) = ws_sender.send(msg).await {
                    error!("Failed to send WebSocket message: {:?}", e);
                    break;
                }
            }
        };

        // Task to receive messages from WebSocket
        let pending_commands_clone = pending_commands.clone();
        let ws_receiver_task = async move {
            while let Some(msg) = ws_receiver.next().await {
                match msg {
                    Ok(WsMessage::Text(text)) => {
                        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) {
                            debug!("Received WebSocket message: {}", json);

                            match json.get("type").and_then(|t| t.as_str()) {
                                Some("command_result") => {
                                    if let Some(command_id) =
                                        json.get("command_id").and_then(|id| id.as_str())
                                    {
                                        let success = json
                                            .get("success")
                                            .and_then(|s| s.as_bool())
                                            .unwrap_or(false);
                                        let output = json
                                            .get("output")
                                            .and_then(|o| o.as_str())
                                            .map(|s| s.to_string());

                                        // Send result to waiting command
                                        let mut pending = pending_commands_clone.lock().unwrap();
                                        if let Some(pending) = pending.remove(command_id) {
                                            let result = if success {
                                                output.unwrap_or_else(|| {
                                                    "Command completed with no output".to_string()
                                                })
                                            } else {
                                                let error = json
                                                    .get("error")
                                                    .and_then(|e| e.as_str())
                                                    .unwrap_or("Unknown error");
                                                format!("Error: {}", error)
                                            };

                                            let _ = pending.sender.send(result);
                                        }
                                    }
                                }
                                Some("heartbeat") => {
                                    if let Some(device_id) =
                                        json.get("device_id").and_then(|id| id.as_str())
                                    {
                                        info!("Heartbeat received from device: {}", device_id);
                                        // TODO: Update device status in UI state
                                    }
                                }
                                Some("foreground_app") => {
                                    if let (Some(device_id), Some(data)) = (
                                        json.get("device_id").and_then(|id| id.as_str()),
                                        json.get("data"),
                                    ) {
                                        let package_name = data
                                            .get("package_name")
                                            .and_then(|p| p.as_str())
                                            .unwrap_or("Unknown");
                                        info!(
                                            "Foreground app update for {}: {}",
                                            device_id, package_name
                                        );
                                        // TODO: Update foreground app in UI state
                                    }
                                }
                                Some("log") => {
                                    if let (Some(device_id), Some(level), Some(message)) = (
                                        json.get("device_id").and_then(|id| id.as_str()),
                                        json.get("level").and_then(|l| l.as_str()),
                                        json.get("message").and_then(|m| m.as_str()),
                                    ) {
                                        info!("Log from {}: [{}] {}", device_id, level, message);
                                        // TODO: Add log to UI logs view
                                    }
                                }
                                Some("error") => {
                                    if let (Some(code), Some(message)) = (
                                        json.get("code").and_then(|c| c.as_str()),
                                        json.get("message").and_then(|m| m.as_str()),
                                    ) {
                                        error!("WebSocket error: {} - {}", code, message);
                                    }
                                }
                                Some(other) => {
                                    debug!("Received unhandled WebSocket message type: {}", other);
                                }
                                None => {
                                    debug!("Received WebSocket message without type field");
                                }
                            }
                        }
                    }
                    Ok(WsMessage::Ping(data)) => {
                        debug!("Received ping");
                    }
                    Ok(WsMessage::Close(_)) => {
                        warn!("WebSocket closed");
                        break;
                    }
                    Ok(_) => {}
                    Err(e) => {
                        error!("WebSocket error: {:?}", e);
                        break;
                    }
                }
            }
        };

        // Run both tasks concurrently
        tokio::select! {
            _ = ws_sender_task => {},
            _ = ws_receiver_task => {},
        };

        Ok(())
    }

    pub async fn connect_websocket_with_channel(
        &mut self,
        mut rx: UnboundedReceiver<WsMessage>,
    ) -> Result<()> {
        let token = self.token.as_ref().ok_or_else(|| anyhow!("Not authenticated"))?;
        let ws_url = format!(
            "{}/ws/client?token={}",
            self.ws_url
                .as_ref()
                .ok_or_else(|| anyhow!("No WebSocket URL"))?,
            token
        );
        info!("Connecting to WebSocket: {}", ws_url);

        let request = ws_url.into_client_request()
            .map_err(|e| anyhow!("Failed to create WebSocket request: {:?}", e))?;

        info!("Attempting to connect_async...");
        let ws_result = connect_async(request).await;
        info!("connect_async result: {:?}", ws_result);

        let (ws_stream, _) = ws_result
            .map_err(|e| anyhow!("Failed to connect to WebSocket: {:?}", e))?;

        let (mut ws_sender, mut ws_receiver) = ws_stream.split();

        let pending_commands = self.pending_commands.clone();

        // Task to send messages to WebSocket
        let ws_sender_task = async move {
            while let Some(msg) = rx.recv().await {
                debug!("Sending WebSocket message: {:?}", msg);
                if let Err(e) = ws_sender.send(msg).await {
                    error!("Failed to send WebSocket message: {:?}", e);
                    break;
                }
            }
        };

        // Task to receive messages from WebSocket
        let pending_commands_clone = pending_commands.clone();
        let ws_receiver_task = async move {
            while let Some(msg) = ws_receiver.next().await {
                match msg {
                    Ok(WsMessage::Text(text)) => {
                        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) {
                            debug!("Received WebSocket message: {}", json);

                            match json.get("type").and_then(|t| t.as_str()) {
                                Some("command_result") => {
                                    if let Some(command_id) =
                                        json.get("command_id").and_then(|id| id.as_str())
                                    {
                                        let success = json
                                            .get("success")
                                            .and_then(|s| s.as_bool())
                                            .unwrap_or(false);
                                        let output = json
                                            .get("output")
                                            .and_then(|o| o.as_str())
                                            .map(|s| s.to_string());

                                        // Send result to waiting command
                                        let mut pending = pending_commands_clone.lock().unwrap();
                                        if let Some(pending) = pending.remove(command_id) {
                                            let result = if success {
                                                output.unwrap_or_else(|| {
                                                    "Command completed with no output".to_string()
                                                })
                                            } else {
                                                let error = json
                                                    .get("error")
                                                    .and_then(|e| e.as_str())
                                                    .unwrap_or("Unknown error");
                                                format!("Error: {}", error)
                                            };

                                            let _ = pending.sender.send(result);
                                        }
                                    }
                                }
                                Some("heartbeat") => {
                                    if let Some(device_id) =
                                        json.get("device_id").and_then(|id| id.as_str())
                                    {
                                        info!("Heartbeat received from device: {}", device_id);
                                        // TODO: Update device status in UI state
                                    }
                                }
                                Some("foreground_app") => {
                                    if let (Some(device_id), Some(data)) = (
                                        json.get("device_id").and_then(|id| id.as_str()),
                                        json.get("data"),
                                    ) {
                                        let package_name = data
                                            .get("package_name")
                                            .and_then(|p| p.as_str())
                                            .unwrap_or("Unknown");
                                        info!(
                                            "Foreground app update for {}: {}",
                                            device_id, package_name
                                        );
                                        // TODO: Update foreground app in UI state
                                    }
                                }
                                Some("log") => {
                                    if let (Some(device_id), Some(level), Some(message)) = (
                                        json.get("device_id").and_then(|id| id.as_str()),
                                        json.get("level").and_then(|l| l.as_str()),
                                        json.get("message").and_then(|m| m.as_str()),
                                    ) {
                                        info!("Log from {}: [{}] {}", device_id, level, message);
                                        // TODO: Add log to UI logs view
                                    }
                                }
                                Some("error") => {
                                    if let (Some(code), Some(message)) = (
                                        json.get("code").and_then(|c| c.as_str()),
                                        json.get("message").and_then(|m| m.as_str()),
                                    ) {
                                        error!("WebSocket error: {} - {}", code, message);
                                    }
                                }
                                Some(other) => {
                                    debug!("Received unhandled WebSocket message type: {}", other);
                                }
                                None => {
                                    debug!("Received WebSocket message without type field");
                                }
                            }
                        }
                    }
                    Ok(WsMessage::Ping(data)) => {
                        debug!("Received ping");
                    }
                    Ok(WsMessage::Close(_)) => {
                        warn!("WebSocket closed");
                        break;
                    }
                    Ok(_) => {}
                    Err(e) => {
                        error!("WebSocket error: {:?}", e);
                        break;
                    }
                }
            }
        };

        // Run both tasks concurrently
        tokio::select! {
            _ = ws_sender_task => {},
            _ = ws_receiver_task => {},
        };

        Ok(())
    }

    async fn get<T: for<'de> Deserialize<'de>>(&self, path: &str) -> Result<T> {
        let path = path.trim_start_matches('/');
        let url = format!("{}/{}", self.base_url, path);
        debug!("GET {}", url);

        let mut request = self.client.get(&url);

        if let Some(auth) = self.auth_header() {
            request = request.header("Authorization", auth);
        }

        let response = request.send().await?;

        if !response.status().is_success() {
            let status = response.status();
            let error_text = response.text().await.unwrap_or_default();
            return Err(anyhow!("GET {} failed: {} - {}", url, status, error_text));
        }

        let data = response.json::<T>().await?;
        Ok(data)
    }

    async fn post<T: for<'de> Deserialize<'de>, B: Serialize>(
        &self,
        path: &str,
        body: &B,
    ) -> Result<T> {
        let path = path.trim_start_matches('/');
        let url = format!("{}/{}", self.base_url, path);
        debug!("POST {}", url);

        let mut request = self.client.post(&url).json(body);

        if let Some(auth) = self.auth_header() {
            request = request.header("Authorization", auth);
        }

        let response = request.send().await?;

        if !response.status().is_success() {
            let status = response.status();
            let error_text = response.text().await.unwrap_or_default();
            return Err(anyhow!("POST {} failed: {} - {}", url, status, error_text));
        }

        let data = response.json::<T>().await?;
        Ok(data)
    }

    pub async fn authenticate(&self, username: &str, password: &str) -> Result<String> {
        let auth_request = AuthRequest {
            username: username.to_string(),
            password: password.to_string(),
        };

        let response: AuthResponse = self.post("/api/auth/login", &auth_request).await?;
        info!("Authenticated for device: {}", response.device_id);
        Ok(response.token)
    }

    pub async fn get_devices(&self) -> Result<Vec<Device>> {
        #[derive(Deserialize)]
        struct DevicesResponse {
            data: DevicesData,
        }

        #[derive(Deserialize)]
        struct DevicesData {
            devices: Vec<Device>,
        }

        let response: DevicesResponse = self.get("/api/devices").await?;
        Ok(response.data.devices)
    }

    pub async fn get_device(&self, device_id: &str) -> Result<serde_json::Value> {
        let url = format!("/api/devices/{}", device_id);
        self.get(&url).await
    }

    pub async fn execute_command(
        &self,
        device_id: &str,
        command: &str,
        sudo: bool,
    ) -> Result<String> {
        // Ensure WebSocket is connected
        if self.ws_sender.is_none() {
            return Err(anyhow!(
                "WebSocket not connected. Call connect_websocket() first."
            ));
        }

        // Send command via REST API to queue it
        let cmd_request = CommandRequest {
            device_id: device_id.to_string(),
            command: command.to_string(),
            sudo,
        };

        let url = format!("/api/devices/{}/commands", device_id);
        let response: serde_json::Value = self.post(&url, &cmd_request).await?;

        let command_id = match response["data"]["command_id"].as_str() {
            Some(id) => id.to_string(),
            None => {
                return Err(anyhow!("No command ID in response"));
            }
        };

        info!("Command queued with ID: {}", command_id);

        // Create a channel to receive the result
        let (tx, mut rx) = unbounded_channel::<String>();

        // Register pending command
        {
            let mut pending = self.pending_commands.lock().unwrap();
            pending.insert(
                command_id.clone(),
                PendingCommand {
                    sender: tx,
                    created_at: std::time::Instant::now(),
                },
            );
        }

        // Also send via WebSocket for real-time tracking (optional)
        if let Some(ws_sender) = &self.ws_sender {
            let ws_msg = serde_json::json!({
                "type": "command",
                "id": command_id,
                "device_id": device_id,
                "command": command,
                "sudo": sudo
            })
            .to_string();

            if let Err(e) = ws_sender.lock().unwrap().send(WsMessage::Text(ws_msg)) {
                warn!("Failed to send command via WebSocket: {:?}", e);
            }
        }

        // Wait for result (timeout after 60 seconds)
        let timeout = Duration::from_secs(60);
        match tokio::time::timeout(timeout, rx.recv()).await {
            Ok(Some(result)) => {
                info!("Command {} completed: {}", command_id, result);
                Ok(result)
            }
            Ok(None) => {
                // Cleanup pending command
                self.pending_commands.lock().unwrap().remove(&command_id);
                Err(anyhow!("Command result channel closed"))
            }
            Err(_) => {
                // Cleanup pending command
                self.pending_commands.lock().unwrap().remove(&command_id);
                Err(anyhow!(
                    "Command timed out after {} seconds",
                    timeout.as_secs()
                ))
            }
        }
    }

    pub async fn get_logs(&self, device_id: &str, limit: Option<i64>) -> Result<Vec<LogEntry>> {
        let mut url = format!("/api/devices/{}/logs", device_id);

        if let Some(l) = limit {
            url.push_str(&format!("?limit={}", l));
        }

        #[derive(Deserialize)]
        struct LogsResponse {
            data: LogsData,
        }

        #[derive(Deserialize)]
        struct LogsData {
            logs: Vec<LogEntry>,
        }

        let response: LogsResponse = self.get(&url).await?;
        Ok(response.data.logs)
    }

    pub async fn get_commands(&self, device_id: &str, limit: Option<i64>) -> Result<Vec<Command>> {
        let mut url = format!("/api/devices/{}/commands", device_id);
        if let Some(l) = limit {
            url.push_str(&format!("?limit={}", l));
        }

        #[derive(Deserialize)]
        struct CommandsResponse {
            data: CommandsData,
        }

        #[derive(Deserialize)]
        struct CommandsData {
            commands: Vec<Command>,
        }

        let response: CommandsResponse = self.get(&url).await?;
        Ok(response.data.commands)
    }

    pub async fn health_check(&self) -> Result<serde_json::Value> {
        self.get("/health").await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_api_client_creation() {
        let client = ApiClient::new("https://localhost:8443");
        assert!(client.is_ok());
    }
}
