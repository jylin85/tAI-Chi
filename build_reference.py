"""
Build the reference data from the annotation workbook + reference videos.

Usage:
    python build_reference.py tai_chi_annotations.xlsx
    python build_reference.py tai_chi_annotations.xlsx --out reference_v2 --window 0.3

Outputs (in --out):
    reference.json   what coach_v2.py loads (targets, tolerances, weights, expert text)
    report.txt       coverage, warnings, similar-neighbour check
    conflicts.csv    joints where videos disagree - for the expert to review
    thumbs/          the image shown to the student for each keyframe
    review/          every annotated frame with the detected skeleton drawn on it
"""
import argparse
import csv
import json
import os
import shutil
import warnings
from collections import defaultdict

import cv2
import numpy as np

import refdata
from joints import JOINT_NAMES, mirror_vector
from pose_common import create_landmarker, detect_image, draw_skeleton, joint_angles


def to_list(a, nd=1):
    return [None if np.isnan(v) else round(float(v), nd) for v in a]


def read_frame(cap, fps, t):
    cap.set(cv2.CAP_PROP_POS_FRAMES, max(0, int(round(t * fps))))
    ok, frame = cap.read()
    return frame if ok else None


def shrink(img, width=480):
    h, w = img.shape[:2]
    return img if w <= width else cv2.resize(img, (width, int(h * width / w)))


