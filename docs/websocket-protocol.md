# SLOL WebSocket Protocol

This document describes the WebSocket and HTTP routes confirmed in `websocket_server/server_websocket.py`.

## Android Client Route

```text
/ws/{client_id}
```

Android may pass a camera name through query parameters:

```text
ws://<SERVER_HOST>:8000/ws/<client_id>?camera_name=<camera_name>
```

The server also accepts `name` as a fallback query parameter for the camera name.

## Android Frame Messages

Android sends binary WebSocket messages containing JPEG image bytes.

The server decodes the bytes using OpenCV:

```text
cv2.imdecode(..., cv2.IMREAD_COLOR)
```

Bounding boxes returned by the server are absolute pixel coordinates relative to the decoded frame.

## Detection Response

The server replies with JSON messages of type `detections`:

```json
{
  "type": "detections",
  "client_id": "android_phone",
  "detections": [
    {
      "class_id": 2,
      "class_name": "head",
      "confidence": 0.76,
      "bbox": [120, 80, 220, 210]
    }
  ],
  "latency_ms": 12.9,
  "timestamp": 1784560000.123,
  "frame_count": 30,
  "recording": {
    "active": false,
    "scenario_id": null,
    "saved_frames": 0
  }
}
```

## Android Control Messages

Android can send JSON text messages over the same WebSocket.

### Start Recording

```json
{
  "type": "start_recording",
  "save_fps": 5
}
```

The server responds with:

```json
{
  "type": "recording_started",
  "status": "success",
  "scenario_id": "scenario_001",
  "saved_frames": 0,
  "save_fps": 5
}
```

### Stop Recording

```json
{
  "type": "stop_recording"
}
```

The server responds with:

```json
{
  "type": "recording_stopped",
  "status": "success",
  "scenario_id": "scenario_001",
  "saved_frames": 125
}
```

### Recording Status

```json
{
  "type": "recording_status"
}
```

The server responds with `recording_status` and the current recording state for that client.

### Control Errors

Invalid JSON returns `control_error`.

Unknown message types return `control_error`.

Recording failures return `recording_error`.

## Dashboard WebSocket Route

```text
/dashboard/ws
```

On connection, the dashboard receives a `hello` message. Runtime messages include dashboard event and detection updates produced by the main server.

## HTTP Routes

| Purpose | Route |
| --- | --- |
| Dashboard page | `/dashboard` |
| Dashboard static files | `/dashboard/static/...` |
| Dashboard output files | `/dashboard/outputs/...` |
| Dashboard info | `/dashboard/api/info` |
| Dashboard cameras | `/dashboard/api/cameras` |
| Dashboard detections | `/dashboard/api/detections` |
| Active dashboard event | `/dashboard/api/events/active` |
| Start dashboard event | `/dashboard/api/events/start` |
| Close dashboard event | `/dashboard/api/events/close` |
| Validate detection | `/dashboard/api/detections/{detection_id}/validate` |
| Server stats | `/stats` |
| Recording status | `/recording/status` |
| Last debug frame | `/debug/last-frame/{client_id}` |

The root route `/` attempts to serve `client_websocket.html`. That file is not part of the current public repository, so `/dashboard` is the documented browser UI entry point.
