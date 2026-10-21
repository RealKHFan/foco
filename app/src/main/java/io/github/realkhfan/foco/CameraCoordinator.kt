package io.github.realkhfan.foco

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.github.realkhfan.foco.camera.CameraEngine
import android.annotation.SuppressLint
import io.github.realkhfan.foco.device.CameraSounds
import io.github.realkhfan.foco.device.MicCheck
import io.github.realkhfan.foco.device.MicStatus
import io.github.realkhfan.foco.device.LocationTracker
import io.github.realkhfan.foco.media.GalleryIntents
import io.github.realkhfan.foco.media.MediaItem
import io.github.realkhfan.foco.media.MediaNaming
import io.github.realkhfan.foco.media.MediaRepository
import io.github.realkhfan.foco.processing.BurstSession
import io.github.realkhfan.foco.processing.ShotKind
import io.github.realkhfan.foco.ui.nightSecondsNow
import io.github.realkhfan.foco.ui.nightWillRun
import io.github.realkhfan.foco.ui.photoModes
import kotlin.math.ceil
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.settings.SettingKey
import io.github.realkhfan.foco.ui.AppState
import io.github.realkhfan.foco.ui.CameraActions
import io.github.realkhfan.foco.ui.CaptureKind
import io.github.realkhfan.foco.ui.IntentMode
import io.github.realkhfan.foco.ui.PhotoMode
import io.github.realkhfan.foco.ui.PanoHint
import io.github.realkhfan.foco.ui.PanoThumb
import io.github.realkhfan.foco.ui.ProTool
import io.github.realkhfan.foco.processing.PanoramaSession
import io.github.realkhfan.foco.ui.ProState
import io.github.realkhfan.foco.ui.Review
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connects the UI to the camera engine and the rest of the phone (gallery, permissions, sounds). */
class CameraCoordinator(
    private val activity: ComponentActivity,
    private val state: AppState,
    private val settings: AppSettings,
    private val engine: CameraEngine,
    private val media: MediaRepository,
    private val location: LocationTracker,
    private val requestPermissions: (Array<String>) -> Unit,
    /** Shows a system confirmation (e.g. moving another app's photo to the trash); calls back with the answer. */
    private val launchIntentSender: (IntentSender, (Boolean) -> Unit) -> Unit,
    private val haptic: () -> Unit,
    /** Opens a system file/folder picker ("Save to…") and calls back with the chosen place. */
    pickPlace: (Intent, (Uri?) -> Unit) -> Unit = { _, cb -> cb(null) },
) : CameraActions {
    val scan = ScanController(activity, state, engine, haptic, { playShutter() }, pickPlace)

    private val sounds by lazy { CameraSounds() }
    private var countdownJob: Job? = null

    init {
        scan.load()
        engine.locationProvider = { location.last }
        engine.screenFlash = object : ImageCapture.ScreenFlash {
            override fun apply(expirationTimeMillis: Long, listener: ImageCapture.ScreenFlashListener) {
                state.screenFlashOn = true
                setWindowBrightness(1f)
                activity.window.decorView.postDelayed({ listener.onCompleted() }, 250)
            }

            override fun clear() {
                state.screenFlashOn = false
                setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            }
        }
    }

    private fun setWindowBrightness(value: Float) {
        val params = activity.window.attributes
        params.screenBrightness = value
        activity.window.attributes = params
    }

    // ---------------------------------------------------------------- capture

    override fun shutter() {
        if (state.burstActive) {
            // Tapping again during Night ends it early and keeps what was captured so far.
            engine.stopBurst()
            return
        }
        if (state.review != null || !state.cameraReady) return
        if (state.countdown != null) {
            countdownJob?.cancel()
            state.countdown = null
            return
        }
        if (state.kind == CaptureKind.VIDEO && engine.isRecording) {
            engine.stopRecording()
            return
        }
        val seconds = settings.timer.seconds
        if (seconds > 0) {
            countdownJob = activity.lifecycleScope.launch {
                for (i in seconds downTo 1) {
                    state.countdown = i
                    haptic()
                    delay(1000)
                }
                state.countdown = null
                captureNow()
            }
        } else {
            captureNow()
        }
    }

    private fun captureNow() {
        when {
            state.kind == CaptureKind.VIDEO -> startVideo()
            state.photoMode == PhotoMode.PANORAMA -> startPanorama()
            state.photoMode == PhotoMode.SCAN -> scan.capture()
            state.photoMode != PhotoMode.PHOTO && !state.processing -> state.message = "This mode isn't available on this phone"
            else -> takePhoto()
        }
    }

    /** Panorama: tap to start, turn slowly, tap again (or go all the way round) to finish. */
    private fun startPanorama() {
        if (state.processingCount >= 2) {
            state.message = "Still saving the last photos…"
            return
        }
        state.panoShots.clear()
        state.panoHint = PanoHint.START
        state.panoPitch = 0f
        state.panoYaw = 0f
        val started = engine.startPanorama(object : PanoramaSession.Listener {
            override fun onShot(thumb: Bitmap, yaw: Float, fov: Float) {
                state.panoFov = fov
                state.panoShots += PanoThumb(yaw, thumb)
                if (state.panoShots.size == 1) {
                    state.thumbnail = thumb
                    state.thumbnailTick++
                }
                haptic()
            }

            override fun onHint(hint: PanoramaSession.Hint) {
                state.panoHint = when (hint) {
                    PanoramaSession.Hint.START -> PanoHint.START
                    PanoramaSession.Hint.SLOW_DOWN -> PanoHint.SLOW_DOWN
                    PanoramaSession.Hint.KEEP_LEVEL -> PanoHint.KEEP_LEVEL
                }
            }

            override fun onCaptured(frames: Int) {
                state.panoActive = false
                state.burstActive = false
                state.capturing = false
                if (frames < 2) {
                    state.message = "Turn the phone slowly to one side to take a panorama"
                    state.processingCount = (state.processingCount - 1).coerceAtLeast(0)
                    loadLatest()
                }
            }

            override fun onSaved(uri: String?) {
                if (state.processingCount == 0) return
                state.processingCount = (state.processingCount - 1).coerceAtLeast(0)
                if (uri == null) {
                    state.message = "Couldn't make the panorama"
                    loadLatest()
                } else {
                    onSaved(uri.toUri(), isVideo = false)
                }
            }
        })
        if (!started) {
            state.message = "Camera not ready"
            return
        }
        state.panoActive = true
        state.burstActive = true
        state.burstNight = false
        state.burstKind = null
        state.capturing = true
        state.modeHint = null
        state.processingCount++
        playShutter()
        haptic()
    }

    private fun playShutter() {
        if (settings.shutterSound || engine.mustPlayShutterSound()) sounds.shutter()
    }

    private fun takePhoto() {
        if (state.capturing) return
        val intentFile0 = if (state.intentMode == IntentMode.IMAGE_CAPTURE) tempFile("capture.jpg") else null
        if (state.processing && processedPhoto(intentFile0)) return
        state.capturing = true
        state.flashTick++
        playShutter()
        haptic()
        val intentFile = intentFile0
        engine.takePhoto(intentFile) { uri, error ->
            state.capturing = false
            when {
                error != null || uri == null -> state.message = "Couldn't save photo"
                intentFile != null -> state.review = Review(intentFile, isVideo = false)
                else -> onSaved(uri, isVideo = false)
            }
        }
    }

    /** Foco's multi-frame photo (HDR, Night or sharper zoom). Returns false if it couldn't start. */
    private fun processedPhoto(intentFile: File?): Boolean {
        if (state.processingCount >= 2) {
            state.message = "Still saving the last photos…"
            return true
        }
        val night = state.nightWillRun(settings)
        val plan = engine.planShot(night, state.nightSecondsNow())
        // Night and Long Exposure: hold still with a countdown. Action Pan: follow the subject.
        val isNight = plan.kind == ShotKind.NIGHT || plan.kind == ShotKind.LONG_EXPOSURE
        val started = engine.startBurst(plan, intentFile, object : BurstSession.Listener {
            override fun onFirstFrame(preview: Bitmap) {
                if (intentFile == null) {
                    state.thumbnail = preview
                    state.thumbnailTick++
                }
            }

            override fun onProgress(done: Int, total: Int) {
                state.burstProgress = (done.toFloat() / total).coerceIn(0f, 1f)
            }

            override fun onCaptured() {
                state.burstActive = false
                state.capturing = false
                burstTicker?.cancel()
                if (isNight) haptic()
            }

            override fun onSaved(uri: String?) {
                state.processingCount = (state.processingCount - 1).coerceAtLeast(0)
                when {
                    uri == null -> state.message = "Couldn't save photo"
                    intentFile != null -> state.review = Review(intentFile, isVideo = false)
                    else -> onSaved(uri.toUri(), isVideo = false)
                }
            }
        })
        if (!started) return false
        state.capturing = true
        state.burstActive = true
        state.burstNight = isNight
        state.burstKind = plan.kind
        state.modeHint = null
        state.burstProgress = 0f
        state.burstSecondsLeft = ceil(plan.seconds).toInt()
        state.processingCount++
        if (plan.kind == ShotKind.HDR || plan.kind == ShotKind.ZOOM) state.flashTick++
        playShutter()
        haptic()
        if (isNight) {
            val begin = System.currentTimeMillis()
            burstTicker?.cancel()
            burstTicker = activity.lifecycleScope.launch {
                while (state.burstActive) {
                    val left = plan.seconds - (System.currentTimeMillis() - begin) / 1000f
                    state.burstSecondsLeft = ceil(left.coerceAtLeast(0f)).toInt()
                    delay(200)
                }
            }
        }
        return true
    }

    private var burstTicker: Job? = null

    override fun snapshot() {
        if (!state.snapshotAvailable) return
        state.flashTick++
        playShutter()
        engine.takePhoto(null) { uri, error ->
            if (error == null && uri != null) onSaved(uri, isVideo = false) else state.message = "Couldn't save photo"
        }
    }

    private fun startVideo() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            !state.secure
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        val intentFile = if (state.intentMode == IntentMode.VIDEO_CAPTURE) tempFile("capture.mp4") else null
        val started = engine.startRecording(intentFile) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    state.recording = true
                    state.recordingPaused = false
                    state.recordingMs = 0
                    if (settings.shutterSound || engine.mustPlayShutterSound()) sounds.startVideo()
                }
                is VideoRecordEvent.Status -> state.recordingMs = event.recordingStats.recordedDurationNanos / 1_000_000
                is VideoRecordEvent.Pause -> state.recordingPaused = true
                is VideoRecordEvent.Resume -> state.recordingPaused = false
                is VideoRecordEvent.Finalize -> {
                    state.recording = false
                    state.recordingPaused = false
                    if (settings.shutterSound || engine.mustPlayShutterSound()) sounds.stopVideo()
                    val uri = event.outputResults.outputUri
                    val ok = event.error == VideoRecordEvent.Finalize.ERROR_NONE || uri != Uri.EMPTY
                    when {
                        !ok -> state.message = "Video couldn't be saved"
                        intentFile != null -> state.review = Review(intentFile, isVideo = true)
                        else -> onSaved(uri, isVideo = true)
                    }
                }
                else -> Unit
            }
        }
        if (!started) state.message = "Camera not ready"
        haptic()
    }

    override fun pauseResume() {
        if (!engine.isRecording) return
        if (state.recordingPaused) engine.resume() else engine.pause()
    }

    private fun onSaved(uri: Uri, isVideo: Boolean) {
        val item = media.itemFor(uri, isVideo) ?: return
        state.lastItem = item
        state.sessionItems.add(0, item)
        activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                if (settings.stripDeviceInfo) media.stripDeviceInfo(item)
                media.thumbnail(item, 256)
            }
            if (state.lastItem == item) {
                state.thumbnail = bitmap
                state.thumbnailTick++
            }
        }
    }

    /** Shows the newest photo in the thumbnail when the app opens (not on the lock screen). */
    fun loadLatest() {
        if (state.secure) {
            // Drop shots deleted meanwhile.
            activity.lifecycleScope.launch {
                val gone = withContext(Dispatchers.IO) { state.sessionItems.filterNot { media.exists(it) } }
                state.sessionItems.removeAll(gone)
                if (state.lastItem in gone) {
                    state.lastItem = state.sessionItems.firstOrNull()
                    state.thumbnail = state.lastItem?.let { withContext(Dispatchers.IO) { media.thumbnail(it, 256) } }
                }
            }
            return
        }
        if (state.processingCount > 0) return
        latestJob?.cancel()
        latestJob = activity.lifecycleScope.launch {
            // Newest photo/video in the camera folder by the time it was taken (also other camera apps'),
            // skipping files that were moved or deleted by other apps.
            val latest = withContext(Dispatchers.IO) { media.latest() }
            if (latest == state.lastItem && state.thumbnail != null) return@launch
            val bitmap = latest?.let { withContext(Dispatchers.IO) { media.thumbnail(it, 256) } }
            if (state.processingCount == 0) {
                state.lastItem = latest
                state.thumbnail = bitmap
            }
        }
    }

    private var latestJob: Job? = null

    private fun tempFile(name: String): File {
        val dir = File(activity.cacheDir, "capture").apply { mkdirs() }
        return File(dir, name).apply { delete() }
    }

    // ---------------------------------------------------------------- modes, lens, zoom

    override fun setKind(kind: CaptureKind) {
        if (kind == state.kind || engine.isRecording || engine.isBursting || state.intentMode != IntentMode.NORMAL) return
        countdownJob?.cancel()
        state.countdown = null
        state.kind = kind
        state.activeTool = null
        if (kind == CaptureKind.VIDEO &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            !state.secure
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        engine.rebind()
        if (kind == CaptureKind.VIDEO) checkMic(force = true)
    }

    private var micJob: Job? = null

    /**
     * Switching to Video opens the microphone for a moment: if it's blocked in quick settings, Android shows
     * its "Unblock microphone?" dialog now instead of when you're already filming.
     */
    fun checkMic(force: Boolean = false) {
        if (state.kind != CaptureKind.VIDEO || engine.isRecording) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state.micStatus = MicStatus.NO_PERMISSION
            return
        }
        if (!force && state.micStatus == MicStatus.OK) return
        micJob?.cancel()
        micJob = activity.lifecycleScope.launch {
            @SuppressLint("MissingPermission")
            val result = withContext(Dispatchers.IO) { MicCheck.probe() }
            if (state.kind == CaptureKind.VIDEO) state.micStatus = result ?: MicStatus.UNKNOWN
        }
    }

    override fun micClicked() {
        when (state.micStatus) {
            MicStatus.NO_PERMISSION -> {
                if (state.secure) {
                    state.message = "Unlock your phone to allow the microphone"
                } else if (state.permissionAsked &&
                    !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                ) {
                    openAppInfo()
                } else {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            }
            MicStatus.BLOCKED -> {
                state.message = "If no prompt appears, turn on \"Microphone access\" in quick settings"
                checkMic(force = true)
            }
            else -> Unit
        }
    }

    override fun setPhotoMode(mode: PhotoMode) {
        if (engine.isRecording || engine.isBursting || state.intentMode != IntentMode.NORMAL) return
        if (mode !in state.photoModes) return
        if (state.kind != CaptureKind.PHOTO) setKind(CaptureKind.PHOTO)
        if (mode == state.photoMode) return
        countdownJob?.cancel()
        state.countdown = null
        state.photoMode = mode
        haptic()
        // Night mode with Night switched off would be pointless: back to automatic.
        if (mode == PhotoMode.NIGHT && state.pro.nightSeconds == 0f) state.pro = state.pro.copy(nightSeconds = null)
        if (state.activeTool == ProTool.NIGHT && mode != PhotoMode.NIGHT && mode != PhotoMode.PHOTO) state.activeTool = null
        // Panoramas are taken with the main camera on the back.
        if ((mode == PhotoMode.PANORAMA || mode == PhotoMode.SCAN) && state.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            state.lensFacing = CameraSelector.LENS_FACING_BACK
            state.manualLensId = null
            engine.resetZoom()
            engine.rebind()
        }
        engine.photoModeChanged()
        state.modeHint = when (mode) {
            PhotoMode.LONG_EXPOSURE -> "Blurs moving water, traffic and crowds. Hold still."
            PhotoMode.ACTION_PAN -> "Follow a moving subject while the photo is taken"
            PhotoMode.PANORAMA -> "Tap the shutter, then turn slowly to one side"
            PhotoMode.SCAN -> if (state.scanPages.isEmpty()) "Point at a page: Foco finds its edges" else null
            else -> null
        }
        if (mode != PhotoMode.PHOTO && !state.processing) {
            state.message = "${mode.label} isn't available on this phone"
        }
    }

    override fun scanEditDone() = scan.editDone()
    override fun scanEditCancel() = scan.editCancel()
    override fun openScanReview() = scan.openReview()
    override fun closeScanReview() = scan.closeReview()
    override fun scanSetFilter(page: io.github.realkhfan.foco.scan.ScanPage, filter: io.github.realkhfan.foco.scan.ScanFilter, all: Boolean) =
        scan.setFilter(page, filter, all)
    override fun scanTurn(page: io.github.realkhfan.foco.scan.ScanPage) = scan.turn(page)
    override fun scanDelete(page: io.github.realkhfan.foco.scan.ScanPage) = scan.delete(page)
    override fun scanMove(page: io.github.realkhfan.foco.scan.ScanPage, delta: Int) = scan.move(page, delta)
    override fun scanCrop(page: io.github.realkhfan.foco.scan.ScanPage) = scan.crop(page)
    override fun scanDiscard() = scan.discard()
    override fun scanImage(page: io.github.realkhfan.foco.scan.ScanPage, size: Int) = scan.image(page, size)
    override fun scanExport(options: io.github.realkhfan.foco.scan.ExportOptions, target: io.github.realkhfan.foco.ui.ExportTarget) =
        scan.export(options, target)
    override fun scanNew() = scan.discard()

    override fun flip() {
        if (engine.isRecording || engine.isBursting || !state.caps.hasFront) return
        state.lensFacing = if (state.lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        state.pro = state.pro.copy(focusDiopters = null, shutterNs = null, iso = null)
        state.focusPoint = null
        state.manualLensId = null
        engine.resetZoom()
        engine.rebind()
    }

    override fun setZoom(zoom: Float, fromChip: Boolean) {
        engine.setZoom(zoom, fromChip)
    }

    override fun focusAt(point: Offset, viewWidth: Int, viewHeight: Int, lock: Boolean) {
        if (state.pro.focusDiopters != null) {
            state.pro = state.pro.copy(focusDiopters = null)
            engine.applyPro()
        }
        state.focusPoint = point
        state.focusLocked = lock
        state.focusTick++
        engine.focusAt(point.x, point.y, lock)
        if (lock) haptic()
    }

    override fun setPro(pro: ProState) {
        state.pro = pro
        engine.applyPro()
    }

    override fun settingChanged(key: SettingKey) {
        when (key) {
            // Flash photos use the camera's own capture; the other choices use Foco's processing.
            SettingKey.FLASH -> if (state.kind == CaptureKind.PHOTO) engine.rebind() else engine.applyFlash()
            SettingKey.TORCH -> engine.applyFlash()
            SettingKey.LOCATION -> if (settings.saveLocation) {
                if (location.hasPermission()) {
                    location.start()
                } else {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            } else {
                location.stop()
            }
            SettingKey.LENS_SELECTION -> {
                state.manualLensId = null
                engine.rebind()
            }
            else -> if (key.needsRebind) engine.rebind()
        }
    }

    // ---------------------------------------------------------------- gallery

    override fun openGallery() {
        if (engine.isRecording) return
        if (state.secure) {
            // Locked phone: only show what was taken since the camera was opened (like Pixel Camera).
            if (state.sessionItems.isNotEmpty()) state.viewerItems = state.sessionItems.toList()
            return
        }
        activity.lifecycleScope.launch {
            val shown = state.lastItem
            val item = withContext(Dispatchers.IO) { shown?.takeIf { media.exists(it) } ?: media.latest() }
            if (item != shown) {
                state.lastItem = item
                state.thumbnail = item?.let { withContext(Dispatchers.IO) { media.thumbnail(it, 256) } }
            }
            val recent = withContext(Dispatchers.IO) { media.recent(300) }
            GalleryIntents.open(activity, item) {
                // No Google Photos: swipe through the recent camera shots in Foco's own viewer.
                state.viewerItems = recent.ifEmpty { listOfNotNull(item) }.ifEmpty { null }
            }
        }
    }

    override fun openItemExternally(item: MediaItem) {
        runCatching { activity.startActivity(Intent.createChooser(GalleryIntents.anyGallery(item), null)) }
            .onFailure { state.message = "No other app can open this" }
    }

    override fun shareItem(item: MediaItem) {
        val send = Intent(Intent.ACTION_SEND).setType(item.mimeType).putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { activity.startActivity(Intent.createChooser(send, null)) }
    }

    override fun closeViewer() {
        state.viewerItems = null
    }

    override fun deleteItem(item: MediaItem) {
        activity.lifecycleScope.launch {
            when (val result = withContext(Dispatchers.IO) { media.remove(item) }) {
                MediaRepository.Removal.Done -> afterRemoved(item)
                MediaRepository.Removal.Failed -> state.message = "Couldn't delete"
                is MediaRepository.Removal.NeedsConfirmation -> launchIntentSender(result.request) { ok ->
                    if (ok) activity.lifecycleScope.launch { afterRemoved(item) }
                }
            }
        }
    }

    private suspend fun afterRemoved(item: MediaItem) {
        run {
            state.sessionItems.remove(item)
            state.viewerItems = state.viewerItems?.filter { it != item }?.takeIf { it.isNotEmpty() }
            if (state.lastItem == item) {
                val next = if (state.secure) state.sessionItems.firstOrNull() else withContext(Dispatchers.IO) { media.latest() }
                state.lastItem = next
                state.thumbnail = next?.let { withContext(Dispatchers.IO) { media.thumbnail(it, 256) } }
            }
        }
    }

    fun onLensHazy() {
        state.message = "Your lens may be smudged. Wipe it for clearer photos."
    }

    // ---------------------------------------------------------------- other apps asking for a photo/video

    override fun reviewRetake() {
        state.review?.file?.delete()
        state.review = null
    }

    override fun reviewAccept() {
        val review = state.review ?: return
        val intent = activity.intent
        val output: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
        }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { deliver(review, output) }
            if (result == null) {
                state.message = "Couldn't hand the file back"
                return@launch
            }
            activity.setResult(Activity.RESULT_OK, result)
            review.file.delete()
            activity.finish()
        }
    }

    private fun deliver(review: Review, output: Uri?): Intent? = runCatching {
        val resolver = activity.contentResolver
        if (output != null) {
            resolver.openOutputStream(output, "wt")?.use { out -> review.file.inputStream().use { it.copyTo(out) } }
                ?: return@runCatching null
            return@runCatching Intent().setData(output).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!review.isVideo) {
            // No output given: the caller expects a small bitmap in "data".
            val full = BitmapFactory.decodeFile(review.file.absolutePath) ?: return@runCatching null
            val scale = 512f / maxOf(full.width, full.height)
            val small = if (scale < 1f) full.scale((full.width * scale).toInt(), (full.height * scale).toInt()) else full
            return@runCatching Intent().putExtra("data", small)
        }
        val values = MediaNaming.contentValues(MediaNaming.baseName(System.currentTimeMillis()), "video/mp4")
            .apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { out -> review.file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.getOrNull()

    // ---------------------------------------------------------------- misc

    override fun qrClicked(text: String) {
        val uri = runCatching { text.toUri() }.getOrNull()
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https") && !state.secure) {
            openUrl(text)
        } else {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("QR code", text))
            if (Build.VERSION.SDK_INT < 33) state.message = "Copied"
        }
    }

    override fun openUrl(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { state.message = "No browser found" }
    }

    override fun requestPermission() {
        requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    override fun openAppInfo() {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
            )
        }
    }

    override fun haptic() = haptic.invoke()

    fun release() {
        countdownJob?.cancel()
        runCatching { sounds.release() }
    }
}
