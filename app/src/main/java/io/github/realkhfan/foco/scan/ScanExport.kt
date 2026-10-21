package io.github.realkhfan.foco.scan

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    JPG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
}

enum class ExportQuality(val label: String, val maxLong: Int, val jpeg: Int) {
    NORMAL("Normal", 2339, 85), // ≈ 200 dpi on A4
    HIGH("High", 3508, 92), // ≈ 300 dpi on A4
}

data class ExportOptions(
    val name: String,
    val format: ExportFormat = ExportFormat.PDF,
    val pageSize: PdfPageSize = PdfPageSize.FIT,
    val quality: ExportQuality = ExportQuality.HIGH,
)

/** File names for an export: "Scan.pdf", or "Scan.jpg" / "Scan (1).jpg", "Scan (2).jpg" … for several pictures. */
object ExportNames {
    fun defaultName(time: Date = Date()): String = "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(time)

    /** A name Foco made up (not typed by the user), so it can be brought up to date. */
    fun isDefault(name: String) = Regex("Scan \\d{4}-\\d{2}-\\d{2} \\d{2}\\.\\d{2}").matches(name)

    /** Keeps only characters that every file system accepts. */
    fun clean(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Scan" }.take(100)

    fun files(name: String, format: ExportFormat, pages: Int): List<String> {
        val base = clean(name)
        if (format == ExportFormat.PDF || pages == 1) return listOf("$base.${format.extension}")
        return (1..pages).map { "$base ($it).${format.extension}" }
    }
}

/** Renders the pages into files and saves or shares them. Runs on a background thread. */
class ScanExporter(private val context: Context, private val store: ScanStore) {
    private val outDir get() = File(context.cacheDir, "share").apply { mkdirs() }

    /** Makes the export files in the app's cache; [progress] gets (done, total). */
    fun build(pages: List<ScanPage>, options: ExportOptions, progress: (Int, Int) -> Unit = { _, _ -> }): List<File> {
        outDir.listFiles()?.forEach { it.delete() }
        val names = ExportNames.files(options.name, options.format, pages.size)
        return if (options.format == ExportFormat.PDF) {
            val parts = pages.mapIndexed { i, page ->
                val bitmap = store.render(page, options.quality.maxLong) ?: error("page ${i + 1} couldn't be read")
                val part = File(outDir, "page-$i.jpg")
                part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality.jpeg, it) }
                val pdfPage = PdfImagePage.fromFile(part, bitmap.width, bitmap.height)
                bitmap.recycle()
                progress(i + 1, pages.size + 1)
                pdfPage
            }
            val pdf = File(outDir, names.first())
            pdf.outputStream().buffered().use { PdfWriter.write(it, parts, options.pageSize, ExportNames.clean(options.name)) }
            parts.indices.forEach { File(outDir, "page-$it.jpg").delete() }
            progress(pages.size + 1, pages.size + 1)
            listOf(pdf)
        } else {
            pages.mapIndexed { i, page ->
                val bitmap = store.render(page, options.quality.maxLong) ?: error("page ${i + 1} couldn't be read")
                val file = File(outDir, names[i])
                file.outputStream().use {
                    if (options.format == ExportFormat.PNG) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    } else {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality.jpeg, it)
                    }
                }
                bitmap.recycle()
                progress(i + 1, pages.size)
                file
            }
        }
    }

    /** Saves to Documents/Foco Scans (PDF) or Pictures/Foco Scans (pictures). Returns the folder shown to the user. */
    fun saveToPhone(files: List<File>, format: ExportFormat): String {
        val resolver = context.contentResolver
        val (collection, folder) = when {
            format != ExportFormat.PDF -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/Foco Scans"
            Build.VERSION.SDK_INT >= 30 -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Documents/Foco Scans"
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download/Foco Scans"
        }
        for (file in files) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("couldn't create ${file.name}")
            try {
                copy(file, resolver.openOutputStream(uri) ?: error("couldn't write ${file.name}"))
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        return folder
    }

    /** Writes one file to a place the user picked ("Save to…" for a single file). */
    fun saveTo(uri: Uri, file: File) {
        val resolver = context.contentResolver
        // "wt" empties a file that already exists; a few providers only know "w".
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri, "w")
        copy(file, out ?: error("couldn't write"))
    }

    /** Writes all files into a folder the user picked ("Save to…" for several pictures). */
    fun saveToFolder(tree: Uri, files: List<File>, format: ExportFormat) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        for (file in files) {
            val doc = DocumentsContract.createDocument(resolver, parent, format.mime, file.name) ?: error("couldn't create ${file.name}")
            copy(file, resolver.openOutputStream(doc) ?: error("couldn't write ${file.name}"))
        }
    }

    /** A share intent for the files (through Foco's file provider, read-only). */
    fun shareIntent(files: List<File>, format: ExportFormat): Intent {
        val authority = "${context.packageName}.files"
        val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = format.mime
        intent.clipData = android.content.ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun copy(file: File, out: OutputStream) {
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }
}
