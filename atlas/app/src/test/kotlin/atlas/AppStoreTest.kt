package atlas

import atlas.core.Inbox
import atlas.core.Md
import atlas.core.MdStores
import atlas.fsrs.FsrsEngine
import atlas.index.Indexer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppStoreTest {

    @TempDir
    lateinit var tmp: File

    private fun buildStore(): Pair<AppStore, File> {
        val config = File(tmp, "config-${System.nanoTime()}")
        val store = AppStore(config)
        val root = File(tmp, "lib-${System.nanoTime()}").apply { mkdirs() }
        File(root, "atlas").mkdirs()
        File(root, "issue").mkdirs()
        File(root, "atlas/cards.md").writeText(
            """
            # Atlas 卡库（cards）

            > 本文件由 Atlas 维护，人可直接编辑；以文件内容为准。请保留 `- key: value` 元数据行格式。

            ## 卡A

            - deck: Android
            - source: 手动
            - id: cardA
            - fsrs: ${FsrsEngine.newCardJson()}

            ## 卡B

            - deck: Android
            - source: 手动
            - id: cardB
            - fsrs: ${FsrsEngine.newCardJson()}

            ## 卡C

            - deck: 网络
            - source: 手动
            - id: cardC
            - suspended: true
            - fsrs: ${FsrsEngine.newCardJson()}
            """.trimIndent(), Charsets.UTF_8,
        )
        File(root, "issue/x.md").writeText("# 某次 ANR 排查复盘\n\n## 未竟事项\n\n- Binder 线程池上限没读透\n", Charsets.UTF_8)
        store.settings = store.settings.copy(libraryPath = root.absolutePath)
        store.openLibrary(root.absolutePath, rescanIfNeeded = true)
        // rescan 在 IO 协程异步执行，轮询等扫描落地
        val deadline = System.currentTimeMillis() + 15_000
        while (store.scanning.value && System.currentTimeMillis() < deadline) Thread.sleep(100)
        while (store.notes.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        return store to root
    }

    @Test
    fun `完成率分子分母与指标真实化`() {
        val (store, _) = buildStore()
        val m = store.metrics2()
        assertEquals(2, m.dueEvents30, "暂停卡不应计入到期分母")
        assertEquals(0, m.reviewed30)
        store.grade(FsrsEngine.Grade.GOOD)
        val m2 = store.metrics2()
        assertEquals(1, m2.reviewed30)
        assertEquals(0.5, m2.completionRate, 1e-9, "1 完成 / 2 到期")
        assertEquals(30, m2.daily.size)
        assertTrue(m2.daily.last().second >= 1, "今天应有复习记录")
    }

    @Test
    fun `暂停卡不进复习队列且可恢复`() {
        val (store, _) = buildStore()
        assertEquals(2, store.dueQueue.size)
        store.suspendCard("cardA", true)
        assertEquals(1, store.dueQueue.size)
        store.suspendCard("cardA", false)
        assertEquals(2, store.dueQueue.size)
    }

    @Test
    fun `上一题下一题只切换浏览位置不评分不移除卡片`() {
        val (store, _) = buildStore()
        val before = store.cards.map { it.id to it.fsrsJson }

        assertEquals("卡A", store.currentCard()?.front)
        assertTrue(store.nextReviewCard())
        assertEquals("卡B", store.currentCard()?.front)
        assertFalse(store.nextReviewCard())
        assertEquals(2, store.dueQueue.size)
        assertTrue(store.previousReviewCard())
        assertEquals("卡A", store.currentCard()?.front)
        assertEquals(before, store.cards.map { it.id to it.fsrsJson })
    }

    @Test
    fun `deckStats 按卡组聚合`() {
        val (store, _) = buildStore()
        val android = store.deckStats().single { it.deck == "Android" }
        assertEquals(2, android.total)
        assertEquals(0, android.suspended)
        assertEquals(2, android.due)
        val net = store.deckStats().single { it.deck == "网络" }
        assertEquals(1, net.suspended)
        assertEquals(0, net.due)
    }

    @Test
    fun `会话统计随评分累计`() {
        val (store, _) = buildStore()
        store.grade(FsrsEngine.Grade.GOOD)
        store.grade(FsrsEngine.Grade.AGAIN)
        val s = store.sessionSummary()
        assertEquals(2, s.count)
        assertEquals(1, s.dist["GOOD"])
        assertEquals(1, s.dist["AGAIN"])
    }

    @Test
    fun `文件监听_外部修改自动重载`() {
        val (store, root) = buildStore()
        val before = store.cards.size
        // 模拟第三方进程（agent/编辑器）改写 cards.md
        File(root, "atlas/cards.md").writeText(
            File(root, "atlas/cards.md").readText() +
                "\n\n## 卡D\n\n- deck: 面试题\n- source: 手动\n- id: cardD\n- fsrs: ${FsrsEngine.newCardJson()}\n",
            Charsets.UTF_8,
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && store.cards.size == before) Thread.sleep(200)
        assertEquals(before + 1, store.cards.size, "≤3s 监听应重载新卡")
    }

    @Test
    fun `经验流_复盘目录识别与候选提议`() {
        val (store, root) = buildStore()
        val retro = store.retroCandidates()
        assertEquals(1, retro.size)
        assertEquals("issue/x.md", retro[0].relPath)

        store.proposeFromRetro(retro[0], asCards = true)
        val outbox = File(root, "atlas/outbox").listFiles()?.map { it.name } ?: emptyList()
        assertTrue(outbox.any { it.startsWith("cardgen-") }, "应生成制卡任务")
    }

    @Test
    fun `文章出题候选确认_source落库且向后兼容`() {
        val (store, root) = buildStore()
        File(root, "atlas/inbox").mkdirs()
        File(root, "atlas/inbox/article-quiz-fsrs.md").writeText(
            """
            kind: question
            source: article-quiz: FSRS 论文精读
            q: FSRS 的间隔调度由哪三个核心变量驱动？
            ref: Stability 决定间隔增长速度
              Difficulty 影响评分后的稳定化
              Retrievability 是当前可提取概率
            ---
            kind: card
            deck: 文章学习/FSRS
            source: article-quiz: FSRS 论文精读
            front: FSRS 的三个核心状态变量是？
            back: Stability、Difficulty、Retrievability
            """.trimIndent(), Charsets.UTF_8,
        )
        store.scanInbox()
        assertEquals(2, store.candidates.size)

        val qc = store.candidates.first { it.kind == Inbox.Kind.QUESTION }
        store.confirmQuestionCandidate(qc)
        assertEquals(1, store.questions.size)
        assertEquals("article-quiz: FSRS 论文精读", store.questions[0].source)
        assertTrue(store.questions[0].ref.contains("Retrievability"), "多行 ref 应完整落库")

        val cc = store.candidates.first { it.kind == Inbox.Kind.CARD }
        store.confirmCardCandidate(cc)
        assertTrue(store.cards.any { it.front.startsWith("FSRS 的三个核心") && it.deck == "文章学习/FSRS" })

        // source 写回 questions.md，且重新加载一致（roundtrip）
        val raw = File(root, "atlas/questions.md").readText()
        assertTrue(raw.contains("- source: article-quiz: FSRS 论文精读"))
        store.reloadKnowledgeFiles()
        assertEquals("article-quiz: FSRS 论文精读", store.questions[0].source)

        // 旧格式无 source 行：照常加载，source 为空（向后兼容）
        File(root, "atlas/questions.md").writeText(
            """
            # Atlas 面试题库（questions）

            ## 老题

            - status: 未测
            - ref: 旧要点
            - id: old1
            """.trimIndent(), Charsets.UTF_8,
        )
        store.reloadKnowledgeFiles()
        assertTrue(store.questions.any { it.id == "old1" && it.source.isEmpty() })
    }

    @Test
    fun `旧闪卡候选统一转为题目入库`() {
        val (store, root) = buildStore()
        File(root, "atlas/inbox").mkdirs()
        File(root, "atlas/inbox/legacy-card.md").writeText(
            """
            kind: card
            deck: 文章学习/性能优化
            source: article-quiz: 性能优化方法论
            front: 性能优化闭环的六个环节依次是？
            back: 指标 → 监控 → 分析 → 方案 → 实验 → 沉淀
            ref: 指标、监控、分析、方案、实验、沉淀
            tags: 性能优化, Android系统启动
            """.trimIndent(), Charsets.UTF_8,
        )
        store.scanInbox()

        store.confirmCandidateAsQuestion(store.candidates.single())

        assertEquals(1, store.questions.size)
        assertEquals("性能优化闭环的六个环节依次是？", store.questions.single().q)
        assertEquals("指标 → 监控 → 分析 → 方案 → 实验 → 沉淀", store.questions.single().answer)
        assertEquals("article-quiz: 性能优化方法论", store.questions.single().source)
        assertEquals("指标、监控、分析、方案、实验、沉淀", store.questions.single().ref)
        assertEquals(listOf("性能优化", "Android系统启动"), store.questions.single().tags)
        assertEquals(3, store.cards.size, "确认候选不应新增闪卡")
        assertEquals(0, store.candidates.size)
    }

    @Test
    fun `题目答案落库roundtrip与转闪卡`() {
        val (store, root) = buildStore()
        store.addQuestionsFromText("什么是 FSRS 调度算法？", "间隔调度算法", "测试来源", "FSRS 是新一代间隔重复调度算法，由三个核心变量驱动复习间隔。")
        assertEquals(1, store.questions.size)
        assertEquals("FSRS 是新一代间隔重复调度算法，由三个核心变量驱动复习间隔。", store.questions[0].answer)

        // 答案存小节正文，写盘后重载一致（roundtrip）
        store.reloadKnowledgeFiles()
        assertTrue(store.questions[0].answer.startsWith("FSRS 是新一代"), "答案应从 questions.md 正文加载")
        val raw = File(root, "atlas/questions.md").readText()
        assertTrue(raw.contains("FSRS 是新一代间隔重复调度算法"), "答案应写进 questions.md 正文")

        // 转闪卡：题面→卡面，答案→卡背，deck=来源（题库是信息源，复习从题库派生）
        store.questionToCard(store.questions[0])
        val card = store.cards.first { it.front == "什么是 FSRS 调度算法？" }
        assertEquals("测试来源", card.deck)
        assertTrue(card.back.startsWith("FSRS 是新一代"))

        // 旧条目无答案：转闪卡兜底用批改要点
        File(root, "atlas/questions.md").writeText(
            """
            # Atlas 面试题库（questions）

            ## 老题

            - status: 未测
            - ref: 旧要点内容
            - id: old1
            """.trimIndent(), Charsets.UTF_8,
        )
        store.reloadKnowledgeFiles()
        val old = store.questions.first { it.id == "old1" }
        assertTrue(old.answer.isEmpty())
        store.questionToCard(old)
        assertEquals("旧要点内容", store.cards.first { it.front == "老题" }.back)
    }
}
