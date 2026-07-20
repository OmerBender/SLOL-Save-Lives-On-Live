"""
WebSocket Server for real-time YOLO detection and scenario recording.

Protocol:
- Binary WebSocket messages: JPEG frames from Android.
- Text WebSocket messages: JSON control messages, e.g. start/stop recording.
"""

import asyncio
import json
import logging
import os
import shutil
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import torch
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from ultralytics import YOLO

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Multi-Camera YOLO Detection Server")
BASE_DIR = Path(__file__).resolve().parent
DEBUG_FRAMES_DIR = BASE_DIR / "debug_frames"
DEBUG_FRAMES_DIR.mkdir(exist_ok=True)

DATA_COLLECTION_DIR = BASE_DIR / "data_collection"

DASHBOARD_STATIC_DIR = BASE_DIR / "dashboard_static"
DASHBOARD_OUTPUTS_DIR = BASE_DIR / "dashboard_outputs"
DEMO_VIDEO_DIR = Path(os.getenv("RESCUE360_DEMO_VIDEO_DIR", str(BASE_DIR / "demo_videos"))).expanduser()
DASHBOARD_SLOTS = int(os.getenv("RESCUE360_DASHBOARD_SLOTS", "5"))
DASHBOARD_FRAME_INTERVAL_SEC = float(os.getenv("RESCUE360_DASHBOARD_FRAME_INTERVAL_SEC", "0.4"))
DASHBOARD_EVENT_HISTORY_LIMIT = int(os.getenv("RESCUE360_DASHBOARD_HISTORY_LIMIT", "500"))
DEMO_TARGET_FPS = int(os.getenv("RESCUE360_DEMO_TARGET_FPS", "8"))
DEMO_CAMERAS = [
    {"camera_id": "cam_1", "camera_name": "Team 1", "source": "testtt.mp4", "slot": 1},
    {"camera_id": "cam_2", "camera_name": "Team 2", "source": "testtt2.mp4", "slot": 2},
    {"camera_id": "cam_3", "camera_name": "Team 3", "source": "testtt3.mp4", "slot": 3},
    {"camera_id": "cam_4", "camera_name": "Team 4", "source": "testtt4.mp4", "slot": 4},
]
LIVE_ANDROID_SLOT = 5
DEMO_CAMERA_IDS = {str(camera["camera_id"]) for camera in DEMO_CAMERAS}
DASHBOARD_OUTPUTS_DIR.mkdir(parents=True, exist_ok=True)
SAVE_FPS = 5
MIN_FREE_SPACE_GB = 5
JPEG_QUALITY = 95
CONFIDENCE_THRESHOLD = 0.35
IOU_THRESHOLD = 0.45
IMAGE_SIZE = 960
DEFAULT_CLASSES = ["foot", "leg", "hand", "head", "arm", "person"]


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def safe_client_id(client_id: str) -> str:
    return "".join(ch if ch.isalnum() or ch in ("-", "_") else "_" for ch in client_id)


def ensure_jpeg_written(path: Path, image: np.ndarray) -> None:
    ok = cv2.imwrite(str(path), image, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
    if not ok:
        raise OSError(f"Failed writing image: {path}")


def yolo_label_line(detection: dict[str, Any], frame_width: int, frame_height: int) -> str:
    x1, y1, x2, y2 = detection["bbox"]
    x_center = ((x1 + x2) / 2) / frame_width
    y_center = ((y1 + y2) / 2) / frame_height
    width = (x2 - x1) / frame_width
    height = (y2 - y1) / frame_height

    values = [
        max(0.0, min(1.0, x_center)),
        max(0.0, min(1.0, y_center)),
        max(0.0, min(1.0, width)),
        max(0.0, min(1.0, height)),
    ]
    return f"{detection['class_id']} {values[0]:.6f} {values[1]:.6f} {values[2]:.6f} {values[3]:.6f}"


def draw_detections(frame: np.ndarray, detections: list[dict[str, Any]]) -> np.ndarray:
    annotated = frame.copy()
    colors = [
        (0, 255, 255),
        (255, 0, 255),
        (0, 255, 0),
        (255, 128, 0),
        (0, 128, 255),
        (255, 255, 0),
    ]

    for detection in detections:
        x1, y1, x2, y2 = detection["bbox"]
        color = colors[detection["class_id"] % len(colors)]
        label = f"{detection['class_name']} {int(detection['confidence'] * 100)}%"

        cv2.rectangle(annotated, (x1, y1), (x2, y2), color, 2)
        label_size, baseline = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)
        label_y = max(y1, label_size[1] + baseline + 4)
        cv2.rectangle(
            annotated,
            (x1, label_y - label_size[1] - baseline - 4),
            (x1 + label_size[0] + 6, label_y + baseline - 2),
            color,
            -1,
        )
        cv2.putText(
            annotated,
            label,
            (x1 + 3, label_y - 4),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.5,
            (0, 0, 0),
            1,
            cv2.LINE_AA,
        )

    return annotated


