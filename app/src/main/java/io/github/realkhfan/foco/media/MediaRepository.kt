package io.github.realkhfan.foco.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A photo/video found in the camera folder, with when it was taken and where the file is. */
data class MediaEntry(val item: MediaItem, val path: String?, val takenMillis: Long)

/** A file found by listing the camera folder directly. */
data class FolderFile(val path: String, val modifiedMillis: Long)

/**
 * Picks the newest photo by the time it was taken, ignoring entries whose file no longer exists
 * (e.g. moved to another app's trash without telling Android's media index).
 */
object LatestPicker {
    fun existing(entries: List<MediaEntry>, fileExists: (MediaEntry) -> Boolean): List<MediaEntry> =
        entries.filter(fileExists).sortedWith(compareByDescending<MediaEntry> { it.takenMillis }.thenByDescending { it.item.id })

    /** Ghost entries: in the media index, but the file is gone. */
    fun ghosts(entries: List<MediaEntry>, fileExists: (MediaEntry) -> Boolean): List<MediaEntry> =
        entries.filterNot(fileExists)

    /** A file in the folder that is newer than anything the media index knows about (needs indexing first). */
    fun newerUnindexed(folder: List<FolderFile>, indexed: List<MediaEntry>): FolderFile? {
        val known = indexed.mapNotNull { it.path }.toSet()
        val newestKnown = indexed.maxOfOrNull { it.takenMillis } ?: Long.MIN_VALUE
        return folder.filter { it.path !in known && it.modifiedMillis > newestKnown + 1000 }.maxByOrNull { it.modifiedMillis }
    }

    private val MEDIA_EXTENSIONS = setOf("jpg", "jpeg", "heic", "heif", "png", "webp", "avif", "mp4", "3gp", "webm", "mkv", "mov")

    /** Hidden files are trashed (".trashed-…") or still being written (".pending-…"). */
    fun isCameraMedia(name: String): Boolean =
        !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS
}

