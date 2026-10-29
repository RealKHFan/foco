package io.github.realkhfan.foco.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.ModeNight
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.HdrAuto
import androidx.compose.material.icons.outlined.Iso
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.ShutterSpeed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.realkhfan.foco.camera.ExposureMath
import io.github.realkhfan.foco.camera.FocusMath
import io.github.realkhfan.foco.camera.WhiteBalanceMath
import io.github.realkhfan.foco.ui.theme.FocoColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Everything the tick slider needs for one Pro tool. */
data class ToolModel(
    val steps: Int,
    val index: Int,
    val bubble: String,
    val isAuto: Boolean,
    val labels: List<Pair<Int, String>>,
    val enabled: Boolean,
    val apply: (Int) -> ProState,
    val reset: ProState,
)

private const val FOCUS_STEPS = 40
private const val SHADOW_STEPS = 20

/** Night slider positions: Off · shorter · Auto (middle) · longer · Max. */
private val NIGHT_VALUES = floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f, -1f, 3f, 4f, 5f, 6f, 8f)
private const val NIGHT_AUTO = 5

private fun secondsLabel(s: Float) = if (s % 1f == 0f) "${s.toInt()} s" else "$s s"

fun toolModel(
    tool: ProTool,
    caps: Capabilities,
    pro: ProState,
    readout: Readout,
    nightEstimate: Float = 1f,
    processing: Boolean = true,
): ToolModel = when (tool) {
    ProTool.NIGHT -> {
        val ns = pro.nightSeconds
        val index = when {
            ns == null -> NIGHT_AUTO
            ns == 0f -> 0
            else -> NIGHT_VALUES.indices.filter { it != NIGHT_AUTO && it != 0 }.minBy { abs(NIGHT_VALUES[it] - ns) }
        }
        ToolModel(
            steps = NIGHT_VALUES.size - 1,
            index = index,
            bubble = when {
                ns == null -> secondsLabel(ceil(nightEstimate.coerceAtLeast(1f)))
                ns == 0f -> "Off"
                else -> secondsLabel(ns)
            },
            isAuto = ns == null,
            labels = listOf(0 to "Off", NIGHT_AUTO to "Auto", NIGHT_VALUES.size - 1 to "Max"),
            enabled = processing,
            apply = { i ->
                pro.copy(
                    nightSeconds = when (i) {
                        NIGHT_AUTO -> null
                        0 -> 0f
                        else -> NIGHT_VALUES[i.coerceIn(0, NIGHT_VALUES.size - 1)]
                    },
                )
            },
            reset = pro.copy(nightSeconds = null),
        )
    }
    ProTool.BRIGHTNESS -> {
        val steps = max(1, caps.evMax - caps.evMin)
        ToolModel(
            steps = steps,
            index = (pro.evIndex - caps.evMin).coerceIn(0, steps),
            bubble = ExposureMath.formatEv(pro.evIndex * caps.evStep),
            isAuto = pro.evIndex == 0,
            labels = listOf(
                0 to ExposureMath.formatEv(caps.evMin * caps.evStep),
                -caps.evMin to "0",
                steps to ExposureMath.formatEv(caps.evMax * caps.evStep),
            ),
            enabled = caps.evMax > caps.evMin,
            apply = { pro.copy(evIndex = it + caps.evMin) },
            reset = pro.copy(evIndex = 0),
        )
    }
    ProTool.SHADOWS -> ToolModel(
        steps = SHADOW_STEPS,
        index = ((pro.shadows + 1f) * SHADOW_STEPS / 2).roundToInt(),
        bubble = ExposureMath.formatEv(pro.shadows),
        isAuto = pro.shadows == 0f,
        labels = listOf(0 to "-1", SHADOW_STEPS / 2 to "0", SHADOW_STEPS to "+1"),
        enabled = caps.tonemapCurve,
        apply = { pro.copy(shadows = (it - SHADOW_STEPS / 2) / (SHADOW_STEPS / 2f)) },
        reset = pro.copy(shadows = 0f),
    )
    ProTool.WHITE_BALANCE -> {
        val values = WhiteBalanceMath.KELVIN_VALUES
        val mid = values.indexOf(5500).coerceAtLeast(0)
        ToolModel(
            steps = values.size - 1,
            index = pro.wbKelvin?.let { ExposureMath.nearestIndexInt(values, it) } ?: mid,
            bubble = pro.wbKelvin?.let { "${it}K" } ?: "Auto",
            isAuto = pro.wbKelvin == null,
            labels = listOf(0 to "${values.first()}K", mid to "5500K", values.size - 1 to "${values.last()}K"),
            enabled = caps.wbAvailable,
            apply = { pro.copy(wbKelvin = values[it]) },
            reset = pro.copy(wbKelvin = null),
        )
    }
    ProTool.FOCUS -> {
        val current = pro.focusDiopters ?: readout.focusDiopters
        ToolModel(
            steps = FOCUS_STEPS,
            index = current?.let { FocusMath.dioptersToSlider(it, FOCUS_STEPS, caps.minFocusDiopters) } ?: FOCUS_STEPS,
            bubble = pro.focusDiopters?.let { FocusMath.label(it) } ?: "Auto",
            isAuto = pro.focusDiopters == null,
            labels = listOf(0 to "Near", FOCUS_STEPS to "Far"),
            enabled = caps.manualFocus,
            apply = { pro.copy(focusDiopters = FocusMath.sliderToDiopters(it, FOCUS_STEPS, caps.minFocusDiopters)) },
            reset = pro.copy(focusDiopters = null),
        )
    }
    ProTool.SHUTTER -> {
        val values = ExposureMath.shutterValues(caps.shutterMinNs, caps.shutterMaxNs).ifEmpty { listOf(16_666_666L) }
        val current = pro.shutterNs ?: readout.shutterNs ?: 16_666_666L
        ToolModel(
            steps = max(1, values.size - 1),
            index = ExposureMath.nearestIndex(values, current),
            bubble = pro.shutterNs?.let { ExposureMath.formatShutter(it) } ?: "Auto",
            isAuto = pro.shutterNs == null,
            labels = listOf(0 to ExposureMath.formatShutter(values.first()), values.size - 1 to ExposureMath.formatShutter(values.last())),
            enabled = caps.manualSensor && values.size > 1,
            apply = { pro.copy(shutterNs = values[it.coerceIn(values.indices)]) },
            reset = pro.copy(shutterNs = null),
        )
    }
    ProTool.ISO -> {
        val values = ExposureMath.isoValues(caps.isoMin, caps.isoMax).ifEmpty { listOf(100) }
        val current = pro.iso ?: readout.iso ?: 100
        ToolModel(
            steps = max(1, values.size - 1),
            index = ExposureMath.nearestIndexInt(values, current),
            bubble = pro.iso?.let { "ISO $it" } ?: "Auto",
            isAuto = pro.iso == null,
            labels = listOf(0 to values.first().toString(), values.size - 1 to values.last().toString()),
            enabled = caps.manualSensor && values.size > 1,
            apply = { pro.copy(iso = values[it.coerceIn(values.indices)]) },
            reset = pro.copy(iso = null),
        )
    }
}

