package com.example.tai_chi

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.poseengine.PoseFeedback
import com.example.poseengine.MediaPipePoseFeedbackEngine
import com.example.poseengine.PoseFeedbackEngine
import com.example.poseengine.ReferenceImage
import com.example.tai_chi.ui.theme.TAiChiTheme
import java.io.File
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { setContentViewForCamera(hasCamera()) }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // Camera for the pose detector; Bluetooth for the two IMU boards.
    private fun requiredPermissions(): List<String> =
        listOf(Manifest.permission.CAMERA) +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Practice and recording sessions run for minutes without anyone touching the screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentViewForCamera(hasCamera())
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }

    private fun setContentViewForCamera(hasPermission: Boolean) {
        setContent {
            TAiChiTheme {
                if (hasPermission) {
                    TaiChiCameraScreen()
                } else {
                    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101512)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Camera access is needed to give Tai-Chi form feedback.",
                                color = Color.White,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaiChiCameraScreen() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val feedback = remember {
        mutableStateListOf(
            PoseFeedback(
                "Ready to practice",
                "Stand far enough back for your whole body to fit in the frame."
            )
        )
    }
    // The reference picture comes straight from the engine (not from the per-frame feedback), and the
    // last one received is kept, so it stays on screen whether or not a person is detected.
    var pinnedReference by remember { mutableStateOf<ReferenceImage?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val engine: PoseFeedbackEngine = remember { MediaPipePoseFeedbackEngine(context) }

    val imu = remember { ImuClient(context) }

    // Session recording (training data). Files go to the app's folder on the tablet; see RECORDINGS.md.
    val recorder = remember {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "unknown"
        SessionRecorder(context, version)
    }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }   // null: no video on this device
    var activeVideo by remember { mutableStateOf<Recording?>(null) }
    var session by remember { mutableStateOf<SessionRecorder.Session?>(null) }
    var recordMessage by remember { mutableStateOf<String?>(null) }
    var showRecordDialog by remember { mutableStateOf(false) }
    var nowNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(session) {
        while (session != null) { nowNs = SystemClock.elapsedRealtimeNanos(); delay(500) }
    }

    fun startRecording(performer: String, notes: String) {
        val s = recorder.start(performer, notes, SystemClock.elapsedRealtimeNanos())
        session = s
        nowNs = s.startedNs
        recordMessage = null
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED   // turning would end the session
        val vc = videoCapture
        if (vc == null) {
            recorder.videoUnavailable(s.id, "this tablet cannot record video while the pose detector runs")
            return
        }
        recorder.expectVideo(s.id)
        try {
            activeVideo = vc.output
                .prepareRecording(context, FileOutputOptions.Builder(File(s.dir, "video.mp4")).build())
                .start(ContextCompat.getMainExecutor(context)) { e ->
                    when (e) {
                        is VideoRecordEvent.Start -> recorder.videoStarted(s.id, "video.mp4", SystemClock.elapsedRealtimeNanos())
                        is VideoRecordEvent.Finalize -> recorder.videoFinished(
                            s.id, s.dir, e.recordingStats.recordedDurationNanos,
                            if (e.hasError()) "error ${e.error}: ${e.cause?.message ?: "unknown"}" else null
                        )
                    }
                }
        } catch (e: Exception) {
            recorder.videoUnavailable(s.id, e.message ?: e.javaClass.simpleName)
        }
    }

    fun stopRecording() {
        activeVideo?.stop()
        activeVideo = null
        val end = SystemClock.elapsedRealtimeNanos()
        val s = recorder.stop(end) ?: return
        session = null
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        recordMessage = "Saving ${s.id} · ${formatDuration(end - s.startedNs)} · " +
            "${recorder.frames.get()} frames · IMU L ${recorder.imuLeft.get()} / R ${recorder.imuRight.get()}…"
    }

    DisposableEffect(Unit) {
        recorder.onSaved = { id, location ->
            activity.runOnUiThread {
                recordMessage = recordMessage?.takeIf { it.contains(id) }?.removePrefix("Saving ")?.removeSuffix("…")
                    .let { summary -> "Saved ${summary ?: id}\nTablet folder: $location" }
            }
        }
        recorder.moveLeftovers()
        imu.onReading = { side, r -> recorder.imu(side, r) }
        imu.start()
        onDispose {
            if (recorder.isRecording) { activeVideo?.stop(); recorder.stop(SystemClock.elapsedRealtimeNanos()) }
            imu.onReading = null
            imu.stop()
            recorder.close()
            engine.close()
            cameraExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            // RGBA frames convert to a Bitmap cheaply, which is what the pose detector reads.
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()
                        analysis.setAnalyzer(cameraExecutor) { image ->
                            val receivedNs = SystemClock.elapsedRealtimeNanos()
                            try {
                                val latest = engine.analyze(image)
                                recorder.frame(receivedNs, engine.lastFrame())
                                val reference = engine.currentReference()
                                activity.runOnUiThread {
                                    if (reference != null) pinnedReference = reference
                                    feedback.clear()
                                    feedback.addAll(latest)
                                }
                            } catch (e: Exception) {
                                activity.runOnUiThread {
                                    if (feedback.none { it.title == "Detector error" }) {
                                        feedback.add(
                                            PoseFeedback(
                                                "Detector error",
                                                "The pose engine could not process this frame: ${e.message ?: e.javaClass.simpleName}",
                                                com.example.poseengine.Severity.WARNING
                                            )
                                        )
                                    }
                                }
                            } finally {
                                image.close()
                            }
                        }
                        // Video for recorded sessions. Mirrored like the preview, so it looks like what the
                        // performer saw. No audio.
                        val video = VideoCapture.Builder(
                            Recorder.Builder().setQualitySelector(
                                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                            ).build()
                        ).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
                        try {
                            provider.unbindAll()
                            try {
                                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis, video)
                                videoCapture = video
                            } catch (_: Exception) {
                                // Some cameras cannot run preview, analysis and video at once: keep coaching,
                                // record data without video.
                                provider.unbindAll()
                                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                                videoCapture = null
                            }
                        } catch (_: Exception) {
                            activity.runOnUiThread {
                                feedback.clear()
                                feedback.add(
                                    PoseFeedback(
                                        "Camera unavailable",
                                        "Could not start the front camera on this device."
                                    )
                                )
                            }
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
        )

        // A translucent, vertically scrolling rail overlays the camera feed.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.34f)
                .background(Color(0xD9101713))
                .systemBarsPadding()   // Android 15 draws apps edge to edge: keep clear of the system bars
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Text(
                "TAI CHI",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "FORM FEEDBACK",
                color = Color(0xFFB8D9C3),
                fontSize = 14.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 14.dp)
            )
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { engine.previous() }, modifier = Modifier.weight(1f)) {
                    Text("Prev", color = Color(0xFFB8D9C3), fontSize = 16.sp)
                }
                TextButton(onClick = { engine.restart() }, modifier = Modifier.weight(1f)) {
                    Text("Restart", color = Color(0xFFB8D9C3), fontSize = 16.sp)
                }
                TextButton(onClick = { engine.next() }, modifier = Modifier.weight(1f)) {
                    Text("Skip", color = Color(0xFFB8D9C3), fontSize = 16.sp)
                }
            }
            RecordControls(
                recording = session != null,
                elapsedNs = session?.let { nowNs - it.startedNs } ?: 0L,
                hasVideo = videoCapture != null,
                message = recordMessage,
                onRecord = { showRecordDialog = true },
                onStop = { stopRecording() },
            )
            ReferencePicture(pinnedReference)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(feedback.filter { !it.isAdjustment() }) { item ->
                    FeedbackCard(item)
                }
            }
        }

        if (showRecordDialog) {
            RecordDialog(
                onStart = { performer, notes -> showRecordDialog = false; startRecording(performer, notes) },
                onDismiss = { showRecordDialog = false },
            )
        }

        // Live accelerometer / gyroscope readings from the two IMU boards, bottom left beside the rail.
        ImuPanel(
            imu = imu,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.66f)
                .navigationBarsPadding()
                .padding(12.dp)
        )

        // Corrections the user needs to make right now get their own large banner across the top,
        // beside the side rail, so they are readable from a distance.
        val adjustments = feedback.filter { it.isAdjustment() }
        if (adjustments.isNotEmpty()) {
            AdjustmentsPanel(
                adjustments = adjustments,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.66f)
                    .statusBarsPadding()
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun ImuPanel(imu: ImuClient, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ImuCard("LEFT IMU", imu.left.value, Modifier.weight(1f))
        ImuCard("RIGHT IMU", imu.right.value, Modifier.weight(1f))
    }
}

