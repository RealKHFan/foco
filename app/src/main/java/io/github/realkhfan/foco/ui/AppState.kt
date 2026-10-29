package io.github.realkhfan.foco.ui

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.realkhfan.foco.camera.LensKind
import io.github.realkhfan.foco.media.MediaItem
import io.github.realkhfan.foco.settings.SettingKey
import java.io.File

enum class CaptureKind { PHOTO, VIDEO }
enum class IntentMode { NORMAL, IMAGE_CAPTURE, VIDEO_CAPTURE }
enum class SettingsTab { GENERAL, PRO }
/** Photo modes in the order of the mode strip (like Pixel Camera, without Portrait). */
enum class PhotoMode(val label: String, val needsProcessing: Boolean = true) {
    ACTION_PAN("Action Pan"), LONG_EXPOSURE("Long Exposure"), PHOTO("Photo", needsProcessing = false), NIGHT("Night"),
    PANORAMA("Panorama"), SCAN("Scan", needsProcessing = false),
}

/** A captured panorama frame for the live strip at the top. */
class PanoThumb(val yaw: Float, val bitmap: android.graphics.Bitmap)

enum class PanoHint(val text: String) {
    START("Turn slowly to one side"), SLOW_DOWN("Slow down"), KEEP_LEVEL("Keep the phone level"),
}
enum class ProTool(val label: String) {
    NIGHT("Night"), BRIGHTNESS("Brightness"), SHADOWS("Shadows"), WHITE_BALANCE("White balance"),
    FOCUS("Focus"), SHUTTER("Shutter"), ISO("ISO"),
}

/** What the current camera (and phone) can do. Everything defaults to "not available" until probed. */
data class Capabilities(
    val hasFlash: Boolean = false,
    val hasFront: Boolean = true,
    val rawSupported: Boolean = false,
    val ultraHdrSupported: Boolean = false,
    val hiResWidth: Int = 0,
    val hiResHeight: Int = 0,
    val lensRatios: Map<String, Float> = emptyMap(),
    val ultrawideId: String? = null,
    val macroAvailable: Boolean = false,
    val manualLensAvailable: Boolean = false,
    val uhdSupported: Boolean = false,
    val fps60Supported: Boolean = false,
    val stabilizationSupported: Boolean = false,
    val hdrVideoSupported: Boolean = false,
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 1f / 3f,
    val manualSensor: Boolean = false,
    val isoMin: Int = 50,
    val isoMax: Int = 3200,
    val shutterMinNs: Long = 100_000L,
    val shutterMaxNs: Long = 500_000_000L,
    val manualFocus: Boolean = false,
    val minFocusDiopters: Float = 0f,
    val cctSupported: Boolean = false,
    val awbModes: Set<Int> = emptySet(),
    val tonemapCurve: Boolean = false,
    val shadowsCurvePoints: Int = 33,
    /** 35 mm-equivalent focal length at 1× (for Exif). */
    val equivalentFocalMm: Float = 0f,
    /** Lowest fps of an "up to 30 fps" range (24 or 15), 0 if none: used for Auto frame rate. */
    val autoFpsLower: Int = 0,
    /** Main lens focal length and sensor width, for the panorama projection. */
    val focalMm: Float = 0f,
    val sensorWidthMm: Float = 0f,
) {
    val hiResAvailable: Boolean get() = hiResWidth.toLong() * hiResHeight >= 40_000_000L
    val teleRatios: List<Float> get() = lensRatios.values.filter { it > 1.6f }.sorted()
    val wbAvailable: Boolean get() = cctSupported || awbModes.size > 1
}

/** Manual (Pro) values. null = automatic. */
data class ProState(
    val evIndex: Int = 0,
    val shadows: Float = 0f,
    val wbKelvin: Int? = null,
    val focusDiopters: Float? = null,
    val shutterNs: Long? = null,
    val iso: Int? = null,
    /** Night capture length in seconds: null = automatic, 0 = off. */
    val nightSeconds: Float? = null,
) {
    val isDefault: Boolean get() = this == ProState()
}

/** How the phone is held: [levelDegrees] for the horizon line, [flatX]/[flatY] when pointing straight down/up. */
data class Tilt(val levelDegrees: Float? = null, val flatX: Float? = null, val flatY: Float? = null)

/** Live values shown at the top while the Pro bar is open: "Wide f/1.68 · Auto focus · Ⓐ 1/10s · Ⓐ ISO 6376". */
data class Readout(
    val lens: LensKind = LensKind.WIDE,
    val aperture: Float? = null,
    val shutterNs: Long? = null,
    val iso: Int? = null,
    val focusDiopters: Float? = null,
)

