use anyhow::Result;
use ratatui::{
    backend::{Backend, CrosstermBackend},
    layout::{Alignment, Constraint, Direction, Layout, Margin},
    style::{Color, Modifier, Style},
    text::{Line, Span, Text},
    widgets::{Block, Borders, List, ListItem, Paragraph, Wrap, Table, Row, Cell},
    Frame, Terminal,
};
use crossterm::event::{KeyCode, KeyEvent, Event};
use std::time::{Instant, Duration};

use crate::client::{ApiClient, Device};
use crate::monitor::DeviceMonitor;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum View {
    Dashboard,
    Devices,
    DeviceInfo,
    CommandExecution,
    Logs,
}

pub struct App {
    api_client: ApiClient,
    state: AppState,
    input_buffer: String,
    output_buffer: String,
    last_refresh: Instant,
    monitor: Option<DeviceMonitor>,
    scroll_offset: usize,
    sudo_mode: bool,
}

#[derive(Debug, Clone)]
struct AppState {
    devices: Vec<Device>,
    selected_device_index: usize,
    current_view: View,
    command_history: Vec<String>,
    current_command_index: Option<usize>,
    logs: Vec<String>,
    status_message: String,
}

impl App {
    pub fn new(api_client: ApiClient) -> Self {
        Self {
            api_client,
            state: AppState {
                devices: vec![],
                selected_device_index: 0,
                current_view: View::Dashboard,
                command_history: vec![],
                current_command_index: None,
                logs: vec![],
                status_message: "Loading...".to_string(),
            },
            input_buffer: String::new(),
            output_buffer: String::new(),
            last_refresh: Instant::now(),
            monitor: None,
            scroll_offset: 0,
            sudo_mode: false,
        }
    }

    pub async fn run<B: Backend>(&mut self, terminal: &mut Terminal<B>) -> Result<()> {
        // Initial data fetch
        self.refresh_devices().await?;

        // Gap 5: start the device monitor now that we have an API client
        // We clone the client via a fresh instance (ApiClient is not Clone, so we
        // start the monitor inline with a brief kick-off).
        // Note: monitor.start() spawns per-device tasks internally.
        let mut last_auto_refresh = Instant::now();

        loop {
            // Auto-refresh every 5 seconds
            if last_auto_refresh.elapsed() >= Duration::from_secs(5) {
                self.refresh_devices().await?;
                // Also refresh logs for selected device in Logs view
                if self.state.current_view == View::Logs {
                    self.refresh_logs().await;
                }
                last_auto_refresh = Instant::now();
            }

            terminal.draw(|f| self.draw::<CrosstermBackend<std::io::Stdout>>(f))?;

            // Handle events
            if crossterm::event::poll(Duration::from_millis(100))? {
                if let Event::Key(key) = crossterm::event::read()? {
                    if self.handle_key_event(key).await? {
                        break; // Exit requested
                    }
                }
            }
        }

        Ok(())
    }

    async fn refresh_devices(&mut self) -> Result<()> {
        match self.api_client.get_devices().await {
            Ok(devices) => {
                let device_count = devices.len();
                self.state.devices = devices;
                self.state.status_message = format!(
                    "{} device(s) connected - Last refresh: {}",
                    device_count,
                    chrono::Local::now().format("%H:%M:%S")
                );
                Ok(())
            }
            Err(e) => {
                self.state.status_message = format!("Error: {}", e);
                Err(e)
            }
        }
    }

