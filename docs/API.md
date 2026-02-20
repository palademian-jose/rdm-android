# RDM API Documentation

## Overview

The Remote Device Manager API provides RESTful endpoints for managing Android devices remotely.

**Base URL:** `https://your-server.com:8443/api`

**Authentication:** JWT Bearer Token

---

## Authentication

All authenticated endpoints require a JWT token in the `Authorization` header:

```
Authorization: Bearer <token>
```

### Login

**POST** `/api/auth/login`

Authenticate with username and password.

**Request Body:**
```json
{
  "username": "admin",
  "password": "admin123"
}
```

**Response (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "device_id": "device-uuid-here"
}
```

**Error (401 Unauthorized):**
```json
{
  "error": "Invalid credentials"
}
```

---

## Devices

### Get All Devices

**GET** `/api/devices`

Retrieve all registered devices.

**Headers:**
```
Authorization: Bearer <token>
```

**Response (200 OK):**
```json
[
  {
    "id": "device-uuid",
    "name": "Samsung Galaxy S21",
    "model": "SM-G991B",
    "android_version": "13",
    "api_level": 33,
    "architecture": "arm64-v8a",
    "device_info": "{...}",
    "user_data": "{...}",
    "last_seen": "2026-02-17T10:30:00Z",
    "created_at": "2026-02-01T00:00:00Z"
  }
]
```

### Get Device Details

**GET** `/api/devices/{device_id}`

Retrieve detailed information about a specific device.

**Response (200 OK):**
```json
{
  "device": {
    "id": "device-uuid",
    "name": "Samsung Galaxy S21",
    "model": "SM-G991B",
    "android_version": "13",
    "api_level": 33,
    "architecture": "arm64-v8a",
    "last_seen": "2026-02-17T10:30:00Z",
    "created_at": "2026-02-01T00:00:00Z"
  },
  "detailed_info": {
    "cpu_info": {
      "cores": 8,
      "model": "Exynos 2100",
      "usage": 25.5
    },
    "memory_info": {
      "total": 8589934592,
      "available": 4294967296,
      "used": 4294967296,
      "percentage_used": 50.0
    },
    "storage_info": {
      "total": 256000000000,
      "available": 128000000000,
      "used": 128000000000,
      "percentage_used": 50.0
    },
    "battery_info": {
      "level": 85,
      "scale": 100,
      "percentage": 85.0,
      "status": "discharging"
    },
    "installed_apps": [
      {
        "package_name": "com.example.app",
        "app_name": "Example App",
        "version_name": "1.0.0",
        "version_code": 1,
        "is_system": false
      }
    ]
  },
  "user_data": {
    "google_accounts": ["user@gmail.com"],
    "email_accounts": ["user@example.com"]
  }
}
```

---

## Commands

### Execute Command

**POST** `/api/commands`

Execute a shell command on a device.

**Request Body:**
```json
{
  "device_id": "device-uuid",
  "command": "ls -la /system/app",
  "sudo": true
}
```

**Response (200 OK):**
```json
{
  "command_id": "cmd-uuid",
  "status": "queued"
}
```

**Error (404 Not Found):**
```json
{
  "error": "Device not connected"
}
```

---

## Logs

### Get Logs

**GET** `/api/logs?device_id={device_id}&limit=100&offset=0`

Retrieve device logs.

**Query Parameters:**
- `device_id` (optional): Filter by device ID
- `limit` (optional, default: 100): Number of logs to return
- `offset` (optional, default: 0): Pagination offset

**Response (200 OK):**
```json
[
  {
    "id": "log-uuid",
    "device_id": "device-uuid",
    "level": "info",
    "message": "Device connected",
    "data": null,
    "timestamp": "2026-02-17T10:30:00Z"
  }
]
```

---

## WebSocket

### Control Client WebSocket

**Endpoint:** `wss://your-server.com:8443/ws`

Connect as a control client (TUI/Web).

**Connect with token:**
```json
{
  "type": "auth",
  "token": "your-jwt-token"
}
```

**Subscribe to device updates:**
```json
{
  "type": "subscribe",
  "device_id": "device-uuid"
}
```

**Incoming messages:**
- `device_info`: Device information updates
- `log`: Log entries
- `command_result`: Command execution results

### Device WebSocket

**Endpoint:** `wss://your-server.com:8443/ws/device`

Connect from Android device.

**Authenticating:**
```json
{
  "type": "auth",
  "token": "device-jwt-token"
}
```

**Sending device info:**
```json
{
  "type": "device_info",
  "device_id": "device-uuid",
  "info": {
    "name": "Device Name",
    "model": "Model",
    ...
  }
}
```

**Sending log:**
```json
{
  "type": "log",
  "device_id": "device-uuid",
  "level": "info",
  "message": "Log message"
}
```

**Command received from server:**
```json
{
  "type": "command",
  "id": "cmd-uuid",
  "command": "ls -la",
  "sudo": false
}
```

**Command result:**
```json
{
  "type": "command_result",
  "id": "cmd-uuid",
  "success": true,
  "output": "command output",
  "error": null
}
```

---

## Health Check

**GET** `/health`

Check server health status.

**Response (200 OK):**
```json
{
  "status": "ok",
  "service": "rdm-server",
  "version": "0.1.0"
}
```

---

## Error Codes

| Status Code | Description |
|------------|-------------|
| 200 | Success |
| 400 | Bad Request |
| 401 | Unauthorized (invalid/expired token) |
| 403 | Forbidden (insufficient permissions) |
| 404 | Not Found |
| 500 | Internal Server Error |

---

## Rate Limiting

None currently implemented. Consider implementing in production.

---

## WebSocket Message Types

| Type | Direction | Description |
|-------|-----------|-------------|
| `auth` | Both | Authentication |
| `device_info` | Device → Server | Device information |
| `command` | Server → Device | Execute command |
| `command_result` | Device → Server | Command output |
| `log` | Device → Server | Log entry |
| `heartbeat` | Both | Keep-alive |
| `error` | Server → Client | Error message |

---

## Security Considerations

1. **TLS Required:** All communication must use HTTPS/WSS
2. **JWT Token Expiration:** Tokens expire after 24 hours
3. **Device Authentication:** Each device authenticates separately
4. **Command Logging:** All commands are logged
5. **Root Access:** Commands with `sudo: true` require root

---

## Examples

### cURL Examples

**Login:**
```bash
curl -X POST https://localhost:8443/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

**Get devices:**
```bash
curl https://localhost:8443/api/devices \
  -H "Authorization: Bearer your-token"
```

**Execute command:**
```bash
curl -X POST https://localhost:8443/api/commands \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{"device_id":"device-uuid","command":"ls -la","sudo":false}'
```

### WebSocket Example (JavaScript)

```javascript
const ws = new WebSocket('wss://localhost:8443/ws');

ws.onopen = () => {
  ws.send(JSON.stringify({
    type: 'auth',
    token: 'your-jwt-token'
  }));
};

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  console.log('Received:', message);
};
```