@Composable
private fun ImuCard(title: String, state: ImuClient.State, modifier: Modifier = Modifier) {
    val r = state.reading
    Column(
        modifier = modifier
            .background(Color(0xE6101713), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        // Title, with the band's battery on the right once it is connected.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            if (r != null) BatteryIndicator(state.battery, state.batterySupported)
        }
        Text(
            state.status,
            color = if (r != null) Color(0xFF78D6A1) else Color(0xFFFFD27A),
            fontSize = 14.sp
        )
        state.battery?.takeIf { r != null }?.let { b ->
            val low = b.percent < BatteryLevel.LOW_PERCENT && !b.charging
            Text(
                when {
                    b.charging -> "Battery %.2f V · charging".format(b.volts)
                    low -> "Battery low (%.2f V): charge this band soon".format(b.volts)
                    else -> "Battery %.2f V".format(b.volts)
                },
                color = if (low) batteryColor(b.percent) else Color(0xFFB8D9C3),
                fontSize = 12.sp
            )
        }
        if (r != null) {
            Text(
                "ACCEL (g)",
                color = Color(0xFFB8D9C3),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                "x %+.2f  y %+.2f  z %+.2f".format(r.ax, r.ay, r.az),
                color = Color.White,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "GYRO (°/s)",
                color = Color(0xFFB8D9C3),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "x %+.1f  y %+.1f  z %+.1f".format(r.gx, r.gy, r.gz),
                color = Color.White,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        // Time-sync debug line: measured drift, Bluetooth delay of the latest packet, lost packets.
        state.clock?.let {
            Text(
                it,
                color = Color(0xFFB8D9C3),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun PoseFeedback.isAdjustment() =
    severity == com.example.poseengine.Severity.TIP || severity == com.example.poseengine.Severity.WARNING

@Composable
private fun AdjustmentsPanel(adjustments: List<PoseFeedback>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xE6101713), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "ADJUST",
            color = Color(0xFFFFD27A),
            fontSize = 16.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
        adjustments.take(3).forEach { item ->
            val color = if (item.severity == com.example.poseengine.Severity.WARNING)
                Color(0xFFFF9188) else Color.White
            Text(
                item.detail,
                color = color,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun FeedbackCard(item: PoseFeedback) {
    val accent = when (item.severity) {
        com.example.poseengine.Severity.GOOD -> Color(0xFF78D6A1)
        com.example.poseengine.Severity.TIP -> Color(0xFFFFD27A)
        com.example.poseengine.Severity.WARNING -> Color(0xFFFF9188)
        com.example.poseengine.Severity.INFO -> Color(0xFFB8D9C3)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF26332B), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(
            item.title,
            color = accent,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            item.detail,
            color = Color(0xFFF2F5F2),
            fontSize = 17.sp,
            lineHeight = 23.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

/** The pose to copy, pinned above the scrolling feedback so it is always visible. */
@Composable
private fun ReferencePicture(ref: ReferenceImage?) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            if (ref != null) "POSE TO COPY  ·  ${ref.label}" else "POSE TO COPY",
            color = Color(0xFFB8D9C3),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(top = 6.dp)
                .background(Color.Black, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (ref != null) {
                Image(
                    bitmap = ref.bitmap.asImageBitmap(),
                    contentDescription = "Pose to copy",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("Loading…", color = Color(0xFFB8D9C3), fontSize = 14.sp)
            }
        }
    }
}