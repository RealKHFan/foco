package io.github.realkhfan.foco.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Fixed greys of the camera UI (Pixel Camera is always dark). */
object FocoColors {
    val Button = Color(0xFF2B2C30)
    val ButtonDark = Color(0xFF1C1D20)
    val Sheet = Color(0xFF101114)
    /** Settings sheet: a little see-through, like Pixel Camera's. */
    val SheetTranslucent = Color(0xD6121317)
    val ToggleBg = Color(0xFF1E1F22)
    val ToggleSelected = Color(0xFF3B3C40)
    val ShutterRing = Color(0xFF3A3A3C)
    val Recording = Color(0xFFE5483C)
    val Dim = Color(0x99FFFFFF)
    val Level = Color(0xFFFFD54F)
}

private val Fallback = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF182C61),
    primaryContainer = Color(0xFF304379),
    onPrimaryContainer = Color(0xFFDBE1FF),
    secondary = Color(0xFFC1C6DD),
    onSecondary = Color(0xFF2A3042),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF111215),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
)

@Composable
fun FocoTheme(dynamic: Boolean = true, content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        dynamicDarkColorScheme(LocalContext.current).copy(background = Color.Black, onBackground = Color.White)
    } else {
        Fallback
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
