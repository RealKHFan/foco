package io.github.realkhfan.foco

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.realkhfan.foco.camera.CameraEngine
import io.github.realkhfan.foco.device.LocationTracker
import io.github.realkhfan.foco.device.MicStatus
import io.github.realkhfan.foco.device.OrientationWatcher
import io.github.realkhfan.foco.media.MediaRepository
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.settings.VolumeKeyAction
import io.github.realkhfan.foco.ui.AppState
import io.github.realkhfan.foco.ui.CameraScreen
import io.github.realkhfan.foco.ui.CaptureKind
import io.github.realkhfan.foco.ui.IntentMode
import io.github.realkhfan.foco.ui.MessageToast
import io.github.realkhfan.foco.ui.PermissionScreen
import io.github.realkhfan.foco.ui.SettingsScreen
import io.github.realkhfan.foco.ui.ViewerScreen
import io.github.realkhfan.foco.ui.ScanCropScreen
import io.github.realkhfan.foco.ui.ScanReviewScreen
import io.github.realkhfan.foco.ui.theme.FocoTheme
import kotlinx.coroutines.launch

open class MainActivity : ComponentActivity() {
    /** true for the lock-screen camera ([SecureCameraActivity]). */
    protected open val isSecure: Boolean = false

    private val state = AppState()
    private lateinit var settings: AppSettings
    private lateinit var engine: CameraEngine
    private lateinit var coordinator: CameraCoordinator
    private lateinit var location: LocationTracker
    private lateinit var orientation: OrientationWatcher
    private var cameraStarted = false
    private var lastPanUi = 0L
    private var panoPitch0 = 0f
    private var screenOffReceiver: BroadcastReceiver? = null

