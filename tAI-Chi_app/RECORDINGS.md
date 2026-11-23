# Recorded sessions: where they are and what is in them

The app's **● Record session** button saves a practice session as training data for the movement-recognition model. This file describes where the recordings go and the exact format of each file. For how to record, see [README.md, section 6.8](README.md#68-recording-training-sessions).

## Where the files are

**On the tablet**, each finished session is one folder in the tablet's **Download** folder:

```
Download/tAI-Chi/<session id>/          (full path: /sdcard/Download/tAI-Chi/<session id>/)
```

To find it on the tablet, open the **Files** app → **Browse** → **Downloads** (or **Internal storage → Download**) → **tAI-Chi**. The video plays from there, and the folder can be shared or copied like any other.

The session id is the start time and the performer: `2026-11-22_19-45-42_Jace`.

The app writes a session to its private folder while recording, then moves it to `Download/tAI-Chi` a moment after **■ Stop**, once the video has finished saving. The app shows "Tablet folder: Download/tAI-Chi/…" when that is done. If the app is closed before that, the session is moved the next time the app opens. Sessions in `Download/tAI-Chi` stay there if the app is uninstalled.

**On your computer**, copy every new session from the `tAI-Chi_app` folder:

```
./tablet.sh pull        # Mac
tablet.cmd pull         # Windows
```

They land in **`tAI-Chi-repo/recordings/<session id>/`**. Sessions copied before are skipped, so you can run it any time. The `recordings/` folder is **not committed to git**: it holds videos of people and gets large. Share it through a shared drive.

You can also copy `Download/tAI-Chi` over USB file transfer: plug the tablet in, choose **File transfer** on the tablet, and open it in Finder (with Android File Transfer) or Windows Explorer.

## What is in a session folder

| File | Contents |
| --- | --- |
| `video.mp4` | What the front camera saw, mirrored like the screen, about 720p, no sound. Missing if the tablet cannot record video while the pose detector runs (`session.json` then has `video_error`). About 40 MB per minute. |
| `camera.csv` | One row per analysed camera frame: the joint angles and all 33 body landmarks. About 9 rows per second on the team tablet. |
| `imu.csv` | One row per IMU reading from either band. 20 per second per band. |
| `session.json` | Who, when, which device, the counts of rows, and video timing |

### Time

Every `time_ns` column uses the **same clock**, the tablet's `SystemClock.elapsedRealtimeNanos` in nanoseconds. Camera rows and IMU rows can therefore be lined up directly by time. To get seconds into the session, compute `(time_ns - started_ns) / 1e9`, using `started_ns` from `session.json`.

IMU times are the moment the **band took the reading**, converted from the band's own clock by [ClockSync.kt](app/src/main/java/com/example/tai_chi/ClockSync.kt). They are not the moment the reading arrived; left and right agree to within a few milliseconds.

**Video time:** second 0 of `video.mp4` corresponds roughly to `video_start_ns` in `session.json`. The match is only to about 0.1 s, because CameraX does not report the exact first frame. That is fine for marking where moves start. For anything finer, use `camera.csv`.

### `camera.csv`

| Column | Meaning |
| --- | --- |
| `time_ns` | When the frame was captured, on the shared clock. `session.json` → `camera_time_source` says whether this came from the camera itself (`camera`, the normal case) or from when the frame reached the detector (`received`). |
| `camera_timestamp_ns` | The camera's own frame timestamp, as reported |
| `received_ns` | When the frame reached the pose detector |
| `pose_index` | Which of the app's reference poses the coach was on (0-based). It follows the Prev/Restart/Skip buttons, so it is **not** a label of the move being performed. |
| `person_detected` | 1 if a person was found in this frame, 0 if not. When 0, the remaining columns are empty. |
| `L_elbow_deg` … `R_knee_deg` | The 8 joint angles in degrees, in the order of `joint_names` in `session.json`. Empty where that joint was not visible enough. |
| `wx_i, wy_i, wz_i` | Landmark `i` (0–32, [BlazePose numbering](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker#pose_landmarker_model)) in metres, centred between the hips. y points down. |
| `ix_i, iy_i` | Landmark `i` in the upright, **unmirrored** camera image, as fractions of width and height (0–1) |
| `vis_i` | How confident the detector is that landmark `i` is visible, 0–1 |

Left and right always mean the **performer's own** left and right.

### `imu.csv`

| Column | Meaning |
| --- | --- |
| `time_ns` | When the band took the reading, on the shared clock |
| `side` | `L` or `R`: which band |
| `ax_g, ay_g, az_g` | Acceleration in g (1 g = 9.81 m/s²). A band lying still reads about 1 on one axis, from gravity. |
| `gx_dps, gy_dps, gz_dps` | Rotation speed in degrees per second |
| `seq` | The band's reading number (0–65535, then wraps). A gap means readings were lost. `-1` from bands running the old firmware. |
| `board_ticks` | The band's own clock, 1024 ticks per second. `-1` from old firmware. |
| `arrival_ns` | When the reading reached the tablet |
| `clock_offset_ns` | The offset that converted `board_ticks` to `time_ns` at that moment |

The rows for the two bands are interleaved, so split them by `side`. If the bands were not connected, the file has only its header line.

### `session.json`

| Field | Meaning |
| --- | --- |
| `performer`, `notes` | As typed when the recording started |
| `status` | `complete`; or `recording` if the app closed before **Stop** was pressed (the files up to that point are still usable) |
| `started_ns`, `ended_ns`, `duration_s` | Start and end on the shared clock |
| `started_wall_clock` | The local date and time when it started |
| `device`, `app_version` | Which tablet and which app build |
| `joint_names`, `landmark_count` | The column order used in `camera.csv` |
| `camera_time_source` | See `time_ns` in `camera.csv` above |
| `video_file`, `video_start_ns`, `video_duration_ns` | Video timing (see "Time" above) |
| `video_error` | Present only if the video could not be recorded |
| `camera_frames`, `imu_readings_left`, `imu_readings_right` | How many rows each file has |

## Reading a session in Python

```python
import json, pandas as pd

d = "recordings/2026-11-22_19-45-42_Jace"
meta = json.load(open(f"{d}/session.json"))
cam = pd.read_csv(f"{d}/camera.csv")
imu = pd.read_csv(f"{d}/imu.csv")

t0 = meta["started_ns"]
cam["t"] = (cam.time_ns - t0) / 1e9          # seconds into the session
imu["t"] = (imu.time_ns - t0) / 1e9
left, right = imu[imu.side == "L"], imu[imu.side == "R"]
```

## Labelling

The recordings carry no move labels. Add them afterwards: while watching `video.mp4`, note the time where each of the 17 moves starts. Keep the labels in a `labels.csv` next to the other files in that session's folder on the computer:

```
move_no,move_name,start_s
1,Preparation (Wu Chi),0.0
2,Beginning,6.5
3,"Ward-off, left",14.2
```

`start_s` is seconds into **the video**. To convert to the shared clock: `video_start_ns + start_s * 1e9`.
