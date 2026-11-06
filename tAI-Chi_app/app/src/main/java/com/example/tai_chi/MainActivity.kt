package com.example.tai_chi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.poseengine.PoseFeedback
import com.example.poseengine.MediaPipePoseFeedbackEngine
import com.example.poseengine.PoseFeedbackEngine
import com.example.tai_chi.ui.theme.TAiChiTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> setContentViewForCamera(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewForCamera(
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
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
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val engine: PoseFeedbackEngine = remember { MediaPipePoseFeedbackEngine(context) }

    DisposableEffect(Unit) {
        onDispose {
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
                            try {
                                val latest = engine.analyze(image)
                                activity.runOnUiThread {
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
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                activity,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                preview,
                                analysis
                            )
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
                .width(148.dp)
                .background(Color(0xD9101713))
                .padding(horizontal = 10.dp, vertical = 18.dp)
        ) {
            Text(
                "TAI CHI",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "FORM FEEDBACK",
                color = Color(0xFFB8D9C3),
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 14.dp)
            )
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { engine.previous() }, modifier = Modifier.weight(1f)) {
                    Text("Prev", color = Color(0xFFB8D9C3), fontSize = 11.sp)
                }
                TextButton(onClick = { engine.restart() }, modifier = Modifier.weight(1f)) {
                    Text("Restart", color = Color(0xFFB8D9C3), fontSize = 11.sp)
                }
                TextButton(onClick = { engine.next() }, modifier = Modifier.weight(1f)) {
                    Text("Skip", color = Color(0xFFB8D9C3), fontSize = 11.sp)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(feedback) { item ->
                    FeedbackCard(item)
                }
            }
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
            .padding(10.dp)
    ) {
        Text(
            item.title,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            item.detail,
            color = Color(0xFFF2F5F2),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
        item.thumbnail?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Pose to copy",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(Color.Black, RoundedCornerShape(6.dp))
            )
        }
    }
}
