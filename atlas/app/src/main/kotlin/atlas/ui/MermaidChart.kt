package atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mermaid flowchart TB/TD 子集的解析结果；超出子集返回 null 回退为代码块展示。节点 = id to 标签，边 = from to 标签。 */
data class MermaidGraph(val nodes: List<Pair<String, String>>, val edges: List<Triple<String, String, String?>>)

private val mermaidNodeDef = Regex("^([A-Za-z0-9_]+)\\[\"([^\"]*)\"\\]$")
private val mermaidEdgeMidLabel = Regex("^([A-Za-z0-9_]+)\\s*--\\s*\"([^\"]*)\"\\s*-->\\s*([A-Za-z0-9_]+)$")
private val mermaidEdgePipeLabel = Regex("^([A-Za-z0-9_]+)\\s*-->\\s*\\|\\s*\"([^\"]*)\"\\s*\\|\\s*([A-Za-z0-9_]+)$")
private val mermaidEdgePlain = Regex("^([A-Za-z0-9_]+)\\s*-->\\s*([A-Za-z0-9_]+)$")
private val mermaidBareNode = Regex("^([A-Za-z0-9_]+)$")
private val mermaidDeclaration = Regex("^(flowchart|graph)\\s+(TB|TD)\\s*$")

/** 解析 flowchart TB/TD 的节点定义、带标签边、普通边与裸节点引用；遇未知语法、缺节点或缺边返回 null。 */
internal fun parseMermaidFlowchart(lines: List<String>): MermaidGraph? {
    var declared = false
    val nodeLabels = linkedMapOf<String, String>()
    val edges = linkedSetOf<Triple<String, String, String?>>()
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("%%")) continue
        if (!declared) {
            if (mermaidDeclaration.matches(line)) declared = true else return null
            continue
        }
        mermaidNodeDef.matchEntire(line)?.let { nodeLabels[it.groupValues[1]] = it.groupValues[2]; continue }
        mermaidEdgeMidLabel.matchEntire(line)?.let {
            edges += Triple(it.groupValues[1], it.groupValues[3], it.groupValues[2]); continue
        }
        mermaidEdgePipeLabel.matchEntire(line)?.let {
            edges += Triple(it.groupValues[1], it.groupValues[3], it.groupValues[2]); continue
        }
        mermaidEdgePlain.matchEntire(line)?.let {
            edges += Triple(it.groupValues[1], it.groupValues[2], null); continue
        }
        mermaidBareNode.matchEntire(line)?.let { nodeLabels.putIfAbsent(it.groupValues[1], it.groupValues[1]); continue }
        return null
    }
    if (!declared || nodeLabels.isEmpty() || edges.isEmpty()) return null
    edges.forEach { (from, to, _) ->
        nodeLabels.putIfAbsent(from, from)
        nodeLabels.putIfAbsent(to, to)
    }
    return MermaidGraph(nodeLabels.toList(), edges.toList())
}

/** Kahn 最长路径分层：返回按层分组的节点 id（保持文档顺序）；存在环或悬空端点返回 null。 */
internal fun mermaidLayerRows(graph: MermaidGraph): List<List<String>>? {
    val ids = graph.nodes.map { it.first }
    val indegree = ids.associateWith { 0 }.toMutableMap()
    val outgoing = ids.associateWith { mutableListOf<String>() }.toMutableMap()
    graph.edges.forEach { (from, to, _) ->
        if (from !in indegree || to !in indegree) return null
        indegree[to] = indegree[to]!! + 1
        outgoing[from]!! += to
    }
    val layer = mutableMapOf<String, Int>()
    val queue = ArrayDeque<String>()
    ids.forEach { id -> if (indegree[id] == 0) { layer[id] = 0; queue += id } }
    var head = 0
    while (head < queue.size) {
        val id = queue[head++]
        val next = layer[id]!! + 1
        for (child in outgoing[id]!!) {
            if (next > (layer[child] ?: -1)) layer[child] = next
            indegree[child] = indegree[child]!! - 1
            if (indegree[child] == 0) queue += child
        }
    }
    if (layer.size != ids.size) return null
    return layer.entries
        .groupBy({ it.value }, { it.key })
        .toSortedMap()
        .values
        .map { row -> row.sortedBy { ids.indexOf(it) } }
}

private class MermaidLayout(
    val nodeTopLefts: Map<String, Offset>,
    val nodeSize: Size,
    val labelLayouts: Map<String, TextLayoutResult>,
    val edges: List<MermaidEdgePlacement>,
    val widthPx: Float,
    val heightPx: Float,
)

private class MermaidEdgePlacement(
    val start: Offset,
    val end: Offset,
    val midY: Float,
    val labelLayout: TextLayoutResult?,
)

