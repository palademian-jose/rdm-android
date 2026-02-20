# RDM Quick Start Guide

Get your Remote Device Manager up and running in 15 minutes.

---

## Prerequisites Check

Before starting, ensure you have:

- [ ] **Rust 1.70+** - `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`
- [ ] **Rooted Android device** - Android 8.0+ (API 26+)
- [ ] **Android Studio** - For building the app (optional, use pre-built APK)
- [ ] **OpenSSL** - `sudo apt install openssl` (Debian/Ubuntu)

---

## Quick Setup (5 minutes)

### Step 1: Run Setup Script

```bash
cd /home/deimos/.openclaw/workspace/remote-device-manager
./setup.sh
```

This will:
- Build the Rust server and TUI
- Generate TLS certificates
- Create configuration files

### Step 2: Configure Server

Edit `server/.env`:

```bash
nano server/.env
```

**Update these values:**
```bash
RDM_HOST=0.0.0.0
RDM_PORT=8443

# IMPORTANT: Change these in production!
ADMIN_USERNAME=your-username
ADMIN_PASSWORD=your-secure-password
JWT_SECRET=generate-32-character-random-string-here
```

### Step 3: Start Server

```bash
cd server
../target/release/rdm-server
```

You should see:
```
🚀 RDM Server starting on 0.0.0.0:8443
```

### Step 4: Start TUI

In a new terminal:

```bash
cd tui
../target/release/rdm-tui
```

You should see the TUI dashboard:
```
📱 RDM Remote Device Manager
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Summary                Quick Actions
No devices connected   Press number to navigate:
                       1 - Dashboard
                       2 - Devices
                       3 - Device Info
                       4 - Execute Command
                       5 - View Logs
                       Q - Quit
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
0 device(s) connected - Last refresh: 14:30:00
[Q]uit [1]Dashboard [2]Devices [3]Info [4]Command [5]Logs
```

---

## Android App Setup (10 minutes)

### Option A: Build from Source (Recommended)

1. **Open Android Studio**
   ```bash
   cd android-app
   # Open in Android Studio
   ```

2. **Update Server URL**

   Edit `app/src/main/java/com/rdm/client/MainActivity.kt`:

   ```kotlin
   private val SERVER_URL = "wss://your-server.com:8443/ws/device"
   // Change to your actual server URL
   ```

3. **Build APK**
   - Build → Build Bundle(s) / APK(s) → Build APK(s)

4. **Install as System App**

   Enable ADB:
   ```bash
   adb devices
   ```

   Push APK:
   ```bash
   adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/
   ```

   Install as system app (requires root):
   ```bash
   adb shell
   su
   mount -o remount,rw /system
   mkdir -p /system/priv-app/RdmClient
   cp /sdcard/app-debug.apk /system/priv-app/RdmClient/RdmClient.apk
   chmod 644 /system/priv-app/RdmClient/RdmClient.apk
   reboot
   ```

### Option B: Use Pre-built APK (Faster)

If you have a pre-built APK:

```bash
# Install using ADB (requires root)
adb install app-debug.apk

# Then move to system partition
adb shell
su
mount -o remount,rw /system
mv /data/app/com.rdm.client*/base.apk /system/priv-app/RdmClient.apk
chmod 644 /system/priv-app/RdmClient.apk
reboot
```

---

## Test Connection

### 1. Verify Device Connection

In TUI, press `2` to view devices:

```
Devices (↑↓ to select)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
▶ Samsung Galaxy S21 - SM-G991B (device-uuid-abc123)
  Pixel 6 - Pixel 6 (device-uuid-def456)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

If you see your device, connection successful! 🎉

### 2. Test Command Execution

Press `4` to go to Command Execution:

```
Command (Enter to execute)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ls -la /system/app
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

Enter `ls -la /data/data` and press Enter.

You should see the output:
```
Output
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
drwxr-x--x ...
drwxr-x--x ...
...
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Common Issues

### Issue: "Connection refused"

**Cause:** Server not running or wrong port

**Solution:**
```bash
# Check if server is running
ps aux | grep rdm-server

# Check port
netstat -tlnp | grep 8443

