# Remote Device Manager (RDM)

A comprehensive remote device management system for rooted Android devices with real-time monitoring, control, and data retrieval.

## Architecture

```
┌─────────────┐       WebSocket/TLS       ┌──────────────┐       WebSocket/TLS       ┌─────────────┐
│   Android    │ ◄──────────────────────► │   Rust Server │ ◄──────────────────────► │     TUI     │
│   System App │                           │   (relay)     │                           │  (Rust)     │
└─────────────┘                           └──────────────┘                           └─────────────┘
     Rooted                                      Auth                                    Terminal UI
   Device Info                                  Logs                                   Command Input
   User Data                                   History                                Monitor
   Root Commands                               Database                                Multi-device
                                              Web UI

                                                │
                                                │ HTTPS/TLS
                                                ▼
                                          ┌──────────────┐
                                          │   Web UI     │
                                          │  (React/Svelte)│
                                          └──────────────┘
                                              Browser
```

## Components

### 1. Android System App (Kotlin)
- System app installed in `/system/priv-app`
- Root access for full device control
- WebSocket connection to server
- Real-time device stats streaming
- Command execution
- File system access

### 2. Rust Server
- Actix-web + Tokio
- WebSocket relay between clients
- JWT authentication
- SQLite database for logs/history
- REST API for web interface
- Real-time WebSocket communication
- TLS encryption

### 3. Rust TUI (Terminal UI)
- Ratatui for TUI
- Multi-device management
- Real-time monitoring dashboard
- Command execution
- Log viewer
- Data export

### 4. Web UI (Optional)
- Browser-based control interface
- Same features as TUI
- REST API access

## Tech Stack

| Component | Tech |
|-----------|------|
| Android | Kotlin + Coroutines + OkHttp + WebSocket |
| Server | Rust + Actix-web + Tokio + SQLite + JWT |
| TUI | Rust + Ratatui + Crossterm |
| Database | SQLite |
| Auth | JWT (HS256) |
| Encryption | TLS 1.3 + WebSocket over TLS |

## Features

### App Selection & Screen Recording
The RDM system can automatically record the screen when specific apps are opened:

- **App Enumeration**: List all installed apps (system & user)
- **App Selection**: Choose which apps to monitor for recording
- **Automatic Recording**: Starts recording when selected app becomes foreground
- **Smart Triggers**: Detects app changes in real-time
- **Permission Handling**: Manages MediaProjection permission flow
- **File Storage**: Saves recordings to device storage (MP4 format)
- **Foreground Service**: Shows persistent notification during recording
- **Audio Capture**: Records audio along with video

**Use Cases:**
- Parental monitoring
- App usage tracking
- Bug reproduction
- Compliance monitoring
- User behavior analysis

**Technical Details:**
- Checks foreground app every 3 seconds
- Uses MediaProjection API for screen capture
- Records video (H.264, 8 Mbps, 30 FPS) + audio (AAC, 128 kbps)
- Requires user permission (MediaProjection dialog)
- Only records when app is configured to be monitored

See [APP_SCREEN_RECORDING.md](APP_SCREEN_RECORDING.md) for details.

### Foreground App Detection
The RDM system can detect which app is currently open/active on connected devices:

- **Automatic Monitoring**: Background monitoring detects app changes in real-time
- **Smart Updates**: Only sends data when app changes (efficient)
- **Package & Activity**: Shows both package name and specific activity
- **Integration**: Works seamlessly with existing device monitoring
- **Commands**: Quick TUI commands to check current app (`Get Foreground App`, `Get Activity Stack`)

**Use Cases:**
- Parental monitoring
- App usage analytics
- Remote troubleshooting
- Device state awareness

See [FOREGROUND_APP_DETECTION.md](FOREGROUND_APP_DETECTION.md) for details.

### Android App
- ✅ Full device info (hardware, software, network)
- ✅ User data (accounts, apps, storage)
- ✅ Root command execution
- ✅ Real-time stats streaming
- ✅ Foreground app detection
- ✅ Installed app listing & management
- ✅ Automatic screen recording on app open
- ✅ File system access
- ✅ Auto-reconnection
- ✅ Secure authentication

