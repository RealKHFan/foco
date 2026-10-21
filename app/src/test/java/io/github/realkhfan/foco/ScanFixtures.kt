package io.github.realkhfan.foco

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import java.util.Random

/** Drawn stand-ins for photographed documents (Robolectric native graphics). */
object ScanFixtures {
    /** Page corners in a 1500×2000 upright photo: a slightly turned A4 page with a little keystone. */
    val PHOTO_W = 1500
    val PHOTO_H = 2000
    val CORNERS = floatArrayOf(320f, 350f, 1160f, 310f, 1210f, 1540f, 270f, 1580f)

    /** A printed page: heading, paragraphs and a small table. */
    fun page(w: Int = 840, h: Int = 1188, title: String = "Meeting notes", seed: Long = 3): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFBFAF6.toInt())
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22252B.toInt() }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F5BB7.toInt() }
        val s = w / 840f
        c.drawRect(70 * s, 70 * s, 140 * s, 140 * s, accent)
        ink.textSize = 46 * s
        c.drawText(title, 165 * s, 125 * s, ink)
        ink.textSize = 20 * s
        c.drawText("14 October 2026 · Room 3", 165 * s, 160 * s, ink.apply { color = 0xFF5F6368.toInt() })
        ink.color = 0xFF22252B.toInt()
        val rnd = Random(seed)
        var y = 230 * s
        repeat(3) { para ->
            val lines = 4 + rnd.nextInt(3)
            repeat(lines) { i ->
                val end = if (i == lines - 1) 300 + rnd.nextInt(300) else 770 - rnd.nextInt(40)
                var x = 70f
                while (x < end) {
                    val word = 30 + rnd.nextInt(70)
                    c.drawRoundRect(x * s, y, (x + word).coerceAtMost(end.toFloat()) * s, y + 13 * s, 3 * s, 3 * s, ink)
                    x += word + 12
                }
                y += 30 * s
            }
            y += 28 * s
            if (para == 1) {
                // A small table.
                val line = Paint().apply { color = 0xFF9AA0A6.toInt(); strokeWidth = 2 * s }
                val top = y
                for (r in 0..4) c.drawLine(70 * s, top + r * 44 * s, 770 * s, top + r * 44 * s, line)
                for (col in listOf(70, 330, 560, 770)) c.drawLine(col * s, top, col * s, top + 176 * s, line)
                for (r in 0 until 4) for (col in listOf(90, 350, 580)) {
                    c.drawRoundRect(col * s, top + (r * 44 + 16) * s, (col + 120 + rnd.nextInt(60)) * s, top + (r * 44 + 28) * s, 3 * s, 3 * s, ink)
                }
                y = top + 210 * s
            }
        }
        return bmp
    }

    /** An upright photo of [page] lying on a wooden table at [corners], with a soft shadow across it. */
    fun photo(page: Bitmap = page(), corners: FloatArray = CORNERS, shadow: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(PHOTO_W, PHOTO_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Wood: warm brown with grain.
        c.drawColor(0xFF7A5434.toInt())
        val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
        val rnd = Random(7)
        repeat(160) {
            grain.color = if (rnd.nextBoolean()) 0x22000000 else 0x18FFFFFF
            val x = rnd.nextFloat() * PHOTO_W
            c.drawLine(x, 0f, x + rnd.nextFloat() * 120f - 60f, PHOTO_H.toFloat(), grain)
        }
        val m = Matrix()
        val pw = page.width.toFloat()
        val ph = page.height.toFloat()
        m.setPolyToPoly(floatArrayOf(0f, 0f, pw, 0f, pw, ph, 0f, ph), 0, corners, 0, 4)
        c.drawBitmap(page, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        if (shadow) {
            // The shadow of the phone and hand: darker towards the right.
            val shade = Paint().apply {
                shader = LinearGradient(500f, 0f, 1400f, 400f, 0x00000000, 0x77000000, Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, PHOTO_W.toFloat(), PHOTO_H.toFloat(), shade)
        }
        return bmp
    }

    /** The photo as the camera stores it: turned [rotation]° anticlockwise (so turning it clockwise makes it upright). */
    fun sensorJpeg(upright: Bitmap, rotation: Int, quality: Int = 92): ByteArray {
        val raw = if (rotation == 0) upright else {
            Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, Matrix().apply { postRotate(-rotation.toFloat()) }, true)
        }
        return ByteArrayOutputStream().also { raw.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }
}
