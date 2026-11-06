# Pose engine integration

The tAI-Chi coach (`coach.py`, `pose_common.py`) is ported to Kotlin in `:pose-engine`.

## Flow
CameraX frame (RGBA, front camera) -> upright, non-mirrored bitmap -> MediaPipe `PoseLandmarker`
(VIDEO mode) -> 8 joint angles from the 3D world landmarks (NaN if visibility < 0.5) -> `TaiChiCoach`
compares them with the current reference keyframe -> `PoseFeedback` cards in the UI.

Matching rules are identical to `coach.py`: mean error < 20 deg and max error < 40 deg, held 0.6 s, then
advance; the worst two joints over 15 deg produce cues ("bend your left knee", "raise your right arm").

## Files (`pose-engine/src/main/java/com/example/poseengine/`)
- `JointAngles.kt` - angle maths (pure Kotlin)
- `TaiChiCoach.kt` - keyframe stepping, hold timer, cues (pure Kotlin; unit-tested)
- `ReferenceSequence.kt` - loads `assets/reference/reference.json` and thumbnails
- `MediaPipePoseFeedbackEngine.kt` - the `PoseFeedbackEngine` used by `MainActivity`
- `PoseFeedbackEngine.kt` - the replaceable interface (now with `restart/next/previous` and `thumbnail`)

## Assets (`pose-engine/src/main/assets/`)
- `pose_landmarker_lite.task` - model. To use the full model, add `pose_landmarker_full.task` and pass
  `modelAsset = "pose_landmarker_full.task"` to `MediaPipePoseFeedbackEngine(...)` (slower, more accurate).
- `reference/` - output of `extract_reference.py`. To change the routine, re-run it and replace the folder.
  Keep `joint_names` unchanged; thumbnails are flipped automatically when `mirrored` is false.

## UI
Prev / Restart / Skip buttons mirror the `p` / `r` / `n` keys of `coach.py`.

## Notes
- Dependency: `com.google.mediapipe:tasks-vision` (version in `gradle/libs.versions.toml`).
- Release builds: keep rules for MediaPipe are in `app/src/main/keepRules/rules.keep`.
- Not yet implemented: skeleton overlay on the camera preview.
- Run unit tests with `./gradlew :pose-engine:testDebugUnitTest`.