### Server
- ✅ WebSocket relay
- ✅ JWT authentication
- ✅ Log storage
- ✅ Command history
- ✅ Device management
- ✅ Web API
- ✅ TLS encryption

### TUI
- ✅ Multi-device dashboard
- ✅ Real-time monitoring
- ✅ Command execution
- ✅ Predefined command library with categories
- ✅ Log viewer
- ✅ Data export
- ✅ Keyboard navigation

## Setup

### Prerequisites
```bash
# Android
- Android Studio
- Rooted Android device
- ADB

# Server
- Rust 1.70+
- OpenSSL (for TLS certificates)

# TUI
- Rust 1.70+
- Linux/macOS/WSL terminal
```

### Quick Start

1. **Setup Server**
```bash
cd server
cargo run
```

2. **Build Android App**
```bash
cd android-app
./gradlew assembleDebug
```

3. **Install Android App (as system app)**
```bash
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/
adb shell
su
mount -o remount,rw /system
cp /sdcard/app-debug.apk /system/priv-app/RdmClient/RdmClient.apk
chmod 644 /system/priv-app/RdmClient/RdmClient.apk
reboot
```

4. **Run TUI**
```bash
cd tui
cargo run
```

## TUI Usage

The TUI provides a terminal-based interface for managing connected devices.

### Keyboard Shortcuts

| Key | Action |
|-----|--------|
| 1 | Dashboard |
| 2 | Devices List |
| 3 | Device Info |
| 4 | Execute Command |
| 5 | View Logs |
| Q / Esc | Quit (or go back from command list) |
| ↑↓ | Navigate devices/commands/logs |
| ←→ | Navigate command categories |
| PageUp/PageDown | Fast scroll (Logs) |
| Tab | Toggle command list / manual input |
| Enter | Execute command |
| Backspace | Delete character |

### Command List Feature

The TUI includes a library of predefined commands organized by categories:

**Categories:**
- **Device Info**: Get device properties, network info, processes, memory, CPU, storage, battery
- **App Management**: List apps, force stop, clear data, uninstall
- **System**: Reboot, set screen brightness
- **Connectivity**: Enable/disable WiFi and Bluetooth

To use the command list:
1. Navigate to Command view (key 4)
2. Press Tab to open the command list
3. Use ←→ to switch between categories
4. Use ↑↓ to select a command
5. Press Enter to execute
6. Press Tab or Esc to return to manual input mode

Commands marked with `[sudo]` require root access.

## Security

- ✅ TLS 1.3 encryption for all connections
- ✅ JWT authentication for all clients
- ✅ Device-specific API keys
- ✅ Encrypted credentials storage
- ✅ Root access validation
- ⚠️ **Use only on devices you own**

## Project Structure

```
remote-device-manager/
├── android-app/           # Kotlin Android system app
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/rdm/client/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── DeviceInfo.kt
│   │   │   │   ├── WebSocketClient.kt
│   │   │   │   └── RootExecutor.kt
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   └── gradlew
├── server/                 # Rust server
│   ├── src/
│   │   ├── main.rs
│   │   ├── auth.rs
│   │   ├── websocket.rs
│   │   ├── database.rs
│   │   └── api.rs
│   ├── database/
│   │   └── rdm.db
│   └── Cargo.toml
├── tui/                    # Rust TUI client
│   ├── src/
│   │   ├── main.rs
│   │   ├── ui.rs
│   │   ├── client.rs
│   │   └── monitor.rs
│   └── Cargo.toml
├── web/                    # Web UI (optional)
│   ├── index.html
│   ├── app.js
│   └── style.css
├── docs/
│   ├── API.md
│   ├── PROTOCOL.md
│   └── SECURITY.md
└── README.md
```

## License

MIT License - Use responsibly on devices you own.

## Disclaimer

This tool requires root access and extensive device permissions. Use only on devices you own and understand the security implications. The authors are not responsible for misuse.
