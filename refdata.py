"""
Load the annotation workbook, validate it, and merge per-video measurements into one
reference (target angle + tolerance + weight per joint, per keyframe).

Pure Python/numpy/openpyxl: no MediaPipe or OpenCV needed.
"""
import datetime
from dataclasses import dataclass

import numpy as np
from openpyxl import load_workbook

from joints import JOINT_NAMES, PROBLEMS

SHEETS = {  # sheet name -> required columns
    "videos": ["video_id", "file", "camera_view", "swap_left_right"],
    "keyframes": ["keyframe_id", "movement_no", "movement_name"],
    "annotations": ["video_id", "screenshot", "time", "type", "keyframe_id"],
    "cues": ["keyframe_id", "joint", "problem", "message"],
    "overrides": ["keyframe_id", "action"],
}
ANNOTATION_TYPES = {"keyframe", "transition", "unsure"}
QUALITIES = {"", "good", "ok", "poor"}
ACTIONS = {"set_target", "set_tolerance", "ignore_video"}


@dataclass
class MergeConfig:
    base_tol: float = 12.0       # deg: floor for tolerance (monocular pose estimation noise)
    max_tol: float = 40.0        # deg: tolerance never gets looser than this
    spread_mult: float = 2.0     # tolerance = spread_mult * inter-video spread (above the floor)
    small_n_tol: float = 18.0    # deg: minimum tolerance when fewer than 3 videos contribute
    conflict_dev: float = 25.0   # deg: a video this far from the median is flagged as a conflict
    hold_s: float = 0.6          # seconds to hold a 'hold' keyframe
    pass_s: float = 0.25         # seconds for a 'pass' (pass-through) keyframe


# ---------------------------------------------------------------- helpers
def yes(v):
    return str(v).strip().lower() in ("y", "yes", "true", "1")


def parse_time(v):
    """Accepts seconds (83.5), 'm:ss' / 'm:ss.s' / 'h:mm:ss' text. Rejects Excel clock-time cells."""
    if isinstance(v, (datetime.time, datetime.datetime, datetime.timedelta)):
        raise ValueError("Excel turned this into a clock time. Format the column as Text, or enter plain seconds.")
    if isinstance(v, (int, float)):
        return float(v)
    parts = str(v).strip().split(":")
    if not 1 <= len(parts) <= 3:
        raise ValueError(f"can't read time '{v}'")
    secs = 0.0
    for p in parts:
        secs = secs * 60 + float(p)
    return secs


def tokens(s):
    return [t.strip() for t in str(s).replace(";", ",").split(",") if t.strip()]


def fnum(v, default=None):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


# ---------------------------------------------------------------- loading
def load(path):
    wb = load_workbook(path, data_only=True)
    data = {}
    for name, required in SHEETS.items():
        if name not in wb.sheetnames:
            raise ValueError(f"Workbook is missing the '{name}' sheet")
        rows = wb[name].iter_rows(values_only=True)
        header = [str(h).strip().lower() if h is not None else "" for h in next(rows)]
        missing = [c for c in required if c not in header]
        if missing:
            raise ValueError(f"Sheet '{name}' is missing columns: {missing}")
        out = []
        for r_i, row in enumerate(rows, start=2):
            d = {h: ("" if v is None else (v.strip() if isinstance(v, str) else v))
                 for h, v in zip(header, row) if h}
            if all(v == "" for v in d.values()) or str(d[header[0]]).startswith("(example)"):
                continue
            d["_row"] = r_i
            out.append(d)
        data[name] = out
    return data


