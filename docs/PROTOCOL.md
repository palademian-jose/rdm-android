# RDM Protocol Documentation

## Communication Protocol

The Remote Device Manager uses WebSocket-based real-time communication with JWT authentication.

---

## Connection Flow

```
┌─────────────┐                    ┌──────────────┐                    ┌─────────────┐
│             │                    │              │                    │             │
│   Android   │  WebSocket (TLS)  │    Server    │  WebSocket (TLS)  │    TUI     │
│   System App │◄────────────────►│   (Relay)    │◄────────────────►│   Client    │
│             │                    │              │                    │             │
└─────────────┘                    └──────────────┘                    └─────────────┘
     Device                                Database + Auth
```

### Step 1: Android Device Connects

1. Android app starts on boot (BootReceiver)
2. App collects device information (DeviceInfoCollector)
3. App authenticates with server (username/password → JWT)
4. App establishes WebSocket connection to `/ws/device`
5. App sends `device_info` message
6. Server saves device info to database

### Step 2: TUI/Web Client Connects

1. User starts TUI or opens web interface
2. Client authenticates (username/password → JWT)
3. Client establishes WebSocket connection to `/ws`
4. Client authenticates with JWT
5. Client subscribes to device updates

### Step 3: Command Execution

1. User enters command in TUI
2. TUI sends `/api/commands` POST request to server
3. Server saves command to database (status: "queued")
4. Server sends `command` message via WebSocket to device
5. Device receives command, executes via RootExecutor
6. Device sends `command_result` message via WebSocket
7. Server updates command status to "completed"
8. Server sends result to TUI via WebSocket

### Step 4: Real-Time Monitoring

1. Android app sends periodic `heartbeat` messages (every 60s)
2. Server updates device `last_seen` timestamp
3. Server streams device stats to connected clients
4. TUI displays real-time updates

---

## Message Format

All WebSocket messages are JSON with a `type` field for routing.

### Generic Message Structure

```json
{
  "type": "message_type",
  "timestamp": 17081234567890,
  "data": {}
}
```

---

## Message Types

### Authentication