def run_yolo_detection(frame: np.ndarray) -> list[dict[str, Any]]:
    detections: list[dict[str, Any]] = []
    if model is None or frame is None or frame.size == 0:
        return detections

    results = model.predict(
        frame,
        imgsz=IMAGE_SIZE,
        conf=CONFIDENCE_THRESHOLD,
        iou=IOU_THRESHOLD,
        verbose=False,
    )

    for result in results:
        for box in result.boxes:
            cls_id = int(box.cls[0])
            confidence = float(box.conf[0])
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            detections.append(
                {
                    "class_id": cls_id,
                    "class_name": model.names[cls_id],
                    "confidence": round(confidence, 3),
                    "bbox": [int(x1), int(y1), int(x2), int(y2)],
                }
            )
    return detections


@dataclass
class RecordingSession:
    client_id: str
    scenario_id: str
    scenario_dir: Path
    save_fps: int
    model_name: str
    confidence_threshold: float
    classes: list[str]
    start_time: str = field(default_factory=utc_now_iso)
    end_time: str | None = None
    source_width: int | None = None
    source_height: int | None = None
    saved_frames: int = 0
    last_saved_at: float = 0.0
    active: bool = True

    @property
    def images_dir(self) -> Path:
        return self.scenario_dir / "images"

    @property
    def labels_dir(self) -> Path:
        return self.scenario_dir / "labels"

    @property
    def annotated_dir(self) -> Path:
        return self.scenario_dir / "annotated"

    @property
    def metadata_path(self) -> Path:
        return self.scenario_dir / "metadata.json"

    def metadata(self) -> dict[str, Any]:
        return {
            "scenario_id": self.scenario_id,
            "client_id": self.client_id,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "source_width": self.source_width,
            "source_height": self.source_height,
            "save_fps": self.save_fps,
            "model_name": self.model_name,
            "confidence_threshold": self.confidence_threshold,
            "iou_threshold": IOU_THRESHOLD,
            "imgsz": IMAGE_SIZE,
            "classes": self.classes,
            "saved_frames": self.saved_frames,
            "active": self.active,
        }


class RecordingManager:
    def __init__(self, base_dir: Path):
        self.base_dir = base_dir
        self.base_dir.mkdir(parents=True, exist_ok=True)
        self.active_sessions: dict[str, RecordingSession] = {}
        self.lock = threading.RLock()
        self.finalize_interrupted_scenarios()

    def finalize_interrupted_scenarios(self) -> None:
        for metadata_path in self.base_dir.glob("scenario_*/metadata.json"):
            try:
                metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            except Exception:
                continue
            if metadata.get("active") is True and metadata.get("end_time") is None:
                metadata["active"] = False
                metadata["end_time"] = utc_now_iso()
                metadata["interrupted"] = True
                metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
                logger.warning(f"[RECORDING RECOVERED] {metadata.get('scenario_id')} marked interrupted")

    def free_space_gb(self) -> float:
        usage = shutil.disk_usage(self.base_dir)
        return usage.free / (1024 ** 3)

    def _check_free_space(self) -> None:
        free_gb = self.free_space_gb()
        if free_gb < MIN_FREE_SPACE_GB:
            raise RuntimeError(f"Not enough disk space: {free_gb:.2f}GB free, minimum is {MIN_FREE_SPACE_GB}GB")

    def _next_scenario_id(self) -> str:
        max_index = 0
        for path in self.base_dir.glob("scenario_*"):
            suffix = path.name.removeprefix("scenario_")
            if suffix.isdigit():
                max_index = max(max_index, int(suffix))
        return f"scenario_{max_index + 1:03d}"

    def start(self, client_id: str, save_fps: int, model_name: str, classes: list[str]) -> dict[str, Any]:
        with self.lock:
            if client_id in self.active_sessions:
                session = self.active_sessions[client_id]
                raise RuntimeError(f"Recording already active: {session.scenario_id}")

            self._check_free_space()
            scenario_id = self._next_scenario_id()
            scenario_dir = self.base_dir / scenario_id
            images_dir = scenario_dir / "images"
            labels_dir = scenario_dir / "labels"
            annotated_dir = scenario_dir / "annotated"
            for directory in (images_dir, labels_dir, annotated_dir):
                directory.mkdir(parents=True, exist_ok=False)

            session = RecordingSession(
                client_id=client_id,
                scenario_id=scenario_id,
                scenario_dir=scenario_dir,
                save_fps=max(1, int(save_fps)),
                model_name=model_name,
                confidence_threshold=CONFIDENCE_THRESHOLD,
                classes=classes,
            )
            self.active_sessions[client_id] = session
            self._write_metadata(session)
            logger.info(f"[RECORDING STARTED] {scenario_id}")
            return {
                "status": "success",
                "scenario_id": scenario_id,
                "saved_frames": session.saved_frames,
                "save_fps": session.save_fps,
            }

    def stop(self, client_id: str, reason: str = "user") -> dict[str, Any]:
        with self.lock:
            session = self.active_sessions.pop(client_id, None)
            if session is None:
                raise RuntimeError("No active recording")

            session.active = False
            session.end_time = utc_now_iso()
            metadata = session.metadata()
            metadata["stop_reason"] = reason
            self._write_metadata(session, metadata)
            logger.info(f"[RECORDING STOPPED] {session.scenario_id}, total frames: {session.saved_frames}")
            return {
                "status": "success",
                "scenario_id": session.scenario_id,
                "saved_frames": session.saved_frames,
            }

    def stop_if_active(self, client_id: str, reason: str) -> None:
        try:
            self.stop(client_id, reason=reason)
        except RuntimeError:
            pass
        except Exception as exc:
            logger.error(f"Failed safely stopping recording for {client_id}: {exc}")

    def save_frame(self, client_id: str, frame: np.ndarray, detections: list[dict[str, Any]]) -> dict[str, Any] | None:
        with self.lock:
            session = self.active_sessions.get(client_id)
            if session is None or not session.active:
                return None

            now = time.monotonic()
            min_interval = 1.0 / max(1, session.save_fps)
            if session.last_saved_at and now - session.last_saved_at < min_interval:
                return None

            frame_height, frame_width = frame.shape[:2]
            if session.source_width is None or session.source_height is None:
                session.source_width = frame_width
                session.source_height = frame_height

            next_index = session.saved_frames + 1
            base_name = f"frame_{next_index:06d}"
            image_path = session.images_dir / f"{base_name}.jpg"
            label_path = session.labels_dir / f"{base_name}.txt"
            annotated_path = session.annotated_dir / f"{base_name}.jpg"

            try:
                ensure_jpeg_written(image_path, frame)
                label_lines = [yolo_label_line(detection, frame_width, frame_height) for detection in detections]
                label_path.write_text("\n".join(label_lines) + ("\n" if label_lines else ""), encoding="utf-8")
                ensure_jpeg_written(annotated_path, draw_detections(frame, detections))

                session.saved_frames = next_index
                session.last_saved_at = now
                self._write_metadata(session)
                logger.info(f"[FRAME SAVED] {session.scenario_id}/{base_name}")
                return {
                    "scenario_id": session.scenario_id,
                    "frame_name": base_name,
                    "saved_frames": session.saved_frames,
                }
            except Exception:
                session.active = False
                session.end_time = utc_now_iso()
                metadata = session.metadata()
                metadata["write_error"] = True
                self._write_metadata(session, metadata)
                self.active_sessions.pop(client_id, None)
                raise

    def _write_metadata(self, session: RecordingSession, metadata: dict[str, Any] | None = None) -> None:
        payload = metadata or session.metadata()
        session.metadata_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    def status_for_client(self, client_id: str) -> dict[str, Any]:
        with self.lock:
            session = self.active_sessions.get(client_id)
            return {
                "active": session is not None,
                "scenario_id": session.scenario_id if session else None,
                "saved_frames": session.saved_frames if session else 0,
            }