def validate(data, file_exists=lambda p: True):
    """Returns (errors, warnings) as lists of strings."""
    errs, warns = [], []
    vids = {}
    for v in data["videos"]:
        if v["video_id"] in vids:
            errs.append(f"videos row {v['_row']}: duplicate video_id {v['video_id']}")
        vids[v["video_id"]] = v
        if not file_exists(v["file"]):
            errs.append(f"videos row {v['_row']}: file not found: {v['file']}")
    kf_ids = []
    for k in data["keyframes"]:
        if not k["keyframe_id"]:
            errs.append(f"keyframes row {k['_row']}: empty keyframe_id")
        elif k["keyframe_id"] in kf_ids:
            errs.append(f"keyframes row {k['_row']}: duplicate keyframe_id {k['keyframe_id']}")
        kf_ids.append(k["keyframe_id"])
        for col in ("critical_joints", "ignore_joints"):
            bad = [t for t in tokens(k.get(col, "")) if t not in JOINT_NAMES]
            if bad:
                errs.append(f"keyframes row {k['_row']}: unknown joint(s) in {col}: {bad}")
        if k.get("hold_type", "") not in ("", "hold", "pass"):
            errs.append(f"keyframes row {k['_row']}: hold_type must be hold or pass")
        if k.get("display_video", "") not in ("",) and k["display_video"] not in vids:
            errs.append(f"keyframes row {k['_row']}: display_video {k['display_video']} not in videos sheet")
    order = {k: i for i, k in enumerate(kf_ids)}

    seen = set()
    per_video = {}
    for a in data["annotations"]:
        where = f"annotations row {a['_row']}"
        if a["video_id"] not in vids:
            errs.append(f"{where}: unknown video_id '{a['video_id']}'")
            continue
        if a["type"] not in ANNOTATION_TYPES:
            errs.append(f"{where}: type must be one of {sorted(ANNOTATION_TYPES)}")
            continue
        if str(a.get("quality", "")).lower() not in QUALITIES:
            errs.append(f"{where}: quality must be good, ok or poor")
        try:
            t = parse_time(a["time"])
        except ValueError as e:
            errs.append(f"{where}: {e}")
            continue
        if a["type"] != "keyframe":
            continue
        if a["keyframe_id"] not in order:
            errs.append(f"{where}: keyframe_id '{a['keyframe_id']}' is not in the keyframes sheet")
            continue
        key = (a["video_id"], a["keyframe_id"])
        if key in seen:
            errs.append(f"{where}: {a['video_id']} already has a screenshot for {a['keyframe_id']}")
        seen.add(key)
        per_video.setdefault(a["video_id"], []).append((t, a["keyframe_id"]))

    # The form is sequential: keyframes should occur in sheet order within each video.
    for vid, items in per_video.items():
        items.sort()
        for (t1, k1), (t2, k2) in zip(items, items[1:]):
            if order[k2] < order[k1]:
                warns.append(f"{vid}: {k2} at {t2:.1f}s comes after {k1} at {t1:.1f}s but is earlier in the "
                             f"form - typo in a time or keyframe_id?")
    covered = {}
    for vid, items in per_video.items():
        for _, k in items:
            covered.setdefault(k, set()).add(vid)
    for k in kf_ids:
        n = len(covered.get(k, ()))
        if n == 0:
            warns.append(f"{k}: no video has a screenshot for this keyframe")
        elif n < 2:
            warns.append(f"{k}: only {n} video - tolerances will be wide and unverified")

    for c in data["cues"]:
        where = f"cues row {c['_row']}"
        if c["keyframe_id"] not in order:
            errs.append(f"{where}: unknown keyframe_id '{c['keyframe_id']}'")
        if c["joint"] not in JOINT_NAMES:
            errs.append(f"{where}: unknown joint '{c['joint']}'")
        if c["problem"] not in PROBLEMS:
            errs.append(f"{where}: problem must be one of {PROBLEMS}")
    for o in data["overrides"]:
        where = f"overrides row {o['_row']}"
        if o["keyframe_id"] not in order:
            errs.append(f"{where}: unknown keyframe_id '{o['keyframe_id']}'")
        if o["action"] not in ACTIONS:
            errs.append(f"{where}: action must be one of {sorted(ACTIONS)}")
        if o.get("joint", "") and o["joint"] not in JOINT_NAMES:
            errs.append(f"{where}: unknown joint '{o['joint']}'")
        if o["action"] in ("set_target", "set_tolerance") and (not o.get("joint") or fnum(o.get("value")) is None):
            errs.append(f"{where}: {o['action']} needs a joint and a numeric value")
        if o["action"] == "ignore_video" and o.get("video_id", "") not in vids:
            errs.append(f"{where}: ignore_video needs a valid video_id")
    return errs, warns


