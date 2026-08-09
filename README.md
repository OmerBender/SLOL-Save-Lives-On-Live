# SLOL – Save Lives On Live

<p align="center">
  <img src=".github/assets/slol-brand-overview.jpeg" alt="SLOL Save Lives On Live" width="520">
</p>

<p align="center">
  <strong>Android + Insta360 X4 → Cloud YOLO Inference → Android Overlay + Command Center</strong>
</p>

SLOL is a real-time rescue-assistance platform that processes live camera frames using a custom YOLOv8s model to help identify visible signs of potentially trapped victims.

**SLOL – Save Lives On Live** is the project's product name. The system is designed to support rescue-team decision-making through live video analysis; it does not make emergency decisions independently.

---

## Project Overview

The system combines computer vision, Android field capture, WebSocket communication, cloud GPU inference, and a browser-based Command Center.

A field operator connects an Android device to an Insta360 X4 camera over Wi-Fi. The Android application displays the live preview, sends selected JPEG frames to the cloud inference server, receives YOLO bounding boxes, and draws the detections on the device. The same server can also publish detection events to the Command Center dashboard.

The Android/Insta360 pipeline represents the real field-device flow. Demo video feeds in the dashboard are presentation feeds used to simulate additional teams; they are not physical live cameras.

Private model weights, local credentials, build outputs, scenario recordings, demo videos, and generated dashboard outputs are intentionally excluded from Git.

---

## Features

- Live Android/Insta360 X4 camera preview
- JPEG frame transmission over WebSocket
- Cloud-based YOLOv8s inference
- Bounding-box results returned to Android
- Browser-based Command Center dashboard
- Optional simulated dashboard feeds for presentation
- Controlled scenario recording for future dataset improvement
- TensorRT model support on NVIDIA GPU machines when an exported engine is available

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
| Computer Vision | YOLOv8s, Ultralytics, OpenCV, NumPy |
| ML Runtime | PyTorch, TensorRT |
| Backend | Python, FastAPI, WebSockets, Uvicorn |
| Mobile | Android, Kotlin, Insta360 Android SDK |
| Dashboard | HTML, CSS, JavaScript, WebSocket updates |
| Cloud | Google Cloud GPU VM, NVIDIA L4, Linux systemd |

---

## Dataset

The training data was prepared from selected Insta360 video material and converted into a custom YOLO object-detection dataset.

```text
Insta360 video
  -> selected or cropped camera angle
  -> MP4 conversion
  -> frame extraction
  -> manual annotation
  -> YOLO dataset preparation
  -> YOLOv8s training
  -> trained weights: best.pt
  -> optional TensorRT export: best.engine
```

The dataset contains approximately 2,000 images, including labeled images and additional background-only negative images used to reduce false-positive detections.

Negative images are intentionally not counted as labeled images because they do not contain annotated objects.

### Dataset Summary

| Metric                      |       Value |
| --------------------------- | ----------: |
| Training images             |       2,268 |
| Validation images           |         431 |
| **Total annotated images**  |   **2,058** |
| **Total images**            |   **2,699** |
| **Total annotated objects** |   **5,345** |

### Class Distribution

| Class     | Objects |
| --------- | ------: |
| Hand      |   1,284 |
| Head      |   1,002 |
| Arm       |     955 |
| Leg       |     833 |
| Foot      |     712 |
| Person    |     559 |
| **Total** | **5,345** |

---

## Training Configuration

| Parameter               | Value     |
| ----------------------- | --------- |
| Model                   | YOLOv8s   |
| Input Resolution        | 960 × 960 |
| Training Images         | 2,268     |
| Validation Images       | 431       |
| Total Annotated Images  | 2,058     |
| Total Images            | 2,699     |
| Total Annotated Objects | 5,345     |

---

## Model Performance

Results for the model selected for deployment:

| Metric      | Value |
| ----------- | ----: |
| Precision   | 0.905 |
| Recall      | 0.907 |
| mAP@50      | 0.937 |
| mAP@50–95   | 0.498 |

### Detection Classes

| ID | Class |
| ---: | --- |
| 0 | hand |
| 1 | arm |
| 2 | head |
| 3 | leg |
| 4 | foot |
| 5 | person |

The model performs object detection and returns bounding boxes, class IDs, class names, and confidence scores. It does not return segmentation masks or pose keypoints.

![Live Android detection result](.github/assets/android-live-detection.jpeg)

![Command Center screenshot](.github/assets/צילום%20מסך%202026-08-03%20ב-14.34.53.png)

Model weights are private and excluded from Git. At runtime, the server uses `best.engine` when available and falls back to `best.pt`.

---

## Quick Start

### 1. Clone the Repository

```bash
git clone https://github.com/OmerBender/SLOL-Save-Lives-On-Live.git
cd SLOL-Save-Lives-On-Live
```

### 2. Install Server Dependencies

Install the Python server dependencies from the repository root:

```bash
pip install -r requirements.txt
```

### 3. Add the Private Model Weights

The private YOLO model weights are not included in the repository.

Place the required model files in the location described in:

[`websocket_server/README.md`](websocket_server/README.md)

### 4. Start the Backend Server

```bash
cd websocket_server
python server_websocket.py
```

### 5. Open the Dashboard

After the server starts, open:

```text
http://127.0.0.1:8000/dashboard
```

Android setup is documented in:

[`android/README.md`](android/README.md)

---

## Repository Validation

The repository includes lightweight structure and documentation validation:

```bash
python3 -m compileall websocket_server tests
python3 -m unittest discover -s tests
```

These checks do not run YOLO inference, WebSocket runtime behavior, GPU execution, or Android integration.

---

## Documentation

- [Backend Server](websocket_server/README.md)
- [Android Application](android/README.md)
- [System Architecture](docs/architecture.md)
- [WebSocket Protocol](docs/websocket-protocol.md)
- [Deployment](docs/deployment.md)

---

## Disclaimer

This project is intended for educational, research, and prototype rescue-assistance purposes only. It is not certified for operational emergency use.
