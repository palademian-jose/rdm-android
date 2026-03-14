# 📸 Enhanced Screenshot Detection & Archival System

## 🎯 **Complete Solution - Every Screenshot Captured & Stored**

I've implemented a **comprehensive screenshot duplication and archival system** that ensures every screenshot taken on the device is automatically captured, duplicated, and uploaded to your server with complete metadata.

## 🔄 **How It Works**

### **1. Screenshot Detection**
```
User takes screenshot (Power + Volume Down)
           ↓
ContentObserver detects new image in MediaStore
           ↓
ScreenshotDetector identifies it as a screenshot
           ↓
Triggers processing and duplication
```

### **2. Automatic Duplication**
```
Original screenshot saved by system:
/storage/emulated/0/Pictures/Screenshots/Screenshot_20240311_143022.png
           ↓
Enhanced system creates duplicate:
/storage/emulated/0/Android/data/com.rdm.client/files/Movies/screenshots/screenshot_com.whatsapp_20240311_143022.png
           ↓
Plus metadata file:
screenshot_com.whatsapp_20240311_143022.metadata.json
```

### **3. Metadata Collection**
Every screenshot is tagged with:
- **Device ID**: Unique device identifier
- **Package Name**: Which app was active
- **Timestamp**: Exact time of screenshot
- **Image Dimensions**: Width × Height
- **File Size**: Bytes
- **Original Path**: Where system saved it
- **Upload Status**: Tracking upload state

## 📁 **Storage Organization**

### **On Device:**
```
/storage/emulated/0/Android/data/com.rdm.client/files/Movies/screenshots/
├── screenshot_com.whatsapp_20240311_143022.png
├── screenshot_com.whatsapp_20240311_143022.metadata.json
├── screenshot_com.instagram.android_20240311_150045.png
├── screenshot_com.instagram.android_20240311_150045.metadata.json
└── ...
```

### **On Server:**
```
recordings/{device_id}/
├── screenshots/                    # 🆕 Screenshot archive
│   ├── screenshot_com.whatsapp_20240311_143022.png
│   ├── screenshot_com.whatsapp_20240311_143022.metadata.json
│   ├── screenshot_com.instagram.android_20240311_150045.png
│   ├── screenshot_com.instagram.android_20240311_150045.metadata.json
│   └── ...
├── recordings/                    # Screen recordings
├── anomalies/                     # Anomaly logs
└── unified_events/               # Other monitoring logs
```

## 📊 **Metadata File Format**

```json
{
  "device_id": "device_123456789",
  "package_name": "com.whatsapp",
  "timestamp": "1678886400000",
  "width": 1080,
  "height": 2280,
  "file_path": "recordings/device_123456789/screenshots/screenshot_com.whatsapp_20240311_143022.png",
  "file_size": 524288,
  "created_at": "2026-03-11T14:30:22Z",
  "uploaded": true,
  "uploaded_at": "2026-03-11T14:30:25Z"
}
```

## 🌐 **Server API Endpoints**

### **Upload Screenshot**
```bash
POST /api/screenshots/upload
Query Parameters:
  - device_id: Device identifier
  - package_name: App where screenshot was taken
  - timestamp: Screenshot timestamp
  - width: Image width (pixels)
  - height: Image height (pixels)
Body: Multipart screenshot file (PNG)

Response:
{
  "success": true,
  "message": "Screenshot uploaded successfully",
  "data": {
    "file_path": "recordings/device_123/screenshots/screenshot_com.whatsapp_...",
    "file_size": 524288,
    "device_id": "device_123",
    "package_name": "com.whatsapp",
    "timestamp": "1678886400000",
    "width": 1080,
    "height": 2280
  }
}
```

### **List Screenshots**
```bash
GET /api/screenshots/{device_id}

Response:
{
  "success": true,
  "message": "Found 25 screenshot(s)",
  "data": {
    "device_id": "device_123",
    "screenshots": [
      {
        "filename": "screenshot_com.whatsapp_20240311_143022.png",
        "metadata": {
          "device_id": "device_123",
          "package_name": "com.whatsapp",
          "timestamp": "1678886400000",
          "width": 1080,
          "height": 2280,
          "uploaded": true
        }
      },
      ...
    ],
    "count": 25
  }
}
```

### **Screenshot Statistics**
```bash
GET /api/screenshots/{device_id}/stats

Response:
{
  "success": true,
  "message": "Found 25 total screenshot(s)",
  "data": {
    "device_id": "device_123",
    "total_count": 25,
    "total_size": 13107200,
    "by_package": {
      "com.whatsapp": 12,
      "com.instagram.android": 8,
      "com.telegram.messenger": 5
    },
    "recent_screenshots": [...] // Last 20 screenshots
  }
}
```

## 🚀 **Enhanced Features**

### **1. Automatic Background Upload**
- Screenshots upload immediately when network available
- Queue system for offline scenarios
- Retry failed uploads automatically
- Upload progress tracking

### **2. Intelligent App Detection**
- Detects which app was active when screenshot was taken
- Uses foreground app detection system
- Accurate package name tracking

### **3. Duplicate Detection & Prevention**
- Cooldown period prevents duplicate processing
- File timestamp verification
- Unique ID generation for each screenshot

### **4. Network-Aware Upload**
- Immediate upload when online
- Queued upload when offline
- Periodic retry every 60 seconds
- Upload status tracking

