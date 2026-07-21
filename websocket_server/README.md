# SLOL WebSocket Server

This folder contains the main SLOL backend server.

The server receives live JPEG frames from Android field devices, runs YOLOv8s inference, returns bounding boxes to the correct Android client, serves the browser-based Command Center, and publishes detection events to connected dashboard browsers.

For the complete system overview, see the root [`README.md`](../README.md).

---

## Responsibilities

- Accept Android WebSocket clients
- Decode binary JPEG frames
- Run the private YOLOv8s model
- Return detection JSON to Android
- Serve the Command Center dashboard
- Publish dashboard detection events
- Process optional demo video feeds only when a dashboard event is active
- Save scenario data only when recording is explicitly requested

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
│   └── README.md              # documentation only; runtime weights are not stored here
├── .env.example
└── README.md
```

Generated runtime folders such as `dashboard_outputs/`, `data_collection/`, `debug_frames/`, `demo_videos/`, and `runs/` are ignored by Git.

---

## Requirements

Install the Python dependencies from the repository root:

```bash
pip install -r requirements.txt
```

Using a virtual environment is recommended:

```bash
python3 -m venv rescue360-env
source rescue360-env/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
```

If an existing virtual environment is already configured, activate it before installing dependencies or starting the server.

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

See [`.env.example`](.env.example) for safe placeholder values.

The server does not currently load `.env` files automatically. Export the required variables in the shell or configure them in the `systemd` service environment.

---

## Model Setup

The runtime loads model files directly from:

```text
websocket_server/best.engine
websocket_server/best.pt
```

`weights_private/` contains documentation and handoff instructions only. It is not the runtime model directory.

Runtime selection confirmed by `server_websocket.py`:

1. If `best.engine` exists directly in `websocket_server/`, the server uses it.
2. Otherwise, the server falls back to `best.pt` directly in `websocket_server/`.

Model files are private and ignored by Git.

See [`weights_private/README.md`](weights_private/README.md) for handoff instructions.

At least one authorized runtime model file is required for inference. If neither model file is available, check the startup logs and the `/stats` endpoint before connecting Android clients.

---

## Run

From the `websocket_server` folder:

```bash
python server_websocket.py
```

By default, the server listens on port `8000`.

Verify that the server is running:

```bash
curl http://127.0.0.1:8000/stats
```

Dashboard:

```text
http://127.0.0.1:8000/dashboard
```

Stats:

```text
http://127.0.0.1:8000/stats
```

> On the Google Cloud VM, the server is normally managed by the `slol` systemd service. Do not start a second manual server process on port `8000` while the service is running.

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

Android WebSocket example:

```text
ws://<SERVER_HOST>:8000/ws/<CLIENT_ID>?camera_name=<CAMERA_NAME>
```

Protocol details: [`../docs/websocket-protocol.md`](../docs/websocket-protocol.md)

---

## Scenario Recording

Scenario recording is a controlled data-collection feature intended for testing and future dataset improvement. It is not enabled automatically during normal dashboard operation.

When Android sends a `start_recording` control message, saved data is written under:

```text
websocket_server/data_collection/
```

Each scenario contains:

- Clean source images
- YOLO label files
- Annotated review images
- Scenario metadata

Generated recording data is ignored by Git.

---

## Google Cloud Notes

The current Google Cloud VM deployment uses the same `websocket_server` folder name and runs the server through a `systemd` service named `slol`.

The service starts automatically when the VM boots, provided it has been enabled with `systemctl`.

See [`../docs/deployment.md`](../docs/deployment.md) for deployment instructions, service configuration, status checks, restart commands, and live logs.

Useful service commands:

```bash
sudo systemctl status slol
sudo systemctl restart slol
sudo systemctl stop slol
sudo systemctl start slol
sudo journalctl -u slol -f
```

---

## Troubleshooting

- If `/dashboard` returns `404`, confirm that `dashboard_static/index.html` exists.
- If Android connects but no detections appear, check `/stats` and the server logs.
- If model loading fails, confirm that an authorized `best.engine` or `best.pt` exists directly in `websocket_server/`.
- If demo feeds do not appear, confirm that a dashboard event is active and that the demo video directory and filenames match the values expected by `server_websocket.py`.
- If port `8000` is already in use, check whether the `slol` systemd service is already running.
- If the systemd service is running, do not also run a manual server process on port `8000`.

---

## Security Notes

- Do not commit model weights, credentials, cloud keys, local secrets, recordings, demo videos, or generated detection outputs.
- Do not publish the real public server IP address in repository documentation.
- Restrict Google Cloud firewall access to trusted clients whenever possible.
- Do not expose port `8000` publicly for long-running production use without additional protection.
- Use HTTPS and secure WebSockets (`WSS`) through a reverse proxy before production deployment.
- Add application-level authentication before exposing control endpoints to untrusted clients.
- Do not store Google Cloud service-account files or Android signing credentials in the repository.
