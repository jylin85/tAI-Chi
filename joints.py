"""Joint definitions. Pure Python/numpy so refdata/matching/ui can be used and tested without MediaPipe."""
import numpy as np

# Joint name -> (point A, vertex B, point C), BlazePose landmark indices.
# ORDER MATTERS: left/right pairs are adjacent so mirror_vector() can swap them.
# "Left"/"right" always mean the PERFORMER'S OWN left/right.
JOINTS = {
    "L_elbow": (11, 13, 15), "R_elbow": (12, 14, 16),
    "L_shoulder": (13, 11, 23), "R_shoulder": (14, 12, 24),
    "L_hip": (11, 23, 25), "R_hip": (12, 24, 26),
    "L_knee": (23, 25, 27), "R_knee": (24, 26, 28),
}
JOINT_NAMES = list(JOINTS)
CONNECTIONS = [
    (11, 12), (11, 13), (13, 15), (12, 14), (14, 16), (11, 23), (12, 24),
    (23, 24), (23, 25), (25, 27), (24, 26), (26, 28),
]

# Problem keys used by the expert's cue table. For elbow/knee/hip a LARGER angle means
# straighter; for the shoulder (hip-shoulder-elbow) a larger angle means the arm is higher.
PROBLEMS = ["too_straight", "too_bent", "arm_too_high", "arm_too_low", "any"]


def mirror_vector(vec):
    """Swap left and right joints (for videos that are flipped / mirror-style)."""
    return np.asarray(vec).reshape(-1, 2)[:, ::-1].ravel()


def problem_key(joint, diff):
    """diff = live angle - target angle."""
    if joint.endswith("shoulder"):
        return "arm_too_high" if diff > 0 else "arm_too_low"
    return "too_straight" if diff > 0 else "too_bent"
