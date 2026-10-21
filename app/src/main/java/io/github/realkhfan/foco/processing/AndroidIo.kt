package io.github.realkhfan.foco.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import io.github.realkhfan.foco.media.MediaNaming
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Copies a YUV_420_888 camera image into a [YuvFrame] (handles any row/pixel stride). */
object FrameCopy {
    fun matches(frame: YuvFrame?, image: ImageProxy) = frame != null && frame.width == image.width && frame.height == image.height

    fun copy(image: ImageProxy, into: YuvFrame) {
        val w = into.width
        val h = into.height
        val yPlane = image.planes[0]
        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, w, h, into.y)
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        copyPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, into.chromaWidth, into.chromaHeight, into.u)
        copyPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, into.chromaWidth, into.chromaHeight, into.v)
        into.timestampNs = image.imageInfo.timestamp
    }

    private fun copyPlane(src: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, dst: ByteArray) {
        val buffer = src.duplicate()
        if (pixelStride == 1) {
            if (rowStride == w) {
                buffer.position(0)
                buffer.get(dst, 0, minOf(dst.size, buffer.remaining()))
                return
            }
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                buffer.get(dst, row * w, minOf(w, buffer.remaining()))
            }
            return
        }
        val rowBytes = ByteArray(rowStride)
        for (row in 0 until h) {
            buffer.position(row * rowStride)
            val n = minOf(rowStride, buffer.remaining())
            buffer.get(rowBytes, 0, n)
            val o = row * w
            var x = 0
            var s = 0
            while (x < w && s < n) {
                dst[o + x] = rowBytes[s]
                x++
                s += pixelStride
            }
        }
    }
}

/** Facts written into the photo's Exif. */
data class PhotoMeta(
    val timeMillis: Long,
    val exposureNs: Long?,
    val iso: Int?,
    val aperture: Float?,
    val focalLengthMm: Float?,
    val focal35mm: Int?,
    val location: Location?,
    val includeDevice: Boolean,
)

/** Renders, encodes (Ultra HDR when there is a gain map) and saves a processed photo. */
object PhotoWriter {
    private const val BAND = 96

    fun encode(renderer: Renderer, ultraHdr: Boolean, quality: Int = 95): ByteArray {
        val bitmap = createBitmap(renderer.outWidth, renderer.outHeight)
        val band = IntArray(renderer.outWidth * BAND)
        var row = 0
        while (row < renderer.outHeight) {
            val rows = minOf(BAND, renderer.outHeight - row)
            renderer.render(row, rows, band)
            bitmap.setPixels(band, 0, renderer.outWidth, 0, row, renderer.outWidth, rows)
            row += rows
        }
        if (ultraHdr && Build.VERSION.SDK_INT >= 34) {
            renderer.gainMap()?.let { attachGainMap(bitmap, it) }
        }
        val out = ByteArrayOutputStream(renderer.outWidth * renderer.outHeight / 2)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Plain JPEG from any band renderer (panoramas). */
    fun encodePixels(width: Int, height: Int, render: (row0: Int, rows: Int, out: IntArray) -> Unit, quality: Int = 93): ByteArray {
        val bitmap = createBitmap(width, height)
        val band = IntArray(width * BAND)
        var row = 0
        while (row < height) {
            val rows = minOf(BAND, height - row)
            render(row, rows, band)
            bitmap.setPixels(band, 0, width, 0, row, width, rows)
            row += rows
        }
        val out = ByteArrayOutputStream(width * height / 2)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @androidx.annotation.RequiresApi(34)
    private fun attachGainMap(bitmap: Bitmap, map: Renderer.GainMap) {
        val contents = createBitmap(map.width, map.height, Bitmap.Config.ALPHA_8)
        if (contents.rowBytes == map.width) {
            contents.copyPixelsFromBuffer(ByteBuffer.wrap(map.data))
        } else {
            val padded = ByteArray(contents.rowBytes * map.height)
            for (y in 0 until map.height) System.arraycopy(map.data, y * map.width, padded, y * contents.rowBytes, map.width)
            contents.copyPixelsFromBuffer(ByteBuffer.wrap(padded))
        }
        val gainmap = Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(map.headroom, map.headroom, map.headroom)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            setDisplayRatioForFullHdr(map.headroom)
            setMinDisplayRatioForHdrTransition(1f)
        }
        bitmap.gainmap = gainmap
    }

    /** Builds an Exif block by writing tags into a tiny JPEG with ExifInterface and cutting the segment out. */
    fun exifSegment(context: Context, meta: PhotoMeta, width: Int, height: Int): ByteArray? = runCatching {
        val tmp = File(context.cacheDir, "exif-${System.nanoTime()}.jpg")
        try {
            val one = createBitmap(8, 8)
            tmp.outputStream().use { one.compress(Bitmap.CompressFormat.JPEG, 50, it) }
            one.recycle()
            val exif = ExifInterface(tmp)
            val date = Date(meta.timeMillis)
            val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date)
            val offset = SimpleDateFormat("XXX", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(date)
            val sub = (meta.timeMillis % 1000).toString().padStart(3, '0')
            exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, sub)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Foco")
            if (meta.includeDevice) {
                exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            }
            meta.exposureNs?.let { exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString()) }
            meta.iso?.let { exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
            meta.aperture?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
            meta.focalLengthMm?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000") }
            meta.focal35mm?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString()) }
            meta.location?.let { exif.setGpsInfo(it) }
            exif.saveAttributes()
            JpegSplice.exifSegment(tmp.readBytes())
        } finally {
            tmp.delete()
        }
    }.getOrNull()

    /** Saves into DCIM/Camera (or [toFile]) and returns the URI. */
    fun save(context: Context, jpeg: ByteArray, name: String, toFile: File?): Uri? {
        if (toFile != null) {
            toFile.writeBytes(jpeg)
            return Uri.fromFile(toFile)
        }
        val resolver = context.contentResolver
        val values = MediaNaming.contentValues(name, "image/jpeg").apply {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Quick thumbnail straight from the first frame, shown while the photo is still processing. */
    fun previewBitmap(frame: YuvFrame, rotation: Int, mirror: Boolean, crop: IntArray?): Bitmap {
        val (pixels, w, h) = Renderer.preview(frame, 320, rotation, mirror, crop)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
