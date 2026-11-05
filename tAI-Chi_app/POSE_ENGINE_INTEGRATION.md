# Tai-Chi pose engine integration

## What is included
- `:app`: full-screen front-camera preview with a translucent, vertically scrolling feedback rail.
- `:pose-engine`: separate Android library module containing the stable `PoseFeedbackEngine` API.
- CameraX sends frames to the engine with latest-frame backpressure; the UI only renders feedback returned by the engine.

## Important
The supplied `tAiChi.zip` is the starter Android app and contains no pose-detection source/model. The second supplied `tAI-Chi.zip` contains Git metadata but no checked-out project files. For that reason the app currently uses `UnconfiguredPoseFeedbackEngine`; it intentionally does not fabricate Tai-Chi corrections.

## Connect the detector
Implement `PoseFeedbackEngine` in `:pose-engine` and return `PoseFeedback` values from `analyze(ImageProxy)`. Keep detector-specific code and model assets inside this module so it can be updated without modifying the camera/UI module. If the existing detector is Python/MMPose, it cannot be imported directly into an Android Kotlin app; port the model to a mobile runtime (for example, ONNX Runtime / TFLite where supported) or expose the detector as a service and implement a client adapter in this module.

`ImageProxy` is owned by the caller and is closed after `analyze` returns. If processing asynchronously, copy the image data before returning. Avoid retaining the `ImageProxy`.
