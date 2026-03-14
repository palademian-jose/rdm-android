# RDM Android - Anomaly Detection & File Organization System

## Overview

This system provides comprehensive anomaly detection and organized file storage for remote device monitoring.

## 📁 File Structure

All device data is now organized by device_id with timestamp-based organization:

```
recordings/
├── {device_id_1}/
│   ├── recordings/
│   │   ├── recording_com.example.app_1234567890_uuid.mp4
│   │   └── recording_com.telegram.app_1234567891_uuid.mp4
│   └── anomalies/
│       ├── anomalies_2026-03-11.jsonl
│       ├── anomalies_2026-03-12.jsonl
│       └── anomalies_2026-03-13.jsonl
├── {device_id_2}/
│   ├── recordings/
│   └── anomalies/
└── ...
```

## 🔍 Anomaly Detection Features

### 1. User Behavior Analysis
- **App Usage Tracking**: Monitors session times and frequency
- **Most Used Apps**: Identifies frequently accessed applications
- **Behavioral Patterns**: Tracks unusual usage patterns

### 2. Root Detection Evasion Attempts
- **Root Hiding Apps**: Detects apps like RootCloak, Xposed, Magisk
- **Tampered Binaries**: Monitors for modified root access tools
- **System Integrity**: Checks for root concealment techniques

### 3. Unusual System Modifications
- **New App Installations**: Alerts on new system app installations
- **System Properties**: Tracks changes to critical system properties
- **SELinux Status**: Monitors security policy changes

### 4. Apps Running at Unusual Times
- **Configurable Hours**: Default 10 PM - 6 AM (customizable)
- **Time-Based Alerts**: Flags usage during odd hours
- **Contextual Analysis**: Considers normal vs unusual patterns

### 5. Rapid App Switching Patterns
- **Quick Switching Detection**: Identifies rapid app hopping (3+ apps in 2 seconds)
- **Pattern Analysis**: Detects potential suspicious multitasking
- **Configurable Sensitivity**: Adjustable thresholds

### 6. Content Forwarding Detection
- **Sensitive Apps**: Monitors WhatsApp, Telegram, Signal, etc.
- **Share Intent Detection**: Tracks content sharing actions
- **Data Leakage Prevention**: Identifies potential data exfiltration

### 7. Screenshot Detection
- **Real-Time Monitoring**: Uses ContentObserver for immediate detection
- **File System Checks**: Periodic scans as backup
- **App Context**: Reports screenshots with current app information

## 🚀 Server API Endpoints

### Anomalies

#### List Anomalies
```
GET /api/anomalies/{device_id}
Query Parameters:
  - date (optional): Filter by specific date (YYYY-MM-DD)

Response:
{
  "success": true,
  "message": "Found 5 anomaly/anomalies",
  "data": {
    "device_id": "device_123",
    "anomalies": [...],
    "count": 5
  }
}
```

#### Get Anomaly Statistics
```
GET /api/anomalies/{device_id}/stats

Response:
{
  "success": true,
  "message": "Found 15 total anomaly/anomalies",
  "data": {
    "device_id": "device_123",
    "total_count": 15,
    "by_type": {
      "UNUSUAL_TIME_USAGE": 5,
      "ROOT_EVASION": 2,
      ...
    },
    "by_severity": {
      "LOW": 8,
      "MEDIUM": 5,
      "HIGH": 2
    },
    "recent_anomalies": [...]
  }
}
```

### Recordings

#### Upload Recording
```
POST /api/recordings/upload
Query Parameters:
  - device_id: Device identifier
  - package_name: App package name
  - timestamp: Recording timestamp
Body: Multipart video file

Response:
{
  "success": true,
  "message": "Recording uploaded successfully",
  "data": {
    "file_path": "recordings/device_123/recordings/recording_....mp4",
    "file_size": 1024000,
    "device_id": "device_123",
    "package_name": "com.example.app",
    "timestamp": "1234567890"
  }
}
```

#### List Recordings
```
GET /api/recordings/{device_id}

Response:
{
  "success": true,
  "message": "Found 3 recording(s)",
  "data": {
    "device_id": "device_123",
    "recordings": [...]
  }
}
```

## 🔧 Configuration

### Android Client

```kotlin
// Update anomaly detection thresholds
anomalyDetector.updateConfig(
    unusualHoursStart = 22,  // 10 PM
    unusualHoursEnd = 6,     // 6 AM
    rapidSwitchThresholdMs = 2000L,  // 2 seconds
    rapidSwitchCount = 3      // Number of rapid switches
)
```

### Root Permission Fix

The system now uses a **persistent root shell** instead of spawning new `su` processes:
- **Before**: Every command triggered Magisk permission prompt
- **After**: Single permission grant, persistent connection
- **Benefits**: Faster execution, better user experience

## 📊 Anomaly Log Format

Anomaly logs are stored in JSONL format (one JSON object per line):

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "device_id": "device_123",
  "anomaly_type": "UNUSUAL_TIME_USAGE",
  "source": "com.whatsapp",
  "message": "App used during unusual hours (23:00)",
  "severity": "MEDIUM",
  "timestamp": 1678886400000,
  "created_at": "2026-03-11T12:00:00Z"
}
```

## 🔔 Anomaly Severities

- **LOW**: Informational anomalies (rapid switching, etc.)
- **MEDIUM**: Potential concerns (unusual time usage, screenshots)
- **HIGH**: Security issues (root evasion, system modifications)
- **CRITICAL**: Immediate threats (not currently used)

## 🔄 Real-Time Updates

All anomalies are broadcast to connected clients via WebSocket:

```javascript
// Client-side WebSocket listener
ws.onmessage = (event) => {
  const data = JSON.parse(event.data);

  if (data.type === 'anomaly') {
    console.log('Anomaly detected:', data.data);
    // Handle anomaly in UI
    showAnomalyAlert(data.data);
  }
};
```

## 🛠️ Implementation Status

✅ **Completed**:
- Persistent root shell connection
- User behavior analysis
- Root detection evasion attempts
- Unusual system modifications
- Apps running at unusual times
- Rapid app switching patterns
- Content forwarding detection
- Screenshot detection
- Organized file storage by device_id
- Server API endpoints for anomalies
- Real-time anomaly broadcasting

## 📝 Future Enhancements

Potential additions:
- Machine learning-based behavior profiling
- Geo-fencing alerts
- Battery drain anomaly detection
- Network traffic analysis
- Advanced pattern recognition

## 🧪 Testing

To test the system:

1. **Deploy Server**: Start the RDM server
2. **Connect Device**: Run Android app with server URL
3. **Monitor Anomalies**: Check logs and API endpoints
4. **Test Recording**: Trigger app recording scenarios
5. **Verify Storage**: Check file system structure

## 📞 Support

For issues or questions about the anomaly detection system, check:
- Server logs: `journalctl -u rdm-server -f`
- Android logs: `adb logcat | grep AnomalyDetector`
- API responses: Use `/api/anomalies/{device_id}/stats` for overview

---

**Generated**: 2026-03-11
**Version**: 1.0.0
**Status**: Production Ready