private fun iconFor(tool: ProTool): ImageVector = when (tool) {
    ProTool.NIGHT -> Icons.Outlined.ModeNight
    ProTool.BRIGHTNESS -> Icons.Outlined.BrightnessMedium
    ProTool.SHADOWS -> Icons.Outlined.Contrast
    ProTool.WHITE_BALANCE -> Icons.Outlined.DeviceThermostat
    ProTool.FOCUS -> Icons.Outlined.CenterFocusWeak
    ProTool.SHUTTER -> Icons.Outlined.ShutterSpeed
    ProTool.ISO -> Icons.Outlined.Iso
}

/** The bar under the shutter: Reset all | Brightness · Shadows · White balance · Focus · Shutter · ISO. */
@Composable
fun ProToolbar(state: AppState, actions: CameraActions, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(onDragStart = { total = 0f }) { _, dy ->
                        total += dy
                        if (total > 24.dp.toPx()) onClose()
                    }
                }
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color(0x66FFFFFF)))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ToolButton(
                label = "Reset all",
                icon = Icons.Outlined.SettingsBackupRestore,
                selected = false,
                enabled = !state.pro.isDefault,
                dimLabel = true,
            ) {
                actions.setPro(ProState())
                state.activeTool = null
            }
            Box(Modifier.padding(top = 14.dp).size(width = 2.dp, height = 36.dp).background(Color(0x55FFFFFF)))
            ProTool.entries.filter {
                it != ProTool.NIGHT || (state.kind == CaptureKind.PHOTO && state.processing &&
                    (state.photoMode == PhotoMode.PHOTO || state.photoMode == PhotoMode.NIGHT))
            }.forEach { tool ->
                val model = toolModel(tool, state.caps, state.pro, state.readout, state.nightEstimate, state.processing)
                ToolButton(
                    label = tool.label,
                    icon = iconFor(tool),
                    selected = state.activeTool == tool,
                    enabled = model.enabled,
                    changed = !model.isAuto,
                ) {
                    state.activeTool = if (state.activeTool == tool) null else tool
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    changed: Boolean = false,
    dimLabel: Boolean = false,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        // At least 80 dp, wider when large text needs it (the row scrolls), so labels never overlap.
        Modifier.widthIn(min = 80.dp).alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 64.dp, height = 60.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (selected) primary else FocoColors.ButtonDark)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White)
            if (changed && !selected) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(6.dp).clip(CircleShape).background(primary))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = if (dimLabel) FocoColors.Dim else Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Pixel-style slider: the marker stays in the middle and the scale moves under it. Plus an "Auto" button. */
