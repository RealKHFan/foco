package io.github.realkhfan.foco.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.ModeNight
import androidx.compose.material.icons.outlined.Panorama
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.ShutterSpeed
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.realkhfan.foco.camera.ExposureMath
import io.github.realkhfan.foco.camera.ZoomMath
import io.github.realkhfan.foco.device.MicStatus
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.settings.FlashSetting
import io.github.realkhfan.foco.settings.FrameRate
import io.github.realkhfan.foco.settings.GridSetting
import io.github.realkhfan.foco.settings.RatioSetting
import io.github.realkhfan.foco.settings.ResolutionSetting
import io.github.realkhfan.foco.settings.SettingKey
import io.github.realkhfan.foco.settings.TimerSetting
import io.github.realkhfan.foco.settings.VideoResolution
import io.github.realkhfan.foco.ui.theme.FocoColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The viewfinder screen. Stateless: everything comes from [state] and goes out through [actions],
 * so the exact same screen can be rendered in tests with a fake preview.
 */
@Composable
fun CameraScreen(
    state: AppState,
    settings: AppSettings,
    actions: CameraActions,
    preview: @Composable (Modifier) -> Unit,
) {
    val iconRotation = rememberIconRotation(state.deviceRotation)
    // Status bar is hidden (like Pixel Camera); keep clear of the camera cut-out.
    val statusTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val wide = !state.scanning && (state.kind == CaptureKind.VIDEO || settings.ratio == RatioSetting.WIDE_9_16)

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // 4:3 sits just above the shutter row so the zoom chips always overlap the picture (as on Pixel);
        // 9:16 starts at the top. Never under the top bar.
        val previewHeight = maxWidth * (if (wide) 16f / 9f else 4f / 3f)
        val anchoredTop = maxHeight - navBottom - CONTROLS_BELOW_PICTURE - previewHeight
        val previewTop = if (wide) statusTop else anchoredTop.coerceAtLeast(statusTop + 56.dp)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = previewTop)
                .fillMaxWidth()
                .aspectRatio(if (wide) 9f / 16f else 3f / 4f),
        ) {
            preview(Modifier.fillMaxSize())
            ViewfinderOverlays(state, settings, actions)
        }

        TopBar(state, settings, actions, iconRotation, Modifier.align(Alignment.TopCenter).padding(top = statusTop))

        BottomControls(state, settings, actions, iconRotation, Modifier.align(Alignment.BottomCenter))

        if (state.viewerItems == null && !state.fullSettingsOpen && !state.scanReviewOpen && state.scanEdit == null) MessageToast(state)

        if (state.screenFlashOn) Box(Modifier.fillMaxSize().background(Color(0xFFFFF4E5)))

        if (state.settingsOpen) {
            SettingsSheet(state, settings, actions, onClose = { state.settingsOpen = false })
        }
        state.review?.let { ReviewOverlay(it, actions) }
    }
}

/** Short messages ("Couldn't save photo", lens warning, …) in the middle of the screen. */
@Composable
fun MessageToast(state: AppState) {
    val text = state.message ?: return
    LaunchedEffect(text) {
        delay(maxOf(3000L, text.length * 60L)) // long messages stay long enough to read
        if (state.message == text) state.message = null
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color.White,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xE6202124))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Height of everything under the zoom chips: shutter row, mode strip, bottom row and spacing. */
private val CONTROLS_BELOW_PICTURE = 300.dp

@Composable
private fun rememberIconRotation(deviceRotation: Int): Float {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(deviceRotation) {
        val desired = -deviceRotation.toFloat()
        val diff = ((desired - target) % 360f + 540f) % 360f - 180f
        target += diff
    }
    return animateFloatAsState(target, tween(300), label = "iconRotation").value
}

// ------------------------------------------------------------------------------------- viewfinder

