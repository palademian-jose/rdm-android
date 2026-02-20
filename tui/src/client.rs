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
    pub name: String,
    pub model: String,
    pub android_version: String,
    pub api_level: i32,
    pub architecture: String,
    pub device_info: String,
    pub user_data: String,
    pub last_seen: String,
    pub created_at: String,
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
            device_id: device_id.to_string(),
            command: command.to_string(),
            sudo,
        };

        let response: serde_json::Value = self.post("/api/commands", &cmd_request).await?;

        match response["status"].as_str() {
            Some("queued") | Some("executing") | Some("completed") => {
                Ok(response.get("output")
                    .and_then(|o| o.as_str())
                    .unwrap_or("Command queued")
                    .to_string())
            }
            Some("failed") => {
                Err(anyhow!("Command failed: {}",
                    response.get("error")
                        .and_then(|e| e.as_str())
                        .unwrap_or("Unknown error")
                ))
            }
            _ => {
                Err(anyhow!("Unexpected command status"))
            }
        }
    }

    pub async fn get_logs(&self, device_id: Option<&str>, limit: Option<i64>) -> Result<Vec<LogEntry>> {
        let mut url = "/api/logs".to_string();
        let mut params = vec![];

        if let Some(did) = device_id {
            params.push(format!("device_id={}", did));
        }
        if let Some(l) = limit {
            params.push(format!("limit={}", l));
        }

        if !params.is_empty() {
            url.push('?');
            url.push_str(&params.join("&"));
        }

        self.get(&url).await
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
