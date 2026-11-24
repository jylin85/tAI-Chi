package com.example.poseengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject

/**
 * The coach v2 reference made by build_reference.py: reference_v2/reference.json + thumbs/, bundled in this
 * module's assets. To update it, re-run build_reference.py and copy reference.json and thumbs/ into
 * pose-engine/src/main/assets/reference_v2/ (the review/ folder and videos are not needed).
 */
internal class ReferenceV2(
    val keyframes: List<V2Keyframe>,
    private val context: Context,
    private val dir: String,
) {
    private var cachedIndex = -1
    private var cachedBitmap: Bitmap? = null

    /** Thumbnail of keyframe [i], decoded on demand (only the current one is kept), or null if it has none. */
    @Synchronized
    fun thumbnail(i: Int): Bitmap? {
        if (i !in keyframes.indices) return null
        if (i == cachedIndex) return cachedBitmap
        cachedIndex = i
        val kf = keyframes[i]
        val name = kf.thumbnail
        cachedBitmap = if (name.isNullOrEmpty()) null else try {
            context.assets.open("$dir/$name").use { stream ->
                val img = BitmapFactory.decodeStream(stream)
                // The student's view is a mirror; a front-facing instructor image is flipped to match it.
                if (img != null && kf.thumbnailFlip) {
                    Bitmap.createBitmap(img, 0, 0, img.width, img.height, Matrix().apply { preScale(-1f, 1f) }, true)
                } else img
            }
        } catch (_: Exception) { null }
        return cachedBitmap
    }

    companion object {
        fun load(context: Context, dir: String = "reference_v2"): ReferenceV2 {
            val root = context.assets.open("$dir/reference.json").bufferedReader().use { JSONObject(it.readText()) }
            val names = root.getJSONArray("joint_names")
            check((0 until names.length()).map { names.getString(it) } == JOINT_NAMES) {
                "Reference was built with a different joint set; rebuild it with build_reference.py"
            }
            val defaultHold = root.optJSONObject("config")?.optDouble("hold_s", 0.6) ?: 0.6
            val kfs = root.getJSONArray("keyframes")
            val out = ArrayList<V2Keyframe>(kfs.length())
            for (k in 0 until kfs.length()) {
                val kf = kfs.getJSONObject(k)
                val cues = HashMap<Pair<String, String>, String>()
                kf.optJSONArray("cues")?.let { arr ->
                    for (c in 0 until arr.length()) {
                        val cue = arr.getJSONObject(c)
                        cues[cue.getString("joint") to cue.getString("problem")] = cue.getString("message")
                    }
                }
                out += V2Keyframe(
                    id = kf.getString("id"),
                    movementNo = kf.optInt("movement_no", 0),
                    movementName = kf.optStringOrEmpty("movement_name"),
                    subName = kf.optStringOrEmpty("sub_name"),
                    holdS = kf.optDouble("hold_s", defaultHold).let { if (it.isNaN()) defaultHold else it },
                    instruction = kf.optStringOrEmpty("instruction"),
                    keyPoints = kf.optStringOrEmpty("key_points"),
                    mistakes = kf.optStringOrEmpty("mistakes"),
                    target = kf.getJSONArray("target").toDoubles(),
                    tol = kf.getJSONArray("tol").toDoubles(),
                    weight = kf.getJSONArray("weight").toDoubles(),
                    cues = cues,
                    thumbnail = if (kf.isNull("thumbnail")) null else kf.optString("thumbnail"),
                    thumbnailFlip = kf.optBoolean("thumbnail_flip", false),
                )
            }
            check(out.isNotEmpty()) { "reference has no keyframes" }
            return ReferenceV2(out, context.applicationContext, dir)
        }

        /** JSON null -> NaN, the same as matching.load_reference(). */
        private fun JSONArray.toDoubles() = DoubleArray(length()) { if (isNull(it)) Double.NaN else getDouble(it) }

        /** optString() turns JSON null into the text "null"; return "" instead. */
        private fun JSONObject.optStringOrEmpty(key: String) = if (isNull(key)) "" else optString(key, "")
    }
}