logger.info("Checking for GPU...")
if torch.backends.mps.is_available():
    logger.info("GPU (Metal) is available - using GPU")
    device = "mps"
elif torch.cuda.is_available():
    logger.info("GPU (CUDA) is available - using GPU")
    device = "cuda"
else:
    logger.info("GPU not available - using CPU")
    device = "cpu"

MODEL_PATH = "best.engine" if (BASE_DIR / "best.engine").exists() else "best.pt"
MODEL_FILE = BASE_DIR / MODEL_PATH
model = None
try:
    model = YOLO(str(MODEL_FILE))
    if MODEL_PATH.endswith(".pt"):
        model.to(device)
    logger.info(f"YOLO model loaded successfully: {MODEL_PATH} on {device.upper()}")
except Exception as exc:
    logger.error(f"Failed to load YOLO model: {exc}")


def get_model_classes() -> list[str]:
    if model is None:
        return DEFAULT_CLASSES
    names = getattr(model, "names", None)
    if isinstance(names, dict):
        return [names[index] for index in sorted(names)]
    if isinstance(names, list):
        return names
    return DEFAULT_CLASSES


recording_manager = RecordingManager(DATA_COLLECTION_DIR)


class ConnectionManager:
    def __init__(self):
        self.active_connections: dict[str, WebSocket] = {}
        self.stats: dict[str, dict[str, Any]] = {}

    async def connect(self, client_id: str, websocket: WebSocket):
        previous_websocket = self.active_connections.get(client_id)

        await websocket.accept()
        self.active_connections[client_id] = websocket
        self.stats[client_id] = {
            "frames_received": 0,
            "frames_processed": 0,
            "avg_latency_ms": 0,
            "connected_at": time.time(),
        }

        if previous_websocket is not None and previous_websocket is not websocket:
            try:
                await previous_websocket.close(code=1000, reason="Replaced by newer connection")
            except Exception:
                pass

        logger.info(f"Client '{client_id}' connected. Total clients: {len(self.active_connections)}")

    def is_current(self, client_id: str, websocket: WebSocket) -> bool:
        return self.active_connections.get(client_id) is websocket

    def disconnect(self, client_id: str, websocket: WebSocket | None = None):
        if websocket is not None and self.active_connections.get(client_id) is not websocket:
            logger.info(f"Ignoring stale disconnect for '{client_id}'")
            return

        if client_id in self.active_connections:
            del self.active_connections[client_id]
        if client_id in self.stats:
            del self.stats[client_id]
        recording_manager.stop_if_active(client_id, reason="websocket_disconnect")
        logger.info(f"Client '{client_id}' disconnected. Total clients: {len(self.active_connections)}")

    async def send_json(self, client_id: str, data: dict[str, Any], websocket: WebSocket | None = None):
        try:
            target_websocket = websocket or self.active_connections.get(client_id)
            if target_websocket is None:
                return
            if websocket is not None and not self.is_current(client_id, websocket):
                return
            await target_websocket.send_json(data)
        except Exception as exc:
            logger.error(f"Error sending to {client_id}: {exc}")

    def get_stats(self):
        stats = {
            "total_clients": len(self.active_connections),
            "clients": self.stats,
            "recording": {
                client_id: recording_manager.status_for_client(client_id)
                for client_id in self.active_connections
            },
        }
        return stats


