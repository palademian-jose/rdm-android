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

### Android App
- ✅ Full device info (hardware, software, network)
- ✅ User data (accounts, apps, storage)
- ✅ Root command execution
- ✅ Real-time stats streaming
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