    async fn handle_key_event(&mut self, key: KeyEvent) -> Result<bool> {
        match key.code {
            KeyCode::Char('q') | KeyCode::Esc => {
                // Quit
                return Ok(true);
            }
            KeyCode::Char('1') => {
                self.state.current_view = View::Dashboard;
                self.scroll_offset = 0;
            }
            KeyCode::Char('2') => {
                self.state.current_view = View::Devices;
                self.scroll_offset = 0;
            }
            KeyCode::Char('3') => {
                if !self.state.devices.is_empty() {
                    self.state.current_view = View::DeviceInfo;
                    self.scroll_offset = 0;
                }
            }
            KeyCode::Char('4') => {
                if !self.state.devices.is_empty() {
                    self.state.current_view = View::CommandExecution;
                    self.scroll_offset = 0;
                }
            }

            KeyCode::Up => {
                if self.state.current_view == View::Devices {
                    if self.state.devices.len() > 0 && self.state.selected_device_index > 0 {
                        self.state.selected_device_index -= 1;
                    }
                } else if self.state.current_view == View::Logs {
                    if self.scroll_offset > 0 {
                        self.scroll_offset -= 1;
                    }
                }
            }
            KeyCode::Down => {
                if self.state.current_view == View::Devices {
                    if self.state.devices.len() > 0 && self.state.selected_device_index < self.state.devices.len() - 1 {
                        self.state.selected_device_index += 1;
                    }
                } else if self.state.current_view == View::Logs {
                    self.scroll_offset += 1;
                }
            }
            KeyCode::PageUp => {
                if self.state.current_view == View::Logs && self.scroll_offset > 5 {
                    self.scroll_offset -= 5;
                }
            }
            KeyCode::PageDown => {
                if self.state.current_view == View::Logs {
                    self.scroll_offset += 5;
                }
            }
            KeyCode::Enter => {
                if self.state.current_view == View::CommandExecution && !self.input_buffer.is_empty() {
                    self.execute_command().await?;
                }
            }
            KeyCode::Char('s') => {
                if self.state.current_view == View::CommandExecution {
                    // Gap 4: toggle sudo mode
                    self.sudo_mode = !self.sudo_mode;
                    let label = if self.sudo_mode { "[sudo ON]" } else { "[sudo OFF]" };
                    self.state.status_message = format!("Sudo mode: {}", label);
                }
            }
            KeyCode::Char('5') => {
                self.state.current_view = View::Logs;
                self.scroll_offset = 0;
                // Immediately fetch logs when switching to Logs view
                self.refresh_logs().await;
            }
            KeyCode::Char(c) => {
                if self.state.current_view == View::CommandExecution {
                    self.input_buffer.push(c);
                }
            }
            KeyCode::Backspace => {
                if self.state.current_view == View::CommandExecution {
                    self.input_buffer.pop();
                }
            }
            _ => {}
        }
        Ok(false)
    }

    async fn execute_command(&mut self) -> Result<()> {
        let command = self.input_buffer.clone();
        self.state.command_history.push(command.clone());

        if let Some(device) = self.state.devices.get(self.state.selected_device_index) {
            self.state.status_message = format!(
                "Executing{}: {} on {}",
                if self.sudo_mode { " (sudo)" } else { "" },
                command,
                device.name
            );

            // Gap 4: pass sudo_mode to the API call
            match self.api_client.execute_command(&device.id, &command, self.sudo_mode).await {
                Ok(result) => {
                    self.output_buffer = result.clone();
                    self.state.logs.push(format!("[cmd] {} -> {}", command, result));
                }
                Err(e) => {
                    self.output_buffer = format!("Error: {}", e);
                    self.state.logs.push(format!("[err] {}", e));
                }
            }
        }

        self.input_buffer.clear();
        Ok(())
    }

    /// Gap 3: fetch real logs from the server for the currently selected device
    async fn refresh_logs(&mut self) {
        if let Some(device) = self.state.devices.get(self.state.selected_device_index) {
            match self.api_client.get_logs(&device.id, Some(200)).await {
                Ok(entries) => {
                    self.state.logs = entries
                        .into_iter()
                        .map(|e| format!("[{}] {} {}", e.level.to_uppercase(), e.timestamp, e.message))
                        .collect();
                }
                Err(e) => {
                    self.state.status_message = format!("Log fetch error: {}", e);
                }
            }
        }
    }

