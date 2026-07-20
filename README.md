# SLOL - Save Lives On Live

SLOL is an end-to-end real-time rescue detection prototype.

The system connects an Android field device to an Insta360 X4 camera, sends selected live frames to a cloud WebSocket server, runs a custom YOLO body-parts detector, returns bounding boxes to the Android app, and publishes detection events to a browser-based Command Center dashboard.

## Repository Layout

```text
.
├── android/                 # Android field application
├── websocket_server/        # Cloud WebSocket server + YOLO inference + Command Center dashboard
└── README.md                # End-to-end project overview
```

## Runtime Architecture

```text
Insta360 X4 Camera
        |
        | Wi-Fi live preview
        v
Android Field App
        |
        | JPEG frames over WebSocket
        v
Google Cloud WebSocket Server
        |
        +-- YOLO inference
        +-- detections returned to Android
        +-- detection events published to Command Center
        v
Command Center Dashboard
```

## Model Weights

Model weights are private and must not be committed to Git.

A local handoff package or disk-on-key may include the approved weights for authorized users. In Git, `.pt`, `.engine`, and exported model files are excluded by `.gitignore`.

Authorized users should place approved weights in:

```text
websocket_server/best.pt
```

On the Google Cloud GPU VM, the model may also be exported to TensorRT:

```text
websocket_server/best.engine
```

Do not commit `.pt`, `.engine`, `.onnx`, or exported model files without explicit permission from the project owner.

## Cloud Runtime

The current cloud deployment uses a Google Cloud G2 machine family instance with an NVIDIA L4 GPU.

The production-style cloud server can be managed as a Linux `systemd` service named:

```text
slol.service
```

Common commands on the VM:

```bash
sudo systemctl status slol
sudo systemctl restart slol
journalctl -u slol -f
curl http://127.0.0.1:8000/stats
```

## Server

See:

```text
websocket_server/README.md
```

## Android

See:

```text
android/README.md
```

Before building Android, copy:

```text
android/gradle.properties.example
```

to:

```text
android/gradle.properties
```

and fill in the authorized Insta360 SDK Maven credentials and cloud WebSocket URL.

## Git Hygiene

The repository intentionally excludes:

* model weights
* private credentials
* Android local build files
* Python virtual environments
* dashboard output frames
* collected scenario data
* demo videos
* temporary archives