**From Client → Server:**
```json
{
  "type": "auth",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Response (if auth fails):**
```json
{
  "type": "error",
  "code": "AUTH_FAILED",
  "message": "Invalid or expired token"
}
```

---

### Device Information

**From Android → Server:**
```json
{
  "type": "device_info",
  "device_id": "device-uuid",
  "info": {
    "id": "device-uuid",
    "name": "Samsung Galaxy S21",
    "model": "SM-G991B",
    "android_version": "13",
    "api_level": 33,
    "architecture": "arm64-v8a",
    "serial": "abc123def456",
    "device_type": "phone",
    "screen_info": {
      "width": 1080,
      "height": 2400,
      "density": 3.0,
      "orientation": 0
    },
    "network_info": {
      "ip_address": "192.168.1.100",
      "mac_address": "AA:BB:CC:DD:EE:FF",
      "wifi_ssid": "MyWiFi",
      "network_type": "WIFI"
    },
    "storage_info": {
      "total": 256000000000,
      "used": 128000000000,
      "available": 128000000000,
      "percentage_used": 50.0
    },
    "memory_info": {
      "total": 8589934592,
      "used": 4294967296,
      "available": 4294967296,
      "percentage_used": 50.0
    },
    "cpu_info": {
      "cores": 8,
      "model": "Exynos 2100",
      "usage": 25.5
    },
    "battery_info": {
      "level": 85,
      "scale": 100,
      "percentage": 85.0,
      "status": "discharging",
      "health": "good",
      "temperature": 35.5
    },
    "installed_apps": [
      {
        "package_name": "com.example.app",
        "app_name": "Example App",
        "version_name": "1.0.0",
        "version_code": 1,
        "is_system": false,
        "installed_date": 1699000000000,
        "last_updated_date": 1699000000000,
        "icon_path": null
      }
    ],
    "user_data": {
      "google_accounts": ["user@gmail.com"],
      "email_accounts": ["user@example.com"],
      "phone_number": "+1234567890",
      "sim_info": {
        "carrier_name": "Carrier",
        "country_code": "US",
        "phone_number": "+1234567890",
        "network_operator": "310260"
      }
    },
    "created_at": "2026-02-01T00:00:00Z"
  }
}
```

**From Server → Clients:**
Same format, broadcasted to all subscribed clients.

---

### Command Execution

**From Server → Device:**
```json
{
  "type": "command",
  "id": "cmd-uuid",
  "command": "ls -la /system/app",
  "sudo": true
}
```

**From Device → Server:**
```json
{
  "type": "command_result",
  "id": "cmd-uuid",
  "success": true,
  "output": "drwxr-xr-x 3 root root 4096 Jan 1 00:00 app1\n...",
  "error": null
}
```

**Failed command:**
```json
{
  "type": "command_result",
  "id": "cmd-uuid",
  "success": false,
  "output": null,
  "error": "Permission denied"
}
```

---

### Logs

**From Device → Server:**
```json
{
  "type": "log",
  "device_id": "device-uuid",
  "level": "info",
  "message": "Command executed successfully",
  "data": "{\"command_id\": \"cmd-uuid\"}"
}
```

**Log Levels:**
- `debug`: Detailed debugging information
- `info`: General informational messages
- `warn`: Warning messages
- `error`: Error messages

---

### Heartbeat

**From Device → Server:**
```json
{
  "type": "heartbeat",
  "device_id": "device-uuid",
  "timestamp": 17081234567890
}
```

**From Server → Device:**
Server responds with pong (handled by WebSocket layer).

---

### Error

**From Server → Client:**
```json
{
  "type": "error",
  "code": "ERROR_CODE",
  "message": "Human-readable error message"
}
```

**Error Codes:**
- `AUTH_FAILED`: Invalid or expired token
- `DEVICE_NOT_FOUND`: Device ID doesn't exist
- `DEVICE_OFFLINE`: Device not connected
- `COMMAND_FAILED`: Command execution failed
- `PERMISSION_DENIED`: Insufficient permissions
- `RATE_LIMITED`: Too many requests

---

## State Management

### Server-Side State

```
Database (SQLite):
├── devices (table)
│   ├── id
│   ├── name
│   ├── model
│   ├── android_version
│   ├── device_info (JSON)
│   ├── user_data (JSON)
│   └── last_seen
├── commands (table)
│   ├── id
│   ├── device_id
│   ├── command
│   ├── output
│   ├── status
│   └── created_at
└── logs (table)
    ├── id
    ├── device_id
    ├── level
    ├── message
    └── timestamp

Memory:
├── WebSocket connections (HashMap)
│   ├── device_id → WebSocket session
│   └── client_id → WebSocket session
└── Device stats (for monitoring)
```

### Client-Side State

**TUI:**
```
AppState:
├── devices: Vec<Device>
├── selected_device_index: usize
├── current_view: View
├── command_history: Vec<String>
├── input_buffer: String
└── output_buffer: String
```

**Android App:**
```
Service State:
├── deviceId: String
├── isConnected: boolean
├── lastHeartbeat: Instant
├── commandQueue: Queue<Command>
└── retryCount: int
```

---

## Reconnection Logic

### Android App

```kotlin
fun scheduleReconnect() {
    coroutineScope.launch {
        delay(5000) // 5 seconds
        if (!isConnected) {
            connect()
            retryCount++
            if (retryCount > 5) {
                delay(60000) // 1 minute after 5 failed attempts
                retryCount = 0
            }
        }
    }
}
```

**Reconnection Strategies:**
1. Exponential backoff (5s, 10s, 20s, 40s)
2. Max 5 rapid attempts before long delay
3. Reconnect on network change
4. Auto-reconnect on boot

### TUI Client

```rust
// Auto-refresh every 5 seconds
if last_auto_refresh.elapsed() >= Duration::from_secs(5) {
    refresh_devices().await?;
    last_auto_refresh = Instant::now();
}
```

---

## Data Flow Diagrams

### Device Registration

```
Android App              Server              Database
    |                       |                    |
    |--(1) Auth----------->|                    |
    |<-(2) Token-----------|                    |
    |                       |                    |
    |--(3) Connect------->|                    |
    |                       |                    |
    |--(4) Device Info--->|--(5) Save-------->|
    |                       |<-(6) Success-------|
    |<-(7) Ack------------|                    |
    |                       |                    |
