# SLOL WebSocket Server

This folder contains the main SLOL backend server.

The server receives live JPEG frames from Android field devices, runs YOLOv8s inference, returns bounding boxes to the correct Android client, serves the browser-based Command Center, and publishes detection events to connected dashboard browsers.

For the complete system overview, see the root [`README.md`](../README.md).

---

## Responsibilities

* Accept Android WebSocket clients
* Decode binary JPEG frames
* Run the private YOLOv8s model
* Return detection JSON to Android
* Serve the Command Center dashboard
* Publish dashboard detection events
* Process optional demo video feeds only when a dashboard event is active
* Save scenario data only when recording is explicitly requested

---

## Directory Structure

```text
websocket_server/
├── server_websocket.py
├── benchmark_video_inference.py
├── dashboard_static/
│   ├── index.html
│   ├── style.css
│   └── app.js
├── weights_private/
│   └── README.md
├── .env.example
└── README.md
```

Generated runtime folders such as `dashboard_outputs/`, `data_collection/`, `debug_frames/`, `demo_videos/`, and `runs/` are ignored by Git.

---

## Requirements

Install from the repository root:

```bash
pip install -r requirements.txt
```

---

## Environment Configuration

The server reads these optional environment variables:

| Variable | Default |
| --- | --- |
| `RESCUE360_DEMO_VIDEO_DIR` | `websocket_server/demo_videos` |
| `RESCUE360_DASHBOARD_SLOTS` | `5` |
| `RESCUE360_DASHBOARD_FRAME_INTERVAL_SEC` | `0.4` |
| `RESCUE360_DASHBOARD_HISTORY_LIMIT` | `500` |
| `RESCUE360_DEMO_TARGET_FPS` | `8` |

See `.env.example` for safe placeholder values. The server does not currently load `.env` files automatically; export variables in the shell or configure them in the service environment.

---

## Model Setup

The server loads model files from this folder:

```text
websocket_server/
```

Runtime selection:

1. If `best.engine` exists, the server uses it.
2. Otherwise, the server falls back to `best.pt`.

Model files are private and ignored by Git.

See [`weights_private/README.md`](weights_private/README.md) for handoff instructions.

---

## Run

From this folder:

```bash
python server_websocket.py
```

The server listens on port `8000`.

Dashboard:

```text
http://127.0.0.1:8000/dashboard
```

Stats:

```text
http://127.0.0.1:8000/stats
```

---

## Routes

| Purpose | Route |
| --- | --- |
| Android WebSocket | `/ws/{client_id}` |
| Dashboard | `/dashboard` |
| Dashboard WebSocket | `/dashboard/ws` |
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

Protocol details: [`../docs/websocket-protocol.md`](../docs/websocket-protocol.md)

---

## Scenario Recording

Scenario recording is used for controlled data collection. It is not part of the permanent operational Command Center flow.

When Android sends a `start_recording` control message, saved data is written under:

```text
websocket_server/data_collection/
```

Each scenario contains clean images, YOLO labels, annotated review images, and metadata.

---

## Google Cloud Notes

The current Google Cloud VM deployment uses the same `websocket_server` folder name and runs the server through a `systemd` service named `slol`.

See [`../docs/deployment.md`](../docs/deployment.md) for deployment and service commands.

---

## Troubleshooting

* If `/dashboard` returns 404, confirm `dashboard_static/index.html` exists.
* If Android connects but no detections appear, check `/stats` and the server logs.
* If model loading fails, confirm an authorized `best.engine` or `best.pt` exists in `websocket_server/`.
* If demo feeds do not appear, confirm the demo video directory and filenames match the values in `server_websocket.py`.
* If the systemd service is running, do not also run a manual server process on port `8000`.

---

## Security Notes

Do not commit model weights, credentials, cloud keys, local secrets, recordings, demo videos, or generated detection outputs.
