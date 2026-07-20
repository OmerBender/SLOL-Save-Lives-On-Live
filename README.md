# SLOL - Save Lives On Live

<p align="center">
  <img src=".github/assets/slol-brand-overview.jpeg" alt="SLOL Save Lives On Live" width="520">
</p>

<p align="center"><strong>Android + Insta360 X4 -> Cloud YOLO Inference -> Android Overlay + Command Center</strong></p>

SLOL is a real-time rescue-assistance platform that processes live camera frames with a custom YOLOv8s model to help identify visible signs of potentially trapped victims.

"SLOL - Save Lives On Live" is the project's product name. The system is designed to support life-saving decisions through live video analysis; it does not make emergency decisions by itself.

---

## Project Overview

The system combines computer vision, Android field capture, WebSocket communication, cloud GPU inference, and a browser-based Command Center.

A field operator connects an Android device to an Insta360 X4 camera over Wi-Fi. The Android application displays the live preview, sends selected JPEG frames to the cloud inference server, receives YOLO bounding boxes, and draws the detections on the device. The same server can also publish detection events to a Command Center dashboard.

The Android/Insta360 pipeline represents the real field-device flow. Demo video feeds in the dashboard are presentation feeds used to simulate additional teams; they are not physical live cameras.

Private model weights, local credentials, build outputs, scenario recordings, demo videos, and generated dashboard outputs are intentionally excluded from Git.

---

## Features

* Live Android/Insta360 X4 camera preview
* JPEG frame transmission over WebSocket
* Cloud-based YOLOv8s inference
* Bounding-box results returned to Android
* Browser-based Command Center dashboard
* Optional simulated dashboard feeds for presentation
* Controlled scenario recording for future dataset improvement
* TensorRT model support on NVIDIA GPU machines when an exported engine is available

---

## High-Level Architecture

```text
Insta360 X4
      |
      | Wi-Fi preview
      v
Android Field Application
      |
      | JPEG frames over WebSocket
      v
Google Cloud Inference Server
      |
      +--> YOLOv8s inference
      |
      +--> Detection results to Android
      |
      +--> Detection events to Command Center
      v
Android Overlay + Command Center Dashboard
```

---

## Technology Stack

| Area | Technologies |
| --- | --- |
| Computer vision | YOLOv8s, Ultralytics, OpenCV, NumPy |
| ML runtime | PyTorch, TensorRT |
| Backend | Python, FastAPI, WebSockets, Uvicorn |
| Mobile | Android, Kotlin, Insta360 Android SDK |
| Dashboard | HTML, CSS, JavaScript, WebSocket updates |
| Cloud | Google Cloud GPU VM, NVIDIA L4, Linux systemd |

---

## Dataset

The dataset contains approximately 2,000 images, including labeled images and additional background-only negative images used to reduce false positives.

Additional negative images are included in the dataset but are intentionally not counted as labeled images.

| Split | Images |
| --- | ---: |
| Train | 1401 |
| Validation | 338 |
| Total labeled images | 1739 |
| Approximate total images | ~2000 |
| Total objects | 4542 |

Class distribution:

| Class | Objects |
| --- | ---: |
| Hand | 1020 |
| Head | 860 |
| Arm | 785 |
| Leg | 728 |
| Foot | 613 |
| Person | 536 |
| **Total** | **4542** |

---

## Training Configuration

| Parameter | Value |
| --- | --- |
| Model | YOLOv8s |
| Input Resolution | 960 × 960 |
| Training Images | 1401 |
| Validation Images | 338 |
| Total Labeled Images | 1739 |
| Approximate Total Images | ~2000 |
| Total Objects | 4542 |

---

## Model Performance

Deployment model results:

| Metric | Value |
| --- | ---: |
| Model | YOLOv8s |
| Input Resolution | 960 × 960 |
| Precision | 0.890 |
| Recall | 0.892 |
| mAP@50 | 0.932 |
| mAP@50-95 | 0.482 |

![Live Android detection result](.github/assets/android-live-detection.jpeg)

Model weights are private and excluded from Git. The server uses `best.engine` when available and falls back to `best.pt`.

---

## Quick Start

Install Python server dependencies from the repository root:

```bash
pip install -r requirements.txt
```

Backend server:

```bash
cd websocket_server
python server_websocket.py
```

Dashboard:

```text
http://127.0.0.1:8000/dashboard
```

Android setup is documented in [`android/README.md`](android/README.md).

---

## Documentation

* Backend server: [`websocket_server/README.md`](websocket_server/README.md)
* Android app: [`android/README.md`](android/README.md)
* Architecture: [`docs/architecture.md`](docs/architecture.md)
* WebSocket protocol: [`docs/websocket-protocol.md`](docs/websocket-protocol.md)
* Deployment: [`docs/deployment.md`](docs/deployment.md)

---

## Disclaimer

This project is intended for educational, research, and prototype rescue-assistance purposes only. It is not certified for operational emergency use.
