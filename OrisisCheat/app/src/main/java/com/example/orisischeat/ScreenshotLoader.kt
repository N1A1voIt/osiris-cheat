package com.example.orisischeat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Loads a screenshot file as compact JPEG bytes suitable for a vision model. */
object ScreenshotLoader {

    private const val MAX_DIMENSION = 1568 // ~Gemini tile-friendly size

    /**
     * Decodes the image at [path], downscales it so its longest side is at most
     * [MAX_DIMENSION] px, and returns it as JPEG bytes.
     */
    fun loadForModel(path: String): Pair<ByteArray, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= MAX_DIMENSION) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(path, opts)
            ?: throw IllegalStateException("Could not decode screenshot at $path")

        val oriented = applyExifRotation(bitmap, path)
        val scaled = scaleDown(oriented)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        val consumed = listOf(bitmap, oriented, scaled).distinct()
        consumed.forEach { if (it != scaled) it.recycle() }
        scaled.recycle()
        return bytes to "image/jpeg"
    }

    /** Camera photos store rotation in EXIF; screenshots don't (no-op for them). */
    private fun applyExifRotation(bitmap: Bitmap, path: String): Bitmap {
        val rotation = when (
            runCatching { ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            ) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
