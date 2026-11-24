package dev.trinhhg.vapp.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import dev.trinhhg.vapp.exporter.api.CoverNormalizer
import dev.trinhhg.vapp.exporter.api.NormalizedCover
import java.io.ByteArrayOutputStream

/**
 * Android [CoverNormalizer] converting PNG/WebP/BMP cover images into universal JPEG via [BitmapFactory]
 * with a white background fill when source pixels contain alpha transparency.
 */
class AndroidCoverNormalizer(
    private val jpegQuality: Int = 90,
) : CoverNormalizer {
    override fun normalizeToJpeg(
        rawBytes: ByteArray,
        mimeType: String?,
    ): NormalizedCover? {
        if (rawBytes.isEmpty()) return null
        if (isJpeg(rawBytes)) {
            return NormalizedCover(rawBytes, extension = "jpg", mimeType = "image/jpeg")
        }

        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        return try {
            val rgbBitmap =
                if (decoded.hasAlpha() || decoded.config != Bitmap.Config.RGB_565) {
                    val out = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(out)
                    canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(decoded, 0f, 0f, null)
                    out
                } else {
                    decoded
                }
            val baos = ByteArrayOutputStream()
            val ok = rgbBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(50, 100), baos)
            if (rgbBitmap !== decoded) {
                rgbBitmap.recycle()
            }
            if (!ok) null else NormalizedCover(baos.toByteArray(), extension = "jpg", mimeType = "image/jpeg")
        } finally {
            decoded.recycle()
        }
    }

    private fun isJpeg(b: ByteArray): Boolean = b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
}