manager = ConnectionManager()


class StartEventBody(BaseModel):
    event_name: str | None = Field(default=None, max_length=200)


class ValidateDetectionBody(BaseModel):
    status: str = Field(..., description="correct | incorrect | unsure")
    note: str | None = Field(default=None, max_length=2000)


class DashboardManager:
    def __init__(self):
        self.clients: set[WebSocket] = set()
        self.lock = asyncio.Lock()
        self.active_event: dict[str, Any] | None = None
        self.recent_detections: list[dict[str, Any]] = []
        self.live_cameras: dict[str, dict[str, Any]] = {}
        self.last_frame_urls: dict[str, str] = {}
        self.last_frame_sent_at: dict[str, float] = {}
        self.demo_workers: list[DemoVideoWorker] = []
        self.validation: dict[str, dict[str, Any]] = {}

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        async with self.lock:
            self.clients.add(websocket)
        logger.info(f"Dashboard client connected. Total dashboards: {len(self.clients)}")

    async def disconnect(self, websocket: WebSocket) -> None:
        async with self.lock:
            self.clients.discard(websocket)
        logger.info(f"Dashboard client disconnected. Total dashboards: {len(self.clients)}")

    async def broadcast(self, message: dict[str, Any]) -> None:
        async with self.lock:
            clients = list(self.clients)
        if not clients:
            return
        dead: list[WebSocket] = []
        for ws in clients:
            try:
                await ws.send_json(message)
            except Exception:
                dead.append(ws)
        if dead:
            async with self.lock:
                for ws in dead:
                    self.clients.discard(ws)

    def current_event(self) -> dict[str, Any] | None:
        return dict(self.active_event) if self.active_event else None

    def cameras(self) -> list[dict[str, Any]]:
        cameras: list[dict[str, Any]] = []
        if self.active_event is not None:
            for demo in DEMO_CAMERAS:
                camera_id = demo["camera_id"]
                source_path = self.demo_source_path(demo["source"])
                cameras.append(
                    {
                        "camera_id": camera_id,
                        "camera_name": demo["camera_name"],
                        "source": f"demo-video: {demo['source']}",
                        "slot": demo["slot"],
                        "configured": True,
                        "status": self.demo_status(camera_id, source_path),
                        "last_frame_url": self.last_frame_urls.get(camera_id),
                    }
                )
        for camera_id, camera in self.live_cameras.items():
            cameras.append(
                {
                    "camera_id": camera_id,
                    "camera_name": camera.get("camera_name") or camera_id,
                    "source": "cloud-live-websocket",
                    "slot": camera.get("slot", LIVE_ANDROID_SLOT),
                    "configured": False,
                    "status": "online" if manager.active_connections.get(camera_id) else "idle",
                    "last_frame_url": self.last_frame_urls.get(camera_id),
                }
            )
        return cameras

    def demo_source_path(self, source: str) -> Path:
        path = Path(source).expanduser()
        if path.is_absolute():
            return path
        return DEMO_VIDEO_DIR / source

    def demo_status(self, camera_id: str, source_path: Path) -> str:
        for worker in self.demo_workers:
            if worker.camera_id == camera_id:
                return worker.status
        return "offline" if not source_path.exists() else "idle"

    def info(self) -> dict[str, Any]:
        return {
            "version": "unified-1.0",
            "detector": "REAL_YOLO" if model is not None else "UNAVAILABLE",
            "weights_path": str(MODEL_FILE),
            "weights_exists": MODEL_FILE.exists(),
            "classes": get_model_classes(),
            "fps_target": DEMO_TARGET_FPS,
            "confidence_threshold": CONFIDENCE_THRESHOLD,
            "dashboard_slots": DASHBOARD_SLOTS,
            "active_event": self.current_event(),
            "demo_video_dir": str(DEMO_VIDEO_DIR),
        }

    async def start_event(self, event_name: str | None) -> dict[str, Any]:
        await self.close_event(broadcast=False)
        now = utc_now_iso()
        self.active_event = {
            "event_id": uuid.uuid4().hex[:12],
            "name": (event_name or "").strip() or None,
            "started_at": now,
            "closed_at": None,
            "status": "active",
        }
        self.recent_detections.clear()
        self.validation.clear()
        self.start_demo_workers()
        await self.broadcast({"type": "event_started", "data": self.current_event()})
        await self.broadcast({"type": "hello", "data": self.hello_payload()})
        logger.info(f"[DASHBOARD EVENT STARTED] {self.active_event['event_id']}")
        return self.current_event() or {}

    async def close_event(self, broadcast: bool = True) -> dict[str, Any] | None:
        self.stop_demo_workers()
        if self.active_event is None:
            return None
        closed = dict(self.active_event)
        closed["closed_at"] = utc_now_iso()
        closed["status"] = "closed"
        self.active_event = None
        self.recent_detections.clear()
        if broadcast:
            await self.broadcast({"type": "event_closed", "data": closed})
        logger.info(f"[DASHBOARD EVENT CLOSED] {closed['event_id']}")
        return closed

    def start_demo_workers(self) -> None:
        self.stop_demo_workers()
        for demo in DEMO_CAMERAS:
            source_path = self.demo_source_path(demo["source"])
            worker = DemoVideoWorker(
                camera_id=demo["camera_id"],
                camera_name=demo["camera_name"],
                source_path=source_path,
                target_fps=DEMO_TARGET_FPS,
            )
            worker.start()
            self.demo_workers.append(worker)
        logger.info(f"Started {len(self.demo_workers)} dashboard demo workers")

    def stop_demo_workers(self) -> None:
        for worker in self.demo_workers:
            worker.stop()
        for worker in self.demo_workers:
            worker.join(timeout=2.0)
        self.demo_workers.clear()

    def hello_payload(self) -> dict[str, Any]:
        return {
            "recent": list(self.recent_detections[-DASHBOARD_EVENT_HISTORY_LIMIT:]),
            "active_event": self.current_event(),
            "cameras": self.cameras(),
        }

    def register_live_camera(self, camera_id: str, camera_name: str | None) -> bool:
        is_new = camera_id not in self.live_cameras
        self.live_cameras[camera_id] = {
            "camera_id": camera_id,
            "camera_name": camera_name or camera_id,
            "slot": LIVE_ANDROID_SLOT if camera_id == "android_phone" else max(LIVE_ANDROID_SLOT, len(self.live_cameras) + LIVE_ANDROID_SLOT),
        }
        return is_new

    async def handle_processed_frame(
        self,
        camera_id: str,
        camera_name: str,
        frame: np.ndarray,
        detections: list[dict[str, Any]],
        timestamp: str | None = None,
        force_frame_update: bool = False,
    ) -> None:
        if frame is None or frame.size == 0:
            return
        is_new_camera = False
        if camera_id not in DEMO_CAMERA_IDS:
            is_new_camera = self.register_live_camera(camera_id, camera_name)
        if self.active_event is None:
            return
        if is_new_camera:
            await self.broadcast({"type": "hello", "data": self.hello_payload()})
        timestamp = timestamp or utc_now_iso()
        last_frame_url = await asyncio.to_thread(self.save_last_frame, camera_id, frame, timestamp)
        if last_frame_url:
            self.last_frame_urls[camera_id] = last_frame_url
            if force_frame_update or self.should_broadcast_frame(camera_id):
                await self.broadcast({
                    "type": "frame_update",
                    "data": {
                        "camera_id": camera_id,
                        "last_frame_url": last_frame_url,
                        "timestamp": timestamp,
                    },
                })

        if not detections:
            return

        frame_path, crop_paths = await asyncio.to_thread(
            self.save_detection_artifacts,
            camera_id,
            frame,
            detections,
            timestamp,
        )
        for index, detection in enumerate(detections):
            event = self.to_detection_event(
                camera_id=camera_id,
                camera_name=camera_name,
                detection=detection,
                timestamp=timestamp,
                frame_path=frame_path,
                crop_path=crop_paths[index] if index < len(crop_paths) else None,
            )
            self.recent_detections.append(event)
            if len(self.recent_detections) > DASHBOARD_EVENT_HISTORY_LIMIT:
                self.recent_detections = self.recent_detections[-DASHBOARD_EVENT_HISTORY_LIMIT:]
            await self.broadcast({"type": "detection", "data": event})

    def should_broadcast_frame(self, camera_id: str) -> bool:
        now = time.time()
        last = self.last_frame_sent_at.get(camera_id, 0.0)
        if now - last < DASHBOARD_FRAME_INTERVAL_SEC:
            return False
        self.last_frame_sent_at[camera_id] = now
        return True

    def camera_output_dir(self, camera_id: str) -> Path:
        safe_id = safe_client_id(camera_id)
        date_dir = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        path = DASHBOARD_OUTPUTS_DIR / date_dir / safe_id
        path.mkdir(parents=True, exist_ok=True)
        return path

    def save_last_frame(self, camera_id: str, frame: np.ndarray, timestamp: str) -> str | None:
        try:
            output_dir = self.camera_output_dir(camera_id)
            path = output_dir / "last.jpg"
            ensure_jpeg_written(path, frame)
            return self.to_dashboard_output_url(path, timestamp)
        except Exception as exc:
            logger.error(f"Failed saving dashboard last frame for {camera_id}: {exc}")
            return None

    def save_detection_artifacts(
        self,
        camera_id: str,
        frame: np.ndarray,
        detections: list[dict[str, Any]],
        timestamp: str,
    ) -> tuple[str | None, list[str | None]]:
        try:
            output_dir = self.camera_output_dir(camera_id)
            event_id = self.active_event["event_id"] if self.active_event else "no_event"
            stem = f"{event_id}_{int(time.time() * 1000)}"
            annotated_path = output_dir / f"{stem}_annotated.jpg"
            ensure_jpeg_written(annotated_path, draw_detections(frame, detections))
            crop_urls: list[str | None] = []
            height, width = frame.shape[:2]
            for idx, detection in enumerate(detections):
                x1, y1, x2, y2 = detection["bbox"]
                x1 = max(0, min(width, int(x1)))
                x2 = max(0, min(width, int(x2)))
                y1 = max(0, min(height, int(y1)))
                y2 = max(0, min(height, int(y2)))
                if x2 <= x1 or y2 <= y1:
                    crop_urls.append(None)
                    continue
                crop_path = output_dir / f"{stem}_{idx}_{detection['class_name']}.jpg"
                ensure_jpeg_written(crop_path, frame[y1:y2, x1:x2])
                crop_urls.append(self.to_dashboard_output_url(crop_path, timestamp))
            return self.to_dashboard_output_url(annotated_path, timestamp), crop_urls
        except Exception as exc:
            logger.error(f"Failed saving dashboard detection artifacts for {camera_id}: {exc}")
            return None, [None for _ in detections]

    def to_dashboard_output_url(self, path: Path, timestamp: str) -> str:
        rel = path.resolve().relative_to(DASHBOARD_OUTPUTS_DIR.resolve())
        return "/dashboard/outputs/" + str(rel).replace("\\", "/") + f"?t={int(time.time() * 1000)}"

    def to_detection_event(
        self,
        camera_id: str,
        camera_name: str,
        detection: dict[str, Any],
        timestamp: str,
        frame_path: str | None,
        crop_path: str | None,
    ) -> dict[str, Any]:
        x1, y1, x2, y2 = detection["bbox"]
        detection_id = uuid.uuid4().hex
        return {
            "detection_id": detection_id,
            "camera_id": camera_id,
            "camera_name": camera_name,
            "class_id": detection["class_id"],
            "class_name": detection["class_name"],
            "confidence": detection["confidence"],
            "bbox": {"x1": x1, "y1": y1, "x2": x2, "y2": y2},
            "timestamp": timestamp,
            "frame_path": frame_path,
            "crop_path": crop_path,
            "event_id": self.active_event["event_id"] if self.active_event else None,
            "validation_status": None,
            "validation_note": None,
            "validated_at": None,
        }

    async def validate_detection(self, detection_id: str, status: str, note: str | None = None) -> dict[str, Any]:
        valid_statuses = {"correct", "incorrect", "unsure"}
        if status not in valid_statuses:
            raise RuntimeError(f"status must be one of {sorted(valid_statuses)}")
        payload = {
            "detection_id": detection_id,
            "validation_status": status,
            "validation_note": note,
            "validated_at": utc_now_iso(),
        }
        self.validation[detection_id] = payload
        for detection in self.recent_detections:
            if detection.get("detection_id") == detection_id:
                detection.update(payload)
                break
        await self.broadcast({"type": "validation", "data": payload})
        return payload


