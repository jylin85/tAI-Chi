package com.example.poseengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import org.json.JSONObject

/**
 * The keyframes made by extract_reference.py: reference/reference.json + kf_XX.jpg, bundled in this
 * module's assets. To use a different reference, replace those files (see POSE_ENGINE_INTEGRATION.md).
 */
internal class ReferenceSequence(
    val targets: List<DoubleArray>,
    private val thumbnailNames: List<String>,
    private val mirrored: Boolean,
    private val context: Context,
    private val dir: String,
) {
    private var cachedIndex = -1
    private var cachedBitmap: Bitmap? = null

    /** Thumbnail of keyframe [i], decoded on demand at half size (only the current one is kept in memory). */
    @Synchronized
    fun thumbnail(i: Int): Bitmap? {
        if (i !in thumbnailNames.indices) return null
        if (i == cachedIndex) return cachedBitmap
        cachedIndex = i
        cachedBitmap = try {
            context.assets.open("$dir/${thumbnailNames[i]}").use { stream ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val img = BitmapFactory.decodeStream(stream, null, opts)
                // The coach view is a mirror: in same-side mode flip the thumbnail so it looks like what
                // the student should see (coach.py does the same); in mirror mode it already matches.
                if (img != null && !mirrored) {
                    Bitmap.createBitmap(img, 0, 0, img.width, img.height, Matrix().apply { preScale(-1f, 1f) }, true)
                } else img
            }
        } catch (_: Exception) { null }
        return cachedBitmap
    }

    companion object {
        fun load(context: Context, dir: String = "reference"): ReferenceSequence {
            val root = context.assets.open("$dir/reference.json").bufferedReader().use { JSONObject(it.readText()) }
            val names = root.getJSONArray("joint_names")
            check((0 until names.length()).map { names.getString(it) } == JOINT_NAMES) {
                "reference was made with different joints"
            }
            val kfs = root.getJSONArray("keyframes")
            val targets = ArrayList<DoubleArray>(kfs.length())
            val thumbs = ArrayList<String>(kfs.length())
            for (k in 0 until kfs.length()) {
                val kf = kfs.getJSONObject(k)
                val a = kf.getJSONArray("angles")
                targets.add(DoubleArray(a.length()) { if (a.isNull(it)) Double.NaN else a.getDouble(it) })
                thumbs.add(kf.getString("thumbnail"))
            }
            check(targets.isNotEmpty()) { "reference has no keyframes" }
            return ReferenceSequence(targets, thumbs, root.optBoolean("mirrored", false), context.applicationContext, dir)
        }
    }
}
