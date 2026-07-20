# SLOL WebSocket Server and Command Center

This folder contains the main cloud server for SLOL.

It provides:

* WebSocket endpoint for Android field devices
* YOLO body-parts inference
* JSON detection responses for Android overlays
* Browser-based Command Center dashboard
* Optional scenario recording for controlled data collection
* Optional demo video feeds for project presentation

## Endpoints

Android WebSocket:

```text
ws://<SERVER_HOST>:8000/ws/<client_id>?camera_name=<camera_name>
```

Command Center dashboard:

```text
http://<SERVER_HOST>:8000/dashboard
```

Local stats on the VM:

```bash
curl http://127.0.0.1:8000/stats
```

## Required Private Weights

The trained model is private and must not be committed to Git.

A local handoff package or disk-on-key may include the approved weights for authorized users. In Git, `.pt`, `.engine`, and exported model files are excluded by `.gitignore`.

Place an authorized model file here:

```text
websocket_server/best.pt
```

For cloud GPU deployment, an exported TensorRT engine can be used:

```text
websocket_server/best.engine
```

## Inference Settings

Current server settings:

```text
IMAGE_SIZE = 960
CONFIDENCE_THRESHOLD = 0.35
IOU_THRESHOLD = 0.45
```

The source video/frame resolution and the YOLO inference image size are not the same thing. The server receives frames from Android and runs YOLO with `imgsz=960`.

## Local Development

```bash
cd websocket_server
python -m venv venv
source venv/bin/activate
pip install -r requirements_websocket.txt
python server_websocket.py
```

Open:

```text
http://127.0.0.1:8000/dashboard
```

## Google Cloud Service

The cloud VM uses a `systemd` service named:

```text
slol.service
```

Useful commands:

```bash
sudo systemctl status slol
sudo systemctl restart slol
sudo systemctl stop slol
sudo systemctl start slol
journalctl -u slol -f
```

The current cloud runtime uses a Google Cloud G2 machine family instance with an NVIDIA L4 GPU.

## Scenario Recording

Scenario recording is intended only for controlled data collection and future model improvement.

When enabled by the Android app, data is saved under:

```text
websocket_server/data_collection/scenario_xxx/
├── images/
├── labels/
├── annotated/
└── metadata.json
```

This folder is runtime data and should not be committed to Git.

## Demo Videos

Demo videos are used only for Command Center presentation feeds. They are not included in Git by default.

Place local/cloud demo files under:

```text
websocket_server/demo_videos/
```

or configure the path with:

```bash
export RESCUE360_DEMO_VIDEO_DIR=<DEMO_VIDEO_DIR>
```

## Benchmark

The benchmark script can run inference on a video file without Android/network input:

```bash
python benchmark_video_inference.py   --video <VIDEO_PATH>   --model best.pt   --imgsz 960   --conf 0.35   --iou 0.45   --json-out benchmark.json
```

Use measured results from the actual runtime environment. Do not treat local CPU results as production performance.