class DemoVideoWorker(threading.Thread):
    def __init__(self, camera_id: str, camera_name: str, source_path: Path, target_fps: int):
        super().__init__(daemon=True, name=f"dashboard-demo-{camera_id}")
        self.camera_id = camera_id
        self.camera_name = camera_name
        self.source_path = source_path
        self.frame_interval = 1.0 / max(1, target_fps)
        self.stop_event = threading.Event()
        self.status = "offline"

    def stop(self) -> None:
        self.stop_event.set()

    def run(self) -> None:
        if not self.source_path.exists():
            logger.warning(f"[{self.camera_id}] demo video missing: {self.source_path}")
            self.status = "offline"
            return
        cap = cv2.VideoCapture(str(self.source_path))
        if not cap.isOpened():
            logger.warning(f"[{self.camera_id}] failed opening demo video: {self.source_path}")
            self.status = "offline"
            return
        self.status = "online"
        logger.info(f"[{self.camera_id}] demo video started: {self.source_path}")
        last = 0.0
        while not self.stop_event.is_set():
            ok, frame = cap.read()
            if not ok or frame is None:
                self.status = "completed"
                break
            now = time.time()
            if now - last < self.frame_interval:
                time.sleep(0.005)
                continue
            last = now
            detections = run_yolo_detection(frame)
            if dashboard_loop is None:
                continue
            asyncio.run_coroutine_threadsafe(
                dashboard_manager.handle_processed_frame(
                    camera_id=self.camera_id,
                    camera_name=self.camera_name,
                    frame=frame.copy(),
                    detections=[detection.copy() for detection in detections],
                    timestamp=utc_now_iso(),
                ),
                dashboard_loop,
            )
        cap.release()
        logger.info(f"[{self.camera_id}] demo video stopped ({self.status})")


