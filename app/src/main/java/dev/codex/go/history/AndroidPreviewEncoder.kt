package dev.codex.go.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Used exclusively for historical copies, never for phone_observe's current image. */
class AndroidPreviewEncoder : PreviewEncoder {
    override fun encode(bytes: ByteArray): ImagePreview? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000) return null
        if (bounds.outMimeType !in setOf("image/png", "image/jpeg", "image/webp")) return null
        // Animated WebP cannot be represented faithfully by a single preview frame.
        if (bounds.outMimeType == "image/webp" && bytes.take(64).toByteArray().toString(Charsets.ISO_8859_1).contains("ANIM")) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 768) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        var scaled: Bitmap? = null
        var opaque: Bitmap? = null
        try {
            val factor = (768.0 / max(bitmap.width, bitmap.height)).coerceAtMost(1.0)
            scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).roundToInt().coerceAtLeast(1), (bitmap.height * factor).roundToInt().coerceAtLeast(1), true)
            val output = ByteArrayOutputStream()
            val encoded = if (scaled.hasAlpha()) {
                Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also { target ->
                    opaque = target; Canvas(target).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
                }
            } else scaled
            if (!encoded.compress(Bitmap.CompressFormat.JPEG, 65, output)) return null
            return ImagePreview(output.toByteArray(), scaled.width, scaled.height, bounds.outWidth, bounds.outHeight, bounds.outMimeType!!)
        } finally { opaque?.recycle(); if (scaled !== bitmap) scaled?.recycle(); bitmap.recycle() }
    }
}
