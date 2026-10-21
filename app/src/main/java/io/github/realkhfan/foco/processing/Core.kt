package io.github.realkhfan.foco.processing

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

/**
 * Planar YUV 4:2:0 frame (full-range BT.601, as Android cameras deliver it), in sensor orientation.
 * Pure Kotlin so the whole processing pipeline can be unit-tested without a phone.
 */
class YuvFrame(val width: Int, val height: Int) {
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val y = ByteArray(width * height)
    val u = ByteArray(chromaWidth * chromaHeight)
    val v = ByteArray(chromaWidth * chromaHeight)
    var timestampNs = 0L
    var exposureNs = 0L
    var iso = 0

    fun copyFrom(other: YuvFrame) {
        require(other.width == width && other.height == height)
        System.arraycopy(other.y, 0, y, 0, y.size)
        System.arraycopy(other.u, 0, u, 0, u.size)
        System.arraycopy(other.v, 0, v, 0, v.size)
        timestampNs = other.timestampNs
        exposureNs = other.exposureNs
        iso = other.iso
    }

    /** Fraction of pixels that are (nearly) clipped, from a sparse sample. */
    fun clippedFraction(step: Int = 7): Float {
        var clipped = 0
        var total = 0
        var i = 0
        while (i < y.size) {
            if ((y[i].toInt() and 0xff) >= 250) clipped++
            total++
            i += step
        }
        return if (total == 0) 0f else clipped.toFloat() / total
    }
}

/** Maps a reference pixel (x, y) to the matching position in another frame. */
data class Affine(val a: Float, val b: Float, val tx: Float, val c: Float, val d: Float, val ty: Float) {
    fun mapX(x: Float, y: Float) = a * x + b * y + tx
    fun mapY(x: Float, y: Float) = c * x + d * y + ty

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 0f, 1f, 0f)
        fun translation(dx: Float, dy: Float) = Affine(1f, 0f, dx, 0f, 1f, dy)
    }
}

/** Runs row bands of an image on all CPU cores. Never call from inside a band (no nesting). */
object Parallel {
    val threads: Int = max(1, Runtime.getRuntime().availableProcessors())
    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "foco-processing").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun rows(total: Int, body: (from: Int, to: Int) -> Unit) {
        if (total <= 0) return
        val chunks = min(total, threads * 3)
        val size = (total + chunks - 1) / chunks
        val futures = ArrayList<Future<*>>(chunks)
        var start = 0
        while (start < total) {
            val from = start
            val to = min(total, start + size)
            futures += pool.submit { body(from, to) }
            start = to
        }
        futures.forEach { it.get() }
    }
}

internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