# The event loop is set at startup from FastAPI. Demo worker threads use it
# to publish dashboard updates without running their own ASGI server.
dashboard_loop: asyncio.AbstractEventLoop | None = None
dashboard_manager = DashboardManager()


async def save_recording_frame(client_id: str, frame: np.ndarray, detections: list[dict[str, Any]]):
    try:
        result = await asyncio.to_thread(recording_manager.save_frame, client_id, frame, detections)
        if result and client_id in manager.stats:
            manager.stats[client_id]["recording"] = result
    except Exception as exc:
        logger.error(f"Recording write failed for {client_id}: {exc}")
        await manager.send_json(
            client_id,
            {
                "type": "recording_error",
                "status": "error",
                "error": str(exc),
            },
        )


async def handle_control_message(client_id: str, websocket: WebSocket, text: str):
    try:
        message = json.loads(text)
    except json.JSONDecodeError:
        await manager.send_json(client_id, {"type": "control_error", "status": "error", "error": "Invalid JSON"}, websocket)
        return

    message_type = message.get("type")
    logger.info(f"[CONTROL] {client_id}: {message_type}")
    try:
        if message_type == "start_recording":
            save_fps = int(message.get("save_fps", SAVE_FPS))
            result = recording_manager.start(
                client_id=client_id,
                save_fps=save_fps,
                model_name=MODEL_PATH,
                classes=get_model_classes(),
            )
            await manager.send_json(client_id, {"type": "recording_started", **result}, websocket)
        elif message_type == "stop_recording":
            result = recording_manager.stop(client_id, reason="user")
            await manager.send_json(client_id, {"type": "recording_stopped", **result}, websocket)
        elif message_type == "recording_status":
            await manager.send_json(
                client_id,
                {"type": "recording_status", "status": "success", **recording_manager.status_for_client(client_id)},
                websocket,
            )
        else:
            await manager.send_json(
                client_id,
                {"type": "control_error", "status": "error", "error": f"Unknown message type: {message_type}"},
                websocket,
            )
    except Exception as exc:
        logger.error(f"Control message failed for {client_id}: {exc}")
        await manager.send_json(
            client_id,
            {
                "type": "recording_error",
                "status": "error",
                "error": str(exc),
            },
            websocket,
        )


