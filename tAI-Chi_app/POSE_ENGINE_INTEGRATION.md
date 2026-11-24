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

## Coach v2 (current)
The app now runs the coach v2 logic from `coach_v2.py` / `matching.py` with the reference made by
`build_reference.py`:
- `CoachV2.kt` - `scorePose` / `selectCues` / `problemKey` (port of `matching.py` + `joints.py`) and
  `TaiChiCoachV2` (the main loop of `coach_v2.py`: per-keyframe hold time, skip/previous/restart, best score).
  Pure Kotlin; `CoachV2Test.kt` covers it. The port was checked against `matching.py` on 2000 random poses
  over the real reference (identical scores, verdicts and cues).
- `ReferenceV2.kt` - loads `assets/reference_v2/reference.json` and `thumbs/`.
- Per joint: weight 1 must be within its tolerance, weight 0.5 gets 1.5x, weight 0 is ignored. The score is the
  worst joint (70% = edge of the allowed range), so a pose matches at >= 70%.
- Expert cues from the workbook are used first, then generated ones. Each pose also shows its instruction,
  key points and common mistakes.
- `MediaPipePoseFeedbackEngine(context, tolScale = 1.3)` is more forgiving, like `--tol-scale 1.3`.

To update the routine: re-run `build_reference.py`, then copy `reference_v2/reference.json` and
`reference_v2/thumbs/` into `pose-engine/src/main/assets/reference_v2/`. The old `reference/` assets,
`TaiChiCoach.kt` and `ReferenceSequence.kt` are kept but no longer used by the engine.