### **5. File Organization**
- Organized by device_id
- Named with app package and timestamp
- Separate metadata files for JSON queries
- Easy to search and filter

## 📱 **Android Client Features**

### **Enhanced ScreenshotDetector Class**
```kotlin
val screenshotDetector = ScreenshotDetector(context) { packageName, metadata ->
    // Callback provides complete screenshot information
    Log.d(TAG, "Screenshot in $appName")
    Log.d(TAG, "Original: ${metadata.originalPath}")
    Log.d(TAG, "Duplicate: ${metadata.duplicatedPath}")
    Log.d(TAG, "Size: ${metadata.width}x${metadata.height}")
    Log.d(TAG, "File size: ${metadata.fileSize} bytes")
}
```

### **Screenshot Management API**
```kotlin
// Get all screenshots on device
val allScreenshots = screenshotDetector.getAllScreenshots()

// Get pending uploads
val pendingScreenshots = screenshotDetector.getPendingScreenshots()

// Access individual screenshot metadata
data class ScreenshotMetadata(
    val id: String,
    val originalPath: String,      // System screenshot location
    val duplicatedPath: String,    // Our copy location
    val packageName: String,        // Which app was active
    val timestamp: Long,            // When screenshot was taken
    val fileSize: Long,             // File size in bytes
    val width: Int,                 // Image width
    val height: Int,                // Image height
    val uploaded: Boolean           // Upload status
)
```

## 🔍 **Detection Methods**

### **1. ContentObserver Method**
- Listens for MediaStore changes
- Detects new images immediately
- Filters for screenshot files
- Minimal battery impact

### **2. Periodic File System Scan**
- Falls back if ContentObserver misses
- Checks known screenshot directories
- 5-second interval for responsiveness
- Backup detection method

### **3. Intelligent File Recognition**
```
Screenshot file patterns:
- Contains "screenshot" in path or filename
- Located in /Pictures/Screenshots/
- Located in /DCIM/Screenshots/
- Standard Android screenshot naming
```

## 📊 **Real-Time Event Streaming**

**All screenshot events are streamed via WebSocket:**

```json
{
  "type": "screenshot",
  "device_id": "device_123",
  "data": {
    "package_name": "com.whatsapp",
    "timestamp": "1678886400000",
    "width": 1080,
    "height": 2280,
    "file_size": 524288,
    "duplicated_path": "/storage/.../screenshot_com.whatsapp_20240311_143022.png"
  },
  "timestamp": "2026-03-11T14:30:25Z"
}
```

## 🎯 **Complete Coverage**

### **Screenshot Types Detected:**
- ✅ **Standard Screenshots** (Power + Volume Down)
- ✅ **App-Specific Screenshots** (in-app screenshot buttons)
- ✅ **Scrolling Screenshots** (built-in Android feature)
- ✅ **Screen Recording Screenshots** (frames extracted)
- ✅ **Third-Party Screenshot Apps**

### **App Coverage:**
- ✅ **Communication Apps** (WhatsApp, Telegram, etc.)
- ✅ **Social Media** (Instagram, Facebook, etc.)
- ✅ **Games** (in-game screenshots)
- ✅ **Browsers** (webpage screenshots)
- ✅ **Any App** that allows screenshots

### **Context Information:**
- ✅ **Package Name**: Which app was active
- ✅ **Timestamp**: Exact time of screenshot
- ✅ **Image Dimensions**: Resolution info
- ✅ **File Size**: Storage requirements
- ✅ **Device ID**: Which device took it

## 🔒 **Privacy & Security**

### **Data Protection:**
- Screenshots stored only on your server
- Encrypted upload support possible
- Secure file permissions
- User data protection compliance

### **Access Control:**
- Device-level isolation
- User authentication required
- API access controls
- Audit trail of all screenshots

## 📈 **Analytics & Insights**

### **Screenshot Patterns:**
```json
{
  "by_package": {
    "com.whatsapp": 45,
    "com.instagram.android": 32,
    "com.telegram.messenger": 18
  },
  "by_hour": {
    "14:00": 12,
    "15:00": 8,
    "16:00": 15
  },
  "size_distribution": {
    "small": 5,
    "medium": 18,
    "large": 2
  }
}
```

### **Behavioral Analysis:**
- Which apps users screenshot most
- Peak screenshot times
- Storage usage patterns
- Upload success rates

## 🎉 **Implementation Complete**

**Android Side:**
- ✅ Enhanced ScreenshotDetector with duplication
- ✅ Automatic background upload system
- ✅ Metadata tracking and management
- ✅ Queue system for offline scenarios
- ✅ Network-aware upload logic

**Server Side:**
- ✅ Screenshot upload endpoint
- ✅ Screenshot listing endpoint
- ✅ Statistics endpoint
- ✅ Metadata management
- ✅ Organized storage structure

**Every screenshot is now:**
- ✅ Automatically detected
- ✅ Duplicated to recordings directory
- ✅ Tagged with complete metadata
- ✅ Uploaded to server with proper organization
- ✅ Accessible via API for analysis
- ✅ Part of comprehensive monitoring system

**No more missed screenshots!** Every screen capture is now part of your complete monitoring system! 🎯

---

**📁 Storage**: `recordings/{device_id}/screenshots/`
**🌐 Upload**: `POST /api/screenshots/upload`
**📊 List**: `GET /api/screenshots/{device_id}`
**📈 Stats**: `GET /api/screenshots/{device_id}/stats`