/** Reads and changes the photos and videos in the camera folder. */
class MediaRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun itemFor(savedUri: Uri, isVideo: Boolean): MediaItem? {
        val id = runCatching { ContentUris.parseId(savedUri) }.getOrNull() ?: return null
        if (id < 0) return null
        val mime = runCatching { resolver.getType(savedUri) }.getOrNull()
            ?: if (isVideo) "video/mp4" else "image/jpeg"
        return MediaItem(id, isVideo, mime)
    }

    /** Newest existing photo/video in DCIM/Camera by the time it was taken (also other camera apps' with photo access). */
    fun latest(): MediaItem? {
        val entries = query(60)
        val ok = LatestPicker.existing(entries, ::fileExists)
        cleanGhosts(entries)
        val unindexed = LatestPicker.newerUnindexed(listCameraFolder(), ok)
        if (unindexed != null) scanAndGet(unindexed.path)?.let { return it }
        return ok.firstOrNull()?.item
    }

    /** Recent existing camera photos/videos, newest first (for Foco's own viewer). */
    fun recent(limit: Int): List<MediaItem> {
        val entries = query(limit + 20)
        cleanGhosts(entries)
        return LatestPicker.existing(entries, ::fileExists).take(limit).map { it.item }
    }

    private fun query(limit: Int): List<MediaEntry> {
        val byTaken = queryOrdered("${MediaStore.MediaColumns.DATE_TAKEN} DESC", limit)
        val byAdded = queryOrdered("${MediaStore.MediaColumns.DATE_ADDED} DESC", limit)
        return (byTaken + byAdded).distinctBy { it.item.id }
    }

    @Suppress("DEPRECATION") // DATA is still the only way to check that the file itself exists.
    private fun queryOrdered(order: String, limit: Int): List<MediaEntry> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)" +
                    " AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" +
                    " AND ${MediaStore.MediaColumns.MIME_TYPE}!=?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                    "${MediaNaming.RELATIVE_PATH}%",
                    "image/x-adobe-dng",
                ),
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "$order, ${MediaStore.Files.FileColumns._ID} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val items = mutableListOf<MediaEntry>()
        runCatching {
            resolver.query(collection, projection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val takenCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val modCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val mime = c.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg"
                    val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L
                    val modified = if (modCol >= 0 && !c.isNull(modCol)) c.getLong(modCol) * 1000 else 0L
                    items += MediaEntry(
                        MediaItem(c.getLong(idCol), isVideo, mime),
                        if (dataCol >= 0) c.getString(dataCol) else null,
                        if (taken > 0) taken else modified,
                    )
                }
            }
        }
        return items
    }

    /** True if the file behind the entry is really there (not just its row in the media index). */
    private fun fileExists(entry: MediaEntry): Boolean {
        val path = entry.path
        if (path != null) {
            val file = File(path)
            if (runCatching { file.exists() }.getOrDefault(false)) return true
            // Can't see it by path: fall back to opening it through the media index.
        }
        return runCatching { resolver.openFileDescriptor(entry.item.uri, "r")?.use { true } ?: false }.getOrDefault(false)
    }

    fun exists(item: MediaItem): Boolean = runCatching {
        resolver.openFileDescriptor(item.uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /** Tells Android that these files are gone, so they disappear from every gallery too. */
    private fun cleanGhosts(entries: List<MediaEntry>) {
        val paths = LatestPicker.ghosts(entries, ::fileExists).mapNotNull { it.path }
        if (paths.isNotEmpty()) runCatching { MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null) }
    }

    private fun listCameraFolder(): List<FolderFile> = runCatching {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        dir.listFiles()?.filter { it.isFile && LatestPicker.isCameraMedia(it.name) }
            ?.map { FolderFile(it.absolutePath, it.lastModified()) }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun scanAndGet(path: String): MediaItem? {
        var result: Uri? = null
        val done = CountDownLatch(1)
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri ->
                result = uri
                done.countDown()
            }
            done.await(2, TimeUnit.SECONDS)
        }
        val uri = result ?: return null
        val isVideo = uri.toString().contains("/video/") ||
            path.substringAfterLast('.').lowercase() in setOf("mp4", "3gp", "webm", "mkv", "mov")
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: if (isVideo) "video/mp4" else "image/jpeg"
        // The scanner returns a Files URI; use the images/video collection like Pixel Camera does.
        return MediaItem(id, isVideo, mime)
    }

    fun thumbnail(item: MediaItem, sizePx: Int): Bitmap? =
        runCatching { resolver.loadThumbnail(item.uri, Size(sizePx, sizePx), null) }.getOrNull()

    fun delete(item: MediaItem): Boolean = runCatching { resolver.delete(item.uri, null, null) > 0 }.getOrDefault(false)

    sealed class Removal {
        data object Done : Removal()
        data object Failed : Removal()
        /** Another app's photo: Android has to ask the user first. */
        class NeedsConfirmation(val request: android.content.IntentSender) : Removal()
    }

    /** Moves a photo/video to the trash (recoverable for 30 days in gallery apps); Android 10 deletes. */
    fun remove(item: MediaItem): Removal {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val values = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
                if (resolver.update(item.uri, values, null, null) > 0) return Removal.Done
            } catch (_: SecurityException) {
                // not ours: ask
            } catch (_: Exception) {
                return Removal.Failed
            }
            return runCatching { Removal.NeedsConfirmation(MediaStore.createTrashRequest(resolver, listOf(item.uri), true).intentSender) }
                .getOrDefault(Removal.Failed)
        }
        return try {
            if (resolver.delete(item.uri, null, null) > 0) Removal.Done else Removal.Failed
        } catch (e: android.app.RecoverableSecurityException) {
            Removal.NeedsConfirmation(e.userAction.actionIntent.intentSender)
        } catch (_: Exception) {
            Removal.Failed
        }
    }

    /** Removes phone make/model/software and the lens model from a saved JPEG (privacy option). */
    fun stripDeviceInfo(item: MediaItem) {
        if (item.isVideo || item.mimeType != "image/jpeg") return
        runCatching {
            resolver.openFileDescriptor(item.uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                listOf(
                    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
                    ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER,
                    ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_CAMERA_OWNER_NAME,
                    ExifInterface.TAG_IMAGE_UNIQUE_ID,
                ).forEach { exif.setAttribute(it, null) }
                exif.saveAttributes()
            }
        }
    }
}
