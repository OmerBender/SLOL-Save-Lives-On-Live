"""
Headless benchmark for YOLO video inference.

Use this on the local machine and on the cloud GPU machine with the same
video/model/settings, then compare average latency and processing FPS.
"""

import argparse
import json
import time
from pathlib import Path

import cv2
import torch
from ultralytics import YOLO


def choose_device() -> str:
    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def parse_args():
    parser = argparse.ArgumentParser(description="Benchmark YOLO inference on a video file.")
    parser.add_argument("--video", required=True, help="Path to the input video.")
    parser.add_argument("--model", default="best.pt", help="Path to YOLO model weights.")
    parser.add_argument("--imgsz", type=int, default=960, help="YOLO inference image size.")
    parser.add_argument("--conf", type=float, default=0.35, help="YOLO confidence threshold.")
    parser.add_argument("--iou", type=float, default=0.45, help="YOLO IoU threshold.")
    parser.add_argument("--max-frames", type=int, default=0, help="Stop after N frames. 0 = full video.")
    parser.add_argument("--warmup-frames", type=int, default=10, help="Frames excluded from timing summary.")
    parser.add_argument("--json-out", default="", help="Optional path for JSON summary.")
    return parser.parse_args()


def main():
    args = parse_args()
    video_path = Path(args.video).expanduser().resolve()
    model_path = Path(args.model).expanduser().resolve()

    if not video_path.exists():
        raise FileNotFoundError(f"Video not found: {video_path}")
    if not model_path.exists():
        raise FileNotFoundError(f"Model not found: {model_path}")

    device = choose_device()
    print("Rescue360 YOLO video benchmark")
    print(f"Device: {device}")
    print(f"Model: {model_path}")
    print(f"Video: {video_path}")
    print(f"Settings: imgsz={args.imgsz}, conf={args.conf}, iou={args.iou}")

    model = YOLO(str(model_path))
    model.to(device)

    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise RuntimeError(f"Could not open video: {video_path}")

    source_fps = cap.get(cv2.CAP_PROP_FPS)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

    print(f"Video info: {width}x{height}, fps={source_fps:.3f}, frames={total_frames}")

    frame_count = 0
    timed_frames = 0
    total_inference_ms = 0.0
    total_detections = 0
    started_at = time.time()

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        frame_count += 1
        inference_started_at = time.time()
        results = model.predict(
            frame,
            imgsz=args.imgsz,
            conf=args.conf,
            iou=args.iou,
            verbose=False,
        )
        inference_ms = (time.time() - inference_started_at) * 1000
        detections_in_frame = sum(len(result.boxes) for result in results)

        if frame_count > args.warmup_frames:
            timed_frames += 1
            total_inference_ms += inference_ms
            total_detections += detections_in_frame

        if frame_count % 30 == 0:
            elapsed = time.time() - started_at
            avg_ms = total_inference_ms / timed_frames if timed_frames else 0
            print(
                f"Frame {frame_count}/{total_frames} | "
                f"det={detections_in_frame} | "
                f"last={inference_ms:.1f}ms | "
                f"avg={avg_ms:.1f}ms | "
                f"wall_fps={frame_count / elapsed:.1f}"
            )

        if args.max_frames and frame_count >= args.max_frames:
            break

    cap.release()

    elapsed_total = time.time() - started_at
    avg_latency_ms = total_inference_ms / timed_frames if timed_frames else 0
    inference_fps = 1000 / avg_latency_ms if avg_latency_ms else 0
    wall_fps = frame_count / elapsed_total if elapsed_total else 0

    summary = {
        "device": device,
        "video": str(video_path),
        "model": str(model_path),
        "source_width": width,
        "source_height": height,
        "source_fps": source_fps,
        "frames_read": frame_count,
        "timed_frames": timed_frames,
        "warmup_frames": args.warmup_frames,
        "imgsz": args.imgsz,
        "conf": args.conf,
        "iou": args.iou,
        "elapsed_total_sec": round(elapsed_total, 3),
        "avg_latency_ms": round(avg_latency_ms, 3),
        "inference_fps": round(inference_fps, 3),
        "wall_fps": round(wall_fps, 3),
        "total_detections": total_detections,
    }

    print("\nSummary")
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    if args.json_out:
        json_path = Path(args.json_out).expanduser().resolve()
        json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nJSON saved: {json_path}")


if __name__ == "__main__":
    main()
