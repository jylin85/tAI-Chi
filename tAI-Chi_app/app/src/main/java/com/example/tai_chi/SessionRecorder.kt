package com.example.tai_chi

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.poseengine.POSE_JOINT_NAMES
import com.example.poseengine.POSE_LANDMARK_COUNT
import com.example.poseengine.PoseFrame
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Records a practice session as training data: every camera frame's pose (camera.csv), every IMU
 * reading from both bands (imu.csv), and a description of the session (session.json). The video
 * itself is recorded by CameraX into the same folder (see MainActivity).
 *
 * While recording, files are written to the app's private folder. When a session is finished (and its
 * video has been saved), the folder is moved to the tablet's public Download/tAI-Chi/ folder, where the
 * Files app can see it and where it survives the app being uninstalled. Sessions left in the private
 * folder (the app closed before they were moved) are moved the next time the app starts.
 *
 * All times are tablet time, SystemClock.elapsedRealtimeNanos, so camera and IMU rows line up
 * directly. File writing happens on one background thread, so recording never slows down the camera
 * analysis or the Bluetooth callbacks. The file formats are described in tAI-Chi_app/RECORDINGS.md.
 */
class SessionRecorder(private val context: Context, private val appVersion: String) {

    class Session(val id: String, val dir: File, val startedNs: Long)

    /** Private folder where sessions are written while recording. */
    private val root = File(context.getExternalFilesDir(null), "recordings")

    /** Called on a background thread once a session has been saved, with where it was saved. */
    @Volatile var onSaved: ((sessionId: String, location: String) -> Unit)? = null

    // A finished session is moved once it is stopped AND its video file is complete. io thread only.
    private class Pending(val dir: File, var stopped: Boolean = false, var videoPending: Boolean = false)
    private val pending = mutableMapOf<String, Pending>()

    private val io = Executors.newSingleThreadExecutor()

    /** Runs [block] on the file-writing thread; ignored after [close] (e.g. a late video event). */
    private fun post(block: () -> Unit) {
        try { io.execute(block) } catch (_: RejectedExecutionException) {}
    }
    @Volatile private var session: Session? = null
    private var camera: BufferedWriter? = null      // io thread only
    private var imuOut: BufferedWriter? = null      // io thread only
    private var meta = JSONObject()                 // io thread only
    private var cameraTimeSource: String? = null    // io thread only

    val frames = AtomicInteger()
    val imuLeft = AtomicInteger()
    val imuRight = AtomicInteger()

    val isRecording: Boolean get() = session != null
    val current: Session? get() = session

    /** Starts a new session folder. [performer] and [notes] are saved in session.json. */
    fun start(performer: String, notes: String, nowNs: Long): Session {
        check(session == null) { "already recording" }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safeName = performer.trim().replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "unknown" }
        val id = "${stamp}_$safeName"
        val dir = File(root, id).apply { mkdirs() }
        val s = Session(id, dir, nowNs)
        frames.set(0); imuLeft.set(0); imuRight.set(0)
        session = s
        post {
            pending[id] = Pending(dir)
            cameraTimeSource = null
            camera = File(dir, "camera.csv").bufferedWriter().apply { write(cameraHeader()); newLine() }
            imuOut = File(dir, "imu.csv").bufferedWriter().apply {
                write("time_ns,side,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,seq,board_ticks,arrival_ns,clock_offset_ns")
                newLine()
            }
            meta = JSONObject().apply {
                put("format_version", 1)
                put("session_id", id)
                put("performer", performer.trim())
                put("notes", notes.trim())
                put("status", "recording")
                put("started_wall_clock", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
                put("started_ns", nowNs)
                put("time_base", "SystemClock.elapsedRealtimeNanos on the tablet")
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                put("app_version", appVersion)
                put("joint_names", JSONArray(POSE_JOINT_NAMES))
                put("landmark_count", POSE_LANDMARK_COUNT)
            }
            writeMeta(dir)
        }
        return s
    }

    /** One analysed camera frame. [receivedNs] is when the frame reached the analyser. */
    fun frame(receivedNs: Long, f: PoseFrame?) {
        if (f == null || session == null) return
        frames.incrementAndGet()
        post {
            val w = camera ?: return@post
            // Use the camera's own frame time if it is on the tablet clock (most devices); otherwise fall
            // back to the time the frame reached the analyser. Decided once per session, from the first frame.
            val source = cameraTimeSource ?: (if (abs(f.imageTimestampNs - receivedNs) < 1_000_000_000L) "camera" else "received")
                .also { cameraTimeSource = it; meta.put("camera_time_source", it) }
            val t = if (source == "camera") f.imageTimestampNs else receivedNs
            val sb = StringBuilder(2048)
            sb.append(t).append(',').append(f.imageTimestampNs).append(',').append(receivedNs).append(',')
            sb.append(f.poseIndex).append(',').append(if (f.angles != null) 1 else 0)
            val n = POSE_JOINT_NAMES.size
            for (j in 0 until n) {
                sb.append(',')
                val a = f.angles?.getOrNull(j)
                if (a != null && !a.isNaN()) sb.append((a * 100).roundToLong() / 100.0)
            }
            for (i in 0 until POSE_LANDMARK_COUNT) {
                appendFloats(sb, f.world, i * 3, 3)
                appendFloats(sb, f.image, i * 2, 2)
                appendFloats(sb, f.visibility, i, 1)
            }
            w.write(sb.toString()); w.newLine()
        }
    }

