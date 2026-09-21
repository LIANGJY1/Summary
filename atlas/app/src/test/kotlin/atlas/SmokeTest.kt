package atlas

import atlas.core.Inbox
import atlas.core.IgnoreRules
import atlas.core.Md
import atlas.core.MdStores
import atlas.core.OutboxTasks
import atlas.core.QuestionListModel
import atlas.core.QuestionTags
import atlas.fsrs.FsrsEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.random.Random

class SmokeTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `md 切块以 ## 小节为一等锚点`() {
        val text = """
            # 标题
            卷首内容
            ## 条目一
            条目一正文
            ## 条目二
            条目二正文
        """.trimIndent()
        val chunks = Md.chunksOf("a/b.md", text)
        assertEquals(3, chunks.size)
        assertEquals("条目一", chunks[1].section)
        assertTrue(chunks[1].body.contains("条目一正文"))
    }

    @Test
    fun `卡库写入后读回一致（文件为准）`() {
        val f = File(tmp, "cards.md")
        val cards = listOf(
            MdStores.CardEntry("id1", "卡面A", "卡背A", "Android", "手动", """{"due":"2026-09-20T00:00:00Z"}""", emptyList()),
            MdStores.CardEntry("id2", "卡面B", "卡背B", "面试题", "AI 生成", FsrsEngine.newCardJson(), listOf("log1")),
        )
        MdStores.saveCards(f, cards)
        val back = MdStores.loadCards(f)
        assertEquals(2, back.size)
        assertEquals("卡面A", back[0].front)
        assertEquals("Android", back[0].deck)
        assertEquals("log1", back[1].logs.first())
        assertEquals("id2", back[1].id)
    }

    @Test
    fun `题目标签元数据可被解析`() {
        val f = File(tmp, "questions.md")
        f.writeText(
            """
            # Atlas 面试题库（questions）

            ## Handler 消息分发流程是什么？

            - status: 未测
            - tags: Android系统启动, Handler
            - id: q1
            """.trimIndent(), Charsets.UTF_8,
        )

        val question = MdStores.loadQuestions(f).single()

        assertEquals(listOf("Android系统启动", "Handler"), question.tags)
        MdStores.saveQuestions(f, listOf(question))
        assertEquals(listOf("Android系统启动", "Handler"), MdStores.loadQuestions(f).single().tags)
        assertTrue(f.readText().contains("- tags: Android系统启动, Handler"))
    }

    @Test
    fun `题目标签规范化去重且按标签分组`() {
        val questions = listOf(
            MdStores.QuestionEntry("q1", "启动", "", "未测", emptyList(), tags = listOf(" Handler ", "Android系统启动", "Handler")),
            MdStores.QuestionEntry("q2", "性能", "", "未测", emptyList(), tags = listOf("性能优化")),
            MdStores.QuestionEntry("q3", "未分类", "", "未测", emptyList()),
        )

        val normalized = QuestionTags.normalize(questions[0].tags)
        val groups = QuestionTags.groupByTag(questions)

        assertEquals(listOf("Handler", "Android系统启动"), normalized)
        assertEquals(listOf("启动"), groups["Handler"]?.map { it.q })
        assertEquals(listOf("未分类"), groups["未分类"]?.map { it.q })
        assertEquals(QuestionTags.BUILT_IN, QuestionTags.BUILT_IN.distinct())
    }

    @Test
    fun `题库视图只按标签筛选和分组而不使用来源`() {
        val questions = listOf(
            MdStores.QuestionEntry("q1", "启动题", "", "未测", emptyList(), source = "文章 A", tags = listOf("Handler")),
            MdStores.QuestionEntry("q2", "消息题", "", "未测", emptyList(), source = "文章 B", tags = listOf("Handler")),
            MdStores.QuestionEntry("q3", "性能题", "", "未测", emptyList(), source = "文章 C", tags = listOf("性能优化")),
        )

        val handlerQuestions = QuestionListModel.visible(questions, "Handler")
        val groups = QuestionListModel.groups(questions)

        assertEquals(listOf("q1", "q2"), handlerQuestions.map { it.id })
        assertEquals(listOf("q1", "q2"), groups.getValue("Handler").map { it.id })
    }

    @Test
    fun `题库视图按题面关键词和标签联合筛选`() {
        val questions = listOf(
            MdStores.QuestionEntry("q1", "Handler 如何分发消息？", "", "未测", emptyList(), tags = listOf("Handler")),
            MdStores.QuestionEntry("q2", "Handler 的消息队列如何退出？", "", "未测", emptyList(), tags = listOf("操作系统")),
            MdStores.QuestionEntry("q3", "Android 系统如何启动？", "", "未测", emptyList(), tags = listOf("Android系统启动")),
        )

        val result = QuestionListModel.visible(questions, tag = "Handler", query = "消息")

        assertEquals(listOf("q1"), result.map { it.id })
        assertEquals(questions.map { it.id }, QuestionListModel.visible(questions, query = "").map { it.id })
    }

    @Test
    fun `生成题库按标签数量和随机开关抽取`() {
        val questions = listOf(
            MdStores.QuestionEntry("q1", "一", "", "未测", emptyList(), tags = listOf("Handler")),
            MdStores.QuestionEntry("q2", "二", "", "未测", emptyList(), tags = listOf("Handler")),
            MdStores.QuestionEntry("q3", "三", "", "未测", emptyList(), tags = listOf("性能优化")),
        )

        assertEquals(listOf("q1", "q2"), QuestionListModel.generateSet(questions, "Handler", 2, random = false).map { it.id })
        assertEquals(2, QuestionListModel.generateSet(questions, "全部", 2, random = true, Random(7)).size)
        assertTrue(QuestionListModel.generateSet(questions, "性能优化", 5, random = false).all { "性能优化" in it.tags })
    }

    @Test
    fun `三档边界：工作仓库仅本地、协作目录忽略`() {
        val rules = IgnoreRules(IgnoreRules.DEFAULT_IGNORED, listOf("honda27m-appstore-tools"))
        assertEquals(atlas.core.Tier.IGNORED, rules.tierOf(".git/config"))
        assertEquals(atlas.core.Tier.IGNORED, rules.tierOf("atlas/cards.md"))
        assertEquals(atlas.core.Tier.LOCAL_ONLY, rules.tierOf("honda27m-appstore-tools/README.md"))
        assertEquals(atlas.core.Tier.FULL, rules.tierOf("android/framework/term.md"))
    }

    @Test
    fun `FSRS 新卡立即可复习且评分输出合法状态`() {
        val fresh = FsrsEngine.newCardJson()
        assertTrue(FsrsEngine.isDue(fresh)) // 新卡立即可复习
        val r = FsrsEngine.review(fresh, FsrsEngine.Grade.GOOD)
        assertTrue(r.newJson.contains("due"))
        assertTrue(r.dueEpochMs > 0)
    }

    @Test
    fun `收件箱候选解析与移除`() {
        val dir = File(tmp, "inbox").apply { mkdirs() }
        val f = File(dir, "batch.md")
        f.writeText(
            """
            kind: card
            deck: 网络
            source: AI 生成
            front: 什么是三档隐私边界
            back: 完全忽略 / 仅本地编目 / 全索引

            ---

            kind: question
            q: trigram 分词适合什么场景
            ref: 中文短查询召回
            """.trimIndent(), Charsets.UTF_8,
        )
        val cands = Inbox.scan(dir)
        assertEquals(2, cands.size)
        assertEquals("网络", cands[0].s("deck"))
        Inbox.removeBlock(cands[0])
        val rest = Inbox.scan(dir)
        assertEquals(1, rest.size)
        assertEquals(Inbox.Kind.QUESTION, rest[0].kind)
    }

    @Test
    fun `收件箱候选可识别生成 skill`() {
        val dir = File(tmp, "inbox-skill").apply { mkdirs() }
        val file = File(dir, "article-quiz.md")
        file.writeText(
            """
            kind: question
            source: article-quiz: 性能优化方法论
            q: 什么是性能优化闭环？
            answer: 指标、监控、分析、方案、实验、沉淀
            """.trimIndent(), Charsets.UTF_8,
        )

        val candidate = Inbox.scan(dir).single()

        assertEquals("article-quiz", Inbox.skillName(candidate))
    }

    @Test
    fun `待确认候选顶部 skill 去重排序`() {
        val file = File(tmp, "candidates.md")
        val candidates = listOf(
            Inbox.Candidate(file, Inbox.Kind.QUESTION, mapOf("source" to "article-quiz: 性能"), "a"),
            Inbox.Candidate(file, Inbox.Kind.QUESTION, mapOf("skill" to "source-annotator"), "b"),
            Inbox.Candidate(file, Inbox.Kind.QUESTION, mapOf("source" to "article-quiz: Android"), "c"),
        )

        assertEquals(listOf("article-quiz", "source-annotator"), Inbox.skillNames(candidates))
    }

    @Test
    fun `暂停卡往返且旧格式默认不暂停`() {
        val f = File(tmp, "cards.md")
        MdStores.saveCards(f, listOf(
            MdStores.CardEntry("id1", "卡面A", "卡背A", "Android", "手动", FsrsEngine.newCardJson(), emptyList(), suspended = true),
            MdStores.CardEntry("id2", "卡面B", "卡背B", "网络", "手动", FsrsEngine.newCardJson(), emptyList()),
        ))
        val back = MdStores.loadCards(f)
        assertTrue(back[0].suspended)
        assertFalse(back[1].suspended)
        assertTrue(f.readText().contains("- suspended: true"))
    }

    @Test
    fun `FSRS retention 可自定义可重置`() {
        assertEquals(0.9, FsrsEngine.desiredRetention(), 1e-9)
        FsrsEngine.schedulerFor(0.85)
        assertEquals(0.85, FsrsEngine.desiredRetention(), 1e-9)
        val fresh = FsrsEngine.newCardJson()
        val r = FsrsEngine.review(fresh, FsrsEngine.Grade.GOOD)
        assertTrue(r.newJson.contains("due"))
        FsrsEngine.resetParams()
        assertEquals(0.9, FsrsEngine.desiredRetention(), 1e-9)
    }

    @Test
    fun `OutboxTasks 生成与 done 扫描`() {
        val dir = File(tmp, "outbox").apply { mkdirs() }
        OutboxTasks.writeCardgen(dir, "a/b.md##小节", "请基于以上来源出 3–5 张闪卡候选")
        OutboxTasks.writeCardgen(dir, "c/d.md##另一节", "请基于以上来源出 3–5 张闪卡候选")
        val tasks = OutboxTasks.scanOutbox(dir)
        assertEquals(2, tasks.size)
        assertTrue(tasks.all { it.type == "cardgen" && it.status == "todo" })
        // agent 侧标记 done
        val f = tasks[0].file
        f.writeText(f.readText().replace("- status: todo", "- status: done"), Charsets.UTF_8)
        assertEquals("done", OutboxTasks.scanOutbox(dir).first { it.file == f }.status)
    }
}
