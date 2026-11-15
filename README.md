# tAI-Chi: Machine Learning Enhanced Tai Chi Training Assistant

tAI-Chi watches you through a camera, compares your body pose with an instructor's, and tells you in plain words what to fix ("bend your left knee", "raise your right arm"). It steps through a Tai Chi routine one pose at a time and moves on once you hold each pose correctly.

The repo holds two implementations of the same coach:

| Part | Where | What it is |
| --- | --- | --- |
| Python prototype | repo root (`*.py`) | Desktop scripts that use a webcam. This is where reference routines are created. |
| Android app | [tAI-Chi_app/](tAI-Chi_app/) | Kotlin + Jetpack Compose app that runs the same coach on a phone or tablet's front camera. |

## How it works

1. **Pose detection.** [MediaPipe Pose Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker) (BlazePose) finds 33 body landmarks in each frame, both as image coordinates and as 3D "world" coordinates in metres.
2. **Joint angles.** Eight angles are computed in 3D from the world landmarks. Each is the angle at the middle landmark of three:

   | Joint | Landmarks (A, vertex, C) | Meaning |
   | --- | --- | --- |
   | `L_elbow` / `R_elbow` | shoulder, elbow, wrist | 180° is a straight arm |
   | `L_shoulder` / `R_shoulder` | elbow, shoulder, hip | how far the arm is lifted from the torso |
   | `L_hip` / `R_hip` | shoulder, hip, knee | 180° is standing straight |
   | `L_knee` / `R_knee` | hip, knee, ankle | 180° is a straight leg |

   A joint is ignored for a frame (stored as NaN) if any of its three landmarks has visibility below 0.5. Angles are used instead of raw positions so the comparison does not depend on body size, distance from the camera, or where you stand in the frame.
3. **Reference routine.** `extract_reference.py` runs the same detection over an instructor video and picks **keyframes**: the moments where the body moves slowest, which are held poses or turning points. Each keyframe stores its eight angles and a thumbnail.
4. **Coaching.** The live angles are compared with the current keyframe:
   - A pose **matches** when the mean error over visible joints is under the threshold (default 20°) and no single joint is off by more than twice the threshold (40°). At least 4 of the 8 joints must be visible.
   - The match must be **held** for 0.6 s, then the coach advances to the next keyframe.
   - When the pose does not match, the two worst joints produce a correction, provided each is off by more than 75% of the threshold (15°). If your angle is larger than the reference, the cue is "bend your … elbow/knee/hip" or "lower your … arm"; if smaller, "straighten your …" or "raise your … arm".

## Repository layout

```
tAI-Chi-repo/
├── tai_chi_pose.py            First MediaPipe test bench (single pose, standalone)
├── pose_common.py             Shared helpers for the two scripts below
├── extract_reference.py       Instructor video -> reference/ (keyframes + thumbnails)
├── coach.py                   Live webcam coach that steps through the keyframes
├── requirements.txt           Python dependencies
├── pose_landmarker_lite.task  MediaPipe model, fast (live use)
├── pose_landmarker_full.task  MediaPipe model, more accurate (offline extraction)
├── reference/                 Current reference routine (output of extract_reference.py)
│   ├── reference.json         Keyframe angles + full angle sequence
│   ├── kf_00.jpg … kf_28.jpg  Keyframe thumbnails with the detected skeleton drawn on
│   ├── source.mp4             Instructor video the routine was extracted from
│   └── source.mp4.f251.webm   Leftover from the video download (looks like the audio track); not used
├── rest of tAI-Chi.zip        Zipped copy of reference/ and __pycache__/; not used by any code
└── tAI-Chi_app/               Android Studio project
    ├── app/                   UI module: camera preview and feedback screen
    ├── pose-engine/           Library module: Kotlin port of the coach, model, and reference
    ├── POSE_ENGINE_INTEGRATION.md   Notes on how the Python coach was ported
    └── gradle/, *.gradle.kts, gradlew   Build configuration
```

## Python prototype

### Setup

Developed with Python 3.12.

```
pip install -r requirements.txt
```

This installs `mediapipe`, `opencv-python`, `numpy`, and `yt-dlp` (only needed to download instructor videos from YouTube). Run the scripts from the repo root, because the model files and `reference/` are looked up relative to the current directory. A model file that is missing is downloaded automatically on first use.

### tai_chi_pose.py: single-pose test bench

The first experiment with MediaPipe. It is self-contained and does not use `pose_common.py` or `reference/`.