def measure(path, video_row, annots, landmarker, out_dir, window, n_win, log):
    """Pose angles for every 'keyframe' annotation of one video (median over a small time window)."""
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        raise SystemExit(f"OpenCV can't open {path} (AV1/VP9? re-encode to H.264, see earlier instructions)")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    swap = refdata.yes(video_row["swap_left_right"])
    vid = video_row["video_id"]
    results = []
    for a in annots:
        t = refdata.parse_time(a["time"])
        rows, center, center_res = [], None, None
        for k, off in enumerate(np.linspace(-window, window, n_win)):
            frame = read_frame(cap, fps, t + off)
            if frame is None:
                continue
            res = detect_image(landmarker, frame)
            if k == n_win // 2:
                center, center_res = frame, res
            if res:
                rows.append(joint_angles(*res))
        if len(rows) < max(2, n_win // 2 + 1) or center is None:
            log(f"  ! {vid} {a['keyframe_id']} @ {t:.1f}s: person detected in only {len(rows)}/{n_win} frames - skipped")
            continue
        A = np.array(rows)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            angles = np.nanmedian(A, axis=0)
            stability = float(np.nanmax(np.nanmax(A, 0) - np.nanmin(A, 0)))
        if swap:
            angles = mirror_vector(angles)
        if stability > 30:
            log(f"  ! {vid} {a['keyframe_id']} @ {t:.1f}s: pose changes by {stability:.0f} deg within +/-{window}s "
                f"(moving fast here) - consider a timestamp where the pose is steadier")
        # review image (with skeleton) + clean frame kept for possible use as the student's thumbnail
        clean = shrink(center)
        os.makedirs(os.path.join(out_dir, "review"), exist_ok=True)
        os.makedirs(os.path.join(out_dir, "frames"), exist_ok=True)
        cv2.imwrite(os.path.join(out_dir, "frames", f"{vid}_{a['keyframe_id']}.jpg"), clean)
        if center_res:
            rv = center.copy()
            draw_skeleton(rv, center_res[0])
            cv2.putText(rv, f"{vid} {a['keyframe_id']} t={t:.1f}s", (10, 30), cv2.FONT_HERSHEY_SIMPLEX,
                        0.9, (0, 255, 255), 2)
            cv2.imwrite(os.path.join(out_dir, "review", f"{vid}_{a['keyframe_id']}.jpg"), shrink(rv, 640))
        results.append({"keyframe_id": a["keyframe_id"], "video": vid, "time": t, "angles": angles,
                        "quality": (a.get("quality") or "good").lower(), "stability": stability,
                        "screenshot": a.get("screenshot", "")})
    cap.release()
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("workbook")
    ap.add_argument("--out", default="reference_v2")
    ap.add_argument("--model", default="full", choices=["lite", "full", "heavy"])
    ap.add_argument("--window", type=float, default=0.3, help="seconds either side of each timestamp to sample")
    ap.add_argument("--samples", type=int, default=5, help="frames sampled per timestamp")
    ap.add_argument("--base-tol", type=float, default=12.0)
    ap.add_argument("--max-tol", type=float, default=40.0)
    ap.add_argument("--conflict-dev", type=float, default=25.0)
    args = ap.parse_args()
    cfg = refdata.MergeConfig(base_tol=args.base_tol, max_tol=args.max_tol, conflict_dev=args.conflict_dev)

    wb_dir = os.path.dirname(os.path.abspath(args.workbook))

    def resolve(p):
        for cand in (p, os.path.join(wb_dir, p)):
            if os.path.exists(cand):
                return cand
        return None

    data = refdata.load(args.workbook)
    errors, warns = refdata.validate(data, file_exists=lambda p: resolve(p) is not None)
    for w in warns:
        print("warning:", w)
    if errors:
        print("\nFix these in the workbook first:")
        for e in errors:
            print("  ERROR:", e)
        raise SystemExit(1)

    os.makedirs(args.out, exist_ok=True)
    report = []

    def log(msg):
        print(msg)
        report.append(msg)

    for w in warns:
        report.append("warning: " + w)

    landmarker = create_landmarker(args.model, mode="image")
    samples = defaultdict(list)
    for v in data["videos"]:
        annots = [a for a in data["annotations"] if a["video_id"] == v["video_id"] and a["type"] == "keyframe"]
        n_trans = sum(1 for a in data["annotations"] if a["video_id"] == v["video_id"] and a["type"] != "keyframe")
        log(f"{v['video_id']}: {len(annots)} keyframe screenshots, {n_trans} transition/unsure (not scored)")
        for r in measure(resolve(v["file"]), v, annots, landmarker, args.out, args.window, args.samples, log):
            samples[r["keyframe_id"]].append(r)

    # ---- merge
    ov_by_kf = defaultdict(list)
    for o in data["overrides"]:
        ov_by_kf[o["keyframe_id"]].append(o)
    cues_by_kf = defaultdict(list)
    for c in data["cues"]:
        cues_by_kf[c["keyframe_id"]].append({"joint": c["joint"], "problem": c["problem"], "message": c["message"]})
    vids = {v["video_id"]: v for v in data["videos"]}

    keyframes, all_conflicts = [], []
    os.makedirs(os.path.join(args.out, "thumbs"), exist_ok=True)
    for i, row in enumerate(data["keyframes"]):
        kid = row["keyframe_id"]
        target, tol, weight, count, conflicts = refdata.merge_keyframe(row, samples[kid], ov_by_kf[kid], cfg)
        all_conflicts += conflicts
        # thumbnail: expert's display_video if it has this keyframe, else the first video that does
        have = [s["video"] for s in samples[kid] if s["quality"] != "poor"]
        pick = row.get("display_video") if row.get("display_video") in have else (have[0] if have else None)
        thumb = None
        if pick:
            src = os.path.join(args.out, "frames", f"{pick}_{kid}.jpg")
            thumb = f"thumbs/{kid}.jpg"
            shutil.copy(src, os.path.join(args.out, thumb))
        flip = str(vids[pick]["camera_view"]).lower() == "front" if pick else False
        hold = (cfg.pass_s if row.get("hold_type") == "pass" else cfg.hold_s)
        keyframes.append({
            "id": kid, "index": i, "movement_no": int(refdata.fnum(row["movement_no"], 0)),
            "movement_name": row["movement_name"], "sub_name": row.get("sub_name", ""),
            "hold_s": hold, "instruction": row.get("instruction", ""), "key_points": row.get("key_points", ""),
            "mistakes": row.get("common_mistakes", ""),
            "target": to_list(target), "tol": to_list(tol), "weight": to_list(weight, 2),
            "n_videos_per_joint": [int(c) for c in count], "cues": cues_by_kf[kid],
            "thumbnail": thumb, "thumbnail_flip": flip,
            "sources": [{"video": s["video"], "time": round(s["time"], 2), "angles": to_list(s["angles"])}
                        for s in samples[kid]],
        })

    # ---- checks for the report
    log("\nKeyframes with measurements from fewer than 3 videos:")
    for k in keyframes:
        n = len(k["sources"])
        if n < 3:
            log(f"  {k['id']}: {n} video(s)")
    arr = [{**k, "target": np.array([np.nan if v is None else v for v in k["target"]]),
            "tol": np.array([np.nan if v is None else v for v in k["tol"]]),
            "weight": np.array(k["weight"], float)} for k in keyframes]
    sim = refdata.similar_neighbours(arr)
    log("\nConsecutive keyframes that are very similar (the coach may match both at once):")
    for a, b, z in sim:
        log(f"  {a} -> {b}: mean difference is only {z:.2f}x tolerance. Merge them, or mark which joint "
            f"distinguishes them (critical_joints / overrides).")
    if not sim:
        log("  none")

    with open(os.path.join(args.out, "conflicts.csv"), "w", newline="", encoding="utf-8") as f:
        wr = csv.writer(f)
        wr.writerow(["keyframe_id", "joint", "median_deg", "values_by_video", "flagged_videos",
                     "what_to_do (use overrides sheet)"])
        for c in all_conflicts:
            wr.writerow([c["keyframe_id"], c["joint"], c["median"],
                         "; ".join(f"{v}={x}" for v, x in c["values"].items()), ",".join(c["flagged_videos"]),
                         "check review/ images; ignore_video, set_target, or confirm it's a real style difference"])
    log(f"\n{len(all_conflicts)} joint conflict(s) written to conflicts.csv")

    ref = {"version": 2, "joint_names": JOINT_NAMES, "workbook": os.path.basename(args.workbook),
           "config": vars(cfg) if hasattr(cfg, "__dict__") else {}, "keyframes": keyframes}
    with open(os.path.join(args.out, "reference.json"), "w", encoding="utf-8") as f:
        json.dump(ref, f, indent=1, ensure_ascii=False)
    with open(os.path.join(args.out, "report.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(report))
    shutil.rmtree(os.path.join(args.out, "frames"), ignore_errors=True)
    print(f"\nDone: {len(keyframes)} keyframes -> {args.out}/reference.json. Read report.txt and conflicts.csv, "
          f"and flip through review/ to check the skeletons.")


if __name__ == "__main__":
    main()
