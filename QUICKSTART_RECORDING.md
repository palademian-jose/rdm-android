# Quick Start Guide: App Selection & Screen Recording

## What This Feature Does

When you select an app in the RDM client, the system will automatically start recording the screen when that app is opened and focused.

## Setup Requirements

### 1. Required Permissions

The app needs these permissions (granted during first use):

- **MediaProjection**: Screen capture permission (user must approve in system dialog)
- **RECORD_AUDIO**: Record audio while recording screen
- **Storage Access**: Save recording files to device

### 2. System App

The RDM client must be installed as a system app (`/system/priv-app/`) to:
- Run in background without restrictions
- Access device APIs without user interaction
- Auto-start on device boot

## How to Use

### Step 1: Connect to Server

1. Open RDM Client on the Android device
2. Enter server URL (e.g., `wss://your-server.com/ws/device`)
3. Toggle "Append Device ID" if needed
4. Click "Connect"
5. Verify status shows "✓ Connected"

### Step 2: Select Apps to Record

**Option A: Via Command Line (Advanced)**
```bash
# List all apps
adb shell pm list packages -3

# Add app to recording list (on device)
adb shell settings put global rdm_recording_apps "com.whatsapp,com.instagram"

# Remove from recording list
adb shell settings put global rdm_recording_apps "com.whatsapp"
```

**Option B: Via TUI**
1. Connect TUI to server
2. Navigate to Command view (key 4)
3. Press Tab for command list
4. Select "List All Apps" under App Management
5. Note the package names of apps you want to record
6. Manually update recording list (requires CLI or app UI)

### Step 3: Grant Recording Permission (First Time Only)

When you first open a monitored app:
1. System will show "Screen Recording" permission dialog
2. Tap "Start Now" or "Allow"
3. Recording will begin automatically
4. You'll see a persistent notification: "Recording: [App Name]"

### Step 4: Automatic Recording Works!

Now:
- When you open a monitored app → Recording starts automatically
- When you switch to another app → Recording stops
- When you go back to monitored app → Recording starts again
- When you close the app → Recording stops

## Viewing Recordings

Recordings are saved to:
```
/storage/emulated/0/Android/data/com.rdm.client/files/Movies/
```

### Access via ADB

```bash
# List recordings
adb shell ls -lh /sdcard/Android/data/com.rdm.client/files/Movies/

# Pull recording to computer
adb pull /sdcard/Android/data/com.rdm.client/files/Movies/recording_xxx.mp4 ./recording.mp4

# Delete all recordings
adb shell rm /sdcard/Android/data/com.rdm.client/files/Movies/*.mp4
```

### Access via Device File Manager

1. Open file manager on Android device
2. Navigate to: `Android/data/com.rdm.client/files/Movies/`
3. Find recording files (format: `recording_[package]_[timestamp].mp4`)
4. Share or move recordings as needed

## Managing Recording Apps

### Check Currently Monitored Apps

```bash
# Check foreground app (current app)
adb shell dumpsys activity activities | grep "mResumedActivity"

# Check via TUI (device info view shows foreground app)
```

### Stop Recording All Apps

```bash
# Clear recording list
adb shell settings put global rdm_recording_apps ""

# Or stop recording service
adb shell am force-stop com.rdm.client
```

## Examples

### Monitor WhatsApp
```bash
# Add to recording list
adb shell settings put global rdm_recording_apps "com.whatsapp"

# Open WhatsApp on device
# System asks for permission → Grant it
# Recording starts automatically
```

### Monitor Multiple Apps
```bash
# Add multiple apps (comma-separated)
adb shell settings put global rdm_recording_apps "com.whatsapp,com.instagram,com.facebook.katana"

# All three apps will trigger recording when opened
```

### Monitor System Apps
```bash
# Add system app
adb shell settings put global rdm_recording_apps "com.android.settings"

# Settings app will be recorded when opened
```

## Troubleshooting

### Recording Doesn't Start

**Check:**
1. Is app in recording list?
2. Is RDM client connected to server?
3. Is ForegroundAppMonitor running? (check logcat)
4. Has user granted MediaProjection permission?