```
python tai_chi_pose.py
```

- Shows the mirrored webcam feed with the skeleton and the per-frame inference time in milliseconds (useful for judging whether the model is fast enough for embedded hardware).
- Press `r` to save your current pose as the reference (written to `reference_pose.json`, loaded again on the next run). Press `q` to quit. Click the video window first so it receives the keys.
- Once a reference exists, it shows the mean angle error and the worst-matching joint. The text is green under 15°, orange under 30°, and red above that.
- Always uses the lite model and camera 0. It has no visibility check, so hidden joints still contribute to the error.

### pose_common.py: shared helpers

Imported by `extract_reference.py` and `coach.py`.

| Name | Purpose |
| --- | --- |
| `JOINTS`, `JOINT_NAMES` | The eight joints and their landmark indices. The order matters: left/right pairs are adjacent so they can be swapped, and the order must match `joint_names` in `reference.json`. |
| `CONNECTIONS` | Landmark pairs drawn as the skeleton (arms, torso, legs). |
| `create_landmarker(variant)` | Loads `pose_landmarker_<variant>.task` (`lite`, `full`, or `heavy`), downloading it if missing. Runs in VIDEO mode and tracks one person. |
| `detect(landmarker, bgr_frame, ts_ms)` | Runs detection on one OpenCV frame. Returns `(image_landmarks, world_landmarks)` or `None`. Timestamps must keep increasing. |
| `joint_angles(landmarks, world, min_vis=0.5)` | The eight angles in degrees, NaN where a joint is not visible. |
| `mirror_vector(vec)` | Swaps left and right joints in an angle vector. |
| `draw_skeleton(...)`, `put_text(...)` | Drawing helpers for the OpenCV window. |

### extract_reference.py: build a reference routine from a video

```
python extract_reference.py "https://www.youtube.com/watch?v=M4HsTHTUS-0"
python extract_reference.py --video my_clip.mp4 --start 30 --end 150
```

What it does:

1. Gets the video, either by downloading it with `yt-dlp` (a single file of at most 720p, saved as `reference/source.<ext>`) or by using the local file given with `--video`.
2. Runs pose detection on about `--fps` frames per second between `--start` and `--end`.
3. Fills gaps where a joint was not visible by linear interpolation, then smooths each angle with a 5-sample moving average.
4. Picks keyframes. It computes how fast the eight angles are changing (degrees per second), keeps only moments slower than the `--percentile` speed, and takes local minima within a ±0.5 s window. It prefers the slowest moments, keeps them at least `--min-gap` seconds apart, and drops any keyframe whose angles are within `--dedupe` degrees (mean) of the previous one.
5. Writes `reference.json` and one annotated thumbnail per keyframe.

Always open the `kf_*.jpg` thumbnails afterwards and check that the skeleton sits on the instructor and that the chosen poses make sense.

| Option | Default | Meaning |
| --- | --- | --- |
| `url` | none | YouTube URL (positional). Give this or `--video`. |
| `--video` | none | Local video file to use instead of downloading. |
| `--out` | `reference` | Output folder. |
| `--fps` | `10` | Analysis frame rate. |
| `--model` | `full` | `lite`, `full`, or `heavy`. |
| `--start`, `--end` | whole video | Time range to analyse, in seconds. |
| `--mirror-reference` | off | Swap left and right so you copy the instructor like a mirror (their right arm is your left arm). Off means same-side: your left arm copies their left arm. |
| `--min-gap` | `2.0` | Minimum seconds between keyframes. |
| `--dedupe` | `12.0` | Skip a keyframe within this mean angle difference (degrees) of the previous one. |
| `--percentile` | `35` | Only moments slower than this speed percentile can be keyframes. Raise it to get more keyframes. |

Troubleshooting:

- **YouTube download fails.** `yt-dlp` is usually out of date or has no JavaScript runtime. Run `pip install -U yt-dlp` and install [Deno](https://deno.com/) (`brew install deno` on macOS, `winget install DenoLand.Deno` on Windows), or download the video yourself and pass it with `--video`.
- **"No frames could be decoded".** OpenCV often cannot read AV1 or VP9 video. Re-encode to H.264 and use the result:

  ```
  ffmpeg -i reference/source.mp4 -c:v libx264 -an -vf scale=-2:720 reference/source_h264.mp4
  python extract_reference.py --video reference/source_h264.mp4
  ```
- **"Too few frames with a detected person".** The instructor needs to be fully visible in the chosen time range.

### coach.py: live coach

```
python coach.py                  # uses reference/reference.json
python coach.py --threshold 25   # more forgiving
```

The window shows a mirrored view of you with your skeleton, the current pose number, the reference thumbnail in the top-right corner, and the mean angle error. The skeleton is orange until the pose matches, then green with a "Good - hold it..." progress bar. While the pose does not match, up to two corrections are listed. After the last pose it shows "Sequence complete!".

| Option | Default | Meaning |
| --- | --- | --- |
| `--ref` | `reference/reference.json` | Reference file. Thumbnails are loaded from the same folder. |
| `--threshold` | `20.0` | Maximum mean joint-angle error in degrees that counts as a match. |
| `--hold` | `0.6` | Seconds the pose must be held. |
| `--camera` | `0` | Webcam index. |

Keys (click the video window first): `r` restart, `n` next pose, `p` previous pose, `q` quit.

Detection runs on the unflipped frame so left and right are your real left and right; only the displayed image is mirrored. For a same-side reference (`mirrored: false`) the thumbnail is flipped to match what you see in the mirror view.

### reference/: the current routine

The checked-in routine was extracted from `reference/source.mp4` in same-side mode, covering about 200 seconds of video. It has **29 keyframes**, with 640×480 thumbnails.

`reference.json` fields:

| Field | Content |
| --- | --- |
| `source` | URL or path the routine was extracted from. |
| `title` | Video title (empty when a local file was used). |
| `joint_names` | The eight joint names, in the order the angles are stored. |
| `mirrored` | `true` if extracted with `--mirror-reference`. |
| `fps` | Actual analysis frame rate (about 10). |
| `keyframes` | List of `{id, time_s, angles, thumbnail}`. `angles` has eight values in degrees; `null` marks a joint that was never visible. |
| `sequence` | `{times, angles}` for every analysed frame (1,990 here), smoothed. Not used yet; it is stored for future work on matching continuous movement (dynamic time warping). |

## Android app (tAI-Chi_app/)

An Android Studio project with two Gradle modules. Application ID `com.example.tai_chi`, display name "tAi-Chi", minimum Android 7.0 (API 24).

### Modules and files

**`:pose-engine`** (library, package `com.example.poseengine`) holds everything about detection and coaching, so the UI never touches MediaPipe directly.

| File | Role |
| --- | --- |
| [PoseFeedbackEngine.kt](tAI-Chi_app/pose-engine/src/main/java/com/example/poseengine/PoseFeedbackEngine.kt) | The interface between UI and detector: `analyze(frame)` returns a list of `PoseFeedback` cards; `restart()`, `next()`, `previous()` control the routine; `currentReference()` returns the picture of the pose to copy. Also defines `PoseFeedback`, `ReferenceImage`, `Severity` (`INFO`, `GOOD`, `TIP`, `WARNING`), and a placeholder `UnconfiguredPoseFeedbackEngine`. |
| [MediaPipePoseFeedbackEngine.kt](tAI-Chi_app/pose-engine/src/main/java/com/example/poseengine/MediaPipePoseFeedbackEngine.kt) | The real engine. On the first frame it loads the model and reference. For each frame it rotates the image upright (not mirrored), runs MediaPipe, computes joint angles, asks `TaiChiCoach` for the state, and turns that into feedback cards. Takes `modelAsset`, `referenceDir`, `threshold`, and `holdSeconds` as constructor parameters. |
| [TaiChiCoach.kt](tAI-Chi_app/pose-engine/src/main/java/com/example/poseengine/TaiChiCoach.kt) | Port of the matching logic in `coach.py`: keyframe stepping, hold timer, cue text, and the states `NO_PERSON`, `TOO_FEW_JOINTS`, `ADJUST`, `HOLDING`, `COMPLETE`. Pure Kotlin. |
| [JointAngles.kt](tAI-Chi_app/pose-engine/src/main/java/com/example/poseengine/JointAngles.kt) | Port of the angle maths in `pose_common.py`. Pure Kotlin. |
| [ReferenceSequence.kt](tAI-Chi_app/pose-engine/src/main/java/com/example/poseengine/ReferenceSequence.kt) | Loads `reference.json` from the module's assets and decodes thumbnails on demand at half size, keeping only the current one in memory. |
| `src/main/assets/` | `pose_landmarker_lite.task` and a copy of `reference/` (identical to the copies at the repo root, without the source video). |
| `src/test/` | JVM unit tests: `JointAnglesTest` (angle maths and visibility) and `TaiChiCoachTest` (holding, advancing, cues, lost pose, controls, completion). |

**`:app`** (package `com.example.tai_chi`) is the UI.

| File | Role |
| --- | --- |
| [MainActivity.kt](tAI-Chi_app/app/src/main/java/com/example/tai_chi/MainActivity.kt) | The whole screen. Requests camera permission, shows the front camera full-screen with CameraX, and sends each frame to the engine on a background thread (only the latest frame is kept, so slow detection drops frames instead of lagging). |
| `ui/theme/` | Default Material 3 theme files from the Android Studio template. The screen itself uses its own fixed colours. |
| `AndroidManifest.xml` | Declares the `CAMERA` permission and the launcher activity. |
| `keepRules/rules.keep` | R8 rules that keep MediaPipe and protobuf classes in release builds. |
| `src/test/`, `src/androidTest/` | Template example tests only. |

### What the screen shows

- **Camera preview** fills the screen.
- **Right rail** (about a third of the width): the title, **Prev / Restart / Skip** buttons (the same as `p` / `r` / `n` in `coach.py`), the **pose to copy** with its number, and a scrolling list of status cards such as "Pose 3/29" with "Error 24° (need < 20°)", "Good - hold it... (50%)", "Pose matched!", and "Sequence complete!".
- **Adjust banner** (top-left, large text meant to be read from a distance): the current corrections and warnings such as "Bend your left knee" or "No person detected. Step back so your whole body is visible."

### Build and run

Open `tAI-Chi_app/` in Android Studio and run the `app` configuration on a device with a front camera, or from a terminal:

```
cd tAI-Chi_app
./gradlew :app:installDebug                 # build and install on a connected device
./gradlew :pose-engine:testDebugUnitTest    # run the coach unit tests
```

Versions are pinned in [gradle/libs.versions.toml](tAI-Chi_app/gradle/libs.versions.toml) and the module build files: Gradle 9.6.0, Android Gradle Plugin 9.4.1, Kotlin 2.2.10, compile/target SDK 37, Compose BOM 2026.02.01, CameraX 1.4.2, MediaPipe `tasks-vision` 0.10.21.

`local.properties` is checked in with one contributor's Windows SDK path. Android Studio rewrites it for your machine; for command-line builds, set `sdk.dir` to your own Android SDK location.

## Changing the routine

1. Run `extract_reference.py` on the new instructor video and review the thumbnails.
2. Test it on the desktop with `python coach.py`.
3. Copy `reference.json` and the `kf_*.jpg` files into `tAI-Chi_app/pose-engine/src/main/assets/reference/`, replacing what is there, and rebuild the app.

Do not change the joint list or its order in only one place. `pose_common.py`, `JointAngles.kt`, and `reference.json` must agree, and both coaches refuse to load a reference whose `joint_names` differ.

To use the more accurate model in the app, add `pose_landmarker_full.task` to the pose-engine assets and pass `modelAsset = "pose_landmarker_full.task"` to `MediaPipePoseFeedbackEngine`. It is slower.

## Tuning

| Setting | Python | Android | Default |
| --- | --- | --- | --- |
| Match threshold (mean error, degrees) | `coach.py --threshold` | `MediaPipePoseFeedbackEngine(threshold = …)` | 20 |
| Hold time (seconds) | `coach.py --hold` | `MediaPipePoseFeedbackEngine(holdSeconds = …)` | 0.6 |
| Minimum landmark visibility | `min_vis` in `joint_angles` | `minVis` in `computeJointAngles` | 0.5 |
| Number and spacing of keyframes | `extract_reference.py --percentile`, `--min-gap`, `--dedupe` | set when the reference is extracted | 35, 2.0, 12.0 |

## Current limitations

- Only still keyframes are compared. The movement between poses is not checked, although the full angle sequence is already saved for that.
- One routine from one video is bundled.
- The Android app does not draw the skeleton over the camera preview.
- The coach logic exists twice (Python and Kotlin), so a change to the matching rules has to be made in both.
- The app README mentions IMUs, but there is no IMU code in the repo yet.
- With a same-side reference, thumbnails are flipped for display, so the "#5 t=31.9s" label drawn on each thumbnail appears mirrored.
- There is no `.gitignore` at the repo root, so build output (`tAI-Chi_app/.gradle/`, `tAI-Chi_app/pose-engine/build/`, `__pycache__/`) and `local.properties` are committed and show up as changes after every build.
