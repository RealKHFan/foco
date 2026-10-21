package io.github.realkhfan.foco.media

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A photo or video saved by Foco. [uri] always has the form content://media/external/{images|video}/media/<ID>. */
data class MediaItem(val id: Long, val isVideo: Boolean, val mimeType: String) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id,
        )
}

object MediaNaming {
    const val RELATIVE_PATH = "DCIM/Camera"

    /** Same scheme as Pixel Camera: PXL_20261020_195959123 */
    fun baseName(timeMillis: Long): String =
        "PXL_" + SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date(timeMillis))

    fun contentValues(displayName: String, mimeType: String): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
    }
}

/**
 * Opening the gallery button the way Pixel Camera does: hand the exact media ID to Google Photos
 * (com.android.camera.action.REVIEW), otherwise Foco's own swipeable viewer.
 */
object GalleryIntents {
    const val GOOGLE_PHOTOS = "com.google.android.apps.photos"
    const val ACTION_REVIEW = "com.android.camera.action.REVIEW"

    fun googlePhotos(item: MediaItem): Intent =
        Intent(ACTION_REVIEW)
            .setDataAndType(item.uri, item.mimeType)
            .setPackage(GOOGLE_PHOTOS)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun anyGallery(item: MediaItem): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, item.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** @return which target was used, for tests and logging. */
    fun open(context: Context, item: MediaItem?, openBuiltInViewer: () -> Unit): Target {
        if (item == null) {
            val launch = context.packageManager.getLaunchIntentForPackage(GOOGLE_PHOTOS)
            if (launch != null && tryStart(context, launch)) return Target.GOOGLE_PHOTOS
            openBuiltInViewer()
            return Target.BUILT_IN
        }
        if (tryStart(context, googlePhotos(item))) return Target.GOOGLE_PHOTOS
        // Without Google Photos, Foco's own viewer lets you swipe through recent shots (it has "Open with…").
        openBuiltInViewer()
        return Target.BUILT_IN
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    enum class Target { GOOGLE_PHOTOS, OTHER_GALLERY, BUILT_IN, NONE }
}
