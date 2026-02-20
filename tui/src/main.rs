use anyhow::Result;
use crossterm::{
    event::{Event, KeyCode},
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use ratatui::{
    backend::CrosstermBackend,
    Terminal,
};
use tracing::info;

mod ui;
mod client;
mod monitor;

use client::ApiClient;
use ui::App;

#[tokio::main]
async fn main() -> Result<()> {
    // Initialize tracing
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    // Load environment variables
    dotenv::dotenv().ok();

    // Get server URL
    let server_url = std::env::var("RDM_SERVER_URL")
        .unwrap_or_else(|_| "https://localhost:8443".to_string());
    let username = std::env::var("RDM_USERNAME").unwrap_or_else(|_| "admin".to_string());
    let password = std::env::var("RDM_PASSWORD").unwrap_or_else(|_| "admin".to_string());

    info!("🚀 RDM TUI starting");
    info!("Server: {}", server_url);

    // Create API client
    let mut api_client = ApiClient::new(&server_url)?;

    // Authenticate
    info!("Authenticating...");
    let token = api_client.authenticate(&username, &password).await?;
    info!("Authenticated successfully");

    api_client.set_token(&token);

    // Initialize terminal
    enable_raw_mode()?;
    let stdout = std::io::stdout();
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;
    execute!(terminal.backend_mut(), EnterAlternateScreen)?;

    let mut app = App::new(api_client);

    // Run the app
    let res = app.run(&mut terminal).await;

    // Cleanup
    disable_raw_mode()?;
    execute!(terminal.backend_mut(), LeaveAlternateScreen)?;

    res
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_state_serialization() {
        let state = AppState {
            devices: vec![],
            selected_device: None,
            current_view: View::Dashboard,
        };

        let json = serde_json::to_string(&state).unwrap();
        let deserialized: AppState = serde_json::from_str(&json).unwrap();

        assert_eq!(deserialized.current_view, View::Dashboard);
    }
}
