package io.github.realkhfan.foco

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.realkhfan.foco.scan.ScanFilter
import io.github.realkhfan.foco.scan.ScanPage
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.ui.AppState
import io.github.realkhfan.foco.ui.CameraActions
import io.github.realkhfan.foco.ui.PhotoMode
import io.github.realkhfan.foco.ui.ScanReviewScreen
import io.github.realkhfan.foco.ui.SettingsScreen
import io.github.realkhfan.foco.ui.modeUnavailable
import io.github.realkhfan.foco.ui.theme.FocoTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Foco hides the status bar, so screens must keep clear of the camera hole (display cut-out) themselves;
 * and the "isn't available" message must only come for modes that really need Foco's processing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w448dp-h997dp-xhdpi")
class TopInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = object : CameraActions {}
    private val holePx = 130 // like the Pixel 8 Pro's punch hole area

    /** Shows [content] with a camera hole at the top and no status bar (as in Foco). */
    private fun withCameraHole(content: @Composable () -> Unit) {
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent { FocoTheme(dynamic = false) { content() } }
        compose.runOnUiThread {
            val composeView = (compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, holePx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
                .build()
            ViewCompat.dispatchApplyWindowInsets(composeView, insets)
        }
        compose.waitForIdle()
    }

    private fun topOf(description: String): Float {
        val density = compose.activity.resources.displayMetrics.density
        return compose.onNodeWithContentDescription(description).getBoundsInRoot().top.value * density
    }

    @Test fun reviewTopBarIsBelowTheCameraHole() {
        val state = AppState().apply {
            scanPages += ScanPage(1L, 90, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), filter = ScanFilter.COLOR)
            scanReviewOpen = true
        }
        withCameraHole { ScanReviewScreen(state, actions) }
        val top = topOf("Back to camera")
        assertTrue("back button at $top px, hole ends at $holePx px", top >= holePx)
        assertTrue("but not far below it ($top px)", top < holePx + 40 * compose.activity.resources.displayMetrics.density)
    }

    @Test fun settingsTopBarIsBelowTheCameraHole() {
        val app = compose.activity.application
        withCameraHole { SettingsScreen(AppState(), AppSettings(app), actions) }
        val top = topOf("Back")
        assertTrue("back button at $top px, hole ends at $holePx px", top >= holePx)
    }

    @Test fun scanIsNeverReportedUnavailable() {
        val state = AppState().apply {
            cameraReady = true
            processing = false // Scan runs on the camera's own photos, without Foco's processing
        }
        assertFalse(state.modeUnavailable(PhotoMode.SCAN))
        assertFalse(state.modeUnavailable(PhotoMode.PHOTO))
        assertTrue(state.modeUnavailable(PhotoMode.NIGHT))
        assertTrue(state.modeUnavailable(PhotoMode.LONG_EXPOSURE))
        state.processing = true
        PhotoMode.entries.forEach { assertFalse("$it with processing", state.modeUnavailable(it)) }
        // Before the camera has started nothing is known yet: no message.
        state.processing = false
        state.cameraReady = false
        PhotoMode.entries.forEach { assertFalse("$it before the camera starts", state.modeUnavailable(it)) }
    }
}
