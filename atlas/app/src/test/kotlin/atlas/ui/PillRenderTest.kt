package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PillRenderTest {
    @Test
    fun renderWrappedPills() {
        val scene = ImageComposeScene(1000, 250, Density(1f, 15f / 14f)) {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMarkdownContentStyle provides MarkdownContentStyle(14, 160),
                    LocalMarkdownReadingColors provides MarkdownReadingColors(
                        body = Color.White,
                        bold = Color.White,
                        boldWeight = FontWeight.SemiBold,
                        inlineCode = Color.White,
                        inlineCodeBackground = Color(0xFF337755),
                    ),
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                        MarkdownText("  1. `android_app` 是应用模块类型，产物是可安装的 APK： `srcs` 编译为 DEX， `resource_dirs` 的资源由 AAPT2 打包，与 `manifest` 合并应用身份与组件声明，按 `certificate` 签名，按分区属性安装。块内其余属性都是这条链路的输入；同样源码改用 `android_library` 只产出供依赖编译的库，不走 APK 链路。")
                    }
                }
            }
        }
        try {
            val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/pill-render.png").writeBytes(bytes)
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            fun isPillPixel(x: Int, y: Int): Boolean {
                val rgb = image.getRGB(x, y)
                val r = rgb shr 16 and 0xff
                val g = rgb shr 8 and 0xff
                val b = rgb and 0xff
                return g > r * 1.35 && g > b * 1.1
            }
            // 这三个代码片段分别位于首行、第二行、第三行。检查真实光栅结果，
            // 避免只固定圆角背景高度，却让文字到背景顶部的距离仍然漂移。
            val regions = listOf(
                intArrayOf(46, 138, 25, 50),
                intArrayOf(245, 335, 55, 80),
                intArrayOf(46, 170, 85, 110),
            )
            val measures = regions.map { (x0, x1, y0, y1) ->
                val backgroundRows = (y0 until y1).filter { y ->
                    (x0 until x1).count { x -> isPillPixel(x, y) } > 10
                }
                val inkRows = backgroundRows.filter { y ->
                    (x0 until x1).count { x -> (image.getRGB(x, y) shr 16 and 0xff) > 80 } >= 5
                }
                assertTrue(backgroundRows.isNotEmpty() && inkRows.isNotEmpty())
                (backgroundRows.last() - backgroundRows.first() + 1) to
                    (inkRows.first() - backgroundRows.first())
            }
            assertTrue(measures.map { it.first }.let { it.max() - it.min() } <= 1, "pill heights: $measures")
            assertTrue(measures.map { it.second }.let { it.max() - it.min() } <= 1, "text top insets: $measures")
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderRomanMarkers() {
        val scene = ImageComposeScene(1000, 260, Density(1f, 15f / 14f)) {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMarkdownContentStyle provides MarkdownContentStyle(14, 160),
                    LocalMarkdownReadingColors provides MarkdownReadingColors(
                        body = Color.White,
                        bold = Color.White,
                        boldWeight = FontWeight.SemiBold,
                        inlineCode = Color.White,
                        inlineCodeBackground = Color(0xFF337755),
                    ),
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                        MarkdownText("""1. 父项
    1. 子项
        1. `app/src/main/java/**/*.java` 纳入源码。
        2. `vehiclebase/src/main/java/**/*.java` 纳入源码。
        3. `vehiclebase/src/main/aidl/**/*.aidl` 纳入源码。
        4. `**` 表示递归匹配。""")
                    }
                }
            }
        }
        try {
            val bytes = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/roman-render.png").writeBytes(bytes)
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            val greenRows = (50 until 250).filter { y ->
                (70 until 600).count { x ->
                    val rgb = image.getRGB(x, y)
                    val r = rgb shr 16 and 0xff
                    val g = rgb shr 8 and 0xff
                    val b = rgb and 0xff
                    g > r * 1.35 && g > b * 1.1
                } > 10
            }
            val rowGroups = greenRows.fold(mutableListOf<MutableList<Int>>()) { groups, y ->
                if (groups.isEmpty() || y > groups.last().last() + 1) groups.add(mutableListOf(y))
                else groups.last().add(y)
                groups
            }
            assertEquals(4, rowGroups.size)
            val leftEdges = rowGroups.map { rows ->
                (70 until 600).first { x -> rows.any { y ->
                    val rgb = image.getRGB(x, y)
                    val r = rgb shr 16 and 0xff
                    val g = rgb shr 8 and 0xff
                    val b = rgb and 0xff
                    g > r * 1.35 && g > b * 1.1
                } }
            }
            assertEquals(1, leftEdges.distinct().size, "roman item body starts: $leftEdges")
        } finally {
            scene.close()
        }
    }
}
