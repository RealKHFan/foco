package io.github.realkhfan.foco.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.ModeNight

/** Small drawn icons for things Material icons don't have (12/50 MP, RAW, ratios, timer digits). */

@Composable
fun MpGlyph(megapixels: String, color: Color, filled: Boolean = false, size: Dp = 24.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 1.6.dp.toPx()
            if (filled) {
                drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx()))
            } else {
                drawRoundRect(
                    color,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(stroke),
                )
            }
        }
        val textColor = if (filled) Color.Black else color
        Text(
            text = "$megapixels\nMP",
            color = textColor,
            style = TextStyle(fontSize = (size.value * 0.34f).sp, lineHeight = (size.value * 0.34f).sp, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun RawGlyph(color: Color, crossed: Boolean) {
    Box(Modifier.size(width = 34.dp, height = 24.dp), contentAlignment = Alignment.Center) {
        Text("RAW", color = color, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Black))
        if (crossed) {
            Canvas(Modifier.size(width = 30.dp, height = 22.dp)) {
                drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

/** Outline of the frame shape: 9:16 (tall) or 3:4. */
@Composable
fun RatioGlyph(widthRatio: Float, heightRatio: Float, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val maxH = size.height * 0.92f
        val h = maxH
        val w = h * widthRatio / heightRatio
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset((size.width - w) / 2, (size.height - h) / 2),
            size = Size(w, h),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(stroke),
        )
    }
}

/** Moon (Night); with a small "A" when Night switches on by itself. */
@Composable
fun NightGlyph(color: Color, auto: Boolean) {
    Box(Modifier.size(26.dp)) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.ModeNight, null, tint = color,
            modifier = Modifier.size(22.dp).align(Alignment.BottomStart),
        )
        if (auto) {
            Text(
                "A",
                color = color,
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** Stopwatch with a number inside (3 / 5 / 10). */
@Composable
fun TimerGlyph(seconds: Int, color: Color, background: Color) {
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(26.dp)) {
            val r = size.minDimension * 0.4f
            val c = Offset(size.width / 2, size.height / 2 + 1.5.dp.toPx())
            drawCircle(color, radius = r, center = c)
            drawRoundRect(
                color,
                topLeft = Offset(size.width / 2 - 3.dp.toPx(), 0f),
                size = Size(6.dp.toPx(), 2.5.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
        Text(
            seconds.toString(),
            color = background,
            style = TextStyle(fontSize = if (seconds >= 10) 9.sp else 11.sp, fontWeight = FontWeight.Black),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