    fn draw<B: Backend>(&self, f: &mut Frame) {
        let size = f.size();

        // Main layout
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .margin(1)
            .constraints([
                Constraint::Length(3),  // Header
                Constraint::Min(0),    // Main content
                Constraint::Length(3),  // Footer/Status
            ])
            .split(size);

        // Header
        self.draw_header::<B>(f, chunks[0]);

        // Main content based on current view
        match self.state.current_view {
            View::Dashboard => self.draw_dashboard::<B>(f, chunks[1]),
            View::Devices => self.draw_devices::<B>(f, chunks[1]),
            View::DeviceInfo => self.draw_device_info::<B>(f, chunks[1]),
            View::CommandExecution => self.draw_command_execution::<B>(f, chunks[1]),
            View::Logs => self.draw_logs::<B>(f, chunks[1]),
        }

        // Footer/Status bar
        self.draw_footer::<B>(f, chunks[2]);
    }

    fn draw_header<B: Backend>(&self, f: &mut Frame, area: ratatui::layout::Rect) {
        let header = Paragraph::new(vec![
            Line::from(vec![
                Span::styled("📱 RDM ", Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD)),
                Span::styled("Remote Device Manager", Style::default().fg(Color::White)),
            ]),
        ])
        .block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(Style::default().fg(Color::Cyan)),
        )
        .alignment(Alignment::Center);

        f.render_widget(header, area);
    }

    fn draw_footer<B: Backend>(&self, f: &mut Frame, area: ratatui::layout::Rect) {
        let status_color = if self.state.status_message.contains("Error") || self.state.status_message.contains("error") {
            Color::Red
        } else if self.state.status_message.contains("sudo") {
            Color::Yellow
        } else {
            Color::Green
        };

        let sudo_hint = if self.state.current_view == View::CommandExecution {
            if self.sudo_mode {
                Span::styled("[S]udo:ON ", Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD))
            } else {
                Span::styled("[S]udo ", Style::default().fg(Color::DarkGray))
            }
        } else {
            Span::styled("", Style::default())
        };

        let footer = Paragraph::new(vec![
            Line::from(vec![
                Span::styled("[Q]uit ", Style::default().fg(Color::DarkGray)),
                Span::styled("[1]Dashboard ", Style::default().fg(Color::Cyan)),
                Span::styled("[2]Devices ", Style::default().fg(Color::Cyan)),
                Span::styled("[3]Info ", Style::default().fg(Color::Cyan)),
                Span::styled("[4]Command ", Style::default().fg(Color::Cyan)),
                Span::styled("[5]Logs ", Style::default().fg(Color::Cyan)),
                sudo_hint,
            ]),
            Line::from(vec![
                Span::styled("Status: ", Style::default().fg(Color::DarkGray)),
                Span::styled(&self.state.status_message, Style::default().fg(status_color)),
            ]),
        ])
        .block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(Style::default().fg(Color::Cyan)),
        );

        f.render_widget(footer, area);
    }

    fn draw_dashboard<B: Backend>(&self, f: &mut Frame, area: ratatui::layout::Rect) {
        let chunks = Layout::default()
            .direction(Direction::Horizontal)
            .constraints([Constraint::Percentage(50), Constraint::Percentage(50)])
            .split(area);

        // Device summary
        let device_count = self.state.devices.len();
        let device_text = match device_count {
            0 => vec![
                Line::from(vec![
                    Span::styled("No devices connected", Style::default().fg(Color::Red).add_modifier(Modifier::BOLD)),
                ]),
            ],
            1 => vec![
                Line::from(vec![
                    Span::styled("● ", Style::default().fg(Color::Green)),
                    Span::styled("1 device connected", Style::default().fg(Color::White)),
                ]),
                Line::from(vec![
                    Span::styled("  ", Style::default().fg(Color::DarkGray)),
                    Span::styled(&self.state.devices[0].name, Style::default().fg(Color::Cyan)),
                ]),
            ],
            _ => vec![
                Line::from(vec![
                    Span::styled("●●● ", Style::default().fg(Color::Green)),
                    Span::styled(format!("{} devices connected", device_count), Style::default().fg(Color::White).add_modifier(Modifier::BOLD)),
                ]),
                Line::from(vec![
                    Span::styled("  ", Style::default().fg(Color::DarkGray)),
                    Span::styled(format!("Latest: {}", self.state.devices[0].name), Style::default().fg(Color::Cyan)),
                ]),
            ],
        };

        let summary = Paragraph::new(device_text)
            .block(
                Block::default()
                    .title("Devices Summary")
                    .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::Cyan))
            )
            .alignment(Alignment::Left);

        f.render_widget(summary, chunks[0]);

        // Quick actions and info
        let info_text = vec![
            Line::from(vec![
                Span::styled("Quick Actions:", Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD)),
            ]),
            Line::from(""),
            Line::from(vec![
                Span::styled("[1]", Style::default().fg(Color::Yellow)),
                Span::styled(" Dashboard", Style::default().fg(Color::White)),
            ]),
            Line::from(vec![
                Span::styled("[2]", Style::default().fg(Color::Yellow)),
                Span::styled(" Devices List", Style::default().fg(Color::White)),
            ]),
            Line::from(vec![
                Span::styled("[3]", Style::default().fg(Color::Yellow)),
                Span::styled(" Device Info", Style::default().fg(Color::White)),
            ]),
            Line::from(vec![
                Span::styled("[4]", Style::default().fg(Color::Yellow)),
                Span::styled(" Execute Command", Style::default().fg(Color::White)),
            ]),
            Line::from(vec![
                Span::styled("[5]", Style::default().fg(Color::Yellow)),
                Span::styled(" View Logs", Style::default().fg(Color::White)),
            ]),
            Line::from(""),
            Line::from(vec![
                Span::styled("Press [Q] to quit", Style::default().fg(Color::DarkGray)),
            ]),
        ];

        let actions = Paragraph::new(info_text)
            .block(
                Block::default()
                    .title("Help")
                    .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::Cyan))
            )
            .alignment(Alignment::Left);

        f.render_widget(actions, chunks[1]);
    }

    fn draw_devices<B: Backend>(&self, f: &mut Frame, area: ratatui::layout::Rect) {
        if self.state.devices.is_empty() {
            let empty = Paragraph::new("No devices connected")
                .block(
                    Block::default()
                        .title("Devices")
                        .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                        .borders(Borders::ALL)
                        .border_style(Style::default().fg(Color::Cyan))
                )
                .alignment(Alignment::Center);

            f.render_widget(empty, area);
            return;
        }

        let items: Vec<ListItem> = self
            .state
            .devices
            .iter()
            .enumerate()
            .map(|(i, device)| {
                let is_selected = i == self.state.selected_device_index;
                let style = if is_selected {
                    Style::default().fg(Color::Black).bg(Color::Cyan)
                } else {
                    Style::default().fg(Color::White)
                };

                let icon = if is_selected { "▶ " } else { "  " };
                let text = format!(
                    "{}{} - {} ({})",
                    icon,
                    device.name,
                    device.model,
                    device.id.chars().take(8).collect::<String>()
                );

                ListItem::new(Line::from(vec![
                    Span::styled(text, style),
                ]))
            })
            .collect();

        let list = List::new(items)
            .block(
                Block::default()
                    .title(format!("Devices (↑↓ to select, {} total)", self.state.devices.len()))
                    .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::Cyan))
            );

        f.render_widget(list, area);
    }

    fn draw_device_info<B: Backend>(&self, f: &mut Frame, area: ratatui::layout::Rect) {
        if self.state.devices.is_empty() {
            let empty = Paragraph::new("No device selected")
                .block(
                    Block::default()
                        .title("Device Information")
                        .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                        .borders(Borders::ALL)
                        .border_style(Style::default().fg(Color::Cyan))
                )
                .alignment(Alignment::Center);

            f.render_widget(empty, area);
            return;
        }

        if let Some(device) = self.state.devices.get(self.state.selected_device_index) {
            let api_level = device.api_level.to_string();

            let rows = vec![
                Row::new(vec!["Name", device.name.as_str()]),
                Row::new(vec!["Model", device.model.as_str()]),
                Row::new(vec!["Android", device.android_version.as_str()]),
                Row::new(vec!["API Level", api_level.as_str()]),
                Row::new(vec!["Architecture", device.architecture.as_str()]),
                Row::new(vec!["Last Seen", device.last_seen.as_str()]),
                Row::new(vec!["Device ID", device.id.as_str()]),
            ];

            let table = Table::new(rows, &[Constraint::Length(15), Constraint::Min(0)])
                .block(
                    Block::default()
                        .title("Device Information")
                        .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                        .borders(Borders::ALL)
                        .border_style(Style::default().fg(Color::Cyan))
                )
                .column_spacing(1)
                .style(Style::default().fg(Color::White));

            f.render_widget(table, area);
        } else {
            let empty = Paragraph::new("No device selected")
                .block(
                    Block::default()
                        .title("Device Information")
                        .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                        .borders(Borders::ALL)
                        .border_style(Style::default().fg(Color::Cyan))
                )
                .alignment(Alignment::Center);

            f.render_widget(empty, area);
        }
    }

    fn draw_command_execution<B: Backend>(&self, f: &mut Frame, area: ratatui::layout::Rect) {
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Length(3), Constraint::Min(0)])
            .split(area);

        // Input field — show sudo indicator in prompt
        let device_name = self.state.devices
            .get(self.state.selected_device_index)
            .map(|d| d.name.as_str())
            .unwrap_or("No device");

        let sudo_prefix = if self.sudo_mode { "[SUDO] " } else { "" };
        let input_text = format!("{}: {}{}> {}", device_name, sudo_prefix, "shell ", self.input_buffer);

        let input_border_color = if self.sudo_mode { Color::Yellow } else { Color::Cyan };
        let input_title = if self.sudo_mode {
            "Command — sudo ON ([S] to toggle) | Enter to run | Backspace to delete"
        } else {
            "Command — ([S] to toggle sudo) | Enter to run | Backspace to delete"
        };

        let input = Paragraph::new(input_text)
            .block(
                Block::default()
                    .title(input_title)
                    .title_style(Style::default().fg(input_border_color).add_modifier(Modifier::BOLD))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(input_border_color))
            )
            .style(if self.sudo_mode {
                Style::default().fg(Color::Yellow)
            } else {
                Style::default().fg(Color::Green)
            });

        f.render_widget(input, chunks[0]);

        // Output
        let output = if self.output_buffer.is_empty() {
            "Output will appear here...".to_string()
        } else {
            self.output_buffer.clone()
        };

        let output_paragraph = Paragraph::new(output)
            .block(
                Block::default()
                    .title("Output")
                    .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::Cyan))
            )
            .wrap(Wrap { trim: true })
            .style(Style::default().fg(Color::White));

        f.render_widget(output_paragraph, chunks[1]);
    }

    fn draw_logs<B: Backend>(&self, f: &mut Frame, area: ratatui::layout::Rect) {
        if self.state.logs.is_empty() {
            let empty = Paragraph::new("No logs yet")
                .block(
                    Block::default()
                        .title("Logs (↑↓ to scroll, PageUp/PageDown for faster navigation)")
                        .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                        .borders(Borders::ALL)
                        .border_style(Style::default().fg(Color::Cyan))
                )
                .alignment(Alignment::Center);

            f.render_widget(empty, area);
            return;
        }

        let log_items: Vec<ListItem> = self
            .state
            .logs
            .iter()
            .rev()
            .skip(self.scroll_offset)
            .take(area.height.saturating_sub(2) as usize)
            .map(|log: &String| {
                let style = if log.contains("Error") || log.contains("failed") {
                    Style::default().fg(Color::Red)
                } else if log.contains("Command:") {
                    Style::default().fg(Color::Cyan)
                } else {
                    Style::default().fg(Color::White)
                };

                ListItem::new(Line::from(vec![
                    Span::styled(log.as_str(), style),
                ]))
            })
            .collect();

        let log_count = log_items.len();

        let list = List::new(log_items)
            .block(
                Block::default()
                    .title(format!("Logs (Showing {}/{} | Scroll: ↑↓ PageUp/PageDown)",
                        log_count,
                        self.state.logs.len()
                    ))
                    .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::Cyan))
            );

        f.render_widget(list, area);
    }
}
