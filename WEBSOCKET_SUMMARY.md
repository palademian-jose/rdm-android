# Real-Time Command Responses via WebSocket

## Summary
Successfully implemented real-time command response delivery between TUI, server, and Android devices using WebSocket connections.

## What Changed

### Server Changes

1. **Client Connection Tracking** (`server/src/main.rs`)
   - Added `client_senders` to track TUI client connections
   - Methods: `add_client_sender()`, `remove_client_sender()`, `broadcast_to_clients()`

2. **Client WebSocket Route** (`server/src/main.rs`)
   - New route: `GET /ws/client?token={token}`
   - Handles WebSocket connections from TUI clients
   - Accepts optional authentication token

3. **Command Result Broadcasting** (`server/src/main.rs`)
   - When device sends `CommandResult`, server now:
     - Saves to database (existing behavior)
     - Broadcasts to all connected TUI clients
     - Includes: command_id, device_id, success, output, error, timestamp

### TUI Client Changes

1. **WebSocket Support** (`tui/src/client.rs`)
   - Added WebSocket connection capabilities
   - Tracks pending commands in HashMap
   - Uses channels for async communication

2. **Real-Time Command Execution** (`tui/src/client.rs`)
   - New `execute_command()` flow:
     - Queue command via REST API
     - Register pending command with channel
     - Send via WebSocket (optional, for tracking)
     - Wait for result via WebSocket
     - Timeout after 60 seconds
     - Return actual command output

3. **Background WebSocket Connection** (`tui/src/main.rs`)
   - Spawns background task on startup
   - Connects to `/ws/client`
   - Handles connection errors gracefully
   - Logs errors but continues operation

### Dependencies

**Server:**
- No new dependencies (uses existing tokio-tungstenite)

**TUI:**
- `futures-util = "0.3"` - For StreamExt, SinkExt traits

## How It Works

### Before
```
TUI → Server (REST): POST /api/commands
Server → TUI (REST): "Command queued" (immediate)
TUI: Shows "Command queued" (no real output)
```

### After
```
TUI → Server (REST): POST /api/commands
Server → TUI (REST): Command ID
TUI → Server (WebSocket): Connect /ws/client
Server → Device (WebSocket): Execute command
Device → Server (WebSocket): Command result
Server → All Clients (WebSocket): Broadcast result
TUI ← Server (WebSocket): Receive result
TUI: Displays actual command output
```

## Benefits

✅ **Real-Time Feedback** - Output appears as soon as command completes
✅ **Multiple Clients** - Several TUI instances can connect and receive results
✅ **Efficient** - No polling overhead, persistent connections
✅ **Timeout Handling** - 60-second timeout prevents hanging
✅ **Error Messages** - Captures and displays command errors

## Message Examples

### Command Request (TUI → Server)
```json
POST /api/devices/{device_id}/commands
{
  "command": "ls -la",
  "sudo": false
}

Response:
{
  "success": true,
  "data": {
    "command_id": "abc-123",
    "device_id": "dev-456",
    "command": "ls -la",
    "sudo": false
  }
}
```

### Command Result (Device → Server)
```json
{
  "type": "command_result",
  "id": "abc-123",
  "success": true,
  "output": "total 24\ndrwxr-xr-x ...",
  "error": null
}
```

### Broadcast to Clients (Server → TUI)
```json
{
  "type": "command_result",
  "command_id": "abc-123",
  "device_id": "dev-456",
  "success": true,
  "output": "total 24\ndrwxr-xr-x ...",
  "error": null,
  "timestamp": "2024-03-01T13:45:00Z"
}
```

## Testing

### Prerequisites
1. Server running on port 8443
2. TUI connects with valid credentials
3. Android device connected to server

### Test Steps

1. **Start Server**
   ```bash
   cd server
   cargo run
   ```

2. **Start TUI**
   ```bash
   cd tui
   cargo run
   ```

3. **Execute Command**
   - Press `4` to go to Command view
   - Type: `ls -la`
   - Press Enter
   - Observe: "Executing: ls -la on {device}"
   - Wait 2-5 seconds
   - Observe: Actual output appears

4. **Verify in Logs**
   ```bash
   # Server should show:
   # "Command result from {device} - ID: abc-123, success: true"
   # "Broadcasting to clients: {json_message}"
   ```

### Expected Results
- TUI shows "Executing: ..." immediately
- After command completes, TUI shows actual output (not just "Command queued")
- Total time: < 5 seconds for simple commands
- Complex commands (e.g., `pm list packages`) may take longer
- Timeout occurs after 60 seconds if no result

## Troubleshooting

### Issue: "WebSocket connection failed"

**Cause:** Server not running or firewall blocking WebSocket

**Solutions:**
1. Check server is running
2. Verify port 8443 is accessible
3. Check WebSocket URL (ws:// or wss://)
4. Enable debug logging: `RUST_LOG=debug cargo run`

### Issue: Commands timeout after 60 seconds

**Cause:** Device disconnected or crashed

**Solutions:**
1. Check device connection status
2. Verify device responds to pings
3. Check server logs for command execution errors
4. Try simple command first (e.g., `echo test`)

### Issue: Only one TUI receives results

**Cause:** Client registration not working properly

**Solutions:**
1. Check `client_senders` HashMap on server
2. Verify `broadcast_to_clients` is called
3. Enable debug logging on server
4. Restart server to clear state

## Future Enhancements

1. **Automatic Reconnection**
   - Reconnect WebSocket if connection drops
   - Implement backoff strategy

2. **Command Status Updates**
   - Show "executing..." while command runs
   - Update display as command progresses

3. **Fallback to Polling**
   - If WebSocket fails, poll for results
   - Seamless switching between methods

4. **Command History**
   - Fetch past commands via WebSocket
   - Re-display command results on reconnect

5. **Better Error Handling**
   - Distinguish network errors from command errors
   - Show appropriate messages

## Documentation

For detailed implementation information, see:
- `WEBSOCKET_IMPLEMENTATION.md` - Complete technical documentation
- `server/src/main.rs` - WebSocket handlers
- `tui/src/client.rs` - WebSocket client implementation

## Status

✅ Server compiles successfully
✅ TUI compiles successfully
✅ WebSocket connection implemented
✅ Command result broadcasting implemented
✅ Pending command tracking implemented
✅ Timeout handling implemented
✅ Error handling implemented

Ready for testing!
