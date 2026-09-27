package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.window.Dialog

internal fun hsvToColor(h: Float, s: Float, v: Float, alpha: Float = 1f): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val c = v * s
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m, alpha.coerceIn(0f, 1f))
}

internal fun colorToHsv(c: Color): Triple<Float, Float, Float> {
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == c.red -> 60f * (((c.green - c.blue) / d) % 6f)
        max == c.green -> 60f * (((c.blue - c.red) / d) + 2f)
        else -> 60f * (((c.red - c.green) / d) + 4f)
    }
    return Triple(if (h < 0f) h + 360f else h, if (max == 0f) 0f else d / max, max)
}

private val STANDARD: List<Color> = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5, 0xFF039BE5,
    0xFF00ACC1, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33, 0xFFFDD835, 0xFFFFB300,
    0xFFFB8C00, 0xFFF4511E, 0xFF6D4C41, 0xFF757575, 0xFF9E9E9E, 0xFFE0E0E0, 0xFFFAFAFA,
    0xFF212121, 0xFF546E7A, 0xFF8D6E63,
).map { Color(it.toInt()) }

/** 在区域内按下即定位，之后持续拖动也回调归一化坐标。 */
private fun Modifier.dragNormalized(onPos: (Float, Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            onPos(
                (down.position.x / size.width).coerceIn(0f, 1f),
                (down.position.y / size.height).coerceIn(0f, 1f),
            )
            while (true) {
                val ev = awaitPointerEvent()
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                pressed.forEach {
                    onPos(
                        (it.position.x / size.width).coerceIn(0f, 1f),
                        (it.position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
        }
    }

@Composable
private fun Swatch(c: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(c)
            .border(1.dp, Theme.Muted.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun SwatchRow(label: String, colors: List<Color>, onPick: (Color) -> Unit) {
    if (colors.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, color = Theme.Muted)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            colors.chunked(12).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { Swatch(it) { onPick(it) } }
                }
            }
        }
    }
}

@Composable
fun ColorPickerDialog(
    title: String,
    initial: Color,
    themeColors: List<Color>,
    onPick: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val (h0, s0, v0) = remember(initial) { colorToHsv(initial) }
    var h by remember { mutableStateOf(h0) }
    var s by remember { mutableStateOf(s0) }
    var v by remember { mutableStateOf(v0) }
    var a by remember { mutableStateOf(initial.alpha) }
    val current = hsvToColor(h, s, v, a)
    val pure = hsvToColor(h, 1f, 1f, 1f)

    val ui = atlasUiTokens()
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(360.dp), shape = MaterialTheme.shapes.medium, color = Theme.Elevated) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = ui.typography.sectionTitle, color = Theme.Accent)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(40.dp)
                            .background(current, RoundedCornerShape(6.dp))
                            .border(1.dp, Theme.Muted.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                    )
                    Text(
                        hexOf(current),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // S/V 面板：横向白→纯色相，纵向透明→黑，叠出标准 SV 方块
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .dragNormalized { nx, ny -> s = nx; v = 1f - ny },
                ) {
                    drawRect(brush = Brush.horizontalGradient(listOf(Color.White, pure), endX = size.width))
                    drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = size.height))
                    val cx = s * size.width
                    val cy = (1f - v) * size.height
                    drawCircle(Color.White, radius = 7f, center = Offset(cx, cy))
                    drawCircle(Color.Black, radius = 7f, center = Offset(cx, cy), style = Stroke(2f))
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("色相 ${h.toInt()}°", fontSize = 11.sp, color = Theme.Muted)
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .dragNormalized { nx, _ -> h = nx * 360f },
                    ) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFFF0000.toInt()), Color(0xFFFFFF00.toInt()), Color(0xFF00FF00.toInt()),
                                    Color(0xFF00FFFF.toInt()), Color(0xFF0000FF.toInt()), Color(0xFFFF00FF.toInt()),
                                    Color(0xFFFF0000.toInt()),
                                ),
                            ),
                        )
                        val cx = (h / 360f) * size.width
                        drawCircle(Color.White, radius = 6f, center = Offset(cx, size.height / 2f))
                        drawCircle(Color.Black, radius = 6f, center = Offset(cx, size.height / 2f), style = Stroke(2f))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("透明度 ${(a * 100).toInt()}%", fontSize = 11.sp, color = Theme.Muted)
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .dragNormalized { nx, _ -> a = nx },
                    ) {
                        drawRect(brush = Brush.horizontalGradient(listOf(pure.copy(alpha = 0f), pure)))
                    }
                }

                SwatchRow("本主题配色（点一下直接借用）", themeColors) { onPick(it) }
                SwatchRow("标准色板", STANDARD) { onPick(it) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消", color = Theme.Muted) }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Button(onClick = { onPick(current) }) { Text("用这个颜色") }
                }
            }
        }
    }
}
