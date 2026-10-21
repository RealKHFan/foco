package io.github.realkhfan.foco.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.realkhfan.foco.BuildConfig
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.settings.GridSetting
import io.github.realkhfan.foco.settings.SettingKey
import io.github.realkhfan.foco.settings.VolumeKeyAction
import io.github.realkhfan.foco.ui.theme.FocoColors

const val SOURCE_URL = "https://github.com/RealKHFan/foco"

/** The full settings page behind the "…" button. */
@Composable
fun SettingsScreen(state: AppState, settings: AppSettings, actions: CameraActions) {
    BackHandler { state.fullSettingsOpen = false }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.fullSettingsOpen = false }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White)
            }
            Text("Settings", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(start = 8.dp))
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item { Section("Processing") }
            item {
                SwitchRow(
                    "Foco processing",
                    "Merges several frames for HDR, Night and Ultra HDR photos. Off = the camera's own single photo",
                    settings.processing,
                ) {
                    settings.processing = it
                    actions.settingChanged(SettingKey.PROCESSING)
                }
            }
            item {
                SwitchRow("Sharper zoom", "Extra frames and detail when zoomed in past a lens (multi-frame upscaling)", settings.zoomDetail, enabled = settings.processing) {
                    settings.zoomDetail = it
                }
            }
            item {
                SwitchRow("Dirty lens warning", "Tells you when the lens looks smudged (simple check, can be wrong)", settings.dirtyLens) {
                    settings.dirtyLens = it
                }
            }
            item { Section("Camera") }
            item {
                SwitchRow("Show quick access controls", "One-tap buttons for flash, timer and ratio at the top", settings.quickAccess) {
                    settings.quickAccess = it
                }
            }
            item {
                SwitchRow("Save location", "Add where photos were taken (GPS, no Google services)", settings.saveLocation) {
                    settings.saveLocation = it
                    actions.settingChanged(SettingKey.LOCATION)
                }
            }
            item {
                SwitchRow("Camera sounds", "Shutter and recording sounds", settings.shutterSound) {
                    settings.shutterSound = it
                }
            }
            item { SwitchRow("Vibration", "Haptic feedback for buttons and sliders", settings.haptics) { settings.haptics = it } }
            item {
                SwitchRow("Framing hints", "Level line while taking photos", settings.level) {
                    settings.level = it
                }
            }
            item {
                ChoiceRow(
                    "Grid type",
                    listOf("None", "3 × 3", "4 × 4", "Golden"),
                    GridSetting.entries.indexOf(settings.grid),
                ) { settings.grid = GridSetting.entries[it] }
            }
            item {
                SwitchRow("Scan QR codes", "Show links and text from QR codes in the viewfinder", settings.qrDetection) {
                    settings.qrDetection = it
                    actions.settingChanged(SettingKey.QR)
                }
            }
            item {
                val ultraHdrPossible = state.caps.ultraHdrSupported || (settings.processing && android.os.Build.VERSION.SDK_INT >= 34)
                SwitchRow(
                    "Ultra HDR",
                    if (ultraHdrPossible) "Brighter highlights on HDR screens (photos still look normal everywhere else)" else "Needs Foco processing and Android 14",
                    settings.ultraHdr && ultraHdrPossible,
                    enabled = ultraHdrPossible,
                ) {
                    settings.ultraHdr = it
                    actions.settingChanged(SettingKey.ULTRA_HDR)
                }
            }
            item {
                SwitchRow("Mirror selfies", "Save front-camera photos the way the preview shows them", settings.mirrorFront) {
                    settings.mirrorFront = it
                }
            }
            item {
                ChoiceRow(
                    "Volume keys",
                    listOf("Shutter", "Zoom", "Volume"),
                    VolumeKeyAction.entries.indexOf(settings.volumeKeys),
                ) { settings.volumeKeys = VolumeKeyAction.entries[it] }
            }
            item { Section("Privacy") }
            item {
                SwitchRow("Remove phone model from photos", "Strips make, model and lens info from the photo's metadata", settings.stripDeviceInfo) {
                    settings.stripDeviceInfo = it
                }
            }
            item {
                InfoRow("No internet access", "Foco can't connect to the internet. Nothing leaves your phone.")
            }
            item { Section("About") }
            item { InfoRow("Foco ${BuildConfig.VERSION_NAME}", "Free software under the GNU GPL v3") }
            item { InfoRow("Source code", SOURCE_URL.removePrefix("https://"), onClick = { actions.openUrl(SOURCE_URL) }) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = if (enabled) Color.White else FocoColors.Dim, fontSize = 17.sp)
            Text(subtitle, color = FocoColors.Dim, fontSize = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(title, color = Color.White, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(title, color = Color.White, fontSize = 17.sp)
        Text(subtitle, color = FocoColors.Dim, fontSize = 14.sp)
    }
}
