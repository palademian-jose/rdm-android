use anyhow::{anyhow, Result};
use actix_web::{web, HttpResponse, Responder};
use bcrypt::{hash, verify, DEFAULT_COST};
use chrono::{Utc, Duration};
use jsonwebtoken::{decode, encode, Algorithm, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use tracing::{info, error, warn};

const JWT_SECRET: &[u8] = b"your-super-secret-jwt-key-change-this-in-production";
const TOKEN_EXPIRATION_HOURS: i64 = 24;

#[derive(Debug, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,
    pub exp: usize,
    pub iat: usize,
    pub jti: String,
}

#[derive(Debug, Deserialize)]
pub struct AuthRequest {
    pub username: String,
    pub password: String,
}

#[derive(Debug, Serialize)]
pub struct AuthResponse {
    pub token: String,
    pub device_id: String,
    pub username: String,
}

pub async fn login(
    req: web::Json<AuthRequest>,
) -> impl Responder {
    let auth_req = req.into_inner();

    info!("Login attempt for user: {}", auth_req.username);

    // Simple authentication using environment variables
    // In production, this should query the database
    let expected_username = std::env::var("ADMIN_USERNAME").unwrap_or_else(|_| "admin".to_string());
    let expected_password = std::env::var("ADMIN_PASSWORD").unwrap_or_else(|_| "admin".to_string());

    if auth_req.username != expected_username || auth_req.password != expected_password {
        warn!("Failed login attempt for user: {}", auth_req.username);
        return HttpResponse::Unauthorized().json(serde_json::json!({
            "error": "Invalid credentials"
        }));
    }

    // Generate JWT token
    let token = match generate_token(&auth_req.username) {
        Ok(t) => t,
        Err(e) => {
            error!("Failed to generate token: {}", e);
            return HttpResponse::InternalServerError().json(serde_json::json!({
                "error": "Failed to generate token"
            }));
        }
    };

    info!("User {} authenticated successfully", auth_req.username);

    HttpResponse::Ok().json(AuthResponse {
        token,
        device_id: "admin-user".to_string(),
        username: auth_req.username,
    })
}

pub fn generate_token(username: &str) -> Result<String> {
    let now = Utc::now();
    let expiration = now + Duration::hours(TOKEN_EXPIRATION_HOURS);
    let jti = Uuid::new_v4().to_string();

    let claims = Claims {
        sub: username.to_string(),
        exp: expiration.timestamp() as usize,
        iat: now.timestamp() as usize,
        jti,
    };

    let token = encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(JWT_SECRET),
    )?;

    Ok(token)
}

pub fn verify_token(token: &str) -> Result<Claims> {
    let token_data = decode::<Claims>(
        token,
        &DecodingKey::from_secret(JWT_SECRET),
        &Validation::new(Algorithm::HS256),
    )?;

    Ok(token_data.claims)
}

pub fn hash_password(password: &str) -> Result<String> {
    let hashed = hash(password, DEFAULT_COST)?;
    Ok(hashed)
}

pub fn verify_password(password: &str, hash: &str) -> Result<bool> {
    Ok(verify(password, hash)?)
}