async def process_frame_message(client_id: str, websocket: WebSocket, frame_data: bytes):
    if not manager.is_current(client_id, websocket):
        logger.info(f"Stopping stale connection loop for '{client_id}'")
        return

    start_time = time.time()
    client_stats = manager.stats.get(client_id)
    if client_stats is None:
        logger.info(f"No stats for '{client_id}', stopping frame processing")
        return

    client_stats["frames_received"] += 1

    np_arr = np.frombuffer(frame_data, np.uint8)
    frame = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
    if frame is None:
        logger.warning(f"Failed to decode frame from {client_id}")
        return

    if client_stats["frames_received"] == 1 or client_stats["frames_received"] % 15 == 0:
        debug_path = DEBUG_FRAMES_DIR / f"debug_last_frame_{safe_client_id(client_id)}.jpg"
        cv2.imwrite(str(debug_path), frame)
        client_stats["last_debug_frame"] = f"/debug/last-frame/{client_id}"
        client_stats["last_frame_shape"] = list(frame.shape)

    detections = run_yolo_detection(frame)

    await save_recording_frame(
        client_id,
        frame.copy(),
        [detection.copy() for detection in detections],
    )

    inference_time = (time.time() - start_time) * 1000
    client_stats["frames_processed"] += 1
    client_stats["avg_latency_ms"] = round(inference_time, 2)

    recording_status = recording_manager.status_for_client(client_id)
    response = {
        "type": "detections",
        "client_id": client_id,
        "detections": detections,
        "latency_ms": round(inference_time, 2),
        "timestamp": time.time(),
        "frame_count": client_stats["frames_received"],
        "recording": recording_status,
    }
    await manager.send_json(client_id, response, websocket)

    camera_name = websocket.query_params.get("camera_name") or websocket.query_params.get("name") or client_id
    await dashboard_manager.handle_processed_frame(
        camera_id=client_id,
        camera_name=camera_name,
        frame=frame.copy(),
        detections=[detection.copy() for detection in detections],
        timestamp=utc_now_iso(),
    )

    if client_stats["frames_received"] % 30 == 0:
        logger.info(
            f"{client_id}: {len(detections)} objects, "
            f"latency={inference_time:.1f}ms, "
            f"frames={client_stats['frames_received']}, "
            f"recording={recording_status['active']}"
        )