data class Review(val file: File, val isVideo: Boolean)

/** A page whose corners are being adjusted. [image] is the whole upright photo. */
class ScanEdit(val page: io.github.realkhfan.foco.scan.ScanPage, val image: Bitmap, corners: FloatArray, val isNew: Boolean) {
    var corners by mutableStateOf(corners)
    var turn by mutableIntStateOf(page.turn)
}

enum class ExportTarget { SAVE, SAVE_TO, SHARE }

/** All UI state. Plain Compose state so the screen can be rendered in tests without a camera. */
class AppState {
    var permissionGranted by mutableStateOf(false)
    var permissionAsked by mutableStateOf(false)
    var secure by mutableStateOf(false)
    var intentMode by mutableStateOf(IntentMode.NORMAL)

    var kind by mutableStateOf(CaptureKind.PHOTO)
    var photoMode by mutableStateOf(PhotoMode.PHOTO)
    var micStatus by mutableStateOf(io.github.realkhfan.foco.device.MicStatus.UNKNOWN)
    /** Photos go through Foco's multi-frame processing (HDR, Night, sharper zoom). */
    var processing by mutableStateOf(false)
    /** The scene is dark enough for Night. */
    var dark by mutableStateOf(false)
    /** Seconds Night would take now (shown on the chip and the slider). */
    var nightEstimate by mutableFloatStateOf(0f)

    // A processed photo being taken / saved
    var burstActive by mutableStateOf(false)
    var burstNight by mutableStateOf(false)
    var burstKind by mutableStateOf<io.github.realkhfan.foco.processing.ShotKind?>(null)

    // Panorama being taken
    var panoActive by mutableStateOf(false)
    val panoShots = mutableStateListOf<PanoThumb>()
    var panoYaw by mutableFloatStateOf(0f)
    var panoPitch by mutableFloatStateOf(0f)
    var panoFov by mutableFloatStateOf(0.9f)
    var panoHint by mutableStateOf(PanoHint.START)
    // Document scan
    val scanPages = mutableStateListOf<io.github.realkhfan.foco.scan.ScanPage>()
    /** Live page outline in the viewfinder (fractions of the picture), or null. */
    var scanQuad by mutableStateOf<FloatArray?>(null)
    var scanEdit by mutableStateOf<ScanEdit?>(null)
    var scanReviewOpen by mutableStateOf(false)
    var scanReviewIndex by mutableIntStateOf(0)
    var scanBusy by mutableStateOf(false)
    var scanExportOpen by mutableStateOf(false)
    var scanExportProgress by mutableStateOf<Float?>(null)
    var scanExportDone by mutableStateOf<String?>(null)
    var scanExportOptions by mutableStateOf(
        io.github.realkhfan.foco.scan.ExportOptions(io.github.realkhfan.foco.scan.ExportNames.defaultName()),
    )
    /** Rendered pages by "page key@size". */
    val scanImages = androidx.compose.runtime.mutableStateMapOf<String, Bitmap>()

    /** Short explanation shown when a mode is picked. */
    var modeHint by mutableStateOf<String?>(null)
    var burstProgress by mutableFloatStateOf(0f)
    var burstSecondsLeft by mutableIntStateOf(0)
    var processingCount by mutableIntStateOf(0)
    /** No motion sensor data (GrapheneOS lets you deny Sensors): rotation and Panorama can't work. */
    var sensorsMissing by mutableStateOf(false)
    /** Phone movement (rad/s around x and y) for the "hold still" dot. */
    var motion by mutableStateOf(Offset.Zero)
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    var cameraReady by mutableStateOf(false)

    var zoom by mutableFloatStateOf(1f)
    var minZoom by mutableFloatStateOf(1f)
    var maxZoom by mutableFloatStateOf(1f)
    var zoomChips by mutableStateOf(listOf(1f))

    var caps by mutableStateOf(Capabilities())
    var pro by mutableStateOf(ProState())
    var readout by mutableStateOf(Readout())
    var manualLensId by mutableStateOf<String?>(null)

    var settingsOpen by mutableStateOf(false)
    var settingsTab by mutableStateOf(SettingsTab.GENERAL)
    var fullSettingsOpen by mutableStateOf(false)
    var proBarOpen by mutableStateOf(false)
    var activeTool by mutableStateOf<ProTool?>(null)

