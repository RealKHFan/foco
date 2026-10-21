package io.github.realkhfan.foco

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.settings.FlashSetting
import io.github.realkhfan.foco.settings.GridSetting
import io.github.realkhfan.foco.settings.RatioSetting
import io.github.realkhfan.foco.settings.TimerSetting
import io.github.realkhfan.foco.ui.AppState
import io.github.realkhfan.foco.ui.CameraActions
import io.github.realkhfan.foco.ui.CameraScreen
import io.github.realkhfan.foco.ui.Capabilities
import io.github.realkhfan.foco.ui.CaptureKind
import io.github.realkhfan.foco.ui.PermissionScreen
import io.github.realkhfan.foco.ui.ProState
import io.github.realkhfan.foco.ui.ProTool
import io.github.realkhfan.foco.ui.Readout
import io.github.realkhfan.foco.ui.SettingsScreen
import io.github.realkhfan.foco.ui.SettingsTab
import io.github.realkhfan.foco.ui.theme.FocoTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen to a PNG (build/screenshots) with a drawn stand-in for the camera picture,
 * so the layout can be checked without a phone. Capabilities are set like a Pixel 8 Pro.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h997dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val actions = object : CameraActions {}
    private lateinit var settings: AppSettings

    private val pixel8Pro = Capabilities(
        hasFlash = true, hasFront = true, rawSupported = true, ultraHdrSupported = true,
        hiResWidth = 8160, hiResHeight = 6144,
        lensRatios = mapOf("2" to 0.49f, "3" to 1f, "4" to 4.86f), ultrawideId = "2",
        macroAvailable = true, manualLensAvailable = true,
        uhdSupported = true, fps60Supported = true, stabilizationSupported = true, hdrVideoSupported = true,
        evMin = -12, evMax = 12, evStep = 1f / 6f, manualSensor = true, isoMin = 50, isoMax = 12800,
        shutterMinNs = 125_000L, shutterMaxNs = 1_000_000_000L, manualFocus = true, minFocusDiopters = 10f,
        cctSupported = true, awbModes = setOf(0, 1, 2, 3, 5, 6, 8), tonemapCurve = true,
    )

    @Before fun setUp() {
        app.getSharedPreferences("foco_settings", 0).edit().clear().commit()
        settings = AppSettings(app)
    }

    private fun state(block: AppState.() -> Unit = {}) = AppState().apply {
        permissionGranted = true
        cameraReady = true
        processing = true
        caps = pixel8Pro
        zoom = 1f
        minZoom = 0.5f
        maxZoom = 30f
        zoomChips = listOf(0.5f, 1f, 2f, 5f)
        thumbnail = sampleThumbnail()
        readout = Readout(io.github.realkhfan.foco.camera.LensKind.WIDE, 1.68f, 100_000_000L, 640, 0.3f)
        block()
    }

    private fun shoot(name: String, state: AppState) {
        compose.setContent {
            FocoTheme(dynamic = false) {
                CameraScreen(state, settings, actions) { FakeViewfinder(it) }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun photo() = shoot("01_photo", state())

    @Test fun photoProControls() = shoot(
        "02_photo_pro_controls",
        state {
            proBarOpen = true
            activeTool = ProTool.WHITE_BALANCE
            pro = ProState(wbKelvin = 4300)
        },
    )

    @Test fun photoSettingsGeneral() {
        settings.timer = TimerSetting.S3
        shoot("03_photo_settings_general", state { settingsOpen = true })
    }

    @Test fun photoSettingsPro() = shoot("04_photo_settings_pro", state { settingsOpen = true; settingsTab = SettingsTab.PRO })

    @Test fun photoWideGridFlashQr() {
        settings.ratio = RatioSetting.WIDE_9_16
        settings.grid = GridSetting.THIRDS
        settings.flash = FlashSetting.ON
        shoot("05_photo_wide_grid_qr", state { qrText = "https://grapheneos.org/features"; zoom = 2.6f; tilt = io.github.realkhfan.foco.ui.Tilt(levelDegrees = 3.4f) })
    }

    @Test fun video() = shoot("06_video", state { kind = CaptureKind.VIDEO })

    @Test fun videoRecording() = shoot(
        "07_video_recording",
        state {
            kind = CaptureKind.VIDEO
            recording = true
            recordingMs = 83_000
            snapshotAvailable = true
        },
    )

    @Test fun videoSettings() = shoot("08_video_settings", state { kind = CaptureKind.VIDEO; settingsOpen = true })

    @Test fun frontCamera() = shoot(
        "09_front_camera",
        state {
            lensFacing = CameraSelector.LENS_FACING_FRONT
            zoomChips = listOf(1f)
            focusPoint = Offset(450f, 700f)
            focusLocked = true
        },
    )

    @Test fun nightAuto() = shoot(
        "13_night_auto",
        state {
            dark = true
            nightEstimate = 3f
            readout = Readout(io.github.realkhfan.foco.camera.LensKind.WIDE, 1.68f, 100_000_000L, 6376, 0.3f)
        },
    )

    @Test fun nightCapture() = shoot(
        "14_night_capture",
        state {
            dark = true
            nightEstimate = 3f
            burstActive = true
            burstNight = true
            burstProgress = 0.45f
            burstSecondsLeft = 2
            processingCount = 1
            motion = Offset(0.05f, -0.03f)
        },
    )

    @Test fun quickAccess() {
        settings.quickAccess = true
        settings.timer = TimerSetting.S3
        shoot("15_quick_access", state())
    }

    @Test fun brightnessPill() = shoot(
        "16_brightness_slider",
        state {
            proBarOpen = true
            activeTool = ProTool.BRIGHTNESS
            pro = ProState(evIndex = 4)
        },
    )

    @Test fun nightSlider() = shoot(
        "17_night_slider",
        state {
            dark = true
            nightEstimate = 3f
            proBarOpen = true
            activeTool = ProTool.NIGHT
            readout = Readout(io.github.realkhfan.foco.camera.LensKind.ULTRAWIDE, 1.95f, 50_000_000L, 1706, 0.3f)
        },
    )

    @Test fun topDownLevel() = shoot("18_top_down_level", state { tilt = io.github.realkhfan.foco.ui.Tilt(flatX = 4f, flatY = -2.5f) })

    @Test fun modeStripLongExposure() = shoot(
        "19_long_exposure",
        state {
            photoMode = io.github.realkhfan.foco.ui.PhotoMode.LONG_EXPOSURE
            modeHint = "Blurs moving water, traffic and crowds. Hold still."
        },
    )

    @Test fun actionPanCapture() = shoot(
        "20_action_pan_capture",
        state {
            photoMode = io.github.realkhfan.foco.ui.PhotoMode.ACTION_PAN
            burstActive = true
            burstKind = io.github.realkhfan.foco.processing.ShotKind.ACTION_PAN
            burstProgress = 0.6f
            processingCount = 1
        },
    )

    @Test fun panoramaIdle() = shoot(
        "21_panorama",
        state {
            photoMode = io.github.realkhfan.foco.ui.PhotoMode.PANORAMA
            modeHint = "Tap the shutter, then turn slowly to one side"
        },
    )

    @Test fun panoramaCapture() = shoot(
        "22_panorama_capture",
        state {
            photoMode = io.github.realkhfan.foco.ui.PhotoMode.PANORAMA
            panoActive = true
            burstActive = true
            processingCount = 1
            panoFov = 0.98f
            listOf(0f, 0.4f, 0.8f, 1.2f).forEachIndexed { i, yaw -> panoShots += io.github.realkhfan.foco.ui.PanoThumb(yaw, panoThumb(i)) }
            panoYaw = 1.45f
            panoPitch = 0.04f
            panoHint = io.github.realkhfan.foco.ui.PanoHint.KEEP_LEVEL
        },
    )

    @Test fun micBlocked() = shoot(
        "23_video_mic_blocked",
        state {
            kind = CaptureKind.VIDEO
            micStatus = io.github.realkhfan.foco.device.MicStatus.BLOCKED
        },
    )

    @Test fun whiteBalanceRuler() = shoot(
        "24_white_balance_ruler",
        state {
            proBarOpen = true
            activeTool = ProTool.WHITE_BALANCE
            pro = ProState(wbKelvin = 4300)
        },
    )

    private fun panoThumb(i: Int): Bitmap {
        val bmp = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(listOf(0xFF6FA8DC, 0xFF7FB3E0, 0xFF8FBEE4, 0xFF9FC9E8)[i].toInt())
        c.drawRect(0f, 40f, 48f, 64f, Paint().apply { color = 0xFF4E7D3A.toInt() })
        if (i == 1) c.drawRect(10f, 28f, 30f, 42f, Paint().apply { color = 0xFF8A5A3C.toInt() })
        return bmp
    }

    @Test fun fullSettings() {
        compose.setContent { FocoTheme(dynamic = false) { SettingsScreen(state(), settings, actions) } }
        compose.onRoot().captureRoboImage("build/screenshots/10_full_settings.png")
    }

    @Test fun permission() {
        compose.setContent { FocoTheme(dynamic = false) { PermissionScreen(AppState(), actions) } }
        compose.onRoot().captureRoboImage("build/screenshots/11_permission.png")
    }

    @Test fun logo() {
        compose.setContent {
            Row(
                Modifier.background(Color(0xFF2B2E36)).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(CircleShape, RoundedCornerShape(28.dp)).forEach { shape ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(112.dp).clip(shape).background(colorResource(R.color.ic_launcher_background))) {
                            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                        }
                        Text("Foco", color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(112.dp).clip(CircleShape).background(Color(0xFFDDE3FF))) {
                        Image(
                            painterResource(R.drawable.ic_launcher_monochrome),
                            null,
                            Modifier.fillMaxSize(),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF2C3A6B)),
                        )
                    }
                    Text("Themed", color = Color.White, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/12_logo.png")
    }

    private fun sampleThumbnail(): Bitmap {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF6FA8DC.toInt())
        c.drawCircle(90f, 40f, 18f, Paint().apply { color = 0xFFFFE08A.toInt() })
        c.drawRect(0f, 80f, 128f, 128f, Paint().apply { color = 0xFF4E7D3A.toInt() })
        return bmp
    }
}

/** A drawn landscape standing in for the live camera picture. */
@Composable
private fun FakeViewfinder(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.background(Color.Black)) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF3D6FB6), Color(0xFF9CC3E6), Color(0xFFF2D9B1)), endY = size.height * 0.62f))
        drawCircle(Color(0xFFFFF1C2), radius = size.width * 0.08f, center = Offset(size.width * 0.72f, size.height * 0.22f))
        val hills = Path().apply {
            moveTo(0f, size.height * 0.62f)
            cubicTo(size.width * 0.25f, size.height * 0.50f, size.width * 0.45f, size.height * 0.58f, size.width * 0.6f, size.height * 0.55f)
            cubicTo(size.width * 0.8f, size.height * 0.51f, size.width * 0.9f, size.height * 0.57f, size.width, size.height * 0.56f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(hills, Brush.verticalGradient(listOf(Color(0xFF5E8F4A), Color(0xFF2E4D25)), startY = size.height * 0.5f, endY = size.height))
        drawRect(Color(0xFF8A5A3C), topLeft = Offset(size.width * 0.18f, size.height * 0.52f), size = Size(size.width * 0.16f, size.height * 0.12f))
        val roof = Path().apply {
            moveTo(size.width * 0.16f, size.height * 0.52f)
            lineTo(size.width * 0.26f, size.height * 0.45f)
            lineTo(size.width * 0.36f, size.height * 0.52f)
            close()
        }
        drawPath(roof, Color(0xFF5B2E22))
    }
}