    private var intentSenderCallback: ((Boolean) -> Unit)? = null
    private var placeCallback: ((android.net.Uri?) -> Unit)? = null
    private val placeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = if (result.resultCode == RESULT_OK) result.data?.data else null
        placeCallback?.invoke(uri)
        placeCallback = null
    }

    private val intentSenderLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        intentSenderCallback?.invoke(result.resultCode == RESULT_OK)
        intentSenderCallback = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        state.permissionAsked = true
        if (MEDIA_PERMISSIONS.any { result[it] == true } && cameraStarted) coordinator.loadLatest()
        if (result.containsKey(Manifest.permission.RECORD_AUDIO)) coordinator.checkMic(force = true)
        if (result[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)) {
            state.permissionGranted = true
            startCamera()
        }
        val locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
            location.start()
        } else if (result.containsKey(Manifest.permission.ACCESS_FINE_LOCATION)) {
            settings.saveLocation = false
            state.message = "Location stays off without permission"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isSecure) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // Like Pixel Camera: the lock-screen camera closes when the screen turns off.
            screenOffReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = finish()
            }
            ContextCompat.registerReceiver(
                this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        settings = AppSettings(this)
        state.secure = isSecure
        applyLaunchIntent(intent)

        location = LocationTracker(this)
        engine = CameraEngine(this, state, settings)
        coordinator = CameraCoordinator(
            activity = this,
            state = state,
            settings = settings,
            engine = engine,
            media = MediaRepository(this),
            location = location,
            requestPermissions = { permissionLauncher.launch(it) },
            launchIntentSender = { sender, callback ->
                intentSenderCallback = callback
                runCatching { intentSenderLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
                    .onFailure { callback(false) }
            },
            pickPlace = { intent, callback ->
                placeCallback = callback
                runCatching { placeLauncher.launch(intent) }.onFailure {
                    placeCallback = null
                    callback(null)
                    state.message = "No file manager to pick a place"
                }
            },
            haptic = {
                if (settings.haptics) window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            },
        )
        orientation = OrientationWatcher(
            this,
            onRotation = { degrees, surfaceRotation ->
                state.deviceRotation = degrees
                engine.setTargetRotation(surfaceRotation)
            },
            onTilt = { state.tilt = it },
            onMotion = { _, x, y ->
                state.motion = androidx.compose.ui.geometry.Offset(x, y)
                if (state.panoActive) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastPanUi > 33) {
                        lastPanUi = now
                        state.panoYaw = orientation.panYaw
                        state.panoPitch = orientation.elevation - panoPitch0
                    }
                } else {
                    panoPitch0 = orientation.elevation
                }
            },
        )
        engine.panSensors = { floatArrayOf(orientation.panYaw, orientation.elevation, orientation.motionRate) }
        engine.resetPan = { orientation.resetPan() }
        engine.motionProvider = { orientation.motionRate }
        engine.onLensHazy = { coordinator.onLensHazy() }

        state.permissionGranted = hasPermission(Manifest.permission.CAMERA)
        setContent { FocoTheme { App() } }
        hideStatusBar()

        if (state.permissionGranted) {
            startCamera()
        } else if (!isSecure) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) + MEDIA_PERMISSIONS)
        } else if (MEDIA_PERMISSIONS.none { hasPermission(it) } && !isSecure) {
            permissionLauncher.launch(MEDIA_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideStatusBar()
            // Back from the "Unblock microphone?" dialog or quick settings: check again.
            if (state.micStatus == MicStatus.BLOCKED || state.micStatus == MicStatus.NO_PERMISSION) coordinator.checkMic(force = true)
        }
    }

    /** Full-screen viewfinder like Pixel Camera: no status bar (swipe down to peek at it). */
    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun applyLaunchIntent(intent: Intent?) {
        when (intent?.action) {
            MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.ACTION_IMAGE_CAPTURE_SECURE -> {
                state.intentMode = IntentMode.IMAGE_CAPTURE
                state.kind = CaptureKind.PHOTO
            }
            MediaStore.ACTION_VIDEO_CAPTURE -> {
                state.intentMode = IntentMode.VIDEO_CAPTURE
                state.kind = CaptureKind.VIDEO
            }
            MediaStore.INTENT_ACTION_VIDEO_CAMERA -> state.kind = CaptureKind.VIDEO
            else -> Unit
        }
    }

    @Composable
    private fun App() {
        if (!state.permissionGranted) {
            PermissionScreen(state, coordinator)
            return
        }
        CameraScreen(state, settings, coordinator) { modifier ->
            AndroidView(
                factory = { context ->
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        engine.previewView = this
                    }
                },
                modifier = modifier,
            )
        }
        BackHandler(enabled = state.settingsOpen || state.proBarOpen) {
            if (state.settingsOpen) {
                state.settingsOpen = false
            } else {
                state.proBarOpen = false
                state.activeTool = null
            }
        }
        state.viewerItems?.let { ViewerScreen(it, state.secure, coordinator) }
        if (state.fullSettingsOpen) SettingsScreen(state, settings, coordinator)
        if (state.scanReviewOpen && state.scanPages.isNotEmpty()) ScanReviewScreen(state, coordinator)
        state.scanEdit?.let { ScanCropScreen(it, coordinator) }
        if (state.viewerItems != null || state.fullSettingsOpen || state.scanReviewOpen || state.scanEdit != null) MessageToast(state)
    }

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        lifecycleScope.launch {
            runCatching { engine.start(this@MainActivity) }
                .onFailure {
                    cameraStarted = false
                    state.message = "Camera couldn't be started"
                }
        }
        coordinator.loadLatest()
        if (state.kind == CaptureKind.VIDEO) coordinator.checkMic(force = true)
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, mediaObserver,
            )
        }
        orientation.start()
        if (settings.saveLocation) location.start()
        if (!state.permissionGranted && hasPermission(Manifest.permission.CAMERA)) {
            state.permissionGranted = true
            startCamera()
        } else if (cameraStarted) {
            coordinator.loadLatest()
        }
    }

    /** Another app added, moved or deleted photos: refresh the gallery bubble (debounced). */
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        private val refresh = Runnable { if (cameraStarted) coordinator.loadLatest() }
        override fun onChange(selfChange: Boolean) {
            window.decorView.removeCallbacks(refresh)
            window.decorView.postDelayed(refresh, 500)
        }
    }

    override fun onStop() {
        runCatching { contentResolver.unregisterContentObserver(mediaObserver) }
        orientation.stop()
        location.stop()
        super.onStop()
    }

    override fun onDestroy() {
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        engine.release()
        coordinator.release()
        super.onDestroy()
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun cameraVisible() = state.permissionGranted && state.viewerItems == null &&
        !state.fullSettingsOpen && state.review == null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (cameraVisible()) {
            when (keyCode) {
                KeyEvent.KEYCODE_CAMERA -> {
                    if (event.repeatCount == 0) coordinator.shutter()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> when (settings.volumeKeys) {
                    VolumeKeyAction.SHUTTER -> {
                        if (event.repeatCount == 0) coordinator.shutter()
                        return true
                    }
                    VolumeKeyAction.ZOOM -> {
                        val factor = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1.15f else 1f / 1.15f
                        coordinator.setZoom((state.zoom * factor).coerceIn(state.minZoom, state.maxZoom))
                        return true
                    }
                    VolumeKeyAction.VOLUME -> Unit
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (cameraVisible() && (keyCode == KeyEvent.KEYCODE_CAMERA || volume && settings.volumeKeys != VolumeKeyAction.VOLUME)) return true
        return super.onKeyUp(keyCode, event)
    }
}

/** Read access to the camera folder, so the gallery bubble can show the newest photo (also Pixel Camera's). */
private val MEDIA_PERMISSIONS: Array<String> = if (android.os.Build.VERSION.SDK_INT >= 33) {
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Opened from the lock screen (double-press power, lock-screen shortcut). Can't reach the gallery. */
class SecureCameraActivity : MainActivity() {
    override val isSecure: Boolean = true
}
