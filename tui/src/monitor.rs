use anyhow::{anyhow, Result};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::time::{Duration, Instant};
use tokio::sync::RwLock;
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message};
use tracing::{info, error, warn, debug};

use crate::client::{ApiClient, Device};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum WsMessage {
    #[serde(rename = "auth")]
    Auth { token: String },
    #[serde(rename = "heartbeat")]
    Heartbeat { device_id: String, timestamp: i64 },
    #[serde(rename = "command")]
    Command { id: String, command: String, sudo: bool },
    #[serde(rename = "log")]
    Log { device_id: String, level: String, message: String },
    #[serde(rename = "error")]
    Error { code: String, message: String },
}

#[derive(Debug, Clone)]
pub struct DeviceStats {
    pub device_id: String,
    pub cpu_usage: f32,
    pub memory_usage: f32,
    pub storage_usage: f32,
    pub battery_level: f32,
    pub last_update: Instant,
}

pub struct DeviceMonitor {
    api_client: ApiClient,
    stats: RwLock<Vec<DeviceStats>>,
    running: bool,
}

impl DeviceMonitor {
    pub fn new(api_client: ApiClient) -> Self {
        Self {
            api_client,
            stats: RwLock::new(vec![]),
            running: false,
        }
    }

    pub async fn start(&mut self) -> Result<()> {
        if self.running {
            return Ok(());
        }

        self.running = true;
        info!("Device monitor started");

        // Get initial device list
        let devices = self.api_client.get_devices().await?;

        for device in devices {
            let stats = RwLock::new(vec![]);

            let device_id = device.id.clone();
            tokio::spawn(async move {
                if let Err(e) = Self::monitor_device(&device_id, &stats).await {
                    error!("Failed to monitor device {}: {}", device_id, e);
                }
            });
        }

        Ok(())
    }

    pub async fn stop(&mut self) {
        self.running = false;
        info!("Device monitor stopped");
    }

    pub async fn get_stats(&self) -> Vec<DeviceStats> {
        self.stats.read().await.clone()
    }

    async fn monitor_device(device_id: &str, stats: &RwLock<Vec<DeviceStats>>) -> Result<()> {
        let mut interval = tokio::time::interval(Duration::from_secs(5));

        loop {
            interval.tick().await;

            // Fetch device info
            let device_info = fetch_device_info(device_id).await?;

            let cpu_usage = device_info.cpu_usage;
            let memory_usage = device_info.memory_usage;
            let storage_usage = device_info.storage_usage;
            let battery_level = device_info.battery_level;

            let device_stats = DeviceStats {
                device_id: device_id.to_string(),
                cpu_usage,
                memory_usage,
                storage_usage,
                battery_level,
                last_update: Instant::now(),
            };

            // Update stats
            {
                let mut s: tokio::sync::RwLockWriteGuard<'_, Vec<DeviceStats>> = stats.write().await;
                // Update or add stats for this device
                if let Some(existing) = s.iter_mut().find(|x| x.device_id == device_id) {
                    *existing = device_stats;
                } else {
                    s.push(device_stats);
                }
            }

            debug!("Updated stats for device {}: CPU={}%, MEM={}%, BAT={}%",
                device_id, cpu_usage, memory_usage, battery_level);
        }
    }
}

async fn fetch_device_info(_device_id: &str) -> Result<DeviceInfo> {
    // This would normally fetch from server
    // For now, return mock data
    Ok(DeviceInfo {
        cpu_usage: (rand::random::<f64>() * 100.0) as f32,
        memory_usage: (rand::random::<f64>() * 100.0) as f32,
        storage_usage: (rand::random::<f64>() * 100.0) as f32,
        battery_level: (rand::random::<f64>() * 100.0) as f32,
    })
}

#[derive(Debug, Clone)]
struct DeviceInfo {
    pub cpu_usage: f32,
    pub memory_usage: f32,
    pub storage_usage: f32,
    pub battery_level: f32,
}

mod rand {
    use std::time::{SystemTime, UNIX_EPOCH};

    pub fn random<T>() -> T
    where
        T: std::ops::Mul<Output = T> + std::ops::Div<Output = T> + Copy,
        f64: std::convert::Into<T>,
    {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .subsec_nanos() as f64;
        let r = nanos / 1_000_000_000.0;
        r.into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_message_serialization() {
        let msg = WsMessage::Command {
            id: "test".to_string(),
            command: "ls".to_string(),
            sudo: false,
        };

        let json = serde_json::to_string(&msg).unwrap();
        let deserialized: WsMessage = serde_json::from_str(&json).unwrap();

        match deserialized {
            WsMessage::Command { id, command, sudo } => {
                assert_eq!(id, "test");
                assert_eq!(command, "ls");
                assert_eq!(sudo, false);
            }
            _ => panic!("Expected Command message"),
        }
    }
}
