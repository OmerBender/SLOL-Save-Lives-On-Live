# SLOL WebSocket Server

This folder contains the main SLOL cloud server.

The server receives live JPEG frames from Android field devices, runs YOLO inference, returns bounding boxes to the correct Android client, and publishes the same detection events to the Command Center dashboard.

For the full project overview, architecture, dataset description, and Android flow, see the root [`README.md`](../README.md).

---

## Server Responsibilities

* Accept Android WebSocket clients
* Receive live frames from Android / Insta360 X4 devices
* Run YOLOv8 body-parts detection
* Return detections to each Android device
* Serve the Command Center dashboard
* Publish detection events to connected dashboard browsers
* Run demo video feeds only when a dashboard event is active
* Save scenario data only when recording is explicitly requested

The Android app does not talk to a separate dashboard server. This server is the single live backend for inference, Android responses, and dashboard updates.

---

## Main Files

| Path | Purpose |
| ---- | ------- |
| `server_websocket.py` | Main FastAPI + WebSocket server |
| `dashboard_static/` | Browser UI for the Command Center dashboard |
| `requirements_unified.txt` | Recommended Python dependencies for the unified server |
| `requirements_websocket.txt` | Legacy/server dependency file |
| `benchmark_video_inference.py` | Benchmark script for video inference tests |
| `weights_private/README.md` | Instructions for private model weights |

Private model files such as `best.pt`, `best.engine`, ONNX files, demo videos, and collected datasets are intentionally excluded from Git.

---

## Model Runtime

The server supports the trained SLOL YOLO model in private runtime formats:

* `best.pt` for PyTorch inference
* `best.engine` for TensorRT inference on NVIDIA GPU machines

On Google Cloud, the deployment was validated on a G2 instance with an NVIDIA L4 GPU. TensorRT is preferred for live cloud inference when available.

The model detects:

* `hand`
* `arm`
* `head`
* `leg`
* `foot`
* `person`

---

## Install

Create and activate a Python virtual environment, then install dependencies:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements_unified.txt
```

Authorized users must place the approved model file in this folder, for example:

```text
websocket_server/best.pt
```

or:

```text
websocket_server/best.engine
```

---

## Run Locally

From this folder:

```bash
source .venv/bin/activate
python server_websocket.py
```

The server listens on:

```text
http://0.0.0.0:8000
```

---

## Android WebSocket Endpoint

Android clients connect to:

```text
ws://<SERVER_HOST>:8000/ws/<client_id>?camera_name=<camera_name>
```

Example format:

```text
ws://<SERVER_HOST>:8000/ws/android_phone?camera_name=Team+A+-+Rescue+Detector
```

Android sends binary JPEG frames. The server replies with detection JSON containing class IDs, class names, confidence scores, bounding boxes, latency, and frame count.

---

## Command Center Dashboard

The dashboard is served by the same FastAPI server:

```text
http://<SERVER_HOST>:8000/dashboard
```

Dashboard browsers connect to:

```text
ws://<SERVER_HOST>:8000/dashboard/ws
```

The dashboard displays:

* Demo feeds for project presentation
* Live Android / Insta360 detection events
* Detection frame, crop, class, confidence, timestamp, and count

Demo feeds are processed only after a dashboard event is started from the browser UI.

---

## Scenario Recording

Scenario recording is used only for controlled data collection and model improvement.

When Android sends a start-recording command, the server creates a scenario folder and saves:

```text
data_collection/<scenario_id>/
├── images/
├── labels/
├── annotated/
└── metadata.json
```

The clean frame is saved separately from the annotated frame. The YOLO label file is generated from the same inference result used for the Android and dashboard response.

Scenario recordings are excluded from Git.

---

## Health and Stats

Runtime stats are available at:

```text
http://<SERVER_HOST>:8000/stats
```

The response includes connected clients, processed frame counts, average latency, last frame shape, and recording state.

---

## Google Cloud Service

On the cloud VM, the server can run as a `systemd` service named `slol`:

```bash
sudo systemctl status slol
sudo systemctl restart slol
sudo systemctl stop slol
sudo journalctl -u slol -f
```

The service starts:

```text
/home/<cloud-user>/websocket_server/server_websocket.py
```

using the project Python environment.

---

## Notes

* Keep model weights private.
* Keep credentials out of Git.
* Do not commit generated datasets, debug frames, benchmark outputs, or demo videos.
* Use the root README for the complete end-to-end system explanation.