@Composable
private fun ViewfinderOverlays(state: AppState, settings: AppSettings, actions: CameraActions) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 70.dp.toPx() }
    val touchSlop = with(density) { 12.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (state.review == null && !state.burstActive) actions.focusAt(it, size.width, size.height, lock = false) },
                    onDoubleTap = { actions.setZoom(if (state.zoom < 1.9f) 2f else 1f, fromChip = true) },
                    onLongPress = { if (!state.burstActive) actions.focusAt(it, size.width, size.height, lock = true) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pinch = false
                    var drag = Offset.Zero
                    var dragging = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            pinch = true
                            val change = event.calculateZoom()
                            if (change != 1f) {
                                actions.setZoom((state.zoom * change).coerceIn(state.minZoom, state.maxZoom))
                            }
                            event.changes.forEach { it.consume() }
                        } else if (!pinch && pressed == 1) {
                            drag += event.changes.first().positionChange()
                            if (drag.getDistance() > touchSlop) dragging = true
                            if (dragging) event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!pinch && dragging && !state.burstActive) {
                        when {
                            abs(drag.x) > swipeThreshold && abs(drag.x) > abs(drag.y) * 1.5f && !state.recording ->
                                swipeMode(state, actions, next = drag.x < 0)
                            drag.y < -swipeThreshold && abs(drag.y) > abs(drag.x) -> state.proBarOpen = true
                            drag.y > swipeThreshold && abs(drag.y) > abs(drag.x) -> {
                                if (state.proBarOpen) {
                                    state.proBarOpen = false
                                    state.activeTool = null
                                } else if (!state.recording) {
                                    state.settingsOpen = true
                                }
                            }
                        }
                    }
                }
            },
    ) {
        GridOverlay(settings.grid)
        if (settings.level && state.kind == CaptureKind.PHOTO && !state.burstActive) LevelOverlay(state)
        if (!state.burstActive) FocusRing(state)
        CaptureFlash(state.flashTick)
        if (state.burstActive && state.burstNight) HoldStill(state)
        if (state.burstActive && state.burstKind == io.github.realkhfan.foco.processing.ShotKind.ACTION_PAN) FollowSubject(state)
        if (state.panoActive) PanoramaGuide(state)
        if (state.scanning && state.scanEdit == null) ScanOverlay(state)
        state.countdown?.let { n ->
            Text(
                n.toString(),
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Swiping across the viewfinder walks through the photo modes: left = the mode on the right. */
private fun swipeMode(state: AppState, actions: CameraActions, next: Boolean) {
    if (state.intentMode != IntentMode.NORMAL || state.kind != CaptureKind.PHOTO) return
    val modes = state.photoModes
    val i = modes.indexOf(state.photoMode)
    val target = if (next) i + 1 else i - 1
    if (target in modes.indices) actions.setPhotoMode(modes[target])
}

/** How many modes a swipe on the strip moves: a short flick is one, a long drag can be more. */
object ModeSwipe {
    fun steps(dragPx: Float, itemPx: Float): Int {
        if (abs(dragPx) < itemPx * 0.25f) return 0
        val n = (abs(dragPx) / itemPx + 0.5f).toInt().coerceAtLeast(1)
        return if (dragPx < 0) n else -n
    }
}

@Composable
private fun GridOverlay(grid: GridSetting) {
    if (grid == GridSetting.NONE) return
    Canvas(Modifier.fillMaxSize()) {
        val lines = when (grid) {
            GridSetting.THIRDS -> listOf(1f / 3, 2f / 3)
            GridSetting.FOURTHS -> listOf(0.25f, 0.5f, 0.75f)
            GridSetting.GOLDEN -> listOf(0.382f, 0.618f)
            GridSetting.NONE -> emptyList()
        }
        val color = Color.White.copy(alpha = 0.45f)
        val stroke = 1.dp.toPx()
        lines.forEach { f ->
            drawLine(color, Offset(size.width * f, 0f), Offset(size.width * f, size.height), stroke)
            drawLine(color, Offset(0f, size.height * f), Offset(size.width, size.height * f), stroke)
        }
    }
}

/** Horizon line with its angle; when pointing straight down/up, a crosshair that lines up when flat. */
@Composable
private fun LevelOverlay(state: AppState) {
    val tilt = state.tilt
    val level = tilt.levelDegrees
    val fx = tilt.flatX
    val fy = tilt.flatY
    if (level != null && abs(level) <= 12f) {
        val isLevel = abs(level) < 1f
        val angle = level - state.deviceRotation
        val color = if (isLevel) FocoColors.Level else Color.White
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val half = 56.dp.toPx()
            val rad = Math.toRadians(angle.toDouble())
            val dx = (half * cos(rad)).toFloat()
            val dy = (half * sin(rad)).toFloat()
            drawLine(color, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), 2.dp.toPx(), cap = StrokeCap.Round)
            val sr = Math.toRadians((-state.deviceRotation).toDouble())
            val sx = (half * 1.35f * cos(sr)).toFloat()
            val sy = (half * 1.35f * sin(sr)).toFloat()
            val w = 2.dp.toPx()
            drawLine(color.copy(alpha = 0.8f), Offset(c.x - sx, c.y - sy), Offset(c.x - sx * 0.8f, c.y - sy * 0.8f), w)
            drawLine(color.copy(alpha = 0.8f), Offset(c.x + sx * 0.8f, c.y + sy * 0.8f), Offset(c.x + sx, c.y + sy), w)
        }
        DegreeLabel(if (isLevel) 0f else level, color, state.deviceRotation)
    } else if (fx != null && fy != null && abs(fx) <= 15f && abs(fy) <= 15f) {
        val aligned = abs(fx) < 1f && abs(fy) < 1f
        val moving = FocoColors.Level
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = 36.dp.toPx()
            val arm = 9.dp.toPx()
            val w = 1.5.dp.toPx()
            drawCircle(Color.White, radius = r, center = c, style = Stroke(w))
            val fixed = if (aligned) moving else Color.White
            drawLine(fixed, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), w)
            drawLine(fixed, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), w)
            val scale = 2.2.dp.toPx()
            val p = Offset(c.x + fx * scale, c.y - fy * scale)
            drawLine(moving, Offset(p.x - arm, p.y), Offset(p.x + arm, p.y), w * 1.4f)
            drawLine(moving, Offset(p.x, p.y - arm), Offset(p.x, p.y + arm), w * 1.4f)
        }
        DegreeLabel(maxOf(abs(fx), abs(fy)), if (aligned) moving else Color.White, state.deviceRotation, below = 52.dp)
    }
}

