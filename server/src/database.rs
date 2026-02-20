use anyhow::{anyhow, Result};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{Pool, SqlitePool, Sqlite};
use bcrypt::{hash, verify, DEFAULT_COST};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
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

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Command {
    pub id: String,
    pub device_id: String,
    pub command: String,
    pub sudo: i32,
    pub output: Option<String>,
    pub error: Option<String>,
    pub status: String,
    pub created_at: String,
    pub completed_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct LogEntry {
    pub id: String,
    pub device_id: String,
    pub level: String,
    pub message: String,
    pub data: Option<String>,
    pub timestamp: String,
}

pub struct Database {
    pool: Pool<Sqlite>,
}

impl Database {
    pub async fn new(database_path: &str) -> Self {
        let database_url = format!("sqlite:{}", database_path);

        let pool = SqlitePool::connect(&database_url)
            .await
            .expect("Failed to create database pool");

        Database { pool }
    }

    pub async fn migrate(&self) -> Result<()> {
        sqlx::query(r#"
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                model TEXT NOT NULL,
                android_version TEXT NOT NULL,
                api_level INTEGER NOT NULL,
                architecture TEXT NOT NULL,
                device_info TEXT NOT NULL,
                user_data TEXT NOT NULL,
                last_seen TEXT NOT NULL,
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS commands (
                id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                command TEXT NOT NULL,
                sudo INTEGER NOT NULL,
                output TEXT,
                error TEXT,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                completed_at TEXT
            );

            CREATE TABLE IF NOT EXISTS logs (
                id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                level TEXT NOT NULL,
                message TEXT NOT NULL,
                data TEXT,
                timestamp TEXT NOT NULL
            );
        "#)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    pub async fn save_device(&self, device: &Device) -> Result<()> {
        sqlx::query(r#"
            INSERT OR REPLACE INTO devices
            (id, name, model, android_version, api_level, architecture, device_info, user_data, last_seen, created_at)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
        "#)
        .bind(&device.id)
        .bind(&device.name)
        .bind(&device.model)
        .bind(&device.android_version)
        .bind(device.api_level)
        .bind(&device.architecture)
        .bind(&device.device_info)
        .bind(&device.user_data)
        .bind(&device.last_seen)
        .bind(&device.created_at)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    pub async fn get_all_devices(&self) -> Result<Vec<Device>> {
        let devices = sqlx::query_as::<_, Device>("SELECT * FROM devices ORDER BY last_seen DESC")
            .fetch_all(&self.pool)
            .await?;

        Ok(devices)
    }

    pub async fn get_device(&self, device_id: &str) -> Result<Option<Device>> {
        let device = sqlx::query_as::<_, Device>("SELECT * FROM devices WHERE id = ?1")
            .bind(device_id)
            .fetch_optional(&self.pool)
            .await?;

        Ok(device)
    }

    pub async fn save_command(&self, device_id: &str, command: &str, sudo: bool) -> Result<String> {
        let id = Uuid::new_v4().to_string();

        sqlx::query(r#"
            INSERT INTO commands (id, device_id, command, sudo, status, created_at)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6)
        "#)
        .bind(&id)
        .bind(device_id)
        .bind(command)
        .bind(sudo as i32)
        .bind("queued")
        .bind(Utc::now().to_rfc3339())
        .execute(&self.pool)
        .await?;

        Ok(id)
    }

    pub async fn update_command(
        &self,
        command_id: &str,
        output: Option<String>,
        error: Option<String>,
        status: &str,
    ) -> Result<()> {
        sqlx::query(r#"
            UPDATE commands
            SET output = ?1, error = ?2, status = ?3, completed_at = ?4
            WHERE id = ?5
        "#)
        .bind(&output)
        .bind(&error)
        .bind(status)
        .bind(Utc::now().to_rfc3339())
        .bind(command_id)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    pub async fn save_log(&self, log: &LogEntry) -> Result<()> {
        sqlx::query(r#"
            INSERT INTO logs (id, device_id, level, message, data, timestamp)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6)
        "#)
        .bind(&log.id)
        .bind(&log.device_id)
        .bind(&log.level)
        .bind(&log.message)
        .bind(&log.data)
        .bind(&log.timestamp)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    pub async fn get_logs(
        &self,
        _device_id: Option<&str>,
        _limit: Option<i64>,
        _offset: Option<i64>,
    ) -> Result<Vec<LogEntry>> {
        let logs = sqlx::query_as::<_, LogEntry>("SELECT * FROM logs ORDER BY timestamp DESC LIMIT 100")
            .fetch_all(&self.pool)
            .await?;

        Ok(logs)
    }
}
