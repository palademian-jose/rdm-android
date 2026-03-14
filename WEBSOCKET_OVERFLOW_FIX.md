# WebSocket Overflow Fix

## Problem
The Android client was getting `WebSocket error: Overflow` when connecting to the server, causing connections to fail repeatedly.

## Root Causes

1. **Multiple components sending messages simultaneously**
   - Anomaly detector events
   - Recording events
   - Unified monitoring manager
   - Periodic heartbeats
   - All sending immediately upon connection

2. **No rate limiting or backpressure**
   - Messages were sent immediately without checking connection state
   - No queue depth monitoring
   - Small buffers getting overwhelmed

3. **Rapid reconnection attempts**
   - Client reconnected every 5 seconds when disconnected
   - Could flood server with connection attempts

## Solutions Implemented

### Android Client (`WebSocketClient.kt`)

1. **Added message queue with backpressure**
   - Implemented a bounded channel (capacity: 100)
   - Messages are queued and sent at a controlled rate
   - Dropped messages logged when queue is full

2. **Added message processor**
   - Processes queue with 10ms delay between sends
   - Prevents flooding the WebSocket

3. **Exponential backoff for reconnections**
   - Starts at 5 seconds
   - Doubles each retry up to 60 seconds max
   - Prevents connection attempt flooding

4. **Increased timeouts**
   - Read/write timeouts: 10s → 30s
   - Connect timeout: 10s → 15s
   - Allows more time for slow connections

5. **Delayed initial sends**
   - 500ms delay before sending device info
   - Prevents overwhelming connection on startup

### Server (`main.rs`)

1. **Graceful overflow handling**
   - Detects "Overflow" errors specifically
   - Logs warning but doesn't immediately close connection
   - Continues processing instead of breaking

2. **Rate limiting on outgoing messages**
   - Added 10ms delay between sends
   - Prevents overwhelming client connections

3. **Better error classification**
   - Distinguishes between overflow and other errors
   - Overflow treated as temporary condition
   - Other errors still cause connection close

## Testing

1. Rebuild server: `cd server && cargo build --release`
2. Rebuild Android app: `cd android-app && ./gradlew assembleDebug`
3. Install and run the updated app
4. Monitor logs for successful connections

## Expected Results

- ✅ Connections succeed without overflow errors
- ✅ Graceful handling of message bursts
- ✅ Stable reconnection behavior
- ✅ Better resilience under load

## Monitoring

Watch for these log messages:
- `Message queue full, dropping message` - Client queue at capacity
- `WebSocket buffer overflow` - Server detected overflow (non-fatal)
- `Scheduling reconnect in X seconds` - Exponential backoff in action

## Future Improvements

1. Make queue size configurable
2. Add metrics for dropped messages
3. Implement persistent queue for offline scenarios
4. Add adaptive rate limiting based on network conditions
