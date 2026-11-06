plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.poseengine"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // ImageProxy is part of this module's public API, so expose CameraX core to the app.
    api("androidx.camera:camera-core:1.4.2")
    implementation(libs.mediapipe.tasks.vision)
    testImplementation(libs.junit)
}
