"""Shared pose-detection helpers (MediaPipe + OpenCV)."""
import os
import urllib.request

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

# Re-exported so older scripts (extract_reference.py, coach.py) keep working unchanged.
from joints import CONNECTIONS, JOINT_NAMES, JOINTS, mirror_vector  # noqa: F401

MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
    "pose_landmarker_{v}/float16/latest/pose_landmarker_{v}.task"
)


def create_landmarker(variant="lite", mode="video"):
    """variant: 'lite' (fast, live use), 'full' or 'heavy' (more accurate, offline).
    mode: 'video' (temporal tracking, increasing timestamps) or 'image' (independent frames)."""
    path = f"pose_landmarker_{variant}.task"
    if not os.path.exists(path):
        print(f"Downloading {variant} pose model...")
        urllib.request.urlretrieve(MODEL_URL.format(v=variant), path)
    options = vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=path),
        running_mode=vision.RunningMode.IMAGE if mode == "image" else vision.RunningMode.VIDEO,
        num_poses=1,
    )
    return vision.PoseLandmarker.create_from_options(options)


def _wrap(bgr_frame):
    rgb = cv2.cvtColor(bgr_frame, cv2.COLOR_BGR2RGB)
    return mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)


def _unpack(res):
    if res.pose_landmarks and res.pose_world_landmarks:
        return res.pose_landmarks[0], res.pose_world_landmarks[0]
    return None


def detect(landmarker, bgr_frame, ts_ms):
    """VIDEO mode. Returns (image_landmarks, world_landmarks) or None. ts_ms must increase."""
    return _unpack(landmarker.detect_for_video(_wrap(bgr_frame), ts_ms))


def detect_image(landmarker, bgr_frame):
    """IMAGE mode (landmarker made with mode='image'). Independent single frames."""
    return _unpack(landmarker.detect(_wrap(bgr_frame)))


def _angle(a, b, c):
    ba, bc = a - b, c - b
    cos = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-9)
    return float(np.degrees(np.arccos(np.clip(cos, -1.0, 1.0))))


def joint_angles(landmarks, world, min_vis=0.5):
    """8 joint angles in degrees (3D). NaN where a joint's landmarks aren't visible."""
    pts = np.array([[l.x, l.y, l.z] for l in world])
    vis = np.array([l.visibility for l in landmarks])
    out = np.full(len(JOINTS), np.nan)
    for k, (a, b, c) in enumerate(JOINTS.values()):
        if min(vis[a], vis[b], vis[c]) >= min_vis:
            out[k] = _angle(pts[a], pts[b], pts[c])
    return out


def draw_skeleton(frame, landmarks, flip_x=False, color=(0, 255, 0)):
    h, w = frame.shape[:2]
    px = [(int((1 - l.x if flip_x else l.x) * w), int(l.y * h)) for l in landmarks]
    for i, j in CONNECTIONS:
        cv2.line(frame, px[i], px[j], color, 2)
    for idx in {i for pair in CONNECTIONS for i in pair}:
        cv2.circle(frame, px[idx], 4, (0, 0, 255), -1)


def put_text(frame, text, y, color=(255, 255, 255), x=10, scale=0.6):
    cv2.putText(frame, text, (x, y), cv2.FONT_HERSHEY_SIMPLEX, scale, (0, 0, 0), 3)
    cv2.putText(frame, text, (x, y), cv2.FONT_HERSHEY_SIMPLEX, scale, color, 1)
