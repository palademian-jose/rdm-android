use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum WsMessage {
    #[serde(rename = "auth")]
    Auth {
        token: String,
    },
    #[serde(rename = "device_info")]
    DeviceInfo {
        device_id: String,
        info: Value,
    },
    #[serde(rename = "command")]
    Command {
        id: String,
        command: String,
        sudo: bool,
    },
    #[serde(rename = "command_result")]
    CommandResult {
        id: String,
        success: bool,
        output: String,
        error: Option<String>,
    },
    #[serde(rename = "log")]
    Log {
        device_id: String,
        level: String,
        message: String,
        data: Option<String>,
    },
    #[serde(rename = "heartbeat")]
    Heartbeat {
        device_id: String,
        timestamp: i64,
    },
    #[serde(rename = "error")]
    Error {
        code: String,
        message: String,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ws_message_serialization() {
        let msg = WsMessage::Command {
            id: "test-cmd-123".to_string(),
            command: "ls -la".to_string(),
            sudo: false,
        };

        let json = serde_json::to_string(&msg).unwrap();
        let deserialized: WsMessage = serde_json::from_str(&json).unwrap();

        match deserialized {
            WsMessage::Command { id, command, sudo } => {
                assert_eq!(id, "test-cmd-123");
                assert_eq!(command, "ls -la");
                assert_eq!(sudo, false);
            }
            _ => panic!("Expected Command message"),
        }
    }
}
