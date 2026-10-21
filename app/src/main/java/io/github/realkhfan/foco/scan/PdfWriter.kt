package io.github.realkhfan.foco.scan

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class PdfPageSize(val label: String, val shortPt: Float, val longPt: Float) {
    /** Page shaped like the scan (long side as long as A4's). */
    FIT("Fit", 0f, 841.89f),
    A4("A4", 595.28f, 841.89f),
    LETTER("Letter", 612f, 792f),
}

/** One page: a JPEG (in memory or in a file) and its size in pixels. */
class PdfImagePage private constructor(val width: Int, val height: Int, private val data: ByteArray?, private val file: java.io.File?) {
    constructor(jpeg: ByteArray, width: Int, height: Int) : this(width, height, jpeg, null)

    val length: Long get() = data?.size?.toLong() ?: file!!.length()

    fun writeTo(out: OutputStream) {
        if (data != null) out.write(data) else file!!.inputStream().use { it.copyTo(out) }
    }

    companion object {
        fun fromFile(file: java.io.File, width: Int, height: Int) = PdfImagePage(width, height, null, file)
    }
}

/**
 * A small PDF writer that embeds JPEGs as they are (DCTDecode), so PDFs stay small and nothing is re-compressed.
 * Landscape scans get landscape pages; on A4/Letter the scan is fitted and centred.
 */
object PdfWriter {
    fun write(out: OutputStream, pages: List<PdfImagePage>, size: PdfPageSize, title: String, time: Date = Date()) {
        require(pages.isNotEmpty())
        val buf = CountingStream(out)
        val offsets = mutableListOf<Long>()
        fun obj(body: () -> Unit) {
            offsets += buf.count
            buf.ascii("${offsets.size} 0 obj\n")
            body()
            buf.ascii("\nendobj\n")
        }
        buf.ascii("%PDF-1.4\n")
        buf.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        // Object numbers: 1 catalog, 2 pages, then 3 per page (page, contents, image), then info.
        val pageIds = pages.indices.map { 3 + it * 3 }
        obj { buf.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj { buf.ascii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>") }
        pages.forEachIndexed { index, page ->
            val (pw, ph) = pageSize(page, size)
            val (dw, dh) = fit(page.width.toFloat(), page.height.toFloat(), pw, ph)
            val x = (pw - dw) / 2f
            val y = (ph - dh) / 2f
            val id = pageIds[index]
            val content = "q ${num(dw)} 0 0 ${num(dh)} ${num(x)} ${num(y)} cm /Im0 Do Q".toByteArray(Charsets.US_ASCII)
            obj {
                buf.ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(pw)} ${num(ph)}] " +
                        "/Resources << /XObject << /Im0 ${id + 2} 0 R >> >> /Contents ${id + 1} 0 R >>",
                )
            }
            obj {
                buf.ascii("<< /Length ${content.size} >>\nstream\n")
                buf.write(content)
                buf.ascii("\nendstream")
            }
            obj {
                buf.ascii(
                    "<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.length} >>\nstream\n",
                )
                page.writeTo(buf)
                buf.ascii("\nendstream")
            }
        }
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(time)
        obj { buf.ascii("<< /Title ${text(title)} /Producer (Foco) /Creator (Foco) /CreationDate (D:${stamp}Z) >>") }
        val infoId = offsets.size
        val xref = buf.count
        buf.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) buf.ascii(String.format(Locale.US, "%010d 00000 n \n", o))
        buf.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info $infoId 0 R >>\nstartxref\n$xref\n%%EOF\n")
        buf.flush()
    }

    fun toBytes(pages: List<PdfImagePage>, size: PdfPageSize, title: String, time: Date = Date()): ByteArray =
        ByteArrayOutputStream().also { write(it, pages, size, title, time) }.toByteArray()

    /** Page size in points: portrait for portrait scans, landscape for landscape scans. */
    fun pageSize(page: PdfImagePage, size: PdfPageSize): Pair<Float, Float> {
        val landscape = page.width > page.height
        if (size == PdfPageSize.FIT) {
            val scale = size.longPt / maxOf(page.width, page.height)
            return page.width * scale to page.height * scale
        }
        return if (landscape) size.longPt to size.shortPt else size.shortPt to size.longPt
    }

    private fun fit(w: Float, h: Float, pw: Float, ph: Float): Pair<Float, Float> {
        val s = minOf(pw / w, ph / h)
        return w * s to h * s
    }

    private fun num(v: Float): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    /** A PDF text string: plain ASCII in (), anything else as UTF-16 hex. */
    fun text(s: String): String {
        if (s.all { it.code in 32..126 }) {
            return "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
        }
        val sb = StringBuilder("<FEFF")
        for (b in s.toByteArray(Charsets.UTF_16BE)) sb.append(String.format("%02X", b.toInt() and 0xff))
        return sb.append('>').toString()
    }

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        fun ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
        override fun flush() = out.flush()
    }
}
