package io.github.realkhfan.foco.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * One scanned page. [corners] are the page outline in the upright photo, as fractions (0..1) in the order
 * top-left, top-right, bottom-right, bottom-left. [rotation] is how far the photo must be turned clockwise to be
 * upright; [turn] is the extra turn the user gave the page. [focal] is the focal length divided by the photo's
 * long side (0 if unknown), for the real page proportions. [version] changes with every edit (for caches).
 */
data class ScanPage(
    val id: Long,
    val rotation: Int,
    val corners: FloatArray,
    val turn: Int = 0,
    val filter: ScanFilter = ScanFilter.COLOR,
    val focal: Float = 0f,
    val version: Int = 0,
) {
    val key: String get() = "$id:$version"

    override fun equals(other: Any?) = other is ScanPage && other.id == id && other.version == version
    override fun hashCode() = (id * 31 + version).toInt()

    fun edited(
        corners: FloatArray = this.corners,
        turn: Int = this.turn,
        filter: ScanFilter = this.filter,
    ) = copy(corners = corners, turn = ((turn % 360) + 360) % 360, filter = filter, version = version + 1)
}

/** Manifest line format, kept separate so it can be tested. */
object ScanManifest {
    fun encode(p: ScanPage): String =
        listOf(p.id, p.rotation, p.turn, p.filter.name, p.focal, p.version, p.corners.joinToString(",")).joinToString("|")

    fun decode(line: String): ScanPage? = runCatching {
        val f = line.split("|")
        val corners = f[6].split(",").map { it.toFloat() }.toFloatArray()
        require(corners.size == 8)
        ScanPage(f[0].toLong(), f[1].toInt(), corners, f[2].toInt(), ScanFilter.valueOf(f[3]), f[4].toFloat(), f[5].toInt())
    }.getOrNull()
}

/**
 * Keeps the pages of the scan in progress in the app's private storage (so they survive leaving the app),
 * and turns them into flat, cleaned-up pictures.
 */
class ScanStore(context: Context) {
    private val dir = File(context.filesDir, "scan").apply { mkdirs() }
    private val manifest = File(dir, "pages.txt")
    private var lastId = 0L

    fun load(): List<ScanPage> {
        if (!manifest.exists()) return emptyList()
        return manifest.readLines().mapNotNull(ScanManifest::decode).filter { source(it).exists() }
    }

    fun save(pages: List<ScanPage>) {
        val tmp = File(dir, "pages.tmp")
        tmp.writeText(pages.joinToString("\n") { ScanManifest.encode(it) })
        tmp.renameTo(manifest)
        // Remove photos of pages that are gone.
        val keep = pages.map { "${it.id}.jpg" }.toSet()
        dir.listFiles()?.filter { it.name.endsWith(".jpg") && it.name !in keep }?.forEach { it.delete() }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun source(page: ScanPage) = File(dir, "${page.id}.jpg")

    /** Stores a new capture and finds the page in it. */
    fun add(jpeg: ByteArray, rotation: Int, focal: Float): ScanPage {
        val id = max(System.currentTimeMillis() * 10, lastId + 1).also { lastId = it }
        File(dir, "$id.jpg").writeBytes(jpeg)
        val page = ScanPage(id, rotation, floatArrayOf(), focal = focal)
        return page.copy(corners = detect(page))
    }

    /** Finds the page outline in the photo (fractions), or the whole photo with a small margin. */
    fun detect(page: ScanPage): FloatArray {
        val preview = upright(page, 720) ?: return Quad.full(1f, 1f)
        val w = preview.width
        val h = preview.height
        val px = IntArray(w * h)
        preview.getPixels(px, 0, w, 0, 0, w, h)
        preview.recycle()
        val gray = FloatArray(w * h) { i ->
            val c = px[i]
            0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)
        }
        val found = DocumentDetector.detect(GrayImage(w, h, gray), minAreaFraction = 0.08f)
        return found?.let { Quad.scale(it.corners, 1f / w, 1f / h) } ?: Quad.full(1f, 1f)
    }