if DASHBOARD_STATIC_DIR.exists():
    app.mount(
        "/dashboard/static",
        StaticFiles(directory=str(DASHBOARD_STATIC_DIR)),
        name="dashboard_static",
    )
app.mount(
    "/dashboard/outputs",
    StaticFiles(directory=str(DASHBOARD_OUTPUTS_DIR)),
    name="dashboard_outputs",
)


@app.on_event("startup")
async def dashboard_startup():
    global dashboard_loop
    dashboard_loop = asyncio.get_running_loop()


@app.on_event("shutdown")
async def dashboard_shutdown():
    await dashboard_manager.close_event(broadcast=False)


@app.get("/dashboard")
async def get_dashboard():
    index_path = DASHBOARD_STATIC_DIR / "index.html"
    if not index_path.exists():
        return JSONResponse({"error": "Dashboard static files are missing"}, status_code=404)
    return FileResponse(str(index_path), media_type="text/html")


@app.get("/dashboard/api/info")
async def dashboard_info():
    return dashboard_manager.info()


@app.get("/dashboard/api/cameras")
async def dashboard_cameras():
    return {"cameras": dashboard_manager.cameras()}


@app.get("/dashboard/api/detections")
async def dashboard_detections(limit: int = 500):
    return {"items": dashboard_manager.recent_detections[-int(limit):]}


@app.get("/dashboard/api/events/active")
async def dashboard_active_event():
    return {"event": dashboard_manager.current_event()}


@app.post("/dashboard/api/events/start")
async def dashboard_start_event(body: StartEventBody):
    return await dashboard_manager.start_event(body.event_name)


@app.post("/dashboard/api/events/close")
async def dashboard_close_event():
    closed = await dashboard_manager.close_event()
    if closed is None:
        return JSONResponse({"detail": "No active event"}, status_code=404)
    return closed


@app.post("/dashboard/api/detections/{detection_id}/validate")
async def dashboard_validate_detection(detection_id: str, body: ValidateDetectionBody):
    try:
        return await dashboard_manager.validate_detection(detection_id, body.status.strip().lower(), body.note)
    except RuntimeError as exc:
        return JSONResponse({"detail": str(exc)}, status_code=422)


@app.websocket("/dashboard/ws")
async def dashboard_websocket(websocket: WebSocket):
    await dashboard_manager.connect(websocket)
    try:
        await websocket.send_json({"type": "hello", "data": dashboard_manager.hello_payload()})
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        await dashboard_manager.disconnect(websocket)
    except Exception as exc:
        logger.error(f"Dashboard WebSocket error: {exc}")
        await dashboard_manager.disconnect(websocket)


@app.get("/")
async def get_root():
    with open("client_websocket.html", "r", encoding="utf-8") as file:
        return HTMLResponse(file.read())


@app.get("/stats")
async def get_stats():
    return manager.get_stats()


@app.get("/recording/status")
async def get_recording_status():
    return {
        "data_collection_dir": str(DATA_COLLECTION_DIR),
        "save_fps": SAVE_FPS,
        "min_free_space_gb": MIN_FREE_SPACE_GB,
        "free_space_gb": round(recording_manager.free_space_gb(), 2),
        "active_sessions": {
            client_id: recording_manager.status_for_client(client_id)
            for client_id in manager.active_connections
        },
    }


@app.get("/debug/last-frame/{client_id}")
async def get_last_debug_frame(client_id: str):
    path = DEBUG_FRAMES_DIR / f"debug_last_frame_{safe_client_id(client_id)}.jpg"
    if not path.exists():
        return {"error": "No debug frame saved yet", "client_id": client_id}
    return FileResponse(str(path), media_type="image/jpeg")


@app.websocket("/ws/{client_id}")
async def websocket_endpoint(websocket: WebSocket, client_id: str):
    await manager.connect(client_id, websocket)

    try:
        while True:
            message = await websocket.receive()

            if message.get("type") == "websocket.disconnect":
                raise WebSocketDisconnect()

            if "bytes" in message and message["bytes"] is not None:
                try:
                    await process_frame_message(client_id, websocket, message["bytes"])
                except Exception as exc:
                    logger.error(f"Error processing frame from {client_id}: {exc}")
                    continue
            elif "text" in message and message["text"] is not None:
                await handle_control_message(client_id, websocket, message["text"])

    except WebSocketDisconnect:
        manager.disconnect(client_id, websocket)
    except Exception as exc:
        logger.error(f"WebSocket error for {client_id}: {exc}")
        manager.disconnect(client_id, websocket)


if __name__ == "__main__":
    import uvicorn

    logger.info("Starting WebSocket Server...")
    logger.info("Access at: http://0.0.0.0:8000")
    logger.info("Stats at: http://0.0.0.0:8000/stats")

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=8000,
        log_level="info",
    )