    var recording by mutableStateOf(false)
    var recordingPaused by mutableStateOf(false)
    var recordingMs by mutableLongStateOf(0L)
    var snapshotAvailable by mutableStateOf(false)
    var countdown by mutableStateOf<Int?>(null)
    var capturing by mutableStateOf(false)

    var thumbnail by mutableStateOf<Bitmap?>(null)
    var thumbnailTick by mutableIntStateOf(0)
    var lastItem by mutableStateOf<MediaItem?>(null)
    val sessionItems = mutableStateListOf<MediaItem>()
    var viewerItems by mutableStateOf<List<MediaItem>?>(null)

    var focusPoint by mutableStateOf<Offset?>(null)
    var focusTick by mutableIntStateOf(0)
    var focusLocked by mutableStateOf(false)

    var flashTick by mutableIntStateOf(0)
    var screenFlashOn by mutableStateOf(false)
    var deviceRotation by mutableIntStateOf(0)
    var tilt by mutableStateOf(Tilt())
    var qrText by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var review by mutableStateOf<Review?>(null)
}

/** Scan mode is on (document scanning). */
val AppState.scanning: Boolean get() = kind == CaptureKind.PHOTO && photoMode == PhotoMode.SCAN

/**
 * The phone refused Foco's multi-frame setup, so a mode that needs it can't work. Photo and Scan work with the
 * camera's own photos. Only known once the camera is running.
 */
fun AppState.modeUnavailable(mode: PhotoMode): Boolean = mode.needsProcessing && cameraReady && !processing

/** The photo modes offered: Scan keeps documents, so it's left out on the lock screen. */
val AppState.photoModes: List<PhotoMode> get() = if (secure) PhotoMode.entries - PhotoMode.SCAN else PhotoMode.entries

/** Night will be used for the next photo (Night mode, or "More light: Night auto" in the dark). */
fun AppState.nightWillRun(settings: io.github.realkhfan.foco.settings.AppSettings): Boolean =
    kind == CaptureKind.PHOTO && processing && pro.nightSeconds != 0f &&
        (photoMode == PhotoMode.NIGHT ||
            (photoMode == PhotoMode.PHOTO && settings.flash == io.github.realkhfan.foco.settings.FlashSetting.NIGHT_AUTO && dark))

/** Night length in seconds: the slider's value, or automatic. */
fun AppState.nightSecondsNow(): Float = pro.nightSeconds?.takeIf { it > 0f } ?: nightEstimate.coerceAtLeast(1f)

/** Everything the UI can ask for. Defaults do nothing so tests can pass `object : CameraActions {}`. */
interface CameraActions {
    fun shutter() {}
    fun setKind(kind: CaptureKind) {}
    fun setPhotoMode(mode: PhotoMode) {}
    fun micClicked() {}
    fun scanEditDone() {}
    fun scanEditCancel() {}
    fun openScanReview() {}
    fun closeScanReview() {}
    fun scanSetFilter(page: io.github.realkhfan.foco.scan.ScanPage, filter: io.github.realkhfan.foco.scan.ScanFilter, all: Boolean) {}
    fun scanTurn(page: io.github.realkhfan.foco.scan.ScanPage) {}
    fun scanDelete(page: io.github.realkhfan.foco.scan.ScanPage) {}
    fun scanMove(page: io.github.realkhfan.foco.scan.ScanPage, delta: Int) {}
    fun scanCrop(page: io.github.realkhfan.foco.scan.ScanPage) {}
    fun scanDiscard() {}
    fun scanImage(page: io.github.realkhfan.foco.scan.ScanPage, size: Int) {}
    fun scanExport(options: io.github.realkhfan.foco.scan.ExportOptions, target: ExportTarget) {}
    fun scanNew() {}
    fun flip() {}
    fun setZoom(zoom: Float, fromChip: Boolean = false) {}
    fun openGallery() {}
    fun focusAt(point: Offset, viewWidth: Int, viewHeight: Int, lock: Boolean) {}
    fun setPro(pro: ProState) {}
    fun settingChanged(key: SettingKey) {}
    fun pauseResume() {}
    fun snapshot() {}
    fun qrClicked(text: String) {}
    fun requestPermission() {}
    fun openAppInfo() {}
    fun reviewAccept() {}
    fun reviewRetake() {}
    fun closeViewer() {}
    fun deleteItem(item: MediaItem) {}
    fun openItemExternally(item: MediaItem) {}
    fun shareItem(item: MediaItem) {}
    fun openUrl(url: String) {}
    fun haptic() {}
}
