package io.github.realkhfan.foco.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BackHand
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.HdrAuto
import androidx.compose.material.icons.outlined.HdrOn
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.camera.core.CameraSelector
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.settings.FlashSetting
import io.github.realkhfan.foco.settings.FrameRate
import io.github.realkhfan.foco.settings.RatioSetting
import io.github.realkhfan.foco.settings.ResolutionSetting
import io.github.realkhfan.foco.settings.SettingKey
import io.github.realkhfan.foco.settings.TimerSetting
import io.github.realkhfan.foco.settings.VideoResolution
import io.github.realkhfan.foco.ui.theme.FocoColors

/** One round option button in a settings row. [glyph] draws the icon in the given colours (content, background). */
class Choice(
    val description: String,
    val enabled: Boolean = true,
    val glyph: @Composable (content: Color, background: Color) -> Unit,
)

private fun iconChoice(description: String, icon: ImageVector, enabled: Boolean = true) =
    Choice(description, enabled) { content, _ -> Icon(icon, null, tint = content) }

private fun textChoice(description: String, text: String, enabled: Boolean = true) =
    Choice(description, enabled) { content, _ -> Text(text, color = content, fontSize = 12.sp, fontWeight = FontWeight.Bold) }

/** "Photo settings" / "Video settings" sheet with General and Pro tabs, like Pixel Camera. */
@Composable
fun SettingsSheet(state: AppState, settings: AppSettings, actions: CameraActions, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(FocoColors.SheetTranslucent)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(onDragStart = { total = 0f }) { _, dy ->
                        total += dy
                        if (total > 60.dp.toPx()) onClose()
                    }
                }
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 14.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color(0x66FFFFFF)))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.kind == CaptureKind.PHOTO) "Photo settings" else "Video settings",
                    color = Color.White,
                    fontSize = 22.sp,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(width = 58.dp, height = 38.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color(0x99FFFFFF), CircleShape)
                        .clickable {
                            onClose()
                            state.fullSettingsOpen = true
                        }
                        .semantics { contentDescription = "More settings" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.MoreHoriz, null, tint = Color.White) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsTab.entries.forEach { tab ->
                    val selected = state.settingsTab == tab
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else FocoColors.ToggleBg)
                            .clickable { state.settingsTab = tab },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (tab == SettingsTab.GENERAL) "General" else "Pro",
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            val rows = when (state.kind) {
                CaptureKind.PHOTO -> if (state.settingsTab == SettingsTab.GENERAL) photoGeneral(state, settings) else photoPro(state, settings)
                CaptureKind.VIDEO -> if (state.settingsTab == SettingsTab.GENERAL) videoGeneral(state, settings) else videoPro(state, settings)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    SettingRow(row) { index ->
                        row.onSelect(index)
                        actions.settingChanged(row.key)
                    }
                }
            }
        }
    }
}

class RowSpec(
    val label: String,
    val values: List<String>,
    val choices: List<Choice>,
    val selected: Int,
    val enabled: Boolean,
    val key: SettingKey,
    val onSelect: (Int) -> Unit,
)

@Composable
private fun SettingRow(row: RowSpec, onSelect: (Int) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.label, color = Color.White, fontSize = 12.sp)
            Text(
                if (row.enabled) row.values[row.selected] else "Unavailable",
                color = if (row.enabled) primary else FocoColors.Dim,
                fontSize = 16.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.choices.forEachIndexed { i, choice ->
                val isSelected = row.enabled && i == row.selected
                val usable = row.enabled && choice.enabled
                val bg = if (isSelected) primary else FocoColors.ToggleBg
                val content = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White
                Box(
                    Modifier
                        .size(40.dp)
                        .alpha(if (usable) 1f else 0.38f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(bg)
                        .clickable(enabled = usable) { onSelect(i) }
                        .semantics { contentDescription = "${row.label}: ${choice.description}" },
                    contentAlignment = Alignment.Center,
                ) { choice.glyph(content, bg) }
            }
        }
    }
}

private fun photoGeneral(state: AppState, settings: AppSettings): List<RowSpec> {
    val front = state.lensFacing == CameraSelector.LENS_FACING_FRONT
    return listOf(
        RowSpec(
            label = "More light",
            values = listOf("Off", "Night auto", if (front) "Screen flash" else "Flash"),
            choices = listOf(
                iconChoice("Off", Icons.Outlined.Block),
                Choice("Night auto", enabled = state.processing || settings.flash == FlashSetting.NIGHT_AUTO) { c, _ -> NightGlyph(c, auto = true) },
                iconChoice("Flash", Icons.Outlined.FlashOn, enabled = front || state.caps.hasFlash),
            ),
            selected = settings.flash.ordinal,
            enabled = true,
            key = SettingKey.FLASH,
        ) { settings.flash = FlashSetting.entries[it] },
        RowSpec(
            label = "Timer",
            values = listOf("Off", "3 seconds", "5 seconds", "10 seconds"),
            choices = listOf(iconChoice("Off", Icons.Outlined.TimerOff)) +
                listOf(3, 5, 10).map { s -> Choice("$s seconds") { c, bg -> TimerGlyph(s, c, bg) } },
            selected = TimerSetting.entries.indexOf(settings.timer),
            enabled = true,
            key = SettingKey.TIMER,
        ) { settings.timer = TimerSetting.entries[it] },
        RowSpec(
            label = "Macro Focus",
            values = listOf("Off", "On"),
            choices = listOf(iconChoice("Off", Icons.Outlined.Block), iconChoice("On", Icons.Outlined.LocalFlorist)),
            selected = if (settings.macro) 1 else 0,
            enabled = !front && state.caps.macroAvailable,
            key = SettingKey.MACRO,
        ) { settings.macro = it == 1 },
        RowSpec(
            label = "Ratio",
            values = listOf("Wide image (9:16)", "Full image (4:3)"),
            choices = listOf(
                Choice("9:16") { c, _ -> RatioGlyph(9f, 16f, c) },
                Choice("4:3") { c, _ -> RatioGlyph(3f, 4f, c) },
            ),
            selected = if (settings.ratio == RatioSetting.WIDE_9_16) 0 else 1,
            enabled = true,
            key = SettingKey.RATIO,
        ) { settings.ratio = if (it == 0) RatioSetting.WIDE_9_16 else RatioSetting.FULL_4_3 },
    )
}

