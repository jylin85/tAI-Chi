"""Scoring a live pose against a reference keyframe, and choosing the feedback to show."""
import json
import os

import numpy as np

from joints import JOINT_NAMES, problem_key


def load_reference(path):
    with open(path, encoding="utf-8") as f:
        ref = json.load(f)
    base = os.path.dirname(path)
    if ref["joint_names"] != JOINT_NAMES:
        raise ValueError("Reference was built with a different joint set; rebuild it with build_reference.py")
    for kf in ref["keyframes"]:
        for k in ("target", "tol", "weight"):
            kf[k] = np.array([np.nan if v is None else v for v in kf[k]], float)
        kf["cue_map"] = {(c["joint"], c["problem"]): c["message"] for c in kf.get("cues", [])}
        kf["thumb_path"] = os.path.join(base, kf["thumbnail"]) if kf.get("thumbnail") else None
    return ref


def score_pose(live, kf, lenient=1.5, tol_scale=1.0):
    """
    z = |live - target| / tolerance for each joint.
      - critical joints (weight 1) must have z <= 1
      - other joints (weight 0.5) get extra leeway: z <= `lenient`
      - ignored joints (weight 0) are skipped
    Score (weakest link, so it always agrees with the verdict): each joint's z is divided by its own
    limit, then z=0 -> 100, z=1 (edge of allowed range) -> 70, z>=3 -> 0. The pose's score is its WORST
    joint, so score >= 70 exactly when the pose matches.
    """
    tgt, tol, w = kf["target"], kf["tol"] * tol_scale, kf["weight"]
    judged = (w > 0) & ~np.isnan(tgt) & ~np.isnan(tol)
    seen = judged & ~np.isnan(live)
    unseen = judged & np.isnan(live) & (w >= 1)
    z = np.full(len(tgt), np.nan)
    z[seen] = np.abs(live[seen] - tgt[seen]) / tol[seen]
    limit = np.where(w >= 1, 1.0, lenient)
    with np.errstate(invalid="ignore"):
        bad = seen & (z > limit)
        ze = z / limit
        pts = np.where(ze <= 1, 100 - 30 * ze, np.clip(70 * (3 - ze) / 2, 0, 70))
    score = float(pts[seen].min()) if seen.any() else 0.0
    need = min(3, int(judged.sum()))
    matched = bool(need > 0 and seen.sum() >= need and not bad.any() and not unseen.any())
    return {"z": z, "bad": np.flatnonzero(bad), "unseen": np.flatnonzero(unseen),
            "score": score, "matched": matched}


def label(joint):
    return ("left " if joint.startswith("L_") else "right ") + joint[2:]


def auto_cue(joint, diff):
    side = "left" if joint.startswith("L_") else "right"
    part = joint[2:]
    if part == "shoulder":
        return f"{'Lower' if diff > 0 else 'Raise'} your {side} arm ({abs(diff):.0f} deg off)"
    return f"{'Bend' if diff > 0 else 'Straighten'} your {side} {part} more ({abs(diff):.0f} deg off)"


def select_cues(kf, live, res, max_cues=2):
    """
    Returns [(text, is_expert_written)]. For each out-of-range joint (critical and worst first):
    expert cue for (joint, problem) -> expert cue for (joint, 'any') -> auto-generated fallback.
    """
    order = sorted(res["bad"], key=lambda j: (-kf["weight"][j], -res["z"][j]))
    cues = []
    for j in order[:max_cues]:
        name = JOINT_NAMES[j]
        diff = live[j] - kf["target"][j]
        msg = kf["cue_map"].get((name, problem_key(name, diff))) or kf["cue_map"].get((name, "any"))
        cues.append((msg, True) if msg else (auto_cue(name, diff), False))
    return cues