@Composable
fun ToolSlider(state: AppState, tool: ProTool, actions: CameraActions, modifier: Modifier = Modifier) {
    val model = toolModel(tool, state.caps, state.pro, state.readout, state.nightEstimate, state.processing)
    val pick = rememberPick(state, tool, actions)
    if (tool == ProTool.BRIGHTNESS || tool == ProTool.SHADOWS) {
        val (low, high) = if (tool == ProTool.BRIGHTNESS) {
            Icons.Outlined.BrightnessLow to Icons.Outlined.BrightnessHigh
        } else {
            Icons.Outlined.Contrast to Icons.Outlined.Contrast
        }
        Row(
            modifier
                .padding(bottom = 26.dp)
                .alpha(if (model.enabled) 1f else 0.38f)
                .clip(CircleShape)
                .background(Color(0xB3000000))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                low, "Less", tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(24.dp).clickable(enabled = model.enabled) { pick((model.index - 1).coerceAtLeast(0)) },
            )
            Ruler(tool, model, pick, Modifier.padding(horizontal = 6.dp).size(width = 220.dp, height = 40.dp), bubble = false, onDoubleTap = { actions.setPro(model.reset) })
            Icon(
                high, "More", tint = Color.White,
                modifier = Modifier.size(26.dp).clickable(enabled = model.enabled) { pick((model.index + 1).coerceAtMost(model.steps)) },
            )
        }
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier
                .padding(bottom = 30.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(if (model.isAuto) primary else FocoColors.Button)
                .clickable { actions.setPro(model.reset) }
                .semantics { contentDescription = "Auto ${tool.label}" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.HdrAuto, null, tint = if (model.isAuto) onPrimary else Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Ruler(tool, model, pick, Modifier.size(width = 270.dp, height = 100.dp), bubble = true, onDoubleTap = { actions.setPro(model.reset) })
    }
}

/** Applies a slider position, always reading the newest values (gesture handlers outlive a composition). */
@Composable
private fun rememberPick(state: AppState, tool: ProTool, actions: CameraActions): (Int) -> Unit = { index ->
    val current = toolModel(tool, state.caps, state.pro, state.readout, state.nightEstimate, state.processing)
    if (index != current.index || current.isAuto) {
        actions.haptic()
        actions.setPro(current.apply(index))
    }
}

/** Space between two ticks: fewer steps get wider spacing, many steps a fine scale. */
fun rulerSpacingDp(steps: Int): Float = (520f / steps.coerceAtLeast(1)).coerceIn(8f, 30f)

/** Where the scale ends up after dragging [dragPx] from [startPos] (positions are in steps). */
fun rulerPosition(startPos: Float, dragPx: Float, spacingPx: Float, steps: Int): Float =
    (startPos - dragPx / spacingPx).coerceIn(0f, steps.toFloat())

/** Step a fling lands on: keeps going a little in the flick direction. */
fun rulerFlingTarget(pos: Float, velocityPxPerS: Float, spacingPx: Float, steps: Int): Int =
    (pos - velocityPxPerS * 0.12f / spacingPx).roundToInt().coerceIn(0, steps)

/**
 * The moving scale. The marker (and value bubble) stay fixed in the middle; dragging moves the ticks and
 * labels under it, and the value under the marker is applied. Flick to move further, tap a tick to jump.
 */
@Composable
private fun Ruler(
    tool: ProTool,
    model: ToolModel,
    pick: (Int) -> Unit,
    modifier: Modifier,
    bubble: Boolean,
    onDoubleTap: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val position = remember(tool) { Animatable(model.index.toFloat()) }
    var dragging by remember(tool) { mutableStateOf(false) }
    val latestModel by rememberUpdatedState(model)
    val latestPick by rememberUpdatedState(pick)
    LaunchedEffect(tool, model.index, model.steps) {
        if (!dragging) position.animateTo(model.index.toFloat(), spring(stiffness = Spring.StiffnessMediumLow))
    }
    val density = LocalDensity.current
    val spacingPx = with(density) { rulerSpacingDp(model.steps).dp.toPx() }

    Canvas(
        modifier
            .alpha(if (model.enabled) 1f else 0.38f)
            .pointerInput(tool, model.enabled, model.steps) {
                if (!model.enabled) return@pointerInput
                detectTapGestures(onDoubleTap = { onDoubleTap() }) { tap ->
                    val target = (position.value + (tap.x - size.width / 2f) / spacingPx).roundToInt().coerceIn(0, latestModel.steps)
                    latestPick(target)
                    scope.launch { position.animateTo(target.toFloat()) }
                }
            }
            .pointerInput(tool, model.enabled, model.steps) {
                if (!model.enabled) return@pointerInput
                val tracker = VelocityTracker()
                var start = 0f
                var dragged = 0f
                var last = -1
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        tracker.resetTracking()
                        start = position.value
                        dragged = 0f
                        last = latestModel.index
                    },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity().x
                        val target = rulerFlingTarget(position.value, velocity, spacingPx, latestModel.steps)
                        if (target != last) latestPick(target)
                        dragging = false
                        scope.launch { position.animateTo(target.toFloat(), spring(stiffness = Spring.StiffnessMediumLow)) }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch { position.animateTo(latestModel.index.toFloat()) }
                    },
                ) { change, dx ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    dragged += dx
                    val pos = rulerPosition(start, dragged, spacingPx, latestModel.steps)
                    scope.launch { position.snapTo(pos) }
                    val index = pos.roundToInt()
                    if (index != last) {
                        last = index
                        latestPick(index)
                    }
                }
            },
    ) {
        val cx = size.width / 2f
        val centerY = if (bubble) 56.dp.toPx() else size.height / 2f
        val pos = position.value
        val fade = size.width / 2f
        val majors = model.labels.map { it.first }.toSet()
        val first = (pos - cx / spacingPx).toInt().coerceAtLeast(0)
        val last = (pos + cx / spacingPx + 1).toInt().coerceAtMost(model.steps)
        for (i in first..last) {
            val x = cx + (i - pos) * spacingPx
            if (x < 0f || x > size.width) continue
            val edge = (1f - abs(x - cx) / fade).coerceIn(0f, 1f)
            val major = i in majors
            val h = if (major) 18.dp.toPx() else 9.dp.toPx()
            drawLine(
                Color.White.copy(alpha = (if (major) 1f else 0.6f) * (0.15f + 0.85f * edge)),
                Offset(x, centerY - h / 2), Offset(x, centerY + h / 2),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
            )
        }
        if (bubble) {
            val labelStyle = TextStyle(color = primary, fontSize = 13.sp)
            model.labels.forEach { (i, text) ->
                val x = cx + (i - pos) * spacingPx
                if (x < -40.dp.toPx() || x > size.width + 40.dp.toPx()) return@forEach
                val edge = (1f - abs(x - cx) / fade).coerceIn(0f, 1f)
                val layout = measurer.measure(text, labelStyle.copy(color = primary.copy(alpha = 0.2f + 0.8f * edge)))
                drawText(layout, topLeft = Offset(x - layout.size.width / 2, centerY + 14.dp.toPx()))
            }
        }
        // Fixed marker in the middle.
        drawRoundRect(
            primary,
            topLeft = Offset(cx - 3.dp.toPx(), centerY - 15.dp.toPx()),
            size = Size(6.dp.toPx(), 30.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
        if (bubble) {
            val bubbleLayout = measurer.measure(model.bubble, TextStyle(color = onPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium))
            val bw = bubbleLayout.size.width + 24.dp.toPx()
            val bh = 32.dp.toPx()
            val bx = cx - bw / 2
            drawRoundRect(primary, topLeft = Offset(bx, 0f), size = Size(bw, bh), cornerRadius = CornerRadius(bh / 2))
            drawText(bubbleLayout, topLeft = Offset(bx + (bw - bubbleLayout.size.width) / 2, (bh - bubbleLayout.size.height) / 2))
        }
    }
}