@Composable
private fun DegreeLabel(degrees: Float, color: Color, deviceRotation: Int, below: androidx.compose.ui.unit.Dp = 22.dp) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "${abs(degrees).roundToInt()}°",
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.offset(y = below).rotate(-deviceRotation.toFloat()),
        )
    }
}

@Composable
private fun FocusRing(state: AppState) {
    val point = state.focusPoint ?: return
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(state.focusTick) {
        alpha.snapTo(1f)
        scale.snapTo(1.25f)
        scale.animateTo(1f, tween(220))
        if (!state.focusLocked) {
            delay(1400)
            alpha.animateTo(0f, tween(400))
        }
    }
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            Color.White.copy(alpha = alpha.value),
            radius = 34.dp.toPx() * scale.value,
            center = point,
            style = Stroke(1.5.dp.toPx()),
        )
    }
    if (state.focusLocked && alpha.value > 0f) {
        val yOffset = with(density) { (point.y - 64.dp.toPx()).roundToInt() }
        Box(Modifier.fillMaxWidth().offset { IntOffset(0, yOffset.coerceAtLeast(0)) }, contentAlignment = Alignment.TopCenter) {
            Text(
                "AE/AF Lock",
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFD54F)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** Night: a circle with a dot that moves with the phone. Keep the dot in the middle. */
@Composable
private fun HoldStill(state: AppState) {
    val density = LocalDensity.current
    val maxOffset = with(density) { 30.dp.toPx() }
    val k = with(density) { 160.dp.toPx() }
    val dx by animateFloatAsState((state.motion.x * k).coerceIn(-maxOffset, maxOffset), tween(80), label = "hx")
    val dy by animateFloatAsState((state.motion.y * k).coerceIn(-maxOffset, maxOffset), tween(80), label = "hy")
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(96.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Color.White.copy(alpha = 0.9f), radius = 40.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
            drawCircle(primary, radius = 7.dp.toPx(), center = Offset(c.x + dx, c.y + dy))
        }
        Text(
            "Hold still",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .offset(y = 72.dp)
                .rotate(-state.deviceRotation.toFloat())
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x99000000))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** Action Pan: keep the moving subject inside this frame while the photo is taken. */
@Composable
private fun FollowSubject(state: AppState) {
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width * 0.3f
            val h = size.height * 0.4f
            drawRoundRect(
                primary, topLeft = Offset((size.width - w) / 2, (size.height - h) / 2), size = Size(w, h),
                cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(2.5.dp.toPx()),
            )
        }
        Text(
            "Follow the subject",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .offset(y = 120.dp)
                .rotate(-state.deviceRotation.toFloat())
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x99000000))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/**
 * Panorama while turning: a line across the middle with a target circle and a dot that shows the tilt
 * (keep the dot on the line), plus the hint text.
 */
@Composable
private fun PanoramaGuide(state: AppState) {
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val pxPerRad = with(density) { 400.dp.toPx() }
    val dotY by animateFloatAsState((-state.panoPitch * pxPerRad).coerceIn(-200f, 200f), tween(60), label = "panoDot")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val stroke = 1.5.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.8f), Offset(0f, c.y), Offset(size.width, c.y), stroke)
            drawCircle(Color.White, radius = 26.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
            drawCircle(primary, radius = 9.dp.toPx(), center = Offset(c.x, c.y + dotY))
        }
        Text(
            state.panoHint.text,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .offset(y = 64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (state.panoHint == PanoHint.START) Color(0x99000000) else Color(0xE6601410))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/**
 * The panorama so far, as a strip at the top: the frames taken, placed by how far you turned,
 * and a white box where the camera points now.
 */
@Composable
private fun PanoramaStrip(state: AppState, modifier: Modifier) {
    val shots = state.panoShots.toList()
    if (shots.isEmpty()) return
    val fov = state.panoFov.coerceAtLeast(0.1f)
    val minYaw = minOf(shots.minOf { it.yaw }, state.panoYaw) - fov / 2
    val maxYaw = maxOf(shots.maxOf { it.yaw }, state.panoYaw) + fov / 2
    Canvas(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xB3000000)),
    ) {
        val stripH = size.height - 8.dp.toPx()
        val first = shots.first().bitmap
        val frameW = stripH * first.width / first.height
        var pxPerRad = frameW / fov
        val total = (maxYaw - minYaw) * pxPerRad
        if (total > size.width - 8.dp.toPx()) pxPerRad *= (size.width - 8.dp.toPx()) / total
        val fw = fov * pxPerRad
        val fh = fw * first.height / first.width
        val top = (size.height - fh) / 2
        val x0 = (size.width - (maxYaw - minYaw) * pxPerRad) / 2
        shots.sortedBy { it.yaw }.forEach { shot ->
            val left = x0 + (shot.yaw - fov / 2 - minYaw) * pxPerRad
            drawImage(
                shot.bitmap.asImageBitmap(),
                dstOffset = androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(fw.roundToInt().coerceAtLeast(1), fh.roundToInt().coerceAtLeast(1)),
            )
        }
        val cur = x0 + (state.panoYaw - fov / 2 - minYaw) * pxPerRad
        drawRoundRect(
            Color.White, topLeft = Offset(cur, top - 2.dp.toPx()), size = Size(fw, fh + 4.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()),
        )
    }
}