```

### Command Execution

```
TUI                    Server              Device              Database
    |                       |                    |                    |
    |--(1) POST command-->|                    |                    |
    |<-(2) Command ID----|                    |                    |
    |                       |--(3) Save cmd---->|                    |
    |                       |<-(4) Success------|                    |
    |                       |                    |                    |
    |<-(5) WS Notif-------|--(6) WS cmd------>|
    |                       |                    |--(7) Execute-->|
    |                       |                    |<-(8) Output---|
    |                       |<-(9) WS result----|                    |
    |<-(10) WS result-----|                    |                    |
    |                       |--(11) Update---->|                    |
    |<-(12) Display--------|                    |                    |
```

---

## Error Handling

### Connection Errors

**Client-Side:**
1. Detect connection loss (WebSocket close/timeout)
2. Log error
3. Schedule reconnection
4. Notify user

**Server-Side:**
1. Log connection error
2. Clean up resources
3. Update device status to "offline"

### Command Errors

**Device-Side:**
1. Try to execute command
2. Catch exceptions
3. Return error in `command_result`
4. Log to file

**Server-Side:**
1. Update command status to "failed"
2. Save error message
3. Notify clients

### Authentication Errors

**Client-Side:**
1. Receive 401 Unauthorized
2. Clear stored token
3. Prompt for credentials
4. Re-authenticate

**Server-Side:**
1. Log authentication failure
2. Return 401 with error message
3. Rate limit repeated failures

---

## Performance Considerations

### Message Size

- **Device info:** ~5-10 KB
- **Command:** ~1 KB
- **Command output:** ~10-100 KB (large outputs should be streamed)

### Connection Limits

- **Max devices:** 100 (configurable)
- **Max clients:** 50 (configurable)
- **Timeout:** 30 seconds (configurable)

### Database Optimization

- **Indexes:** On `device_id`, `status`, `timestamp`
- **Cleanup:** Delete logs older than 30 days
- **Archival:** Archive old commands to separate table

---

## Security Considerations

### Token Security

- **Expiration:** 24 hours
- **Storage:** Encrypted at rest
- **Transmission:** Over TLS
- **Revocation:** Implement token blacklist if needed

### Command Validation

- **Allow-list:** Only allow safe commands by default
- **Timeout:** 30 second timeout per command
- **Rate Limit:** Max 10 commands/minute per device
- **Sanitization:** Remove dangerous characters

### Data Privacy

- **Encryption:** Encrypt sensitive fields in database
- **Anonymization:** Remove device identifiers from logs
- **Retention:** Delete data after 30 days (configurable)
- **Access Control:** Role-based permissions

---

## Testing Protocol

### Unit Tests

- Message serialization/deserialization
- Command execution logic
- Authentication flow

### Integration Tests

- Full connection flow
- Command execution round-trip
- Reconnection scenarios

### Load Tests

- 100 devices connected simultaneously
- 1000 commands/minute
- Memory usage under load

---

## Future Enhancements

### Protocol v2 (Planned)

- **Binary protocol:** Protocol Buffers instead of JSON
- **Compression:** Gzip for large payloads
- **Streaming:** Real-time log streaming
- **File Transfer:** Direct file upload/download
- **Screen Sharing:** VNC-like remote control

---

## Version History

- **v1.0** (Current): Initial release with basic features
- **v0.9**: Beta testing
- **v0.1**: Initial prototype

---

## Support

For protocol issues or questions:
- GitHub Issues: [repository-url]
- Email: support@your-domain.com
- Documentation: [docs-url]
