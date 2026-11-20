"""
Tai Chi coach v2: walks the student through every keyframe, shows the expert's guidance,
and gives corrective cues (expert-written where available).

Usage:
    python coach_v2.py                         # uses reference_v2/reference.json
    python coach_v2.py --tol-scale 1.3         # more forgiving (beginners)

Keys (click the video window first):  n skip pose   p previous pose   r restart   q quit
"""
import argparse
import csv
import os
import time
from collections import Counter
from datetime import datetime

import cv2
import numpy as np

from joints import JOINT_NAMES
from matching import label, load_reference, score_pose, select_cues
from pose_common import create_landmarker, detect, draw_skeleton, joint_angles
from ui import GREEN, ORANGE, RED, WHITE, Renderer


def load_thumb(kf):
    if not kf["thumb_path"]:
        return None
    img = cv2.imread(kf["thumb_path"])
    # The student's view is a mirror. A front-facing instructor image is flipped to match it.
    return cv2.flip(img, 1) if (img is not None and kf.get("thumbnail_flip")) else img


def write_log(kfs, status, best, seconds, problems, directory="sessions"):
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, datetime.now().strftime("session_%Y%m%d_%H%M%S.csv"))
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["keyframe_id", "status", "best_score", "seconds_on_pose", "most_common_problem"])
        for i, kf in enumerate(kfs):
            top = problems[i].most_common(1)
            w.writerow([kf["id"], status[i], f"{best[i]:.0f}", f"{seconds[i]:.1f}", top[0][0] if top else ""])
    print(f"Session saved to {path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref", default="reference_v2/reference.json")
    ap.add_argument("--tol-scale", type=float, default=1.0, help=">1 is more forgiving, <1 stricter")
    ap.add_argument("--hold-scale", type=float, default=1.0)
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--font", default=None, help="path to a .ttf/.ttc font (for non-Latin text)")
    args = ap.parse_args()

    ref = load_reference(args.ref)
    kfs = ref["keyframes"]
    n = len(kfs)
    n_moves = len({k["movement_no"] for k in kfs})
    thumbs = [load_thumb(k) for k in kfs]
    renderer = Renderer(args.font)
    landmarker = create_landmarker("lite")
    cap = cv2.VideoCapture(args.camera)
    if not cap.isOpened():
        raise RuntimeError("Could not open webcam")

    def reset():
        return {"idx": 0, "hold": None, "banner": None, "banner_until": 0.0, "entered": time.time(),
                "status": ["not_reached"] * n, "best": [0.0] * n, "secs": [0.0] * n,
                "problems": [Counter() for _ in range(n)]}

    S = reset()
    t0 = time.time()

    def advance(to, how):
        i = S["idx"]
        S["status"][i] = how
        S["secs"][i] += time.time() - S["entered"]
        S["idx"], S["hold"], S["entered"] = to, None, time.time()
        if how == "matched" and to < n:
            nxt = kfs[to]
            S["banner"] = f"Matched!  Next: {nxt['movement_name']} {nxt['sub_name']}".strip()
            S["banner_until"] = time.time() + 3.0
        elif to >= n:
            S["banner"], S["banner_until"] = "Sequence complete - well done!", time.time() + 4.0

    while True:
        ok, frame = cap.read()
        if not ok:
            break
        now = time.time()
        res = detect(landmarker, frame, int((now - t0) * 1000))  # unflipped frame: true left/right
        view = cv2.flip(frame, 1)                                   # mirror view for the student
        idx = S["idx"]
        done = idx >= n
        kf = kfs[min(idx, n - 1)]
        status, color, cues, frac = "", WHITE, [], None

        if done:
            avg = np.mean([b for b, st in zip(S["best"], S["status"]) if st == "matched"] or [0])
            status, color = f"Complete. Average match on matched poses: {avg:.0f}%.  Press r to restart.", GREEN
        elif res is None:
            status, color = "Step into view so your whole body is visible", RED
            S["hold"] = None
        else:
            landmarks, world = res
            live = joint_angles(landmarks, world)
            sc = score_pose(live, kf, tol_scale=args.tol_scale)
            S["best"][idx] = max(S["best"][idx], sc["score"])
            draw_skeleton(view, landmarks, flip_x=True, color=(0, 220, 0) if sc["matched"] else (0, 165, 255))
            if len(sc["unseen"]):
                names = ", ".join(label(JOINT_NAMES[j]) for j in sc["unseen"][:2])
                status, color = f"I can't see your {names} - step back or adjust the camera", ORANGE
                S["hold"] = None
            elif sc["matched"]:
                S["hold"] = S["hold"] or now
                need = kf["hold_s"] * args.hold_scale
                frac = min(1.0, (now - S["hold"]) / need)
                status, color = f"Match {sc['score']:.0f}% - hold it...", GREEN
                if frac >= 1.0:
                    advance(idx + 1, "matched")
            else:
                S["hold"] = None
                status, color = f"Match {sc['score']:.0f}%", ORANGE
                cues = select_cues(kf, live, sc)
                for j in sc["bad"]:
                    S["problems"][idx][JOINT_NAMES[j]] += 1

        progress = ["done" if st == "matched" else "skipped" if st == "skipped" else "pending" for st in S["status"]]
        if not done:
            progress[idx] = "current"
        frame_out = renderer.render(view, {
            "title": f"Movement {kf['movement_no']} of {n_moves}: {kf['movement_name']}",
            "step_text": f"Pose {min(idx + 1, n)} / {n}",
            "thumb": thumbs[min(idx, n - 1)], "pose_name": f"{kf['movement_name']} {kf['sub_name']}".strip(),
            "instruction": kf["instruction"], "key_points": kf["key_points"], "mistakes": kf["mistakes"],
            "status": status, "status_color": color, "matched_frac": frac, "cues": cues,
            "banner": S["banner"] if now < S["banner_until"] else None, "progress": progress,
            "footer": "n skip   p previous   r restart   q quit",
        })
        cv2.imshow("Tai Chi coach", frame_out)

        key = cv2.waitKey(1) & 0xFF
        if key == ord("q"):
            break
        elif key == ord("r"):
            if any(st != "not_reached" for st in S["status"]):
                write_log(kfs, S["status"], S["best"], S["secs"], S["problems"])
            S = reset()
        elif key == ord("n") and not done:
            advance(idx + 1, "skipped")
        elif key == ord("p") and idx > 0:
            S["idx"], S["hold"], S["entered"] = idx - 1, None, now

    if any(st != "not_reached" for st in S["status"]):
        write_log(kfs, S["status"], S["best"], S["secs"], S["problems"])
    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