@Composable
private fun CaptureFlash(tick: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        alpha.snapTo(0.85f)
        alpha.animateTo(0f, tween(220))
    }
    if (alpha.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha.value)))
}

// ------------------------------------------------------------------------------------- top

@Composable
private fun TopBar(state: AppState, settings: AppSettings, actions: CameraActions, iconRotation: Float, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
            when {
                state.recording -> RecordingTimer(state, Modifier.align(Alignment.Center))
                settings.quickAccess && !state.burstActive ->
                    QuickAccess(state, settings, actions, iconRotation, Modifier.align(Alignment.CenterStart))
                else -> StatusIcons(state, settings, iconRotation, Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
            }
        }
        if (state.proBarOpen && !state.recording) ReadoutRow(state)
        if (state.kind == CaptureKind.VIDEO && !state.recording) MicChip(state, actions)
        if (state.panoActive) PanoramaStrip(state, Modifier.padding(top = 4.dp))
    }
}

/** Warns before filming when the video would have no sound, with a one-tap fix. */
@Composable
private fun MicChip(state: AppState, actions: CameraActions) {
    val (text, button) = when (state.micStatus) {
        MicStatus.BLOCKED -> "Microphone is blocked: videos will be silent" to "Unblock"
        MicStatus.NO_PERMISSION -> "No microphone access: videos will be silent" to "Allow"
        else -> return
    }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xF0601410))
            .clickable(onClick = actions::micClicked)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.MicOff, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
        Text(
            button,
            color = Color(0xFF601410),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).background(Color.White).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** Small status icons like Pixel Camera's (12 MP, macro, Night auto, flash, timer). Tap opens the settings. */
@Composable
private fun StatusIcons(state: AppState, settings: AppSettings, iconRotation: Float, modifier: Modifier) {
    Row(
        modifier.clickable { state.settingsOpen = true },
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.kind == CaptureKind.PHOTO) {
            val mp = if (settings.resolution == ResolutionSetting.MP50 && state.caps.hiResAvailable) "50" else "12"
            Box(Modifier.rotate(iconRotation)) { MpGlyph(mp, Color.White, filled = true, size = 22.dp) }
            if (settings.macro && state.caps.macroAvailable) {
                Icon(Icons.Outlined.LocalFlorist, null, tint = Color.White, modifier = Modifier.size(22.dp).rotate(iconRotation))
            }
            if (state.processing && (settings.flash == FlashSetting.NIGHT_AUTO && state.photoMode == PhotoMode.PHOTO || state.photoMode == PhotoMode.NIGHT)) {
                Box(Modifier.rotate(iconRotation)) { NightGlyph(Color.White, auto = state.photoMode != PhotoMode.NIGHT) }
            }
            if (settings.flash == FlashSetting.ON) {
                Icon(Icons.Outlined.FlashOn, null, tint = Color.White, modifier = Modifier.size(22.dp).rotate(iconRotation))
            }
        } else {
            Text(videoLabel(state, settings), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.rotate(iconRotation))
            if (settings.torch && state.caps.hasFlash) {
                Icon(Icons.Outlined.FlashlightOn, null, tint = Color.White, modifier = Modifier.size(22.dp).rotate(iconRotation))
            }
        }
        if (settings.timer != TimerSetting.OFF) {
            Box(Modifier.rotate(iconRotation)) { TimerGlyph(settings.timer.seconds, Color.White, Color.Black) }
        }
    }
}

private fun videoLabel(state: AppState, settings: AppSettings) = buildString {
    append(if (settings.videoResolution == VideoResolution.UHD && state.caps.uhdSupported) "4K" else "1080p")
    append(" · ")
    append(
        when {
            settings.frameRate == FrameRate.FPS60 && state.caps.fps60Supported -> "60"
            settings.frameRate == FrameRate.AUTO -> "Auto"
            else -> "30"
        },
    )
}

