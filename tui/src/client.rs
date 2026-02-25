use anyhow::{anyhow, Result};
use reqwest::{Client, ClientBuilder, StatusCode};
use serde::{Deserialize, Serialize};
use std::time::Duration;
use tracing::{info, error, debug};

pub struct ApiClient {
    client: Client,
    base_url: String,
    token: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub model: String,
    #[serde(default)]
    pub android_version: Option<String>,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub api_level: Option<i32>,
    #[serde(default)]
    pub architecture: Option<String>,
    #[serde(default)]
    pub device_info: Option<String>,
    #[serde(default)]
    pub user_data: Option<String>,
    #[serde(default)]
    pub last_seen: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
    pub latitude: Option<f64>,
    pub longitude: Option<f64>,
    pub battery_level: Option<i32>,
    pub storage_free: Option<i64>,
    pub ram_free: Option<i64>,
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
    command: String,
    sudo: bool,
}

impl ApiClient {
    pub fn new(base_url: &str) -> Result<Self> {
        let client = ClientBuilder::new()
            .timeout(Duration::from_secs(30))
            .danger_accept_invalid_certs(true) // For development - remove in production
            .build()?;

        Ok(Self {
            client,
            base_url: base_url.trim_end_matches('/').to_string(),
            token: None,
        })
    }

    pub fn set_token(&mut self, token: &str) {
        self.token = Some(token.to_string());
    }

    fn auth_header(&self) -> Option<String> {
        self.token.as_ref().map(|t| format!("Bearer {}", t))
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

    async fn post<T: for<'de> Deserialize<'de>, B: Serialize>(&self, path: &str, body: &B) -> Result<T> {
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

    pub async fn execute_command(&self, device_id: &str, command: &str, sudo: bool) -> Result<String> {
        let cmd_request = CommandRequest {
            command: command.to_string(),
            sudo,
        };

        // Correct endpoint: POST /api/devices/{device_id}/commands
        let url = format!("/api/devices/{}/commands", device_id);
        let response: serde_json::Value = self.post(&url, &cmd_request).await?;

        // Server returns { success, message, data: { command_id, ... } }
        if response["success"].as_bool().unwrap_or(false) {
            Ok(response["message"]
                .as_str()
                .unwrap_or("Command sent")
                .to_string())
        } else {
            Err(anyhow!(
                "Command failed: {}",
                response["message"].as_str().unwrap_or("Unknown error")
            ))
        }
    }

    pub async fn get_logs(&self, device_id: &str, limit: Option<i64>) -> Result<Vec<LogEntry>> {
        #[derive(Deserialize)]
        struct LogsResponse {
            data: Option<LogsData>,
        }
        #[derive(Deserialize)]
        struct LogsData {
            logs: Vec<LogEntry>,
        }

        // Correct endpoint: GET /api/devices/{device_id}/logs
        let mut url = format!("/api/devices/{}/logs", device_id);
        if let Some(l) = limit {
            url.push_str(&format!("?limit={}", l));
        }
        let response: LogsResponse = self.get(&url).await?;
        Ok(response.data.map(|d| d.logs).unwrap_or_default())
    }

    pub async fn get_commands(&self, device_id: &str, limit: Option<i64>) -> Result<Vec<Command>> {
        let mut url = format!("/api/devices/{}/commands", device_id);
        if let Some(l) = limit {
            url.push_str(&format!("?limit={}", l));
        }

        self.get(&url).await
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
