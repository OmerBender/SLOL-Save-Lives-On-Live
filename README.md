# SLOL - Save Lives On Live

<p align="center">
  <img src=".github/assets/slol-brand-overview.jpeg" alt="SLOL Save Lives On Live" width="520">
</p>

<p align="center"><strong>Android + Insta360 X4 -> Cloud YOLO Inference -> Android Overlay + Command Center</strong></p>

SLOL is a real-time rescue-assistance platform that processes live camera frames with a custom YOLOv8s model to help identify visible signs of potentially trapped victims.

"SLOL - Save Lives On Live" is the project's product name. The system is designed to support life-saving decisions through live video analysis; it does not make emergency decisions by itself.

---

## Overview

The system combines computer vision, Android field capture, WebSocket communication, cloud GPU inference, and a browser-based Command Center.

A field operator connects an Android device to an Insta360 X4 camera over Wi-Fi. The Android application displays the live preview, sends selected JPEG frames to the cloud inference server, receives YOLO bounding boxes, and draws the detections on the device. The same server can also publish detection events to a Command Center dashboard.

Private model weights, local credentials, build outputs, scenario recordings, demo videos, and generated dashboard outputs are intentionally excluded from Git.

---

## Demonstration

The Android/Insta360 pipeline represents the real field-device flow. Demo video feeds in the dashboard are presentation feeds used to simulate additional teams; they are not physical live cameras.

---

## Main Features

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

Detailed architecture: [`docs/architecture.md`](docs/architecture.md)

WebSocket protocol: [`docs/websocket-protocol.md`](docs/websocket-protocol.md)

Deployment notes: [`docs/deployment.md`](docs/deployment.md)

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

## Model Performance

Best validation results achieved during training:

| Metric | Value |
| --- | ---: |
| Precision | 0.90 |
| Recall | 0.88 |
| mAP@50 | 0.93 |
| mAP@50-95 | 0.49 |

![Live Android detection result](.github/assets/android-live-detection.jpeg)

Model details documented in this repository:

| Item | Value |
| --- | --- |
| Architecture | YOLOv8s object detection |
| Inference image size | 960 |
| Classes | hand, arm, head, leg, foot, person |
| Weights | Private, excluded from Git |
| TensorRT engine | Supported when `best.engine` is present |
| Validation image count | Not documented |
| Validation instance count | Not documented |
| Epoch count | Not documented |
| Dataset version | Not documented |

---

## Dataset

The model was trained on a custom body-parts dataset created for disaster-response-style scenarios.

Dataset characteristics documented by the project owner:

* Annotated body-part instances
* Positive and negative samples
* Rescue-like and cluttered environments
* Body-part classes: hand, arm, head, leg, foot, person
* Background-only examples to reduce false positives
* Data derived from camera footage and selected viewing angles

Approximate class distribution:

| Class | Objects |
| --- | ---: |
| Hand | 1000+ |
| Arm | 700+ |
| Head | 800+ |
| Leg | 700+ |
| Foot | 600+ |
| Person | 500+ |

---

## Project Status

| Component | Status |
| --- | --- |
| YOLO body-part model | Working, weights private |
| Cloud inference server | Working |
| Android WebSocket client | Working |
| Android detection overlay | Working |
| Command Center dashboard | Working |
| Insta360 X4 live integration | Working in the Android app |
| Simulated dashboard feeds | Simulated for demonstration |
| Multi-camera physical deployment | In progress |
| Scenario recording | Working, used for data collection |
| Production authentication | Planned |
| HTTPS/WSS production proxy | Planned |
| Public model weights | Not included in the public repository |

---

## Repository Structure

```text
SLOL-Save-Lives-On-Live/
├── .github/
│   ├── assets/
│   └── workflows/
├── android/
│   ├── app/
│   ├── README.md
│   └── gradle.properties.example
├── docs/
│   ├── architecture.md
│   ├── deployment.md
│   └── websocket-protocol.md
├── tests/
├── websocket_server/
│   ├── server_websocket.py
│   ├── benchmark_video_inference.py
│   ├── dashboard_static/
│   ├── weights_private/
│   └── README.md
├── requirements.txt
├── CONTRIBUTING.md
├── LICENSE.md
└── README.md
```

The repository folder is named `websocket_server`. The current Google Cloud VM deployment also uses that folder name. It was not renamed to avoid breaking the existing working deployment.

---

## Quick Start Links

* Backend server: [`websocket_server/README.md`](websocket_server/README.md)
* Android app: [`android/README.md`](android/README.md)
* Architecture: [`docs/architecture.md`](docs/architecture.md)
* WebSocket protocol: [`docs/websocket-protocol.md`](docs/websocket-protocol.md)
* Deployment: [`docs/deployment.md`](docs/deployment.md)

Install Python server dependencies from the repository root:

```bash
pip install -r requirements.txt
```

---

## Authors

### Omer Bender

Computer Science Student  
Machine Learning & Computer Vision Developer

### Eithan Shaoat

Project Contributor

---

## License

No open-source license has currently been selected. The source code may be viewed for evaluation, but reuse, redistribution, or commercial deployment rights are not granted unless explicitly authorized by the project owner.

---

## Disclaimer

This project is intended for educational, research, and prototype rescue-assistance purposes only. It is not certified for operational emergency use.