private fun computeMermaidLayout(graph: MermaidGraph, rows: List<List<String>>, textMeasurer: TextMeasurer, density: Density): MermaidLayout {
    fun px(dp: androidx.compose.ui.unit.Dp) = with(density) { dp.toPx() }
    val padH = px(20.dp)
    val padV = px(12.dp)
    val nodeGap = px(36.dp)
    val layerGap = px(64.dp)
    val margin = px(10.dp)
    val maxTextWidth = px(230.dp).toInt()

    val nodeTextStyle = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    val measured = graph.nodes.associate { (id, label) ->
        id to textMeasurer.measure(label, nodeTextStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = maxTextWidth))
    }
    val nodeW = measured.values.maxOf { it.size.width } + padH * 2
    val nodeH = measured.values.maxOf { it.size.height } + padV * 2
    val rowWidths = rows.map { row -> row.size * nodeW + (row.size - 1) * nodeGap }
    val totalW = maxOf(rowWidths.max(), nodeW) + margin * 2
    val totalH = rows.size * nodeH + (rows.size - 1) * layerGap + margin * 2

    val topLefts = mutableMapOf<String, Offset>()
    rows.forEachIndexed { rIdx, row ->
        row.forEachIndexed { cIdx, id ->
            val x = margin + (totalW - margin * 2 - rowWidths[rIdx]) / 2 + cIdx * (nodeW + nodeGap)
            topLefts[id] = Offset(x, margin + rIdx * (nodeH + layerGap))
        }
    }
    val edges = graph.edges.map { (from, to, label) ->
        val f = topLefts.getValue(from)
        val t = topLefts.getValue(to)
        val labelLayout = label?.let {
            textMeasurer.measure(it, TextStyle(fontSize = 10.sp), maxLines = 1)
        }
        MermaidEdgePlacement(
            start = Offset(f.x + nodeW / 2, f.y + nodeH),
            end = Offset(t.x + nodeW / 2, t.y),
            midY = (f.y + nodeH + t.y) / 2,
            labelLayout = labelLayout,
        )
    }
    return MermaidLayout(
        nodeTopLefts = topLefts,
        nodeSize = Size(nodeW, nodeH),
        labelLayouts = measured,
        edges = edges,
        widthPx = totalW,
        heightPx = totalH,
    )
}

/** 内置 Mermaid flowchart TB/TD 渲染（零依赖）；解析或分层失败时回退为等宽代码块。 */
@Composable
fun MermaidFlowchartView(lines: List<String>, modifier: Modifier = Modifier) {
    val graph = remember(lines) { parseMermaidFlowchart(lines) }
    val rows = remember(graph) { graph?.let { mermaidLayerRows(it) } }
    if (graph == null || rows == null) {
        Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Theme.CodeBg, tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                lines.forEach { code ->
                    Text(code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        return
    }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val layout = remember(graph, rows, density) { computeMermaidLayout(graph, rows, textMeasurer, density) }
    val nodeTextColor = MaterialTheme.colorScheme.onSurface
    val edgeLabelColor = Theme.Muted
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val nodeFill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val chipColor = Theme.CodeBg

    Box(modifier.fillMaxWidth().clipToBounds().horizontalScroll(rememberScrollState())) {
        Canvas(
            Modifier
                .width(with(density) { layout.widthPx.toDp() })
                .height(with(density) { layout.heightPx.toDp() }),
        ) {
            drawMermaid(layout, nodeTextColor, edgeLabelColor, lineColor, nodeFill, chipColor)
        }
    }
}

private fun DrawScope.drawMermaid(
    layout: MermaidLayout,
    nodeTextColor: Color,
    edgeLabelColor: Color,
    lineColor: Color,
    nodeFill: Color,
    chipColor: Color,
) {
    val corner = CornerRadius(10.dp.toPx())
    layout.edges.forEach { edge ->
        val elbow = Path().apply {
            moveTo(edge.start.x, edge.start.y)
            lineTo(edge.start.x, edge.midY)
            lineTo(edge.end.x, edge.midY)
            lineTo(edge.end.x, edge.end.y - 6.dp.toPx())
        }
        drawPath(elbow, color = lineColor, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
        val arrow = Path().apply {
            moveTo(edge.end.x, edge.end.y)
            lineTo(edge.end.x - 4.dp.toPx(), edge.end.y - 9.dp.toPx())
            lineTo(edge.end.x + 4.dp.toPx(), edge.end.y - 9.dp.toPx())
            close()
        }
        drawPath(arrow, color = lineColor)
        edge.labelLayout?.let { labelLayout ->
            val chipW = labelLayout.size.width + 14.dp.toPx()
            val chipH = labelLayout.size.height + 6.dp.toPx()
            val chipX = (edge.start.x + edge.end.x) / 2 - chipW / 2
            drawRoundRect(chipColor, Offset(chipX, edge.midY - chipH / 2), Size(chipW, chipH), CornerRadius(6.dp.toPx()))
            drawText(
                labelLayout,
                color = edgeLabelColor,
                topLeft = Offset((edge.start.x + edge.end.x) / 2 - labelLayout.size.width / 2, edge.midY - labelLayout.size.height / 2),
            )
        }
    }
    layout.nodeTopLefts.forEach { (id, topLeft) ->
        drawRoundRect(nodeFill, topLeft, layout.nodeSize, corner)
        drawRoundRect(lineColor, topLeft, layout.nodeSize, corner, style = Stroke(width = 1.dp.toPx()))
        // 直接绘制布局阶段缓存的 TextLayoutResult：几何与节点框一致，不会重新测量导致文字溢出重叠
        drawText(
            layout.labelLayouts.getValue(id),
            color = nodeTextColor,
            topLeft = Offset(
                topLeft.x + (layout.nodeSize.width - layout.labelLayouts.getValue(id).size.width) / 2,
                topLeft.y + (layout.nodeSize.height - layout.labelLayouts.getValue(id).size.height) / 2,
            ),
        )
    }
}
