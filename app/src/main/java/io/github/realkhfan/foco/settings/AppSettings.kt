package io.github.realkhfan.foco.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** "More light": Off, Night automatically when it's dark (default, like Pixel), or the flash. */
enum class FlashSetting { OFF, NIGHT_AUTO, ON }
enum class TimerSetting(val seconds: Int) { OFF(0), S3(3), S5(5), S10(10) }
enum class RatioSetting { WIDE_9_16, FULL_4_3 }
enum class ResolutionSetting { MP12, MP50 }
enum class GridSetting { NONE, THIRDS, FOURTHS, GOLDEN }
enum class VolumeKeyAction { SHUTTER, ZOOM, VOLUME }
enum class VideoResolution { FHD, UHD }
/** AUTO = 30 fps that may drop to 24 in low light for brighter video. */
enum class FrameRate(val fps: Int) { AUTO(30), FPS30(30), FPS60(60) }

/** Settings that change how the camera has to be bound (or only how it behaves). */
enum class SettingKey(val needsRebind: Boolean) {
    FLASH(false), TIMER(false), MACRO(true), RATIO(true),
    RESOLUTION(true), RAW(true), LENS_SELECTION(true),
    VIDEO_RESOLUTION(true), FRAME_RATE(true), STABILIZATION(true), HDR_VIDEO(true), TORCH(false),
    LOCATION(false), SHUTTER_SOUND(false), HAPTICS(false), GRID(false), LEVEL(false),
    QR(true), ULTRA_HDR(true), MIRROR_FRONT(false), VOLUME_KEYS(false), STRIP_DEVICE_INFO(false),
    PROCESSING(true), ZOOM_DETAIL(false), QUICK_ACCESS(false), DIRTY_LENS(false),
}

/**
 * Persistent app settings. Every property is backed by Compose state, so the UI
 * recomposes as soon as a value changes, and by SharedPreferences, so it survives restarts.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("foco_settings", Context.MODE_PRIVATE)

    // Photo – General
    var flash by enumPref("flash", FlashSetting.NIGHT_AUTO)
    var timer by enumPref("timer", TimerSetting.OFF)
    var macro by boolPref("macro", false)
    var ratio by enumPref("ratio", RatioSetting.FULL_4_3)

    // Photo – Pro
    var resolution by enumPref("resolution", ResolutionSetting.MP12)
    var raw by boolPref("raw", false)
    var manualLens by boolPref("manual_lens", false)

    // Video
    var videoResolution by enumPref("video_resolution", VideoResolution.UHD)
    var frameRate by enumPref("frame_rate", FrameRate.FPS30)
    var stabilization by boolPref("stabilization", true)
    var hdrVideo by boolPref("hdr_video", false)
    var torch by boolPref("torch", false)

    // App settings ("…" screen)
    var saveLocation by boolPref("save_location", false)
    var shutterSound by boolPref("shutter_sound", true)
    var haptics by boolPref("haptics", true)
    var grid by enumPref("grid", GridSetting.NONE)
    var level by boolPref("level", true)
    var qrDetection by boolPref("qr_detection", true)
    var ultraHdr by boolPref("ultra_hdr", true)
    var mirrorFront by boolPref("mirror_front", true)
    var volumeKeys by enumPref("volume_keys", VolumeKeyAction.SHUTTER)
    var stripDeviceInfo by boolPref("strip_device_info", false)

    // Processing and extras
    /** Multi-frame HDR / Night / sharper zoom made by Foco itself. */
    var processing by boolPref("processing", true)
    var zoomDetail by boolPref("zoom_detail", true)
    var quickAccess by boolPref("quick_access", false)
    var dirtyLens by boolPref("dirty_lens", true)

    private fun boolPref(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        private val state = mutableStateOf(prefs.getBoolean(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state.value = value
            prefs.edit { putBoolean(key, value) }
        }
    }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E) =
        object : ReadWriteProperty<Any?, E> {
            private val state = mutableStateOf(
                prefs.getString(key, null)
                    ?.let { name -> enumValues<E>().firstOrNull { it.name == name } }
                    ?: default,
            )
            override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: E) {
                state.value = value
                prefs.edit { putString(key, value.name) }
            }
        }
}