    /** The whole photo, upright, at most [maxSide] on its long side (for the corner editor). */
    fun upright(page: ScanPage, maxSide: Int): Bitmap? {
        val file = source(page)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = min(1f, maxSide.toFloat() / max(raw.width, raw.height))
        if (page.rotation == 0 && scale == 1f) return raw
        val m = Matrix().apply { postRotate(page.rotation.toFloat()); postScale(scale, scale) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }

    /** Size of the photo as stored (before turning upright). */
    private fun rawSize(page: ScanPage): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source(page).absolutePath, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    /** The flattened, cleaned-up page, at most [maxLong] pixels on its long side. */
    fun render(page: ScanPage, maxLong: Int): Bitmap? {
        val (rw, rh) = rawSize(page)
        if (rw <= 0) return null
        val swap = page.rotation == 90 || page.rotation == 270
        val uw = (if (swap) rh else rw).toFloat()
        val uh = (if (swap) rw else rh).toFloat()
        // Corners in the upright photo, in pixels, for the real proportions.
        val upright = Quad.scale(page.corners, uw, uh)
        val focal = if (page.focal > 0f) page.focal * max(uw, uh) else null
        val (aspect, _) = PageShape.snap(PageShape.aspect(upright, focal, uw / 2f, uh / 2f))
        val (fullW, fullH) = PageShape.outputSize(upright, aspect, Int.MAX_VALUE)
        // Decode only as much resolution as the output needs.
        var sample = 1
        while (max(fullW, fullH) / (sample * 2) >= maxLong) sample *= 2
        val raw = BitmapFactory.decodeFile(source(page).absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val (outW, outH) = PageShape.outputSize(upright, aspect, maxLong)
        val src = toRaw(upright, rw.toFloat(), rh.toFloat(), page.rotation, raw.width.toFloat() / rw)
        val turned = page.turn == 90 || page.turn == 270
        val tw = if (turned) outH else outW
        val th = if (turned) outW else outH
        // A hair's breadth of overscan, so the table at the page's edge doesn't show as a dark line.
        val dst = overscan(turnedCorners(tw.toFloat(), th.toFloat(), page.turn), tw.toFloat(), th.toFloat(), EDGE_TRIM)
        val out = createBitmap(tw, th)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        val m = Matrix()
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) {
            raw.recycle()
            return null
        }
        canvas.drawBitmap(raw, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        raw.recycle()
        if (page.filter != ScanFilter.ORIGINAL) {
            val px = IntArray(tw * th)
            out.getPixels(px, 0, tw, 0, 0, tw, th)
            Enhance.apply(px, tw, th, page.filter)
            out.setPixels(px, 0, tw, 0, 0, tw, th)
        }
        return out
    }

    companion object {
        /** Upright pixel corners → corners in the stored (not yet turned) photo, scaled by [scale]. */
        fun toRaw(upright: FloatArray, rawW: Float, rawH: Float, rotation: Int, scale: Float): FloatArray {
            val out = FloatArray(8)
            for (i in 0 until 4) {
                val u = upright[i * 2]
                val v = upright[i * 2 + 1]
                val (x, y) = when (rotation) {
                    90 -> v to (rawH - u)
                    180 -> (rawW - u) to (rawH - v)
                    270 -> (rawW - v) to u
                    else -> u to v
                }
                out[i * 2] = x * scale
                out[i * 2 + 1] = y * scale
            }
            return out
        }

        /** Where the page's top-left, top-right, bottom-right, bottom-left go in a [w]×[h] output turned by [turn]. */
        fun turnedCorners(w: Float, h: Float, turn: Int): FloatArray = when (turn) {
            90 -> floatArrayOf(w, 0f, w, h, 0f, h, 0f, 0f)
            180 -> floatArrayOf(w, h, 0f, h, 0f, 0f, w, 0f)
            270 -> floatArrayOf(0f, h, 0f, 0f, w, 0f, w, h)
            else -> floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        }

        fun clampCorners(c: FloatArray): FloatArray = FloatArray(8) { c[it].coerceIn(0f, 1f) }

        /** Fraction trimmed from each edge of the page. */
        const val EDGE_TRIM = 0.004f

        /** Pushes output corners outwards from the centre by [trim] of the size on each side. */
        fun overscan(c: FloatArray, w: Float, h: Float, trim: Float): FloatArray =
            FloatArray(8) { if (it % 2 == 0) w / 2 + (c[it] - w / 2) * (1 + 2 * trim) else h / 2 + (c[it] - h / 2) * (1 + 2 * trim) }
    }
}
