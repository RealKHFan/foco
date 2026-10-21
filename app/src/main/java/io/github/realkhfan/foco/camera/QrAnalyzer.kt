package io.github.realkhfan.foco.camera

import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Offline QR code detection on the viewfinder (ZXing, no Google services). Large frames are sampled down
 * first so scanning stays quick. Does not close the image.
 */
class QrScanner {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private var buffer = ByteArray(0)

    fun scan(image: ImageProxy): String? {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val step = maxOf(1, image.width / 1400)
        val w = image.width / step
        val h = image.height / step
        if (buffer.size != w * h) buffer = ByteArray(w * h)
        val src = plane.buffer.duplicate()
        if (step == 1) {
            for (row in 0 until h) {
                src.position(row * rowStride)
                src.get(buffer, row * w, minOf(w, src.remaining()))
            }
        } else {
            val line = ByteArray(rowStride)
            for (row in 0 until h) {
                src.position(row * step * rowStride)
                val n = minOf(rowStride, src.remaining())
                src.get(line, 0, n)
                val o = row * w
                for (x in 0 until w) buffer[o + x] = line[minOf(x * step, n - 1)]
            }
        }
        val luminance = PlanarYUVLuminanceSource(buffer, w, h, 0, 0, w, h, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(luminance))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}

/**
 * Rough "is the lens smudged?" check: a smudge spreads light like haze, so even the darkest spots of a
 * detailed, well-lit scene stay bright. Plain walls and dark scenes are ignored. Much simpler than Google's.
 */
object LensHaze {
    /** @return true if this frame looks hazy. */
    fun looksHazy(image: ImageProxy): Boolean {
        val gw = 48
        val gh = 36
        val yp = image.planes[0]
        val up = image.planes[1]
        val vp = image.planes[2]
        val yb = yp.buffer
        val ub = up.buffer
        val vb = vp.buffer
        val dark = FloatArray(gw * gh)
        val luma = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x = (gx + 0.5f) * image.width / gw
            val y = (gy + 0.5f) * image.height / gh
            val yi = y.toInt() * yp.rowStride + x.toInt() * yp.pixelStride
            val ci = (y.toInt() / 2) * up.rowStride + (x.toInt() / 2) * up.pixelStride
            val cv = (y.toInt() / 2) * vp.rowStride + (x.toInt() / 2) * vp.pixelStride
            if (yi >= yb.limit() || ci >= ub.limit() || cv >= vb.limit()) continue
            val yy = (yb.get(yi).toInt() and 0xff).toFloat()
            val uu = (ub.get(ci).toInt() and 0xff) - 128f
            val vv = (vb.get(cv).toInt() and 0xff) - 128f
            val r = yy + 1.402f * vv
            val g = yy - 0.344f * uu - 0.714f * vv
            val b = yy + 1.772f * uu
            dark[gy * gw + gx] = minOf(r, g, b).coerceIn(0f, 255f)
            luma[gy * gw + gx] = yy
        }
        var darkChannel = 0f
        var edges = 0f
        var mean = 0f
        var n = 0
        for (gy in 1 until gh - 1) for (gx in 1 until gw - 1) {
            var m = 255f
            for (dy in -1..1) for (dx in -1..1) m = minOf(m, dark[(gy + dy) * gw + gx + dx])
            darkChannel += m
            val c = luma[gy * gw + gx]
            edges += kotlin.math.abs(c - luma[gy * gw + gx + 1]) + kotlin.math.abs(c - luma[(gy + 1) * gw + gx])
            mean += c
            n++
        }
        darkChannel /= n
        edges /= n
        mean /= n
        return mean in 70f..200f && edges > 9f && darkChannel > 95f
    }
}