**Solution:**
```bash
# Check if app is in list
adb shell dumpsys activity activities | grep "mResumedActivity"

# Restart RDM client
adb shell am force-stop com.rdm.client
adb shell am start -n com.rdm.client/.MainActivity
```

### No Output File Created

**Check:**
1. Does app have storage permissions?
2. Is there enough disk space?
3. Is ScreenRecordService running?

**Solution:**
```bash
# Check storage
adb shell df -h /sdcard/

# Check service
adb shell ps -A | grep ScreenRecord

# Restart app
adb shell am force-stop com.rdm.client
```

### Permission Dialog Doesn't Appear

**Cause:** MediaProjection permission was already granted

**Solution:** No action needed. Recording will start automatically.

### Recording Stops Immediately

**Cause:** Device locked or another app is recording

**Solution:**
1. Keep device unlocked
2. Stop other recording apps
3. Check logcat for errors

## Performance Tips

### Reduce Recording Quality

Edit `ScreenRecordService.kt`:
```kotlin
setVideoEncodingBitRate(4 * 1000 * 1000) // 4 Mbps (was 8 Mbps)
setVideoFrameRate(24) // 24 FPS (was 30)
```

### Save Space

```bash
# Delete old recordings (older than 7 days)
adb shell find /sdcard/Android/data/com.rdm.client/files/Movies/ -name "*.mp4" -mtime +7 -delete

# Compress recordings
adb shell ffmpeg -i recording.mp4 -vcodec libx264 -crf 28 compressed.mp4
```

### Extend Battery Life

- Reduce frame rate to 24 FPS
- Lower bitrate to 4 Mbps
- Disable audio if not needed
- Record only when necessary

## Security & Privacy

### ⚠️ Important Notes

1. **User Consent Required**: MediaProjection permission must be granted by user
2. **Visible Notification**: Recording shows persistent notification
3. **Local Storage Only**: Files stored on device, not uploaded automatically
4. **Sensitive Data**: Can capture personal information, passwords, conversations
5. **Legal Compliance**: May require disclosure to device users

### Best Practices

- Inform users before monitoring
- Provide easy way to disable recording
- Secure recordings (encryption, biometric access)
- Delete old recordings regularly
- Comply with local laws (GDPR, CCPA, etc.)

### Work Use

- Must have employee consent
- Check labor laws in your jurisdiction
- May violate privacy regulations without disclosure
- Consider using enterprise mobile device management (MDM) instead

## Advanced Usage

### Record on Schedule (Cron)

```bash
# Enable recording for specific hours
echo "0 9 * * * settings put global rdm_recording_enabled true" | crond
echo "0 17 * * * settings put global rdm_recording_enabled false" | crond
```

### Auto-Upload to Server

Modify `ScreenRecordService.kt` to upload after recording:
```kotlin
private fun uploadRecording(filePath: String) {
    // Upload to RDM server via WebSocket
    val file = File(filePath)
    val content = file.readBytes()
    val base64 = Base64.encodeToString(content, Base64.DEFAULT)

    webSocketClient.send(JSON.stringify(mapOf(
        "type" to "recording_upload",
        "filename" to file.name,
        "data" to base64
    )))
}
```

### Real-Time Streaming

Replace MediaRecorder with MediaCodec + WebSocket:
```kotlin
// Stream encoded H.264 frames via WebSocket
fun streamFrame(frame: ByteArray) {
    webSocketClient.sendFrame(frame)
}
```

## Support

### Logs

```bash
# Monitor recording logs
adb logcat | grep -E "(ScreenRecord|ForegroundApp|MediaProjection)"

# Save logs to file
adb logcat -d > rdm_logs.txt
```

### Issues

For bugs or feature requests, see:
- [APP_SCREEN_RECORDING.md](APP_SCREEN_RECORDING.md) - Full documentation
- [FOREGROUND_APP_DETECTION.md](FOREGROUND_APP_DETECTION.md) - App detection details
- GitHub Issues - Report problems here

## Summary

✅ List all installed apps
✅ Select apps to monitor
✅ Auto-record when app opens
✅ Stop recording when app closes
✅ Save to device storage
✅ Manage via CLI or app
✅ Works in background

⚠️ Requires user permission
⚠️ Shows persistent notification
⚠️ Uses battery and storage
⚠️ Captures sensitive data

Use responsibly and legally!
