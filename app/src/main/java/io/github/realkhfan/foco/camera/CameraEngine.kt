package io.github.realkhfan.foco.camera

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.animation.DecelerateInterpolator
import androidx.annotation.OptIn
import androidx.annotation.RequiresPermission
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import io.github.realkhfan.foco.media.MediaNaming
import io.github.realkhfan.foco.processing.BurstSession
import io.github.realkhfan.foco.processing.JpegSplice
import io.github.realkhfan.foco.processing.PhotoMeta
import io.github.realkhfan.foco.processing.PhotoWriter
import io.github.realkhfan.foco.processing.PanoramaSession
import io.github.realkhfan.foco.ui.PhotoMode
import io.github.realkhfan.foco.processing.ShotPlan
import io.github.realkhfan.foco.processing.ShotPlanner
import io.github.realkhfan.foco.processing.ToneSettings
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.settings.FlashSetting
import io.github.realkhfan.foco.settings.FrameRate
import io.github.realkhfan.foco.settings.RatioSetting
import io.github.realkhfan.foco.settings.ResolutionSetting
import io.github.realkhfan.foco.settings.VideoResolution
import io.github.realkhfan.foco.ui.AppState
import io.github.realkhfan.foco.ui.Capabilities
import io.github.realkhfan.foco.ui.CaptureKind
import io.github.realkhfan.foco.ui.IntentMode
import io.github.realkhfan.foco.ui.Readout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns CameraX: binds the right use cases for the current mode, probes what the phone allows,
 * applies zoom, flash and the Pro controls, and runs Foco's multi-frame photos.
 * All public methods are called on the main thread.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(
    private val context: Context,
    private val state: AppState,
    private val settings: AppSettings,
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor { Thread(it, "foco-analysis") }

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var photoFormat = ImageCapture.OUTPUT_FORMAT_JPEG
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private var recording: Recording? = null

    private var targetRotation = Surface.ROTATION_0
    /** Zoom shown to the user = CameraX zoom × [zoomFactor] (≠ 1 only when bound to one physical lens). */
    private var zoomFactor = 1f
    private var boundPhysicalId: String? = null
    private var desiredZoom = 1f
    private var logicalMinZoom = 1f
    private var logicalMaxZoom = 1f
    private var zoomAnimator: ValueAnimator? = null

    @Volatile private var meteredShutterNs = 16_666_666L
    @Volatile private var meteredIso = 100
    @Volatile private var lastAperture: Float? = null
    @Volatile private var lastFocalMm: Float? = null
    @Volatile private var activeLensRatio = 1f
    @Volatile private var clippedFraction = 0f
    private var lastReadoutMs = 0L
    private var lastQrScan = 0L
    private var lastQrHit = 0L
    private var lastStats = 0L
    private var lastHazeCheck = 0L
    private var hazeStrikes = 0
    private var lastHazeWarning = 0L
    private var lastScanDetect = 0L
    private var lastScanHit = 0L
    @Volatile private var boundForScan = false
    private val scanning: Boolean get() = state.kind == CaptureKind.PHOTO && state.photoMode == PhotoMode.SCAN
    /** Rotation from the camera's frames to the (portrait) screen, for drawing the page outline. */
    @Volatile private var displayRotation = 90

    /** Exposure/ISO of recent frames by sensor timestamp, so a burst can tell which frames have new settings. */
    private val frameMeta = ConcurrentHashMap<Long, LongArray>()

    @Volatile private var burst: BurstSession? = null
    @Volatile private var pano: PanoramaSession? = null
    /** Camera settings while a burst runs: a fixed exposure (Night, highlight frame) or locked auto exposure. */
    private data class BurstCamera(val exposureNs: Long?, val iso: Int?, val frameNs: Long = 33_333_333L)
    private var burstOverride: BurstCamera? = null
    private var highlightRequested = false
    private var burstTimeout: Runnable? = null

    var previewView: PreviewView? = null
        set(value) {
            field = value
            if (value != null && provider != null && camera == null) rebind()
        }
    var screenFlash: ImageCapture.ScreenFlash? = null
    var locationProvider: () -> Location? = { null }
    /** Phone rotation speed in rad/s (gyroscope), for picking night exposures. */
    var motionProvider: () -> Float = { 0f }
    var onLensHazy: () -> Unit = {}

    val isRecording: Boolean get() = recording != null
    /** A processed photo or panorama is being taken. */
    val isBursting: Boolean get() = burst != null || pano != null
    /** (turn, elevation, turning speed) from the gyroscope, for panoramas. */
    var panSensors: () -> FloatArray = { floatArrayOf(0f, 0f, 0f) }
    var resetPan: () -> Unit = {}

    private val qrScanner = QrScanner()

    private val zoomObserver = Observer<ZoomState> { zs ->
        if (boundPhysicalId == null) {
            logicalMinZoom = zs.minZoomRatio
            logicalMaxZoom = zs.maxZoomRatio
        }
        state.minZoom = zs.minZoomRatio * zoomFactor
        state.maxZoom = zs.maxZoomRatio * zoomFactor
        state.zoom = zs.zoomRatio * zoomFactor
        state.zoomChips = ZoomMath.chips(
            minZoom = if (boundPhysicalId == null) logicalMinZoom else minOf(logicalMinZoom, state.minZoom),
            maxZoom = if (boundPhysicalId == null) logicalMaxZoom else maxOf(logicalMaxZoom, state.maxZoom),
            teleRatios = state.caps.teleRatios,
            isFront = state.lensFacing == CameraSelector.LENS_FACING_FRONT,
        )
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp != null && exposure != null && iso != null) {
                frameMeta[timestamp] = longArrayOf(exposure, iso.toLong())
                if (frameMeta.size > 90) {
                    val cutoff = timestamp - 3_000_000_000L
                    frameMeta.keys.removeAll { it < cutoff }
                }
            }
            val aeOff = request.get(CaptureRequest.CONTROL_AE_MODE) == CameraMetadata.CONTROL_AE_MODE_OFF
            if (!aeOff && exposure != null && iso != null) {
                meteredShutterNs = exposure
                meteredIso = iso
            }
            lastAperture = result.get(CaptureResult.LENS_APERTURE)
            lastFocalMm = result.get(CaptureResult.LENS_FOCAL_LENGTH)
            val activeId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: boundPhysicalId
            val now = SystemClock.uptimeMillis()
            if (now - lastReadoutMs < 250) return
            lastReadoutMs = now
            val aperture = result.get(CaptureResult.LENS_APERTURE)
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val shutterNow = meteredShutterNs
            val isoNow = meteredIso
            mainHandler.post {
                val ratio = activeId?.let { state.caps.lensRatios[it] }
                if (ratio != null) activeLensRatio = ratio
                val lens = when {
                    ratio != null -> LensMath.kindForRatio(ratio)
                    state.lensFacing == CameraSelector.LENS_FACING_FRONT -> LensKind.WIDE
                    state.zoom < 0.95f -> LensKind.ULTRAWIDE
                    state.caps.teleRatios.any { state.zoom >= it - 0.05f } -> LensKind.TELEPHOTO
                    else -> LensKind.WIDE
                }
                state.readout = Readout(lens, aperture, exposure, iso, focus)
                if (burst == null) {
                    state.dark = ShotPlanner.isDark(shutterNow, isoNow)
                    state.nightEstimate = ShotPlanner.autoNightSeconds(shutterNow, isoNow, ShotPlanner.motionFor(motionProvider()))
                }
            }
        }
    }

    /** Every frame of the full-resolution stream: bursts, QR codes, highlight statistics, lens haze. */
    private val analyzer = ImageAnalysis.Analyzer { image ->
        try {
            val session = burst
            if (session != null) {
                feedBurst(session, image)
                return@Analyzer
            }
            val panorama = pano
            if (panorama != null) {
                panorama.offer(image)
                return@Analyzer
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastStats > 500) {
                lastStats = now
                clippedFraction = sampleClipped(image)
            }
            if (boundForScan) {
                // Only while the viewfinder is visible (not behind the page editor or the review).
                if (now - lastScanDetect > 120 && state.scanEdit == null && !state.scanReviewOpen) {
                    lastScanDetect = now
                    detectPage(image)
                }
                return@Analyzer
            }
            if (settings.qrDetection && state.intentMode == IntentMode.NORMAL && now - lastQrScan > 350) {
                lastQrScan = now
                val text = runCatching { qrScanner.scan(image) }.getOrNull()
                mainHandler.post {
                    if (text != null) {
                        lastQrHit = SystemClock.uptimeMillis()
                        state.qrText = text
                    } else if (SystemClock.uptimeMillis() - lastQrHit > 2500) {
                        state.qrText = null
                    }
                }
            }
            if (settings.dirtyLens && now - lastHazeCheck > 2000 && meteredIso <= 400) {
                lastHazeCheck = now
                if (runCatching { LensHaze.looksHazy(image) }.getOrDefault(false)) hazeStrikes++ else hazeStrikes = 0
                if (hazeStrikes >= 3 && now - lastHazeWarning > 30 * 60_000L) {
                    hazeStrikes = 0
                    lastHazeWarning = now
                    mainHandler.post { onLensHazy() }
                }
            }
        } finally {
            image.close()
        }
    }

    private fun sampleClipped(image: ImageProxy): Float {
        val buf = image.planes[0].buffer
        var clipped = 0
        var total = 0
        var i = 0
        val limit = buf.limit()
        while (i < limit) {
            if ((buf.get(i).toInt() and 0xff) >= 250) clipped++
            total++
            i += 97
        }
        return if (total == 0) 0f else clipped.toFloat() / total
    }

    private fun feedBurst(session: BurstSession, image: ImageProxy) {
        val ts = image.imageInfo.timestamp
        var meta = frameMeta[ts]
        // The capture result sometimes arrives a moment after the picture: wait briefly when exposure matters.
        if (meta == null && (session.plan.exposureNs != null || session.wantsHighlight)) {
            var tries = 0
            while (meta == null && tries++ < 10) {
                Thread.sleep(5)
                meta = frameMeta[ts]
            }
        }
        val more = session.offer(image, meta?.get(0) ?: 0L, meta?.get(1)?.toInt() ?: 0)
        if (more && session.wantsHighlight && !highlightRequested) {
            highlightRequested = true
            mainHandler.post { requestHighlightFrame(session) }
        }
    }

    suspend fun start(owner: LifecycleOwner) {
        this.owner = owner
        if (provider == null) provider = awaitProvider()
        rebind()
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, mainExecutor)
    }

    fun release() {
        burst?.stop()
        pano?.stop()
        recording?.stop()
        provider?.unbindAll()
        analysisExecutor.shutdown()
    }

    private enum class Setup { PROCESSING, CLASSIC, CLASSIC_SIMPLE, VIDEO, VIDEO_NO_SNAPSHOT, VIDEO_SIMPLE }

    /** Night, Long Exposure, Action Pan and Panorama always need Foco's frames; Photo only if processing is on. */
    private fun wantsProcessing(caps: Capabilities): Boolean = state.kind == CaptureKind.PHOTO && state.photoMode != PhotoMode.SCAN && (
        state.photoMode != PhotoMode.PHOTO ||
            settings.processing && !settings.raw && settings.flash != FlashSetting.ON &&
            !(settings.resolution == ResolutionSetting.MP50 && caps.hiResAvailable)
        )

    /** Rebinds only if the new photo mode needs a different camera setup. */
    fun photoModeChanged() {
        val caps = state.caps
        val scan = scanning
        if (wantsProcessing(caps) != state.processing || scan != boundForScan) rebind()
        if (state.photoMode == PhotoMode.PANORAMA) setZoom(1f, fromChip = true)
    }

    /** (Re)binds everything for the current mode/settings. Tries simpler setups if the phone refuses a combination. */
    fun rebind() {
        val provider = provider ?: return
        val owner = owner ?: return
        val view = previewView ?: return
        if (recording != null || burst != null || pano != null) return

        zoomAnimator?.cancel()
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        provider.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null
        analysis = null
        state.cameraReady = false
        state.qrText = null

        val back = CameraSelector.DEFAULT_BACK_CAMERA
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        val hasBack = runCatching { provider.hasCamera(back) }.getOrDefault(false)
        val hasFront = runCatching { provider.hasCamera(front) }.getOrDefault(false)
        if (!hasBack && !hasFront) {
            state.message = "No camera found"
            return
        }
        if (state.lensFacing == CameraSelector.LENS_FACING_BACK && !hasBack) state.lensFacing = CameraSelector.LENS_FACING_FRONT
        if (state.lensFacing == CameraSelector.LENS_FACING_FRONT && !hasFront) state.lensFacing = CameraSelector.LENS_FACING_BACK
        val isBack = state.lensFacing == CameraSelector.LENS_FACING_BACK
        val baseSelector = if (isBack) back else front

        val info = provider.getCameraInfo(baseSelector)
        val caps = probe(info, isBack).copy(hasFront = hasFront && hasBack)
        state.caps = caps

        val physicalId: String? = when {
            !isBack -> null
            state.kind == CaptureKind.PHOTO && settings.macro && caps.macroAvailable -> caps.ultrawideId
            settings.manualLens && caps.manualLensAvailable ->
                state.manualLensId?.takeIf { it in caps.lensRatios }
                    ?: caps.lensRatios.minByOrNull { abs(it.value - 1f) }?.key
            else -> null
        }
        val setups = when {
            state.kind == CaptureKind.VIDEO -> listOf(Setup.VIDEO, Setup.VIDEO_NO_SNAPSHOT, Setup.VIDEO_SIMPLE)
            wantsProcessing(caps) -> listOf(Setup.PROCESSING, Setup.CLASSIC, Setup.CLASSIC_SIMPLE)
            else -> listOf(Setup.CLASSIC, Setup.CLASSIC_SIMPLE)
        }

        var bound: Camera? = null
        var usedPhysical = physicalId
        var usedSetup = setups.first()
        val selectors = buildList {
            if (physicalId != null) add(CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).setPhysicalCameraId(physicalId).build())
            add(baseSelector)
        }
        loop@ for (selector in selectors) {
            for (setup in setups) {
                val cases = buildUseCases(view, caps, setup)
                bound = try {
                    provider.bindToLifecycle(owner, selector, *cases.toTypedArray())
                } catch (e: Exception) {
                    Log.w(TAG, "bind failed ($setup, physical=${selector != baseSelector})", e)
                    provider.unbindAll()
                    null
                }
                if (bound != null && setup == Setup.PROCESSING) {
                    // Only worth it with (nearly) full-resolution frames.
                    val size = analysis?.resolutionInfo?.resolution
                    if (size == null || size.width.toLong() * size.height < 8_000_000L) {
                        Log.i(TAG, "full-resolution frames not available ($size): using the camera's own photos")
                        provider.unbindAll()
                        bound = null
                    }
                }
                if (bound != null) {
                    usedSetup = setup
                    if (setup == Setup.CLASSIC_SIMPLE || setup == Setup.VIDEO_SIMPLE) {
                        state.message = "Some of these options can't be combined on this phone"
                    }
                    if (selector == baseSelector) usedPhysical = null
                    break@loop
                }
            }
            if (selector != baseSelector) state.message = "This phone doesn't let apps pick that lens directly"
        }
        val cam = bound ?: run {
            state.message = "Camera couldn't be started"
            return
        }
        camera = cam
        displayRotation = runCatching { cam.cameraInfo.sensorRotationDegrees }.getOrDefault(90)
        state.processing = usedSetup == Setup.PROCESSING
        boundPhysicalId = usedPhysical
        zoomFactor = usedPhysical?.let { caps.lensRatios[it] } ?: 1f
        if (usedPhysical == null) state.manualLensId = null else if (!settings.macro) state.manualLensId = usedPhysical

        cam.cameraInfo.zoomState.observe(owner, zoomObserver)
        applyFlash()
        applyPro()
        applyZoom(desiredZoom)
        state.cameraReady = true
    }

    private fun buildUseCases(view: PreviewView, caps: Capabilities, setup: Setup): List<UseCase> {
        val isVideo = state.kind == CaptureKind.VIDEO
        boundForScan = scanning
        val ratio = if (!scanning && (isVideo || settings.ratio == RatioSetting.WIDE_9_16)) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val aspect = AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO)

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
        val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val cases = mutableListOf<UseCase>(preview)
        imageCapture = null
        videoCapture = null
        analysis = null
        state.snapshotAvailable = false

        when (setup) {
            Setup.PROCESSING -> {
                // Full-resolution YUV frames (4:3 = whole sensor) that Foco merges itself.
                val full = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build(),
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(targetRotation)
                    .build()
                full.setAnalyzer(analysisExecutor, analyzer)
                analysis = full
                cases += full
            }
            Setup.CLASSIC, Setup.CLASSIC_SIMPLE -> {
                val simple = setup == Setup.CLASSIC_SIMPLE
                val wantHiRes = !simple && !scanning && settings.resolution == ResolutionSetting.MP50 &&
                    caps.hiResAvailable && ratio == AspectRatio.RATIO_4_3
                photoFormat = when {
                    scanning -> ImageCapture.OUTPUT_FORMAT_JPEG
                    !simple && settings.raw && caps.rawSupported -> ImageCapture.OUTPUT_FORMAT_RAW_JPEG
                    !simple && settings.ultraHdr && caps.ultraHdrSupported && !wantHiRes -> ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR
                    else -> ImageCapture.OUTPUT_FORMAT_JPEG
                }
                val selector = ResolutionSelector.Builder().setAspectRatioStrategy(aspect).apply {
                    if (wantHiRes) {
                        setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        setResolutionStrategy(
                            ResolutionStrategy(Size(caps.hiResWidth, caps.hiResHeight), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                        )
                    } else {
                        setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    }
                }.build()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(selector)
                    .setOutputFormat(photoFormat)
                    .setTargetRotation(targetRotation)
                    .apply { screenFlash?.let { setScreenFlash(it) } }
                    .setFlashMode(photoFlashMode(caps))
                    .build()
                imageCapture = capture
                cases += capture
                if (!simple && (settings.qrDetection || scanning) && state.intentMode == IntentMode.NORMAL) {
                    val small = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                                .build(),
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    small.setAnalyzer(analysisExecutor, analyzer)
                    analysis = small
                    cases += small
                }
            }
            Setup.VIDEO, Setup.VIDEO_NO_SNAPSHOT, Setup.VIDEO_SIMPLE -> {
                val simple = setup == Setup.VIDEO_SIMPLE
                val quality = if (settings.videoResolution == VideoResolution.UHD && caps.uhdSupported && !simple) Quality.UHD else Quality.FHD
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
                    .setAspectRatio(AspectRatio.RATIO_16_9)
                    .build()
                val builder = VideoCapture.Builder(recorder)
                    .setTargetRotation(targetRotation)
                    .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                if (!simple) {
                    if (settings.stabilization && caps.stabilizationSupported) builder.setVideoStabilizationEnabled(true)
                    when {
                        settings.frameRate == FrameRate.FPS60 && caps.fps60Supported -> builder.setTargetFrameRate(Range(60, 60))
                        settings.frameRate == FrameRate.AUTO && caps.autoFpsLower > 0 -> builder.setTargetFrameRate(Range(caps.autoFpsLower, 30))
                        else -> Unit
                    }
                    if (settings.hdrVideo && caps.hdrVideoSupported) builder.setDynamicRange(DynamicRange.HLG_10_BIT)
                }
                val video = builder.build()
                videoCapture = video
                cases += video
                if (setup == Setup.VIDEO && state.intentMode == IntentMode.NORMAL) {
                    val snapshot = ImageCapture.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build(),
                        )
                        .setTargetRotation(targetRotation)
                        .build()
                    photoFormat = ImageCapture.OUTPUT_FORMAT_JPEG
                    imageCapture = snapshot
                    cases += snapshot
                }
                state.snapshotAvailable = imageCapture != null
            }
        }
        return cases
    }

    /** Reads what this camera can do. Uses Camera2 characteristics directly so nothing is guessed. */
    private fun probe(info: CameraInfo, isBack: Boolean): Capabilities {
        val c2 = Camera2CameraInfo.from(info)
        fun <T : Any> ch(key: CameraCharacteristics.Key<T>): T? = runCatching { c2.getCameraCharacteristic(key) }.getOrNull()

        val available = ch(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in available
        val isoRange = ch(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutterRange = ch(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = ch(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val awb = ch(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet() ?: emptySet()
        val toneModes = ch(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
        val maxCurvePoints = ch(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
        val cct = Build.VERSION.SDK_INT >= 36 &&
            ch(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)?.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT) == true
        val hiRes = ch(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getHighResolutionOutputSizes(ImageFormat.JPEG)
            ?.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f }
            ?.maxByOrNull { it.width.toLong() * it.height }
        val formats = runCatching { ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats }.getOrDefault(emptySet())
        val exposure = info.exposureState
        val video = runCatching { Recorder.getVideoCapabilities(info) }.getOrNull()
        val sdrQualities = runCatching { video?.getSupportedQualities(DynamicRange.SDR) }.getOrNull().orEmpty()
        val hdrVideo = runCatching { video?.supportedDynamicRanges?.contains(DynamicRange.HLG_10_BIT) == true }.getOrDefault(false)
        val stabilization = runCatching { video?.isStabilizationSupported == true }.getOrDefault(false)
        val fpsRanges = runCatching { info.supportedFrameRateRanges }.getOrDefault(emptySet())
        val fps60 = fpsRanges.any { it.lower == 60 && it.upper == 60 }
        val autoLower = fpsRanges.filter { it.upper == 30 && it.lower in 15..29 }.map { it.lower }
            .let { lows -> if (24 in lows) 24 else lows.minOrNull() ?: 0 }

        val logicalFocal = ch(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val logicalSize = ch(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val logicalEq = if (logicalFocal != null && logicalSize != null) {
            LensSpec("logical", logicalFocal, logicalSize.width, logicalSize.height, null, 0f).equivalentFocalMm
        } else {
            null
        }
        // Physical lenses (only the back camera on Pixels has several).
        var ratios = emptyMap<String, Float>()
        var lenses = emptyList<LensSpec>()
        if (isBack) {
            lenses = LensMath.distinct(probeLenses(c2.cameraId))
            ratios = LensMath.ratios(lenses, logicalEq)
        }
        val cameraXPhysical = runCatching { info.physicalCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.toSet() }
            .getOrDefault(emptySet())
        val selectable = ratios.filterKeys { it in cameraXPhysical }
        val ultrawide = selectable.filter { it.value < 0.8f }.minByOrNull { it.value }?.key
        val ultrawideFocus = lenses.firstOrNull { it.id == ultrawide }?.minFocusDiopters ?: 0f
        val evRange = exposure.exposureCompensationRange
        val evStep = exposure.exposureCompensationStep

        return Capabilities(
            hasFlash = info.hasFlashUnit(),
            rawSupported = ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats,
            ultraHdrSupported = ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in formats,
            hiResWidth = hiRes?.width ?: 0,
            hiResHeight = hiRes?.height ?: 0,
            lensRatios = ratios,
            ultrawideId = ultrawide,
            macroAvailable = ultrawide != null && ultrawideFocus > minFocus * 1.5f,
            manualLensAvailable = selectable.size > 1,
            uhdSupported = Quality.UHD in sdrQualities,
            fps60Supported = fps60,
            stabilizationSupported = stabilization,
            hdrVideoSupported = hdrVideo,
            evMin = if (exposure.isExposureCompensationSupported) evRange.lower else 0,
            evMax = if (exposure.isExposureCompensationSupported) evRange.upper else 0,
            evStep = if (evStep.denominator != 0) evStep.toFloat() else 1f / 3f,
            manualSensor = manualSensor && isoRange != null && shutterRange != null,
            isoMin = isoRange?.lower ?: 50,
            isoMax = isoRange?.upper ?: 3200,
            shutterMinNs = shutterRange?.lower ?: 100_000L,
            shutterMaxNs = shutterRange?.upper ?: 500_000_000L,
            manualFocus = manualSensor && minFocus > 0f,
            minFocusDiopters = minFocus,
            cctSupported = cct,
            awbModes = awb,
            tonemapCurve = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in toneModes && maxCurvePoints >= 2,
            shadowsCurvePoints = maxCurvePoints.coerceIn(2, 33),
            equivalentFocalMm = logicalEq ?: 0f,
            autoFpsLower = autoLower,
            focalMm = logicalFocal ?: 0f,
            sensorWidthMm = logicalSize?.width ?: 0f,
        )
    }

    private fun probeLenses(logicalId: String): List<LensSpec> = runCatching {
        val manager = context.getSystemService(CameraManager::class.java)
        val logical = manager.getCameraCharacteristics(logicalId)
        logical.physicalCameraIds.mapNotNull { id ->
            runCatching {
                val c = manager.getCameraCharacteristics(id)
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@runCatching null
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching null
                LensSpec(
                    id = id,
                    focalLengthMm = focal,
                    sensorWidthMm = size.width,
                    sensorHeightMm = size.height,
                    aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
                    minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun photoFlashMode(caps: Capabilities = state.caps): Int = when {
        settings.flash != FlashSetting.ON -> ImageCapture.FLASH_MODE_OFF
        state.lensFacing == CameraSelector.LENS_FACING_FRONT ->
            if (screenFlash != null) ImageCapture.FLASH_MODE_SCREEN else ImageCapture.FLASH_MODE_OFF
        caps.hasFlash -> ImageCapture.FLASH_MODE_ON
        else -> ImageCapture.FLASH_MODE_OFF
    }

    fun applyFlash() {
        if (state.kind == CaptureKind.PHOTO) runCatching { imageCapture?.flashMode = photoFlashMode() }
        val torch = state.kind == CaptureKind.VIDEO && settings.torch && state.caps.hasFlash
        camera?.cameraControl?.enableTorch(torch)
    }

    /** Sends the Pro values (and, during a burst, the burst's exposure) to the camera. */
    fun applyPro() {
        val cam = camera ?: return
        val caps = state.caps
        val pro = state.pro
        val options = CaptureRequestOptions.Builder()
        val override = burstOverride
        if (override != null) {
            if (override.exposureNs != null && override.iso != null) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, override.exposureNs)
                options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, override.iso)
                options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(override.exposureNs, override.frameNs))
            } else {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        } else if (caps.manualSensor && (pro.shutterNs != null || pro.iso != null)) {
            val (shutter, iso) = ExposureMath.complete(
                pro.shutterNs, pro.iso, meteredShutterNs, meteredIso,
                caps.shutterMinNs..caps.shutterMaxNs, caps.isoMin..caps.isoMax,
            )
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(shutter, 33_333_333L))
        }
        val focus = pro.focusDiopters
        if (caps.manualFocus && focus != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        }
        val kelvin = pro.wbKelvin
        if (kelvin != null) {
            if (caps.cctSupported && Build.VERSION.SDK_INT >= 36) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                options.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_CCT)
                options.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, kelvin)
            } else if (caps.awbModes.size > 1) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, WhiteBalanceMath.presetFor(kelvin, caps.awbModes))
            }
        }
        // With Foco's processing the Shadows control is applied to the photo itself; otherwise via the camera.
        if (!state.processing && caps.tonemapCurve && abs(pro.shadows) > 0.01f) {
            val curve = ToneCurve.shadows(pro.shadows, caps.shadowsCurvePoints)
            options.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            options.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, TonemapCurve(curve, curve, curve))
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())
        if (caps.evMax > caps.evMin && override == null) {
            cam.cameraControl.setExposureCompensationIndex(pro.evIndex.coerceIn(caps.evMin, caps.evMax))
        }
    }

    // ------------------------------------------------------------------ zoom & focus

    /** @param zoom zoom as shown to the user (0.5 = ultrawide). Chip taps glide smoothly like Pixel Camera. */
    fun setZoom(zoom: Float, fromChip: Boolean) {
        desiredZoom = zoom
        val caps = state.caps
        val canSwitchLens = fromChip && settings.manualLens && caps.manualLensAvailable &&
            state.lensFacing == CameraSelector.LENS_FACING_BACK &&
            !(settings.macro && state.kind == CaptureKind.PHOTO) && recording == null
        if (canSwitchLens) {
            val target = caps.lensRatios.filter { it.value <= zoom + 0.05f }.maxByOrNull { it.value }?.key
                ?: caps.lensRatios.minByOrNull { it.value }?.key
            if (target != null && target != boundPhysicalId) {
                state.manualLensId = target
                rebind()
                return
            }
        }
        if (fromChip) animateZoom(zoom) else {
            zoomAnimator?.cancel()
            applyZoom(zoom)
        }
    }

    private fun animateZoom(target: Float) {
        val cam = camera ?: return
        val start = ((cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f) * zoomFactor).coerceAtLeast(0.05f)
        zoomAnimator?.cancel()
        if (abs(start - target) < 0.01f) {
            applyZoom(target)
            return
        }
        val from = ln(start)
        val to = ln(target.coerceAtLeast(0.05f))
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { applyZoom(exp(from + (to - from) * (it.animatedValue as Float))) }
            start()
        }
    }

    private fun applyZoom(zoom: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value
        val raw = zoom / zoomFactor
        val clamped = if (zs != null) raw.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else raw
        cam.cameraControl.setZoomRatio(clamped)
    }

    fun resetZoom() {
        desiredZoom = 1f
    }

    fun focusAt(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        val view = previewView ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .apply { if (lock) disableAutoCancel() else setAutoCancelDuration(5, TimeUnit.SECONDS) }
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun setTargetRotation(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
        analysis?.targetRotation = rotation
        if (recording == null) videoCapture?.targetRotation = rotation
    }

    /**
     * Scan: finds the page in a small copy of the viewfinder and shows its outline (smoothed, so it doesn't jitter).
     * Runs on the analysis thread.
     */
    private fun detectPage(image: ImageProxy) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val step = maxOf(1, maxOf(image.width, image.height) / 320)
        val w = image.width / step
        val h = image.height / step
        val gray = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * step * plane.rowStride
            for (x in 0 until w) gray[y * w + x] = (buf.get(row + x * step * plane.pixelStride).toInt() and 0xff).toFloat()
        }
        val found = io.github.realkhfan.foco.scan.DocumentDetector.detect(io.github.realkhfan.foco.scan.GrayImage(w, h, gray))
        val now = SystemClock.uptimeMillis()
        val quad = found?.let {
            val upright = io.github.realkhfan.foco.scan.Quad.rotate(it.corners, w.toFloat(), h.toFloat(), displayRotation)
            val swap = displayRotation == 90 || displayRotation == 270
            io.github.realkhfan.foco.scan.Quad.scale(upright, 1f / (if (swap) h else w), 1f / (if (swap) w else h))
        }
        mainHandler.post {
            if (quad != null) {
                lastScanHit = now
                val prev = state.scanQuad
                state.scanQuad = if (prev == null) quad else FloatArray(8) { prev[it] + (quad[it] - prev[it]) * 0.5f }
            } else if (now - lastScanHit > 500) {
                state.scanQuad = null
            }
        }
    }

    /** Scan: takes a full-resolution JPEG into memory (not into the gallery). */
    fun takeScanPhoto(onResult: (jpeg: ByteArray?, rotation: Int, focal: Float) -> Unit) {
        val capture = imageCapture ?: return onResult(null, 0, 0f)
        capture.targetRotation = targetRotation
        runCatching { capture.flashMode = if (settings.flash == FlashSetting.ON && state.caps.hasFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }
        val caps = state.caps
        val focal = if (caps.focalMm > 0f && caps.sensorWidthMm > 0f) caps.focalMm / caps.sensorWidthMm * state.zoom.coerceAtLeast(0.3f) else 0f
        capture.takePicture(analysisExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = try {
                    val buffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } catch (_: Exception) {
                    null
                }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                mainHandler.post { onResult(bytes, rotation, focal) }
            }

            override fun onError(exception: ImageCaptureException) {
                mainHandler.post { onResult(null, 0, 0f) }
            }
        })
    }

    fun mustPlayShutterSound(): Boolean = runCatching { CameraInfo.mustPlayShutterSound() }.getOrDefault(false)

    // ------------------------------------------------------------------ processed photos (HDR / Night / zoom)

    /** What the next processed photo would be. */
    fun planShot(night: Boolean, nightSeconds: Float): ShotPlan {
        val caps = state.caps
        when (state.photoMode) {
            PhotoMode.LONG_EXPOSURE -> return ShotPlanner.longExposure(
                state.dark, meteredShutterNs, meteredIso, ShotPlanner.motionFor(motionProvider()),
                caps.shutterMaxNs, caps.isoMin, caps.isoMax,
            )
            PhotoMode.ACTION_PAN -> return ShotPlanner.actionPan()
            else -> Unit
        }
        if (night) {
            return ShotPlanner.night(
                nightSeconds, meteredShutterNs, meteredIso, ShotPlanner.motionFor(motionProvider()),
                caps.shutterMaxNs, caps.isoMin, caps.isoMax,
            )
        }
        val digitalZoom = state.zoom / activeLensRatio.coerceAtLeast(0.3f)
        return ShotPlanner.daylight(clippedFraction, digitalZoom, settings.zoomDetail)
    }

    /** Starts a processed photo. Returns false if not possible right now (then use [takePhoto]). */
    fun startBurst(plan: ShotPlan, toFile: File?, listener: BurstSession.Listener): Boolean {
        val cam = camera ?: return false
        if (!state.processing || burst != null || analysis == null) return false
        val rotation = runCatching { cam.cameraInfo.getSensorRotationDegrees(targetRotation) }.getOrDefault(0)
        val mirror = state.lensFacing == CameraSelector.LENS_FACING_FRONT && settings.mirrorFront
        val wide = settings.ratio == RatioSetting.WIDE_9_16
        val night = plan.kind == io.github.realkhfan.foco.processing.ShotKind.NIGHT ||
            plan.kind == io.github.realkhfan.foco.processing.ShotKind.LONG_EXPOSURE && plan.exposureNs != null
        val ultraHdr = settings.ultraHdr && Build.VERSION.SDK_INT >= 34
        val tone = ToneSettings(
            night = night,
            blackLevel = if (night) 0.004f else 0f,
            sharpen = plan.sharpen,
            shadows = (state.pro.shadows + if (night) 0.15f else 0f).coerceIn(-1f, 1f),
            saturation = if (night) 1.12f else 1f,
            headroom = if (!ultraHdr) 1f else if (night) 3f else 4f,
            chromaDenoise = night,
        )
        val meteredNs = meteredShutterNs
        val meteredIsoNow = meteredIso
        val zoomNow = state.zoom
        val strip = settings.stripDeviceInfo
        val location = if (settings.saveLocation) locationProvider() else null
        val focal35 = state.caps.equivalentFocalMm.takeIf { it > 0f }?.let { (it * zoomNow).roundToInt() }
        val session = BurstSession(
            plan = plan,
            rotation = rotation,
            mirror = mirror,
            crop = { w, h -> if (wide) crop16x9(w, h) else null },
            tone = tone,
            ultraHdr = ultraHdr,
            finish = { renderer, hdr, stats ->
                val time = System.currentTimeMillis()
                var jpeg = PhotoWriter.encode(renderer, hdr)
                val meta = PhotoMeta(
                    timeMillis = time,
                    exposureNs = (if (stats.exposureNs > 0) stats.exposureNs else meteredNs),
                    iso = if (stats.iso > 0) stats.iso else meteredIsoNow,
                    aperture = lastAperture,
                    focalLengthMm = lastFocalMm,
                    focal35mm = focal35,
                    location = location,
                    includeDevice = !strip,
                )
                PhotoWriter.exifSegment(context, meta, renderer.outWidth, renderer.outHeight)?.let { jpeg = JpegSplice.insertExif(jpeg, it) }
                PhotoWriter.save(context, jpeg, MediaNaming.baseName(time), toFile)?.toString()
            },
            listener = object : BurstSession.Listener {
                override fun onFirstFrame(preview: android.graphics.Bitmap) = listener.onFirstFrame(preview)
                override fun onProgress(done: Int, total: Int) = listener.onProgress(done, total)
                override fun onCaptured() {
                    endBurst()
                    listener.onCaptured()
                }
                override fun onSaved(uri: String?) = listener.onSaved(uri)
            },
        )
        highlightRequested = false
        burstOverride = burstCameraSettings(plan)
        applyPro()
        burst = session
        val timeout = Runnable { burst?.stop() }
        burstTimeout = timeout
        mainHandler.postDelayed(timeout, (plan.seconds * 1600).toLong() + 3000L)
        return true
    }

    /** Ends the burst early (shutter tapped again during Night). */
    fun stopBurst() {
        burst?.stop()
        pano?.stop()
    }

    /** Starts a panorama. Returns false if not possible right now. */
    fun startPanorama(listener: PanoramaSession.Listener): Boolean {
        val cam = camera ?: return false
        if (!state.processing || burst != null || pano != null || analysis == null) return false
        val rotation = runCatching { cam.cameraInfo.getSensorRotationDegrees(targetRotation) }.getOrDefault(0)
        val caps = state.caps
        val size = analysis?.resolutionInfo?.resolution
        val longSide = size?.let { maxOf(it.width, it.height) } ?: 4000
        // Focal length in pixels of the camera frames; a typical main camera (≈ 70° across the long side) if unknown.
        val focalPx = if (caps.focalMm > 0f && caps.sensorWidthMm > 0f) {
            caps.focalMm / caps.sensorWidthMm * longSide
        } else {
            longSide / 2f / kotlin.math.tan(Math.toRadians(35.0)).toFloat()
        }
        val strip = settings.stripDeviceInfo
        val location = if (settings.saveLocation) locationProvider() else null
        resetPan()
        val session = PanoramaSession(
            rotation = rotation,
            // Zooming in crops and enlarges the frames: same as a longer lens.
            focalFull = focalPx * state.zoom.coerceAtLeast(1f),
            sensors = panSensors,
            save = { w, h, render ->
                val time = System.currentTimeMillis()
                var jpeg = PhotoWriter.encodePixels(w, h, render)
                val meta = PhotoMeta(time, null, null, lastAperture, lastFocalMm, null, location, !strip)
                PhotoWriter.exifSegment(context, meta, w, h)?.let { jpeg = JpegSplice.insertExif(jpeg, it) }
                PhotoWriter.save(context, jpeg, MediaNaming.baseName(time) + ".PANO", null)?.toString()
            },
            listener = object : PanoramaSession.Listener {
                override fun onShot(thumb: android.graphics.Bitmap, yaw: Float, fov: Float) = listener.onShot(thumb, yaw, fov)
                override fun onHint(hint: PanoramaSession.Hint) = listener.onHint(hint)
                override fun onCaptured(frames: Int) {
                    pano = null
                    burstOverride = null
                    applyPro()
                    listener.onCaptured(frames)
                }
                override fun onSaved(uri: String?) = listener.onSaved(uri)
            },
        )
        // Same exposure and colour for every frame, so the joins don't show.
        burstOverride = BurstCamera(null, null)
        applyPro()
        pano = session
        return true
    }

    private fun endBurst() {
        burstTimeout?.let { mainHandler.removeCallbacks(it) }
        burstTimeout = null
        burst = null
        burstOverride = null
        highlightRequested = false
        applyPro()
    }

    private fun burstCameraSettings(plan: ShotPlan): BurstCamera {
        val manualPro = state.pro.shutterNs != null || state.pro.iso != null
        val exposure = plan.exposureNs
        return if (exposure != null && plan.iso != null && state.caps.manualSensor && !manualPro) {
            BurstCamera(exposure, plan.iso, exposure)
        } else {
            BurstCamera(null, null)
        }
    }

    /** Switches the camera to a darker exposure for the highlight-recovery frame. */
    private fun requestHighlightFrame(session: BurstSession) {
        if (burst !== session || !state.caps.manualSensor) {
            session.stop()
            return
        }
        val divisor = session.plan.highlightDivisor ?: 4f
        val caps = state.caps
        val target = (meteredShutterNs / divisor).toLong().coerceIn(caps.shutterMinNs, caps.shutterMaxNs)
        val iso = meteredIso.coerceIn(caps.isoMin, caps.isoMax)
        session.highlightTargetNs = target
        burstOverride = BurstCamera(target, iso)
        applyPro()
    }

    /** Centre 16:9 crop of a 4:3 frame (sensor orientation). */
    private fun crop16x9(w: Int, h: Int): IntArray {
        val long = maxOf(w, h)
        val short = long * 9 / 16
        return if (w >= h) {
            val ch = minOf(h, short)
            intArrayOf(0, (h - ch) / 2, w, ch)
        } else {
            val cw = minOf(w, short)
            intArrayOf((w - cw) / 2, 0, cw, h)
        }
    }

    // ------------------------------------------------------------------ camera's own photos (RAW, flash, 50 MP, video snapshot)

    /**
     * Takes a photo with the camera's own processing into DCIM/Camera (or [toFile] for other apps).
     * [onResult] gets the saved JPEG's URI.
     */
    fun takePhoto(toFile: File?, onResult: (Uri?, Throwable?) -> Unit) {
        val capture = imageCapture ?: return onResult(null, IllegalStateException("Camera not ready"))
        capture.targetRotation = targetRotation
        if (state.kind == CaptureKind.PHOTO) runCatching { capture.flashMode = photoFlashMode() }
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = state.lensFacing == CameraSelector.LENS_FACING_FRONT && settings.mirrorFront
            if (settings.saveLocation) location = locationProvider()
        }
        var reported = false
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                if (outputFileResults.imageFormat == ImageFormat.RAW_SENSOR || reported) return
                reported = true
                onResult(outputFileResults.savedUri ?: toFile?.let { Uri.fromFile(it) }, null)
            }

            override fun onError(exception: ImageCaptureException) {
                if (reported) return
                reported = true
                onResult(null, exception)
            }
        }
        if (toFile != null) {
            val options = ImageCapture.OutputFileOptions.Builder(toFile).setMetadata(metadata).build()
            capture.takePicture(options, mainExecutor, callback)
            return
        }
        val name = MediaNaming.baseName(System.currentTimeMillis())
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val jpeg = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, collection, MediaNaming.contentValues(name, "image/jpeg"),
        ).setMetadata(metadata).build()
        if (photoFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG) {
            val raw = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver, collection, MediaNaming.contentValues(name, "image/x-adobe-dng"),
            ).setMetadata(metadata).build()
            capture.takePicture(raw, jpeg, mainExecutor, callback)
        } else {
            capture.takePicture(jpeg, mainExecutor, callback)
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(toFile: File?, onEvent: (VideoRecordEvent) -> Unit): Boolean {
        val video = videoCapture ?: return false
        if (recording != null) return false
        video.targetRotation = targetRotation
        val pending = if (toFile != null) {
            video.output.prepareRecording(context, FileOutputOptions.Builder(toFile).build())
        } else {
            val name = MediaNaming.baseName(System.currentTimeMillis())
            val builder = MediaStoreOutputOptions.Builder(
                context.contentResolver, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ).setContentValues(MediaNaming.contentValues(name, "video/mp4"))
            if (settings.saveLocation) locationProvider()?.let { builder.setLocation(it) }
            video.output.prepareRecording(context, builder.build())
        }
        if (hasAudioPermission()) enableAudio(pending)
        recording = pending.start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Finalize) recording = null
            onEvent(event)
        }
        return true
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun enableAudio(pending: PendingRecording) {
        pending.withAudioEnabled()
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun stopRecording() {
        recording?.stop()
    }

    fun pause() {
        recording?.pause()
    }

    fun resume() {
        recording?.resume()
    }

    companion object {
        private const val TAG = "FocoCamera"

    }
}