# ---------------------------------------------------------------- merging
def merge_keyframe(kf_row, samples, overrides, cfg):
    """
    samples: list of dicts {video, angles (np array, NaN = unseen), quality, ...} (already L/R-canonicalised)
    Returns (target, tol, weight, n_per_joint, conflicts)

    Method: per joint, target = MEDIAN across videos; tolerance = max(base_tol, spread_mult * spread)
    where spread is the median absolute deviation (robust to one odd video). Videos far from the median are
    reported as conflicts for the expert to resolve with the 'overrides' sheet.
    """
    ignored = {}
    for o in overrides:
        if o["action"] == "ignore_video":
            ignored.setdefault(o["video_id"], set()).add(o.get("joint") or "*")

    n_j = len(JOINT_NAMES)
    target, tol = np.full(n_j, np.nan), np.full(n_j, np.nan)
    count = np.zeros(n_j, int)
    conflicts = []
    scale = fnum(kf_row.get("tolerance_scale"), 1.0) or 1.0
    usable = [s for s in samples if str(s.get("quality", "good")).lower() != "poor"]

    for j, name in enumerate(JOINT_NAMES):
        vals, vids = [], []
        for s in usable:
            skip = ignored.get(s["video"], set())
            if "*" in skip or name in skip or np.isnan(s["angles"][j]):
                continue
            vals.append(float(s["angles"][j]))
            vids.append(s["video"])
        n = len(vals)
        count[j] = n
        if n == 0:
            continue
        vals = np.array(vals)
        med = float(np.median(vals))
        dev = np.abs(vals - med)
        spread = float(np.median(dev)) if n >= 3 else (float(np.ptp(vals)) / 2 if n == 2 else 0.0)
        t = min(max(cfg.base_tol, cfg.spread_mult * spread), cfg.max_tol)
        if n < 3:
            t = max(t, cfg.small_n_tol)
        target[j], tol[j] = med, t * scale
        limit = cfg.conflict_dev if n >= 3 else cfg.conflict_dev * 0.75
        flagged = [(vids[i], vals[i]) for i in range(n) if dev[i] > limit]
        if flagged:
            conflicts.append({
                "keyframe_id": kf_row["keyframe_id"], "joint": name, "median": round(med, 1),
                "values": {v: round(float(x), 1) for v, x in zip(vids, vals)},
                "flagged_videos": [v for v, _ in flagged],
            })

    for o in overrides:  # expert overrides always win
        if o["action"] == "set_target":
            target[JOINT_NAMES.index(o["joint"])] = float(o["value"])
        elif o["action"] == "set_tolerance":
            tol[JOINT_NAMES.index(o["joint"])] = float(o["value"])
    # a hand-set target on a joint no video measured still needs a tolerance
    for o in overrides:
        if o["action"] == "set_target":
            j = JOINT_NAMES.index(o["joint"])
            if np.isnan(tol[j]):
                tol[j] = cfg.small_n_tol * scale

    crit, ign = set(tokens(kf_row.get("critical_joints", ""))), set(tokens(kf_row.get("ignore_joints", "")))
    weight = np.ones(n_j)
    for j, name in enumerate(JOINT_NAMES):
        if name in ign:
            weight[j] = 0.0
        elif crit and name not in crit:
            weight[j] = 0.5
    return target, tol, weight, count, conflicts


def similar_neighbours(keyframes, threshold=1.0):
    """Consecutive keyframes whose targets are within tolerance of each other would be matched at once."""
    out = []
    for a, b in zip(keyframes, keyframes[1:]):
        ok = (a["weight"] > 0) & (b["weight"] > 0) & ~np.isnan(a["target"]) & ~np.isnan(b["target"])
        if ok.sum() < 2:
            continue
        z = np.abs(a["target"][ok] - b["target"][ok]) / a["tol"][ok]
        if z.mean() < threshold:
            out.append((a["id"], b["id"], float(z.mean())))
    return out
