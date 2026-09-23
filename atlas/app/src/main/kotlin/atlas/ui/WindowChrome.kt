package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Cursor

/**
 * undecorated 窗口的自定义 chrome（PRD v0.11：顶栏与系统标题栏融为一体）：
 * 顶栏拖动区、双击最大化、边缘隐形缩放热区、窗口控制按钮。
 */

/** 缩放热区边缘厚度 / 角落尺寸 / 无装饰窗口最小尺寸 */
private val EdgeThickness = 5.dp
private val CornerSize = 16.dp
private val AppWindowMinSize = DpSize(720.dp, 480.dp)

/** 顶栏拖动移动窗口；双击切换最大化。最大化状态忽略拖动。
 *  注意：detectDragGestures 的 onDrag 回调是"距上一事件的增量"，必须累加，不能当作相对起点的位移。 */
fun Modifier.appTitleBarDrag(state: WindowState): Modifier = composed {
    pointerInput(Unit) {
        var lastX = 0.dp
        var lastY = 0.dp
        var dragging = false
        detectDragGestures(
            onDragStart = {
                val pos = state.position
                if (pos is WindowPosition.Absolute && state.placement == WindowPlacement.Floating) {
                    lastX = pos.x; lastY = pos.y; dragging = true
                } else {
                    dragging = false
                }
            },
            onDrag = { change, amount ->
                change.consume()
                if (!dragging) return@detectDragGestures
                lastX += amount.x.toDp()
                lastY += amount.y.toDp()
                state.position = WindowPosition(lastX, lastY)
            },
            onDragEnd = { dragging = false },
            onDragCancel = { dragging = false },
        )
    }.pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { toggleMaximize(state) })
    }
}

private fun toggleMaximize(state: WindowState) {
    state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
}

private enum class WindowResizeArea { N, S, E, W, NE, NW, SE, SW }

/** 边缘/角落隐形缩放热区（放在窗口根 Box 的最外层，最大化时不生效）。 */
private fun Modifier.windowResizeArea(state: WindowState, area: WindowResizeArea): Modifier = composed {
    val cursor = remember(area) {
        PointerIcon(
            Cursor.getPredefinedCursor(
                when (area) {
                    WindowResizeArea.N -> Cursor.N_RESIZE_CURSOR
                    WindowResizeArea.S -> Cursor.S_RESIZE_CURSOR
                    WindowResizeArea.E -> Cursor.E_RESIZE_CURSOR
                    WindowResizeArea.W -> Cursor.W_RESIZE_CURSOR
                    WindowResizeArea.NE -> Cursor.NE_RESIZE_CURSOR
                    WindowResizeArea.NW -> Cursor.NW_RESIZE_CURSOR
                    WindowResizeArea.SE -> Cursor.SE_RESIZE_CURSOR
                    WindowResizeArea.SW -> Cursor.SW_RESIZE_CURSOR
                }
            )
        )
    }
    pointerInput(area) {
        var originW = 0.dp; var originH = 0.dp; var originX = 0.dp; var originY = 0.dp
        var hasOrigin = false
        var accX = 0f; var accY = 0f
        detectDragGestures(
            onDragStart = {
                originW = state.size.width; originH = state.size.height
                val pos = state.position
                if (pos is WindowPosition.Absolute) {
                    originX = pos.x; originY = pos.y; hasOrigin = true
                } else {
                    hasOrigin = false
                }
                accX = 0f; accY = 0f
            },
            onDrag = { change, amount ->
                change.consume()
                if (!hasOrigin || state.placement != WindowPlacement.Floating) return@detectDragGestures
                accX += amount.x; accY += amount.y
                val dx = accX.toDp(); val dy = accY.toDp()
                var w = originW; var h = originH; var x = originX; var y = originY
                when (area) {
                    WindowResizeArea.E, WindowResizeArea.NE, WindowResizeArea.SE ->
                        w = (w + dx).coerceAtLeast(AppWindowMinSize.width)
                    WindowResizeArea.W, WindowResizeArea.NW, WindowResizeArea.SW -> {
                        w = (w - dx).coerceAtLeast(AppWindowMinSize.width); x = originX + (originW - w)
                    }
                    else -> {}
                }
                when (area) {
                    WindowResizeArea.S, WindowResizeArea.SE, WindowResizeArea.SW ->
                        h = (h + dy).coerceAtLeast(AppWindowMinSize.height)
                    WindowResizeArea.N, WindowResizeArea.NE, WindowResizeArea.NW -> {
                        h = (h - dy).coerceAtLeast(AppWindowMinSize.height); y = originY + (originH - h)
                    }
                    else -> {}
                }
                state.size = DpSize(w, h)
                state.position = WindowPosition(x, y)
            },
        )
    }.pointerHoverIcon(cursor)
}

/** 窗口根包装：内容之上叠加边缘缩放热区（最大化时不绘制，避免挡住内容边缘的交互）。 */
@Composable
fun WindowResizeBorders(state: WindowState, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        if (state.placement == WindowPlacement.Floating) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(EdgeThickness).windowResizeArea(state, WindowResizeArea.N))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EdgeThickness).windowResizeArea(state, WindowResizeArea.S))
            Box(Modifier.align(Alignment.CenterStart).width(EdgeThickness).fillMaxHeight().windowResizeArea(state, WindowResizeArea.W))
            Box(Modifier.align(Alignment.CenterEnd).width(EdgeThickness).fillMaxHeight().windowResizeArea(state, WindowResizeArea.E))
            Box(Modifier.align(Alignment.TopStart).size(CornerSize).windowResizeArea(state, WindowResizeArea.NW))
            Box(Modifier.align(Alignment.TopEnd).size(CornerSize).windowResizeArea(state, WindowResizeArea.NE))
            Box(Modifier.align(Alignment.BottomStart).size(CornerSize).windowResizeArea(state, WindowResizeArea.SW))
            Box(Modifier.align(Alignment.BottomEnd).size(CornerSize).windowResizeArea(state, WindowResizeArea.SE))
        }
    }
}

/** 顶栏右侧的窗口控制按钮：最小化 / 最大化-还原 / 关闭（图标用 Box 绘制，避免字体缺字）。 */
@Composable
fun WindowControlButtons(state: WindowState, onClose: () -> Unit) {
    val glyphColor = Theme.Muted
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChromeButton("最小化", onClick = { state.isMinimized = true }) {
            Box(Modifier.width(11.dp).height(1.dp).background(glyphColor))
        }
        ChromeButton(if (state.placement == WindowPlacement.Maximized) "还原" else "最大化", onClick = { toggleMaximize(state) }) {
            if (state.placement == WindowPlacement.Maximized) {
                Box(Modifier.size(11.dp)) {
                    Box(Modifier.align(Alignment.TopEnd).size(8.dp).border(1.dp, glyphColor, RectangleShape))
                    Box(Modifier.align(Alignment.BottomStart).size(8.dp).border(1.dp, glyphColor, RectangleShape))
                }
            } else {
                Box(Modifier.size(9.dp).border(1.dp, glyphColor, RectangleShape))
            }
        }
        ChromeButton("关闭", hoverColor = Theme.BadRed.copy(alpha = 0.8f), onClick = onClose) {
            Text("×", fontSize = 15.sp, color = glyphColor)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChromeButton(
    desc: String,
    hoverColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    var hover by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = 38.dp, height = 26.dp)
            .background(if (hover) hoverColor else Color.Transparent, MaterialTheme.shapes.small)
            .clickable { onClick() }
            .onPointerEvent(PointerEventType.Enter) { hover = true }
            .onPointerEvent(PointerEventType.Exit) { hover = false }
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { glyph() }
}