# Restart server
cd server
../target/release/rdm-server
```

### Issue: "Invalid credentials"

**Cause:** Wrong username/password

**Solution:**
```bash
# Check credentials in tui/.env
cat tui/.env

# Reset admin password (edit server/.env)
nano server/.env
# Restart server after changes
```

### Issue: "TLS certificate error"

**Cause:** Self-signed certificate or expired cert

**Solution:**
```bash
# For development, accept self-signed cert
# In production, use valid certificates
cd certs
openssl req -x509 -newkey rsa:4096 \
    -keyout server.key -out server.crt -days 365 -nodes \
    -subj "/CN=your-server.com"
```

### Issue: "Device not connecting"

**Cause:** Wrong server URL, network issues, or app not installed as system app

**Solution:**
```bash
# Check server URL in app
adb shell dumpsys package com.rdm.client | grep version

# Check app logs
adb logcat | grep Rdm

# Reinstall as system app
# Follow steps in Option A above
```

### Issue: "Root access denied"

**Cause:** App doesn't have root permissions

**Solution:**
```bash
# Verify app is in /system/priv-app
adb shell
su
ls -la /system/priv-app/

# Check file permissions
chmod 644 /system/priv-app/RdmClient/RdmClient.apk

# Reboot device
reboot
```

---

## Next Steps

### For Development

1. **Read the code:**
   - `server/src/` - Rust server implementation
   - `android-app/app/src/` - Kotlin Android app
   - `tui/src/` - Rust TUI client

2. **Customize features:**
   - Add new commands
   - Modify UI layout
   - Add authentication methods

3. **Run tests:**
   ```bash
   # Server tests
   cd server
   cargo test

   # TUI tests
   cd tui
   cargo test
   ```

### For Production

1. **Security checklist:**
   - [ ] Change all default passwords
   - [ ] Use valid TLS certificates
   - [ ] Enable database encryption
   - [ ] Configure firewall
   - [ ] Set up VPN access
   - [ ] Implement rate limiting
   - [ ] Enable logging and monitoring

2. **Deployment:**
   - Deploy server to VPS/cloud
   - Configure domain and SSL
   - Set up backups
   - Configure monitoring alerts

3. **Documentation:**
   - Create user manual
   - Document custom commands
   - Setup incident response plan

---

## Useful Commands

### Server Management

```bash
# Start server
cd server
../target/release/rdm-server

# Stop server
pkill rdm-server

# View logs
tail -f logs/rdm-server.log

# Backup database
cp server/database/rdm.db server/database/rdm.db.backup
```

### TUI Commands

```
Q           - Quit
1           - Dashboard view
2           - Devices list
3           - Device info
4           - Execute command
5           - View logs
↑/↓         - Navigate device list
Enter        - Execute command / Submit
Backspace    - Delete character
```

### ADB Commands

```bash
# List devices
adb devices

# View device logs
adb logcat

# Install APK
adb install app-debug.apk

# Uninstall APK
adb uninstall com.rdm.client

# Shell into device
adb shell

# Reboot device
adb reboot
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                  RDM System                      │
├─────────────────────────────────────────────────────┤
│                                                     │
│  Android App        Rust Server        Rust TUI        │
│  (Kotlin)          (Actix)          (Ratatui)      │
│     │                   │                   │          │
│     └─────WebSocket────▶│◀────WebSocket────┘          │
│                           │                               │
│                           ▼                               │
│                      SQLite DB                          │
│                  (Logs + Commands)                     │
│                                                           │
└─────────────────────────────────────────────────────┘
```

---

## Resources

- **Full README:** `README.md`
- **API Documentation:** `docs/API.md`
- **Security Guidelines:** `docs/SECURITY.md`
- **Protocol Documentation:** `docs/PROTOCOL.md`

---

## Support

- **GitHub Issues:** [repository-url]
- **Documentation:** [docs-url]
- **Email:** support@your-domain.com

---

## License

MIT License - See LICENSE file for details.

**⚠️ Use only on devices you own!**

---

**Happy managing! 🚀**

If you run into issues, check the Common Issues section above or consult the full documentation.
