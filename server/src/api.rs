use actix_web::web;

#[derive(serde::Deserialize)]
pub struct LogsQuery {
    pub device_id: Option<String>,
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

#[derive(serde::Deserialize)]
pub struct DevicesQuery {
    pub online: Option<bool>,
}

#[derive(serde::Deserialize)]
pub struct CommandsQuery {
    pub device_id: String,
    pub status: Option<String>,
    pub limit: Option<i64>,
}

#[derive(serde::Serialize)]
pub struct ApiResponse<T> {
    pub success: bool,
    pub data: Option<T>,
    pub error: Option<String>,
}

impl<T> ApiResponse<T> {
    pub fn success(data: T) -> Self {
        Self {
            success: true,
            data: Some(data),
            error: None,
        }
    }

    pub fn error(message: String) -> Self {
        Self {
            success: false,
            data: None,
            error: Some(message),
        }
    }
}

#[derive(serde::Deserialize)]
pub struct DeviceCommandRequest {
    pub device_id: String,
    pub command: String,
    pub sudo: bool,
    pub timeout: Option<u64>,
}