/** "Show quick access controls": one-tap buttons at the top, each tap goes to the next value. */
@Composable
private fun QuickAccess(state: AppState, settings: AppSettings, actions: CameraActions, iconRotation: Float, modifier: Modifier) {
    @Composable
    fun QuickButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .clickable { actions.haptic(); onClick() }
                .semantics { contentDescription = description }
                .rotate(iconRotation),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.kind == CaptureKind.PHOTO) {
            QuickButton("More light", {
                val front = state.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                val options = buildList {
                    add(FlashSetting.OFF)
                    add(FlashSetting.NIGHT_AUTO)
                    if (state.caps.hasFlash || front) add(FlashSetting.ON)
                }
                settings.flash = options[(options.indexOf(settings.flash) + 1) % options.size]
                actions.settingChanged(SettingKey.FLASH)
            }) {
                when (settings.flash) {
                    FlashSetting.OFF -> Icon(Icons.Outlined.Block, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    FlashSetting.NIGHT_AUTO -> NightGlyph(Color.White, auto = true)
                    FlashSetting.ON -> Icon(Icons.Outlined.FlashOn, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            QuickButton("Timer", {
                settings.timer = TimerSetting.entries[(settings.timer.ordinal + 1) % TimerSetting.entries.size]
            }) {
                if (settings.timer == TimerSetting.OFF) {
                    Icon(Icons.Outlined.TimerOff, null, tint = Color.White, modifier = Modifier.size(22.dp))
                } else {
                    TimerGlyph(settings.timer.seconds, Color.White, Color.Black)
                }
            }
            QuickButton("Ratio", {
                settings.ratio = if (settings.ratio == RatioSetting.FULL_4_3) RatioSetting.WIDE_9_16 else RatioSetting.FULL_4_3
                actions.settingChanged(SettingKey.RATIO)
            }) {
                if (settings.ratio == RatioSetting.FULL_4_3) RatioGlyph(3f, 4f, Color.White) else RatioGlyph(9f, 16f, Color.White)
            }
            if (state.caps.macroAvailable && state.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                QuickButton("Macro", {
                    settings.macro = !settings.macro
                    actions.settingChanged(SettingKey.MACRO)
                }) {
                    Icon(Icons.Outlined.LocalFlorist, null, tint = if (settings.macro) FocoColors.Level else Color.White, modifier = Modifier.size(22.dp))
                }
            }
            if (state.caps.hiResAvailable) {
                QuickButton("Resolution", {
                    settings.resolution = if (settings.resolution == ResolutionSetting.MP12) ResolutionSetting.MP50 else ResolutionSetting.MP12
                    actions.settingChanged(SettingKey.RESOLUTION)
                }) { MpGlyph(if (settings.resolution == ResolutionSetting.MP50) "50" else "12", Color.White, size = 22.dp) }
            }
        } else {
            if (state.caps.hasFlash) {
                QuickButton("Torch", {
                    settings.torch = !settings.torch
                    actions.settingChanged(SettingKey.TORCH)
                }) {
                    Icon(if (settings.torch) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            if (state.caps.uhdSupported) {
                QuickButton("Resolution", {
                    settings.videoResolution = if (settings.videoResolution == VideoResolution.UHD) VideoResolution.FHD else VideoResolution.UHD
                    actions.settingChanged(SettingKey.VIDEO_RESOLUTION)
                }) { Text(if (settings.videoResolution == VideoResolution.UHD) "4K" else "FHD", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            QuickButton("Frame rate", {
                val options = buildList {
                    if (state.caps.autoFpsLower > 0) add(FrameRate.AUTO)
                    add(FrameRate.FPS30)
                    if (state.caps.fps60Supported) add(FrameRate.FPS60)
                }
                settings.frameRate = options[(options.indexOf(settings.frameRate) + 1) % options.size]
                actions.settingChanged(SettingKey.FRAME_RATE)
            }) {
                Text(
                    when (settings.frameRate) { FrameRate.AUTO -> "A"; FrameRate.FPS30 -> "30"; FrameRate.FPS60 -> "60" },
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
            }
            QuickButton("Timer", {
                settings.timer = TimerSetting.entries[(settings.timer.ordinal + 1) % TimerSetting.entries.size]
            }) {
                if (settings.timer == TimerSetting.OFF) {
                    Icon(Icons.Outlined.TimerOff, null, tint = Color.White, modifier = Modifier.size(22.dp))
                } else {
                    TimerGlyph(settings.timer.seconds, Color.White, Color.Black)
                }
            }
        }
    }
}

@Composable
private fun ReadoutRow(state: AppState) {
    val r = state.readout
    val pro = state.pro
    val auto = "Ⓐ "
    val lens = buildString {
        append(r.lens.label)
        r.aperture?.let { append(" f/").append("%.2f".format(it).trimEnd('0').trimEnd('.')) }
    }
    val focus = if (pro.focusDiopters != null) "Manual focus" else "Auto focus"
    val shutter = (pro.shutterNs ?: r.shutterNs)?.let { (if (pro.shutterNs == null) auto else "") + ExposureMath.formatShutter(it) }
    val iso = (pro.iso ?: r.iso)?.let { (if (pro.iso == null) auto else "") + "ISO $it" }
    // Spread out like Pixel Camera; with large text it scrolls instead of the values running into each other.
    Row(
        Modifier.fillMaxWidth().background(Color(0x66000000)).horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOfNotNull(lens, focus, shutter, iso).forEach {
            Text(it, color = Color.White, fontSize = 14.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun RecordingTimer(state: AppState, modifier: Modifier) {
    val seconds = state.recordingMs / 1000
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (state.recordingPaused) Color.White else FocoColors.Recording))
        Text(
            "%02d:%02d".format(seconds / 60, seconds % 60) + if (state.recordingPaused) "  Paused" else "",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ------------------------------------------------------------------------------------- bottom

@Composable
private fun BottomControls(state: AppState, settings: AppSettings, actions: CameraActions, iconRotation: Float, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        state.qrText?.let { if (!state.recording && !state.burstActive) QrChip(it, actions) }
        state.modeHint?.let { hint ->
            if (!state.burstActive && state.kind == CaptureKind.PHOTO) {
                LaunchedEffect(hint) {
                    delay(4500)
                    if (state.modeHint == hint) state.modeHint = null
                }
                Text(
                    hint,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.BottomCenter) {
            val tool = state.activeTool
            when {
                state.burstActive -> Unit
                tool != null && state.proBarOpen -> ToolSlider(state, tool, actions, Modifier.padding(bottom = 4.dp))
                state.zoomChips.size > 1 && !(state.kind == CaptureKind.PHOTO && state.photoMode == PhotoMode.PANORAMA) ->
                    ZoomChips(state, actions, iconRotation, Modifier.padding(bottom = 34.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        ShutterRow(state, settings, actions, iconRotation)
        Spacer(Modifier.height(24.dp))
        if (state.proBarOpen && !state.recording) {
            ProToolbar(state, actions, onClose = {
                state.proBarOpen = false
                state.activeTool = null
            })
        } else {
            ModeStrip(state, actions)
            Spacer(Modifier.height(18.dp))
            BottomRow(state, settings, actions)
        }
    }
}

@Composable
private fun ZoomChips(state: AppState, actions: CameraActions, iconRotation: Float, modifier: Modifier) {
    val chips = state.zoomChips
    val selected = ZoomMath.selectedIndex(chips, state.zoom)
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier
            .clip(CircleShape)
            .background(Color(0xB31E1F22))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    actions.setZoom((state.zoom * exp(dragAmount / 260f)).coerceIn(state.minZoom, state.maxZoom))
                }
            }
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEachIndexed { i, chip ->
            val isSelected = i == selected
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) primary else Color.Transparent)
                    .clickable {
                        actions.haptic()
                        actions.setZoom(chip, fromChip = true)
                    }
                    .semantics { contentDescription = "Zoom ${ZoomMath.formatZoom(chip)}" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ZoomMath.chipLabel(chip, state.zoom, isSelected),
                    color = if (isSelected) onPrimary else Color.White,
                    fontSize = if (isSelected) 14.sp else 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.rotate(iconRotation),
                )
            }
        }
    }
}

@Composable
private fun ShutterRow(state: AppState, settings: AppSettings, actions: CameraActions, iconRotation: Float) {
    Row(
        Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            state.recording -> RoundButton(
                icon = { Icon(if (state.recordingPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, "Pause", tint = Color.White) },
                onClick = actions::pauseResume,
            )
            state.intentMode != IntentMode.NORMAL -> Spacer(Modifier.size(56.dp))
            state.scanning && state.scanPages.isNotEmpty() -> ScanStack(state, actions, iconRotation)
            else -> Thumbnail(state, actions, iconRotation)
        }
        Shutter(state, settings, actions)
        when {
            state.recording -> if (state.snapshotAvailable) {
                RoundButton(
                    icon = { Icon(Icons.Outlined.PhotoCamera, "Take photo", tint = Color.White, modifier = Modifier.rotate(iconRotation)) },
                    onClick = actions::snapshot,
                )
            } else {
                Spacer(Modifier.size(56.dp))
            }
            state.scanning -> if (state.scanPages.isNotEmpty()) ScanDoneButton(state, actions) else Spacer(Modifier.size(56.dp))
            state.caps.hasFront && !state.burstActive -> FlipButton(actions, iconRotation)
            else -> Spacer(Modifier.size(56.dp))
        }
    }
}

@Composable
private fun RoundButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(FocoColors.Button).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
private fun Thumbnail(state: AppState, actions: CameraActions, iconRotation: Float) {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(state.thumbnailTick) {
        if (state.thumbnailTick == 0) return@LaunchedEffect
        pulse.snapTo(0.7f)
        pulse.animateTo(1f, tween(260))
    }
    Box(
        Modifier
            .size(56.dp)
            .scale(pulse.value)
            .rotate(iconRotation)
            .clip(RoundedCornerShape(14.dp))
            .background(FocoColors.Button)
            .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(14.dp))
            .clickable(onClick = actions::openGallery)
            .semantics { contentDescription = "Gallery" },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = state.thumbnail
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (state.processingCount > 0) {
            // Still being processed (like the spinner on Pixel Camera's thumbnail).
            Box(Modifier.fillMaxSize().background(Color(0x55000000)))
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun Shutter(state: AppState, settings: AppSettings, actions: CameraActions) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val inner by animateFloatAsState(if (pressed) 0.86f else 1f, tween(90), label = "shutter")
    val isVideo = state.kind == CaptureKind.VIDEO
    val night = !isVideo && (state.burstNight && state.burstActive || !state.burstActive && state.nightWillRun(settings))
    val ringProgress = state.burstActive && (state.burstNight || state.burstKind == io.github.realkhfan.foco.processing.ShotKind.ACTION_PAN)
    val progress by animateFloatAsState(if (state.burstActive) state.burstProgress else 0f, tween(250), label = "progress")
    val primary = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(84.dp)
                .alpha(if (state.cameraReady) 1f else 0.5f)
                .clickable(interactionSource = interaction, indication = null) { actions.shutter() }
                .semantics {
                    contentDescription = when {
                        state.burstActive -> "Stop"
                        state.recording -> "Stop recording"
                        isVideo -> "Record"
                        night -> "Take Night photo"
                        state.photoMode == PhotoMode.SCAN -> "Scan page"
                        else -> "Take photo"
                    }
                },
        ) {
            val r = size.minDimension / 2
            drawCircle(FocoColors.ShutterRing, radius = r)
            if (ringProgress) {
                val w = 5.dp.toPx()
                drawArc(
                    primary, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
                )
            }
            val innerR = (r - 7.dp.toPx()) * inner
            when {
                state.recording -> {
                    drawCircle(Color.White, radius = innerR)
                    val s = 26.dp.toPx()
                    drawRoundRect(
                        FocoColors.Recording,
                        topLeft = Offset(center.x - s / 2, center.y - s / 2),
                        size = Size(s, s),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
                isVideo -> {
                    drawCircle(Color.White, radius = innerR)
                    drawCircle(FocoColors.Recording, radius = innerR - 4.dp.toPx())
                }
                else -> drawCircle(Color.White, radius = innerR)
            }
        }
        val modeIcon = when {
            isVideo -> null
            state.photoMode == PhotoMode.LONG_EXPOSURE -> Icons.Outlined.ShutterSpeed
            state.photoMode == PhotoMode.ACTION_PAN -> Icons.AutoMirrored.Outlined.DirectionsRun
            state.photoMode == PhotoMode.PANORAMA -> Icons.Outlined.Panorama
            state.photoMode == PhotoMode.SCAN -> Icons.Outlined.DocumentScanner
            night -> Icons.Outlined.ModeNight
            else -> null
        }
        when {
            state.scanBusy && !isVideo && state.photoMode == PhotoMode.SCAN ->
                CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
            state.panoActive -> Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(primary))
            state.burstActive && state.burstNight && state.burstSecondsLeft > 0 ->
                Text("${state.burstSecondsLeft}", color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            modeIcon != null -> Icon(modeIcon, null, tint = Color.Black, modifier = Modifier.size(30.dp))
        }
    }
}

/** Scan: finish taking pages and look through them. */
@Composable
private fun ScanDoneButton(state: AppState, actions: CameraActions) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(primary)
            .clickable(onClick = actions::openScanReview)
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = "Done scanning" },
        contentAlignment = Alignment.Center,
    ) {
        Text("Done", color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FlipButton(actions: CameraActions, iconRotation: Float) {
    var spins by remember { mutableFloatStateOf(0f) }
    val spin by animateFloatAsState(spins, tween(350), label = "flip")
    RoundButton(
        icon = {
            Icon(
                Icons.Outlined.Sync,
                "Switch camera",
                tint = Color.White,
                modifier = Modifier.size(30.dp).rotate(iconRotation + spin),
            )
        },
        onClick = {
            spins -= 180f
            actions.flip()
        },
    )
}

/**
 * The mode strip (Action Pan · Long Exposure · Photo · Night · Panorama · Scan). The selected mode sits in the middle in
 * a lavender pill, like Pixel Camera. Swipe it (or the viewfinder) left for the mode on the right and back, or tap.
 */
@Composable
private fun ModeStrip(state: AppState, actions: CameraActions) {
    if (state.recording || state.burstActive || state.intentMode != IntentMode.NORMAL) {
        Spacer(Modifier.height(36.dp))
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val photoModes = state.photoModes
    val isPhoto = state.kind == CaptureKind.PHOTO
    val labels = if (isPhoto) photoModes.map { it.label } else listOf("Video")
    val selected = if (isPhoto) photoModes.indexOf(state.photoMode) else 0
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    // Item centres from the last layout, to keep the strip from jumping when the selection changes.
    val centres = remember { FloatArray(PhotoMode.entries.size + 1) }
    val density = LocalDensity.current
    val itemGuess = with(density) { 110.dp.toPx() }
    Layout(
        content = {
            labels.forEachIndexed { i, name ->
                Text(
                    name,
                    color = if (i == selected) onPrimary else Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (i == selected) primary else Color.Transparent)
                        .clickable(enabled = isPhoto) { actions.setPhotoMode(photoModes[i]) }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(isPhoto) {
                if (!isPhoto) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val current = photoModes.indexOf(state.photoMode)
                        val moved = ModeSwipe.steps(offset.value, itemGuess)
                        val target = (current + moved).coerceIn(0, photoModes.lastIndex)
                        scope.launch {
                            if (target != current) {
                                // Keep the strip where the finger left it, then glide the new mode to the middle.
                                offset.snapTo(offset.value + (centres[target] - centres[current]))
                                actions.setPhotoMode(photoModes[target])
                            }
                            offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, dx ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + dx) }
                }
            },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val placeables = measurables.map { it.measure(Constraints()) }
        val xs = IntArray(placeables.size)
        var x = 0
        placeables.forEachIndexed { i, p ->
            xs[i] = x
            if (i < centres.size) centres[i] = (x + p.width / 2).toFloat()
            x += p.width + gap
        }
        val selCenter = xs[selected] + placeables[selected].width / 2
        val shift = constraints.maxWidth / 2 - selCenter + offset.value.roundToInt()
        val height = constraints.maxHeight
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, p -> p.place(xs[i] + shift, (height - p.height) / 2) }
        }
    }
}

/** Settings (left) · Photo/Video (middle) · Pro controls or the Night chip (right) – as in Pixel Camera. */
@Composable
private fun BottomRow(state: AppState, settings: AppSettings, actions: CameraActions) {
    Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp)) {
        if (state.recording || state.burstActive) return@Box
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(52.dp)
                .clip(CircleShape)
                .background(FocoColors.ToggleBg)
                .clickable { state.settingsOpen = true }
                .semantics { contentDescription = "Settings" },
            contentAlignment = Alignment.Center,
        ) {
            CameraSettingsGlyph()
        }
        if (state.intentMode == IntentMode.NORMAL) {
            PhotoVideoToggle(state, actions, Modifier.align(Alignment.Center))
        }
        if (state.nightWillRun(settings)) {
            Row(
                Modifier
                    .align(Alignment.CenterEnd)
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(FocoColors.ToggleBg)
                    .clickable {
                        state.proBarOpen = true
                        state.activeTool = ProTool.NIGHT
                    }
                    .padding(horizontal = 14.dp)
                    .semantics { contentDescription = "Night length" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NightGlyph(Color.White, auto = state.pro.nightSeconds == null)
                Text("${ceil(state.nightSecondsNow()).toInt()} s", color = Color.White, fontSize = 15.sp)
            }
        } else {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(FocoColors.ToggleBg)
                    .clickable { state.proBarOpen = !state.proBarOpen }
                    .semantics { contentDescription = "Pro controls" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Tune, null, tint = Color.White)
            }
        }
    }
}

/** Gear with a little camera: Pixel Camera's settings button. */
@Composable
private fun CameraSettingsGlyph() {
    Box(Modifier.size(30.dp)) {
        Icon(Icons.Filled.Settings, null, tint = Color.White, modifier = Modifier.size(22.dp).align(Alignment.TopStart))
        Box(
            Modifier.size(17.dp).align(Alignment.BottomEnd).clip(RoundedCornerShape(5.dp)).background(FocoColors.ToggleBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun PhotoVideoToggle(state: AppState, actions: CameraActions, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier.clip(CircleShape).background(FocoColors.ToggleBg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            CaptureKind.PHOTO to Icons.Filled.PhotoCamera,
            CaptureKind.VIDEO to Icons.Outlined.Videocam,
        ).forEach { (kind, icon) ->
            val selected = state.kind == kind
            Box(
                Modifier
                    .size(width = 48.dp, height = 44.dp)
                    .clip(CircleShape)
                    .background(if (selected) primary else Color.Transparent)
                    .clickable { actions.setKind(kind) }
                    .semantics { contentDescription = if (kind == CaptureKind.PHOTO) "Photo" else "Video" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (selected) onPrimary else Color.White)
            }
        }
    }
}

@Composable
private fun QrChip(text: String, actions: CameraActions) {
    val isLink = text.startsWith("http://") || text.startsWith("https://")
    Row(
        Modifier
            .padding(bottom = 8.dp)
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF0202124))
            .clickable { actions.qrClicked(text) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.QrCode2, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(
            text.removePrefix("https://").removePrefix("http://"),
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(if (isLink) Icons.AutoMirrored.Outlined.OpenInNew else Icons.Outlined.ContentCopy, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

// ------------------------------------------------------------------------------------- permission

@Composable
fun PermissionScreen(state: AppState, actions: CameraActions) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.PhotoCamera, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(24.dp))
        Text("Foco needs your camera", color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Photos and videos stay on your phone. Foco has no internet access.",
            color = FocoColors.Dim,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        if (state.secure) {
            Text("Unlock your phone to allow access.", color = Color.White, textAlign = TextAlign.Center)
        } else {
            Button(onClick = { if (state.permissionAsked) actions.openAppInfo() else actions.requestPermission() }) {
                Text(if (state.permissionAsked) "Open app settings" else "Allow camera")
            }
            if (state.permissionAsked) {
                TextButton(onClick = actions::requestPermission) { Text("Ask again") }
            }
        }
    }
}
