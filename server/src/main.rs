use actix_web::{web, App, HttpResponse, HttpServer, Responder};
use actix_cors::Cors;
use actix_files::Files;
use std::sync::Arc;
use tracing::{info, error};

mod auth;
mod database;
mod devices;

struct AppState {
    devices: Arc<std::sync::RwLock<Vec<devices::Device>>>,
}

async fn health() -> impl Responder {
    HttpResponse::Ok().json(serde_json::json!({
        "status": "ok",
        "service": "rdm-server",
        "version": "0.1.0"
    }))
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    // Initialize tracing
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    // Load environment variables
    dotenv::dotenv().ok();

    // Get server config
    let host = std::env::var("RDM_HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
    let port = std::env::var("RDM_PORT")
        .unwrap_or_else(|_| "8443".to_string())
        .parse::<u16>()
        .unwrap_or(8443);

    info!("🚀 RDM Server starting on {}:{}", host, port);

    // Configure CORS
    let cors = Arc::new(Cors::permissive());

    // Initialize shared state
    let app_state = Arc::new(AppState {
        devices: Arc::new(std::sync::RwLock::new(Vec::new())),
    });

    // Bind address
    let bind_addr = format!("{}:{}", host, port);

    HttpServer::new(move || {
        // Create fresh CORS inside the closure
        let cors = Cors::permissive();

        // Serve web directory with dashboard.html as default
        let files = Files::new("/web", "./web")
            .index_file("dashboard.html");

        App::new()
            .wrap(cors)
            .app_data(app_state.clone())
            .route("/health", web::get().to(health))
            .route("/api/auth/login", web::post().to(auth::login))
            .route("/api/devices", web::get().to(devices::get_all_devices))
            .route("/api/devices/{device_id}", web::get().to(devices::get_device_by_id))
            .service(files)
    })
    .bind(&bind_addr)?
    .run()
    .await
}
