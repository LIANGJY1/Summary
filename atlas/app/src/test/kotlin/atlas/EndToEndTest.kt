package atlas

import atlas.core.IgnoreRules
import atlas.core.Inbox
import atlas.core.MdStores
import atlas.fsrs.FsrsEngine
import atlas.index.Indexer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端集成测试：真实 SQLite + 真实文件流，覆盖 PRD 主闭环。
 * 反复深度验证入口——任何回归先跑这里。
 */
class EndToEndTest {

    @TempDir
    lateinit var tmp: File

    private fun buildLibrary(): File {
        val root = File(tmp, "lib").apply { mkdirs() }
        File(root, "android/framework").mkdirs()
        File(root, "honda27m-appstore-tools").mkdirs()
        File(root, ".git").mkdirs()
        File(root, "atlas").mkdirs()
        File(root, "android/framework/term.md").writeText(
            """
            # Framework 术语
            ## ANR 队列机制
            ANR 的四种类型包括输入超时（5s）与广播超时（前台 10s）。
            ## Binder 线程池
            Binder 线程池默认 16 个线程。
            """.trimIndent(), Charsets.UTF_8,
        )
        File(root, "honda27m-appstore-tools/README.md").writeText("# Honda 工具链（工作敏感，应仅本地）", Charsets.UTF_8)
        File(root, ".git/config").writeText("[core]", Charsets.UTF_8)
        File(root, "atlas/notes.md").writeText("协作目录内容，不应被索引", Charsets.UTF_8)
        return root
    }

    private fun rules() = IgnoreRules(IgnoreRules.DEFAULT_IGNORED, listOf("honda27m-appstore-tools"))

    @Test
    fun `端到端-建库索引与三档边界`() {
        val root = buildLibrary()
        val conn = Indexer.connect(File(tmp, "db/atlas.db"))
        val ix = Indexer(conn)
        ix.rescan(root, rules(), full = true)

        // 完全忽略：.git 与 atlas 协作目录不进 items
        val paths = ix.allItems().map { it.relPath }
        assertTrue(paths.none { it.startsWith(".git") }, ".git 不应被索引")
        assertTrue(paths.none { it.startsWith("atlas/") }, "协作目录不应被索引")
        // 仅本地：编目但不进 FTS
        assertTrue(paths.any { it.startsWith("honda27m") }, "仅本地目录应被编目")
        assertEquals(0, ix.search("Honda 工具链").size, "仅本地内容不应出现在全文检索")
        // 全索引：正常命中
        assertTrue(ix.search("ANR 队列机制").any { it.path == "android/framework/term.md" })
        conn.close()
    }

    @Test
    fun `端到端-双字词LIKE兜底与上下文包`() {
        val root = buildLibrary()
        val conn = Indexer.connect(File(tmp, "db/atlas.db"))
        val ix = Indexer(conn)
        ix.rescan(root, rules(), full = true)
        // ≥3 字走 trigram MATCH
        assertTrue(ix.search("ANR 队列机制").isNotEmpty())
        // <3 字（"队列"是 2 字）走 LIKE 兜底且必须命中
        val two = ix.search("队列")
        assertTrue(two.any { it.path == "android/framework/term.md" }, "双字词必须由 LIKE 兜底命中")
        // 上下文包素材非空
        assertTrue(ix.contextChunks("ANR 队列机制").isNotEmpty())
        conn.close()
    }

    @Test
    fun `端到端-复盘目录名识别（无 frontmatter 也标记）`() {
        val root = buildLibrary()
        File(root, "issue").mkdirs()
        File(root, "issue/2026-w38.md").writeText("# 第 38 周复盘\n\n## 未竟事项\n\n- Binder 没读透\n", Charsets.UTF_8)
        val conn = Indexer.connect(File(tmp, "db/atlas.db"))
        val ix = Indexer(conn)
        ix.rescan(root, rules(), full = true)
        val marked = ix.allItems().filter { it.marker == atlas.core.DocMarker.RETROSPECTIVE }.map { it.relPath }
        assertTrue(marked.contains("issue/2026-w38.md"), "issue/ 目录复盘应被识别，实际：$marked")
        conn.close()
    }

    @Test
    fun `端到端-制卡与收件箱确认门`() {
        val root = buildLibrary()
        val cardsF = File(root, "atlas/cards.md")

        // agent 出卡 → inbox → 确认
        val inboxDir = File(root, "atlas/inbox").apply { mkdirs() }
        File(inboxDir, "batch.md").writeText(
            """
            kind: card
            deck: Framework
            source: AI 生成
            front: ANR 的前台广播超时是多少秒
            back: 前台广播 10 秒
            """.trimIndent(), Charsets.UTF_8,
        )
        val cands = Inbox.scan(inboxDir)
        assertEquals(1, cands.size)
        // 模拟确认（等价 store.confirmCardCandidate 的核心两步）
        val c = cands[0]
        val card = MdStores.CardEntry(
            atlas.core.Md.md5(c.s("front") + c.s("deck")), c.s("front"), c.s("back"),
            c.s("deck"), c.s("source").ifBlank { "收件箱" }, FsrsEngine.newCardJson(), emptyList(),
        )
        MdStores.saveCards(cardsF, listOf(card))
        Inbox.removeBlock(c)
        assertEquals(0, Inbox.scan(inboxDir).size)
        val loaded = MdStores.loadCards(cardsF)
        assertEquals(1, loaded.size)
        assertTrue(FsrsEngine.isDue(loaded[0].fsrsJson))

        assertTrue(cardsF.isFile)
    }
}