    /** One IMU reading, from a Bluetooth thread. */
    fun imu(side: ImuClient.Side, r: ImuClient.Reading) {
        if (session == null) return
        (if (side == ImuClient.Side.LEFT) imuLeft else imuRight).incrementAndGet()
        post {
            val w = imuOut ?: return@post
            w.write(
                "${r.timeNs},${if (side == ImuClient.Side.LEFT) "L" else "R"}," +
                    "${r.ax},${r.ay},${r.az},${r.gx},${r.gy},${r.gz}," +
                    "${r.seq},${r.boardTicks},${r.arrivalNs},${r.offsetNs}"
            )
            w.newLine()
        }
    }

    /** Call right after [start] if a video recording is being started for this session. */
    fun expectVideo(sessionId: String) = post { pending[sessionId]?.videoPending = true }

    /** Video bookkeeping, from CameraX's recording events. */
    fun videoStarted(sessionId: String, file: String, startNs: Long) = post {
        if (meta.optString("session_id") != sessionId) return@post
        meta.put("video_file", file)
        meta.put("video_start_ns", startNs)
        meta.put("video_start_note", "approximate (within about 0.1 s): when CameraX reported the recording had started")
        session?.dir?.let { writeMeta(it) }
    }

    /** The video file is complete. May arrive after [stop], even after the next session has started. */
    fun videoFinished(sessionId: String, dir: File, durationNs: Long, error: String?) = post {
        val target = if (meta.optString("session_id") == sessionId) meta
            else runCatching { JSONObject(File(dir, "session.json").readText()) }.getOrElse { JSONObject() }
        target.put("video_duration_ns", durationNs)
        if (error != null) target.put("video_error", error)
        File(dir, "session.json").writeText(target.toString(2))
        pending[sessionId]?.videoPending = false
        tryMove(sessionId)
    }

    /** No video for this session: [reason] is saved in session.json. */
    fun videoUnavailable(sessionId: String, reason: String) = post {
        if (meta.optString("session_id") == sessionId) meta.put("video_error", reason)
        pending[sessionId]?.videoPending = false
        tryMove(sessionId)
    }

    /** Stops recording and closes the CSV files. Returns the finished session, or null if none. */
    fun stop(nowNs: Long): Session? {
        val s = session ?: return null
        session = null
        val counts = Triple(frames.get(), imuLeft.get(), imuRight.get())
        post {
            camera?.close(); camera = null
            imuOut?.close(); imuOut = null
            meta.put("status", "complete")
            meta.put("ended_ns", nowNs)
            meta.put("duration_s", (nowNs - s.startedNs) / 1e9)
            meta.put("camera_frames", counts.first)
            meta.put("imu_readings_left", counts.second)
            meta.put("imu_readings_right", counts.third)
            writeMeta(s.dir)
            pending[s.id]?.stopped = true
            tryMove(s.id)
        }
        return s
    }

    /** Moves sessions left in the private folder by an earlier run of the app. Call once at startup. */
    fun moveLeftovers() = post {
        val active = session?.id
        root.listFiles()?.filter { it.isDirectory && it.name != active && it.name !in pending }
            ?.sortedBy { it.name }
            ?.forEach { move(it) }
    }

    fun close() { stop(android.os.SystemClock.elapsedRealtimeNanos()); io.shutdown() }

    private fun tryMove(id: String) {
        val p = pending[id] ?: return
        if (!p.stopped || p.videoPending) return
        pending.remove(id)
        move(p.dir)
    }

    /**
     * Copies a finished session folder to Download/tAI-Chi/<id>/ and deletes the private copy.
     * On Android 9 and older the private folder is visible to file managers, so it stays there.
     */
    private fun move(dir: File) {
        if (Build.VERSION.SDK_INT < 29) {
            onSaved?.invoke(dir.name, dir.path)
            return
        }
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_FOLDER/${dir.name}/"
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            for (f in dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }) {
                // A previous attempt may have left a partial copy: replace it rather than adding "name (1)".
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(relative, f.name)
                )
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType(f.name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: throw IOException("could not create ${f.name}")
                (resolver.openOutputStream(uri) ?: throw IOException("could not write ${f.name}")).use { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            dir.deleteRecursively()
            onSaved?.invoke(dir.name, relative.trimEnd('/'))
        } catch (e: Exception) {
            // Keep the private copy; the next app start tries again.
            onSaved?.invoke(dir.name, "${dir.path} (could not move to Download: ${e.message})")
        }
    }

    private fun mimeType(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mp4" -> "video/mp4"
        "csv" -> "text/csv"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    companion object {
        /** Sessions end up in Download/<PUBLIC_FOLDER>/ on the tablet. */
        const val PUBLIC_FOLDER = "tAI-Chi"
    }

    private fun writeMeta(dir: File) {
        File(dir, "session.json").writeText(meta.toString(2))
    }

    private fun appendFloats(sb: StringBuilder, src: FloatArray?, from: Int, count: Int) {
        for (k in 0 until count) {
            sb.append(',')
            if (src != null) sb.append(src[from + k])
        }
    }

    private fun cameraHeader(): String {
        val sb = StringBuilder("time_ns,camera_timestamp_ns,received_ns,pose_index,person_detected")
        for (j in POSE_JOINT_NAMES) sb.append(',').append(j).append("_deg")
        for (i in 0 until POSE_LANDMARK_COUNT) {
            sb.append(",wx_$i,wy_$i,wz_$i,ix_$i,iy_$i,vis_$i")
        }
        return sb.toString()
    }
}
