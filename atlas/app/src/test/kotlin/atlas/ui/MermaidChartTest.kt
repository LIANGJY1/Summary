package atlas.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MermaidChartTest {
    private val docDiagram = listOf(
        "flowchart TB",
        "    Apps[\"应用：业务代码与系统应用\"]",
        "    Framework[\"应用框架：SDK 客户端与 system_server 服务\"]",
        "    Native[\"原生库与 ART：运行时、Bionic、Skia、媒体与原生服务\"]",
        "    HAL[\"HAL：Stable AIDL、存量 HIDL 与厂商实现\"]",
        "    Kernel[\"Linux 内核：调度、内存、Binder、网络与驱动\"]",
        "",
        "    Apps -->|\"SDK 调用 / Binder\"| Framework",
        "    Framework -->|\"JNI / Native Binder\"| Native",
        "    Framework -->|\"Stable AIDL / HIDL\"| HAL",
        "    Native -->|\"系统调用 / ioctl / mmap\"| Kernel",
        "    HAL -->|\"系统调用 / 驱动接口\"| Kernel",
    )

    @Test
    fun `parses layered flowchart from real doc`() {
        val graph = assertNotNull(parseMermaidFlowchart(docDiagram))
        assertEquals(5, graph.nodes.size)
        assertEquals(5, graph.edges.size)
        assertEquals("应用：业务代码与系统应用", graph.nodes.first().second)
        assertEquals("SDK 调用 / Binder", graph.edges.first().third)
    }

    @Test
    fun `layers follow longest path`() {
        val graph = assertNotNull(parseMermaidFlowchart(docDiagram))
        val rows = assertNotNull(mermaidLayerRows(graph))
        assertEquals(listOf(listOf("Apps"), listOf("Framework"), listOf("Native", "HAL"), listOf("Kernel")), rows)
    }

    @Test
    fun `cycle falls back to null`() {
        val graph = parseMermaidFlowchart(listOf("flowchart TB", "A[\"甲\"]", "B[\"乙\"]", "A --> B", "B --> A"))
        assertNull(mermaidLayerRows(assertNotNull(graph)))
    }

    @Test
    fun `unknown syntax falls back to null`() {
        assertNull(parseMermaidFlowchart(listOf("flowchart TB", "A --> |broken")))
        assertNull(parseMermaidFlowchart(listOf("classDiagram", "A <|-- B")))
        assertNull(parseMermaidFlowchart(listOf("flowchart TB", "    A[\"只有节点没有边\"]")))
    }

    @Test
    fun `mid label edge and bare node supported`() {
        val graph = assertNotNull(
            parseMermaidFlowchart(listOf("flowchart TD", "A[\"入口\"]", "B", "A -- \"确认\" --> B")),
        )
        assertEquals(listOf("A" to "入口", "B" to "B"), graph.nodes)
        assertEquals(listOf(Triple("A", "B", "确认")), graph.edges)
    }

    @Test
    fun `declaration must be TB or TD`() {
        assertNull(parseMermaidFlowchart(listOf("flowchart LR", "A[\"甲\"]", "A --> B")))
    }
}
