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

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CommandCategory {
    DeviceInfo,
    AppManagement,
    System,
    Connectivity,
}

#[derive(Debug, Clone)]
pub struct PredefinedCommand {
    pub id: String,
    pub name: String,
    pub command: String,
    pub category: CommandCategory,
    pub requires_sudo: bool,
    pub description: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum View {
    Dashboard,
    Devices,
    DeviceInfo,
    CommandList,
    CommandExecution,
    Logs,
}

impl CommandCategory {
    pub fn display_name(&self) -> &str {
        match self {
            CommandCategory::DeviceInfo => "Device Info",
            CommandCategory::AppManagement => "App Management",
            CommandCategory::System => "System",
            CommandCategory::Connectivity => "Connectivity",
        }
    }
}

pub fn get_predefined_commands() -> Vec<PredefinedCommand> {
    vec![
        // Device Info
        PredefinedCommand {
            id: "getprop".to_string(),
            name: "Get Device Properties".to_string(),
            command: "getprop".to_string(),
            category: CommandCategory::DeviceInfo,
            requires_sudo: false,
            description: "Get all device properties".to_string(),
        },
        PredefinedCommand {
            id: "ip_addr".to_string(),
            name: "Get Network Info".to_string(),
            command: "ip addr show".to_string(),
            category: CommandCategory::DeviceInfo,
            requires_sudo: false,
            description: "Show network interface information".to_string(),
        },
        PredefinedCommand {
            id: "ps_aux".to_string(),
            name: "Get Process List".to_string(),
            command: "ps aux".to_string(),
            category: CommandCategory::DeviceInfo,
            requires_sudo: false,
            description: "List all running processes".to_string(),
        },
        PredefinedCommand {
            id: "meminfo".to_string(),
            name: "Get Memory Info".to_string(),
            command: "cat /proc/meminfo".to_string(),
            category: CommandCategory::DeviceInfo,
            requires_sudo: false,
            description: "Show memory usage information".to_string(),
        },
        PredefinedCommand {
            id: "cpuinfo".to_string(),
            name: "Get CPU Info".to_string(),
            command: "cat /proc/cpuinfo".to_string(),
            category: CommandCategory::DeviceInfo,
            requires_sudo: false,
            description: "Show CPU information".to_string(),
        },
        PredefinedCommand {
            id: "df_h".to_string(),
            name: "Get Storage Info".to_string(),
            command: "df -h".to_string(),
            category: CommandCategory::DeviceInfo,
            requires_sudo: false,
            description: "Show disk space usage".to_string(),
        },
        PredefinedCommand {
            id: "battery".to_string(),
            name: "Get Battery Info".to_string(),
            command: "dumpsys battery".to_string(),
            category: CommandCategory::DeviceInfo,
            requires_sudo: false,
            description: "Show battery status".to_string(),
        },
        PredefinedCommand {
            id: "foreground_app".to_string(),
            name: "Get Foreground App".to_string(),
            command: "dumpsys activity activities | grep 'mResumedActivity'".to_string(),
            category: CommandCategory::DeviceInfo,
            requires_sudo: false,
            description: "Get currently running app".to_string(),
        },
        PredefinedCommand {
            id: "activity_stack".to_string(),
            name: "Get Activity Stack".to_string(),
            command: "dumpsys activity activities".to_string(),
            category: CommandCategory::DeviceInfo,
            requires_sudo: false,
            description: "Get full activity stack".to_string(),
        },
        // App Management
        PredefinedCommand {
            id: "pm_list_3".to_string(),
            name: "List Installed Apps".to_string(),
            command: "pm list packages -3".to_string(),
            category: CommandCategory::AppManagement,
            requires_sudo: false,
            description: "List third-party installed apps".to_string(),
        },
        PredefinedCommand {
            id: "pm_list_s".to_string(),
            name: "List System Apps".to_string(),
            command: "pm list packages -s".to_string(),
            category: CommandCategory::AppManagement,
            requires_sudo: false,
            description: "List system apps".to_string(),
        },
        PredefinedCommand {
            id: "force_stop".to_string(),
            name: "Force Stop App".to_string(),
            command: "am force-stop <package>".to_string(),
            category: CommandCategory::AppManagement,
            requires_sudo: true,
            description: "Force stop an app (replace <package>)".to_string(),
        },
        PredefinedCommand {
            id: "pm_clear".to_string(),
            name: "Clear App Data".to_string(),
            command: "pm clear <package>".to_string(),
            category: CommandCategory::AppManagement,
            requires_sudo: true,
            description: "Clear app data (replace <package>)".to_string(),
        },
        PredefinedCommand {
            id: "pm_uninstall".to_string(),
            name: "Uninstall App".to_string(),
            command: "pm uninstall <package>".to_string(),
            category: CommandCategory::AppManagement,
            requires_sudo: true,
            description: "Uninstall an app (replace <package>)".to_string(),
        },
        PredefinedCommand {
            id: "pm_list_all".to_string(),
            name: "List All Apps".to_string(),
            command: "pm list packages".to_string(),
            category: CommandCategory::AppManagement,
            requires_sudo: false,
            description: "List all installed apps".to_string(),
        },
        // System
        PredefinedCommand {
            id: "reboot".to_string(),
            name: "Reboot Device".to_string(),
            command: "reboot".to_string(),
            category: CommandCategory::System,
            requires_sudo: true,
            description: "Reboot the device".to_string(),
        },
        PredefinedCommand {
            id: "brightness".to_string(),
            name: "Set Screen Brightness".to_string(),
            command: "settings put system screen_brightness <level>".to_string(),
            category: CommandCategory::System,
            requires_sudo: true,
            description: "Set brightness 0-255 (replace <level>)".to_string(),
        },
        // Connectivity
        PredefinedCommand {
            id: "wifi_enable".to_string(),
            name: "Enable WiFi".to_string(),
            command: "svc wifi enable".to_string(),
            category: CommandCategory::Connectivity,
            requires_sudo: true,
            description: "Enable WiFi".to_string(),
        },
        PredefinedCommand {
            id: "wifi_disable".to_string(),
            name: "Disable WiFi".to_string(),
            command: "svc wifi disable".to_string(),
            category: CommandCategory::Connectivity,
            requires_sudo: true,
            description: "Disable WiFi".to_string(),
        },
        PredefinedCommand {
            id: "bt_enable".to_string(),
            name: "Enable Bluetooth".to_string(),
            command: "service call bluetooth_manager 6".to_string(),
            category: CommandCategory::Connectivity,
            requires_sudo: true,
            description: "Enable Bluetooth".to_string(),
        },
        PredefinedCommand {
            id: "bt_disable".to_string(),
            name: "Disable Bluetooth".to_string(),
            command: "service call bluetooth_manager 8".to_string(),
            category: CommandCategory::Connectivity,
            requires_sudo: true,
            description: "Disable Bluetooth".to_string(),
        },
    ]
}

pub struct App {
    api_client: ApiClient,
    state: AppState,
    input_buffer: String,
    output_buffer: String,
    last_refresh: Instant,
    monitor: Option<DeviceMonitor>,
    scroll_offset: usize,
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
    selected_command_index: usize,
    selected_category_index: usize,
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
                selected_command_index: 0,
                selected_category_index: 0,
            },
            input_buffer: String::new(),
            output_buffer: String::new(),
            last_refresh: Instant::now(),
            monitor: None,
            scroll_offset: 0,
        }
    }

    pub async fn run<B: Backend>(&mut self, terminal: &mut Terminal<B>) -> Result<()> {
        // Initial data fetch
        self.refresh_devices().await?;

        let mut last_auto_refresh = Instant::now();

        loop {
            // Auto-refresh every 5 seconds
            if last_auto_refresh.elapsed() >= Duration::from_secs(5) {
                self.refresh_devices().await?;
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
                if self.state.current_view == View::CommandList {
                    self.state.current_view = View::CommandExecution;
                    return Ok(false);
                }
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
            KeyCode::Tab => {
                if self.state.current_view == View::CommandExecution {
                    self.state.current_view = View::CommandList;
                    self.scroll_offset = 0;
                } else if self.state.current_view == View::CommandList {
                    self.state.current_view = View::CommandExecution;
                    self.scroll_offset = 0;
                }
            }
            KeyCode::Char('5') => {
                self.state.current_view = View::Logs;
                self.scroll_offset = 0;
            }
            KeyCode::Up => {
                if self.state.current_view == View::Devices {
                    if self.state.devices.len() > 0 && self.state.selected_device_index > 0 {
                        self.state.selected_device_index -= 1;
                    }
                } else if self.state.current_view == View::CommandList {
                    self.handle_command_list_navigation(-1);
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
                } else if self.state.current_view == View::CommandList {
                    self.handle_command_list_navigation(1);
                } else if self.state.current_view == View::Logs {
                    self.scroll_offset += 1;
                }
            }
            KeyCode::Left => {
                if self.state.current_view == View::CommandList && self.state.selected_category_index > 0 {
                    self.state.selected_category_index -= 1;
                    self.state.selected_command_index = 0;
                }
            }
            KeyCode::Right => {
                if self.state.current_view == View::CommandList {
                    let categories = self.get_categories();
                    if self.state.selected_category_index < categories.len() - 1 {
                        self.state.selected_category_index += 1;
                        self.state.selected_command_index = 0;
                    }
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
                } else if self.state.current_view == View::CommandList {
                    self.execute_selected_command().await?;
                }
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

    fn handle_command_list_navigation(&mut self, direction: i32) {
        let commands = get_predefined_commands();
        let categories = self.get_categories();
        let current_category = &categories[self.state.selected_category_index];

        let category_commands: Vec<_> = commands
            .iter()
            .filter(|c| c.category == *current_category)
            .collect();

        let new_index = if direction > 0 {
            self.state.selected_command_index + 1
        } else {
            if self.state.selected_command_index > 0 {
                self.state.selected_command_index - 1
            } else {
                0
            }
        };

        if new_index < category_commands.len() {
            self.state.selected_command_index = new_index;
        }
    }

    async fn execute_selected_command(&mut self) -> Result<()> {
        let commands = get_predefined_commands();
        let categories = self.get_categories();
        let current_category = &categories[self.state.selected_category_index];

        if let Some(cmd) = commands
            .iter()
            .filter(|c| c.category == *current_category)
            .nth(self.state.selected_command_index)
        {
            self.input_buffer = cmd.command.clone();
            self.state.current_view = View::CommandExecution;
            self.execute_command().await?;
        }
        Ok(())
    }

    fn get_categories(&self) -> Vec<CommandCategory> {
        vec![
            CommandCategory::DeviceInfo,
            CommandCategory::AppManagement,
            CommandCategory::System,
            CommandCategory::Connectivity,
        ]
    }

    async fn execute_command(&mut self) -> Result<()> {
        let command = self.input_buffer.clone();
        self.state.command_history.push(command.clone());

        if let Some(device) = self.state.devices.get(self.state.selected_device_index) {
            self.state.status_message = format!("Executing: {} on {}", command, device.name);

            match self.api_client.execute_command(&device.id, &command, false).await {
                Ok(result) => {
                    self.output_buffer = result.clone();
                    self.state.logs.push(format!("Command: {} -> {}", command, result));
                }
                Err(e) => {
                    self.output_buffer = format!("Error: {}", e);
                    self.state.logs.push(format!("Command failed: {}", e));
                }
            }
        }

        self.input_buffer.clear();
        Ok(())
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
            View::CommandList => self.draw_command_list::<B>(f, chunks[1]),
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
        let status_color = if self.state.status_message.contains("Error") {
            Color::Red
        } else {
            Color::Green
        };

        let footer = Paragraph::new(vec![
            Line::from(vec![
                Span::styled("[Q]uit ", Style::default().fg(Color::DarkGray)),
                Span::styled("[1]Dashboard ", Style::default().fg(Color::Cyan)),
                Span::styled("[2]Devices ", Style::default().fg(Color::Cyan)),
                Span::styled("[3]Info ", Style::default().fg(Color::Cyan)),
                Span::styled("[4]Command ", Style::default().fg(Color::Cyan)),
                Span::styled("[5]Logs ", Style::default().fg(Color::Cyan)),
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

            // Get foreground app from state if available
            let foreground_app = self.state.devices
                .iter()
                .find(|d| d.id == device.id)
                .and_then(|d| d.foreground_app.as_ref())
                .map(|s| s.as_str())
                .unwrap_or("Unknown");

            let rows = vec![
                Row::new(vec!["Name", device.name.as_str()]),
                Row::new(vec!["Model", device.model.as_str()]),
                Row::new(vec!["Android", device.platform.as_str()]),
                Row::new(vec!["API Level", api_level.as_str()]),
                Row::new(vec!["Architecture", device.architecture.as_str()]),
                Row::new(vec!["Foreground App", foreground_app]),
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

        // Input field
        let device_name = self.state.devices
            .get(self.state.selected_device_index)
            .map(|d| d.name.as_str())
            .unwrap_or("No device");

        let input_text = format!("{}: {} > {}", device_name, "shell", self.input_buffer);

        let input = Paragraph::new(input_text)
            .block(
                Block::default()
                    .title("Command (Enter to execute, Backspace to delete, Tab for command list)")
                    .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::Cyan))
            )
            .style(Style::default().fg(Color::Green));

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

    fn draw_command_list<B: Backend>(&self, f: &mut Frame, area: ratatui::layout::Rect) {
        let chunks = Layout::default()
            .direction(Direction::Horizontal)
            .constraints([Constraint::Length(25), Constraint::Min(0)])
            .split(area);

        // Categories list (left panel)
        let categories = self.get_categories();
        let category_items: Vec<ListItem> = categories
            .iter()
            .enumerate()
            .map(|(i, cat)| {
                let is_selected = i == self.state.selected_category_index;
                let style = if is_selected {
                    Style::default().fg(Color::Black).bg(Color::Cyan)
                } else {
                    Style::default().fg(Color::White)
                };

                ListItem::new(Line::from(vec![
                    Span::styled(format!("  {}  ", cat.display_name()), style),
                ]))
            })
            .collect();

        let categories_list = List::new(category_items)
            .block(
                Block::default()
                    .title("Categories")
                    .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::Cyan))
            );

        f.render_widget(categories_list, chunks[0]);

        // Commands list (right panel)
        let commands = get_predefined_commands();
        let current_category = &categories[self.state.selected_category_index];
        let category_commands: Vec<_> = commands
            .iter()
            .filter(|c| c.category == *current_category)
            .collect();

        let command_items: Vec<ListItem> = category_commands
            .iter()
            .enumerate()
            .map(|(i, cmd)| {
                let is_selected = i == self.state.selected_command_index;
                let (bg_color, fg_color) = if is_selected {
                    (Color::Cyan, Color::Black)
                } else {
                    (Color::Reset, Color::White)
                };

                let sudo_indicator = if cmd.requires_sudo {
                    Span::styled("[sudo] ", Style::default().fg(Color::Red).add_modifier(Modifier::BOLD))
                } else {
                    Span::styled("       ", Style::default())
                };

                ListItem::new(Line::from(vec![
                    sudo_indicator,
                    Span::styled(&cmd.name, Style::default().fg(fg_color).bg(bg_color)),
                ]))
            })
            .collect();

        let commands_list = List::new(command_items)
            .block(
                Block::default()
                    .title(format!("Commands (↑↓ to select, Enter to execute, Esc/Tab to go back)")
                    )
                    .title_style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::Cyan))
            );

        f.render_widget(commands_list, chunks[1]);
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