private fun photoPro(state: AppState, settings: AppSettings): List<RowSpec> = listOf(
    RowSpec(
        label = "Resolution",
        values = listOf("12MP", "50MP"),
        choices = listOf(
            Choice("12MP") { c, _ -> MpGlyph("12", c) },
            Choice("50MP", enabled = state.caps.hiResAvailable) { c, _ -> MpGlyph("50", c) },
        ),
        selected = if (settings.resolution == ResolutionSetting.MP50 && state.caps.hiResAvailable) 1 else 0,
        enabled = true,
        key = SettingKey.RESOLUTION,
    ) { settings.resolution = if (it == 1) ResolutionSetting.MP50 else ResolutionSetting.MP12 },
    RowSpec(
        label = "RAW/JPEG",
        values = listOf("JPEG only", "RAW + JPEG"),
        choices = listOf(
            Choice("JPEG only") { c, _ -> RawGlyph(c, crossed = true) },
            Choice("RAW + JPEG") { c, _ -> RawGlyph(c, crossed = false) },
        ),
        selected = if (settings.raw) 1 else 0,
        enabled = state.caps.rawSupported,
        key = SettingKey.RAW,
    ) { settings.raw = it == 1 },
    lensSelection(state, settings),
)

private fun videoGeneral(state: AppState, settings: AppSettings): List<RowSpec> = listOfNotNull(
    RowSpec(
        label = "Resolution",
        values = listOf("1080p", "4K"),
        choices = listOf(textChoice("1080p", "FHD"), textChoice("4K", "4K", enabled = state.caps.uhdSupported)),
        selected = if (settings.videoResolution == VideoResolution.UHD && state.caps.uhdSupported) 1 else 0,
        enabled = true,
        key = SettingKey.VIDEO_RESOLUTION,
    ) { settings.videoResolution = if (it == 1) VideoResolution.UHD else VideoResolution.FHD },
    RowSpec(
        label = "Frame rate",
        values = listOf("Auto (24–30 FPS)", "30 FPS", "60 FPS"),
        choices = listOf(
            textChoice("Auto", "A", enabled = state.caps.autoFpsLower > 0),
            textChoice("30 FPS", "30"),
            textChoice("60 FPS", "60", enabled = state.caps.fps60Supported),
        ),
        selected = when {
            settings.frameRate == FrameRate.FPS60 && state.caps.fps60Supported -> 2
            settings.frameRate == FrameRate.AUTO && state.caps.autoFpsLower > 0 -> 0
            else -> 1
        },
        enabled = true,
        key = SettingKey.FRAME_RATE,
    ) { settings.frameRate = FrameRate.entries[it] },
    RowSpec(
        label = "Video stabilization",
        values = listOf("Off", "Standard"),
        choices = listOf(iconChoice("Off", Icons.Outlined.Block), iconChoice("Standard", Icons.Outlined.BackHand)),
        selected = if (settings.stabilization && state.caps.stabilizationSupported) 1 else 0,
        enabled = state.caps.stabilizationSupported,
        key = SettingKey.STABILIZATION,
    ) { settings.stabilization = it == 1 },
    RowSpec(
        label = "Torch",
        values = listOf("Off", "On"),
        choices = listOf(iconChoice("Off", Icons.Outlined.Block), iconChoice("On", Icons.Outlined.FlashlightOn)),
        selected = if (settings.torch) 1 else 0,
        enabled = state.caps.hasFlash,
        key = SettingKey.TORCH,
    ) { settings.torch = it == 1 },
)

private fun videoPro(state: AppState, settings: AppSettings): List<RowSpec> = listOf(
    RowSpec(
        label = "10-bit HDR",
        values = listOf("Off", "On"),
        choices = listOf(iconChoice("Off", Icons.Outlined.Block), iconChoice("On", Icons.Outlined.HdrOn)),
        selected = if (settings.hdrVideo && state.caps.hdrVideoSupported) 1 else 0,
        enabled = state.caps.hdrVideoSupported,
        key = SettingKey.HDR_VIDEO,
    ) { settings.hdrVideo = it == 1 },
    lensSelection(state, settings),
)

private fun lensSelection(state: AppState, settings: AppSettings) = RowSpec(
    label = "Lens Selection",
    values = listOf("Auto", "Manual"),
    choices = listOf(iconChoice("Auto", Icons.Outlined.HdrAuto), iconChoice("Manual", Icons.Outlined.Camera)),
    selected = if (settings.manualLens && state.caps.manualLensAvailable) 1 else 0,
    enabled = state.caps.manualLensAvailable && state.lensFacing == CameraSelector.LENS_FACING_BACK,
    key = SettingKey.LENS_SELECTION,
) { settings.manualLens = it == 1 }
