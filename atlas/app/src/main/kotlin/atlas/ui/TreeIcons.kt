package atlas.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 目录树行内图标：16 视口线性风格（stroke 1.5，圆角端点），绘制时用 Icon(tint) 换色。 */
private fun treeIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f,
    ).apply(block).build()

/** 朝右的展开箭头；展开态由调用方 rotate(90°) 转成朝下。 */
internal val TreeChevronIcon: ImageVector by lazy {
    treeIcon("TreeChevron") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = {
                moveTo(6f, 3.6f)
                lineTo(10.4f, 8f)
                lineTo(6f, 12.4f)
            },
        )
    }
}

internal val TreeFolderIcon: ImageVector by lazy {
    treeIcon("TreeFolder") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = {
                moveTo(14.2f, 13f)
                lineTo(1.8f, 13f)
                lineTo(1.8f, 3.2f)
                lineTo(6f, 3.2f)
                lineTo(7.6f, 5f)
                lineTo(14.2f, 5f)
                close()
            },
        )
    }
}

/** Markdown 文档：折角 + 两行正文线。 */
internal val TreeFileIcon: ImageVector by lazy {
    treeIcon("TreeFile") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = {
                moveTo(4.2f, 1.8f)
                lineTo(9.6f, 1.8f)
                lineTo(12.2f, 4.4f)
                lineTo(12.2f, 14.2f)
                lineTo(4.2f, 14.2f)
                close()
                moveTo(9.6f, 1.8f)
                lineTo(9.6f, 4.4f)
                lineTo(12.2f, 4.4f)
                moveTo(6.2f, 8.4f)
                lineTo(10.2f, 8.4f)
                moveTo(6.2f, 11f)
                lineTo(10.2f, 11f)
            },
        )
    }
}
