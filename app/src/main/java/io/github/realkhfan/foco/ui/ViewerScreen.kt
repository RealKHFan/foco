package io.github.realkhfan.foco.ui

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.Size
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.realkhfan.foco.media.MediaItem
import io.github.realkhfan.foco.ui.theme.FocoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Foco's own small viewer. Used on the lock screen (only this session's shots, like Pixel Camera)
 * and as a last resort when no gallery app is installed.
 */
@Composable
fun ViewerScreen(items: List<MediaItem>, secure: Boolean, actions: CameraActions) {
    BackHandler(onBack = actions::closeViewer)
    val pager = rememberPagerState { items.size }
    var confirmDelete by remember { mutableStateOf<MediaItem?>(null) }
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].id }) { page ->
            val item = items[page]
            var playing by remember(item) { mutableStateOf(false) }
            if (item.isVideo && playing) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(item.uri)
                            setOnPreparedListener { it.isLooping = false; start() }
                            setOnCompletionListener { playing = false }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val bitmap by produceState<Bitmap?>(null, item) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.loadThumbnail(item.uri, Size(2048, 2048), null) }.getOrNull()
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    if (item.isVideo) {
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(Color(0x99000000)).clickable { playing = true },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp)) }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions::closeViewer) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                "${pager.currentPage + 1} / ${items.size}",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            if (!secure) {
                IconButton(onClick = { items.getOrNull(pager.currentPage)?.let(actions::shareItem) }) {
                    Icon(Icons.Outlined.Share, "Share", tint = Color.White)
                }
                IconButton(onClick = { items.getOrNull(pager.currentPage)?.let(actions::openItemExternally) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open with", tint = Color.White)
                }
            }
            IconButton(onClick = { confirmDelete = items.getOrNull(pager.currentPage) }) {
                Icon(Icons.Outlined.Delete, "Delete", tint = Color.White)
            }
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (item.isVideo) "Move this video to the trash?" else "Move this photo to the trash?") },
            text = { Text("Gallery apps keep the trash for 30 days, so you can still get it back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    actions.deleteItem(item)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Shown when another app asked for a photo/video: keep it (✓) or take it again (✕). */
@Composable
fun ReviewOverlay(review: Review, actions: CameraActions) {
    BackHandler(onBack = actions::reviewRetake)
    val bitmap by produceState<Bitmap?>(null, review) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (review.isVideo) {
                    ThumbnailUtils.createVideoThumbnail(review.file, Size(1440, 1440), null)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(review.file.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 1440) sample *= 2
                    BitmapFactory.decodeFile(review.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) { }) {
        bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(FocoColors.Button).clickable(onClick = actions::reviewRetake),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, "Retake", tint = Color.White, modifier = Modifier.size(32.dp)) }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(onClick = actions::reviewAccept),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Check, "Use this", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp)) }
        }
    }
}
