@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package atlas.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogProperties
import atlas.AppStore
import atlas.SourceQuestionGitDiff
import atlas.sourceQuestionGitKey
import atlas.core.KnowledgeTree
import atlas.core.KnowledgeTreeNode
import atlas.core.Log
import atlas.core.MdStores.QuestionEntry
import atlas.core.QuestionListModel
import atlas.core.QuestionReorder
import atlas.core.QuestionTags
import atlas.core.ReorderSlot
import atlas.core.QuestionStatus
import atlas.core.SourceQuestions
import atlas.core.TextDiff
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** 学习页只负责闪卡复习与卡片浏览；题目管理位于独立的「题库」页。 */
@Composable
fun LearningView(store: AppStore, section: String, onSectionChange: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
            Text("复习", color = Theme.MdH1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
        }
        VDivider()
        ReviewSection(store)
    }
}

@Composable
private fun GenerateQuestionSetDialog(
    questions: List<QuestionEntry>,
    tags: List<String>,
    onDismiss: () -> Unit,
) {
    var countText by remember { mutableStateOf("5") }
    var sourceTag by remember { mutableStateOf("全部") }
    var random by remember { mutableStateOf(true) }
    var generated by remember { mutableStateOf<List<QuestionEntry>>(emptyList()) }
    val count = countText.toIntOrNull()?.coerceIn(1, 100) ?: 5
    val candidates = QuestionListModel.visible(questions, sourceTag)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成题库") },
        confirmButton = {
            Button(onClick = {
                generated = QuestionListModel.generateSet(questions, sourceTag, count, random)
            }) { Text(if (generated.isEmpty()) "生成" else "重新生成") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("关闭") } },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("从现有题库抽取一套练习题，不会修改原题库。", fontSize = 12.sp, color = Theme.Muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("数量", fontSize = 13.sp)
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { value -> if (value.all { it.isDigit() } && value.length <= 3) countText = value },
                        modifier = Modifier.width(90.dp),
                        singleLine = true,
                    )
                    Text("最多 100 道", fontSize = 11.sp, color = Theme.Muted)
                }
                Text("题目范围", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sourceTag == "全部", onClick = { sourceTag = "全部" })
                    Text("全部")
                    RadioButton(selected = sourceTag != "全部", onClick = { if (sourceTag == "全部") sourceTag = tags.firstOrNull { it != "全部" } ?: "全部" })
                    Text("按标签")
                }
                if (sourceTag != "全部") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.filter { it != "全部" }.forEach { tag ->
                            Text(
                                tag,
                                Modifier
                                    .clickable { sourceTag = tag }
                                    .background(if (sourceTag == tag) Theme.Selected else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.small)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                fontSize = 11.sp,
                                color = if (sourceTag == tag) Theme.Accent else Theme.Muted,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = random, onCheckedChange = { random = it })
                    Text("随机抽取", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("可用题目 ${candidates.size} 道", fontSize = 11.sp, color = Theme.Muted)
                }
                if (generated.isNotEmpty()) {
                    VDivider()
                    Text("已生成 ${generated.size} 道", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Theme.Accent)
                    generated.forEachIndexed { index, question ->
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Theme.Panel, MaterialTheme.shapes.small)
                                .padding(10.dp),
                        ) {
                            Text("${index + 1}. ${question.q}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (question.tags.isNotEmpty()) Text(question.tags.joinToString("、"), fontSize = 10.sp, color = Theme.Muted)
                        }
                    }
                } else if (candidates.isEmpty()) {
                    Text("当前范围没有可抽取的题目。", fontSize = 12.sp, color = Theme.WarnOrange)
                }
            }
        },
    )
}

// ---------------- 题库（中心信息源：题目+答案，派生面试/闪卡/复习） ----------------

/** 遗留题库卡的阅读宽度。 */
private val QuizContentWidth = 880.dp
/** 同源题库卡在 1040dp 页面列内保留每侧 24dp 留白。 */
private val SourceQuestionContentWidth = 992.dp

@Composable
private fun LegacyQuestionSection(store: AppStore) {
    var showImport by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuestionEntry?>(null) }
    var deleting by remember { mutableStateOf<QuestionEntry?>(null) }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) } // 支持同时展开多个条目
    var query by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf("全部") }
    var grouped by remember { mutableStateOf(false) }
    val tags = listOf("全部") + (QuestionTags.BUILT_IN + store.questions.flatMap { it.tags }).distinct().sorted()
    val visible = QuestionListModel.visible(store.questions, tagFilter, query)
    val visibleOrder = visible.mapIndexed { index, question -> question.id to (index + 1) }.toMap()
    val visibleGroups: List<Pair<String, List<QuestionEntry>>> = if (grouped) {
        QuestionListModel.groups(visible).map { it.key to it.value }
    } else {
        listOf("" to visible)
    }
    val untested = store.questions.count { it.status == "未测" }
    val retest = store.questions.count { it.status == "待复测" }
    val stable = store.questions.count { it.status == "已稳定" }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索题目关键词…") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Theme.InputBg,
                unfocusedContainerColor = Theme.InputBg,
                focusedBorderColor = Theme.Accent.copy(alpha = 0.82f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f),
                cursorColor = Theme.Accent,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { Log.d("打开导入题单对话框"); showImport = true }) { Text("+ 导入题单") }
            OutlinedButton(onClick = { Log.d("打开生成题库对话框"); showGenerate = true }) { Text("生成题库") }
            Text("点击题目展开详情与操作", fontSize = 11.sp, color = Theme.Muted)
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("共 ${store.questions.size} 题 · 未测 $untested · 待复测 $retest · 已稳定 $stable", fontSize = 11.sp, color = Theme.Muted)
            Spacer(Modifier.weight(1f))
            Text("标签:", fontSize = 11.sp, color = Theme.Muted)
            tags.forEach { tag ->
                Text(tag, Modifier
                    .clickable { tagFilter = tag }
                    .background(if (tagFilter == tag) Theme.Selected else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp,
                    color = if (tagFilter == tag) Theme.Accent else Theme.Muted)
            }
            Spacer(Modifier.weight(1f))
            Text(if (grouped) "列表视图" else "按标签分组", Modifier.clickable { grouped = !grouped }, fontSize = 11.sp, color = Theme.Accent)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (visible.isEmpty()) {
                item { Text("没有匹配的题目。", fontSize = 13.sp, color = Theme.Muted) }
            }
            visibleGroups.forEach { (group, questions) ->
                if (grouped) {
                    item(key = "group:$group") {
                        Text("$group（${questions.size}）", Modifier.padding(top = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Theme.Accent)
                    }
                }
                items(questions, key = { if (grouped) "$group:${it.id}" else it.id }) { q ->
                val expanded = q.id in expandedIds
                val order = visibleOrder[q.id] ?: 0
                Column(
                    Modifier.fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = QuizContentWidth)
                        .background(
                            // 展开只增加内容，不切换整块色板，避免列表出现刺眼的大色块。
                            Theme.Panel,
                            MaterialTheme.shapes.small,
                        )
                        .clickable {
                            Log.d("题目条目点击 ${q.q.take(16)} 展开=$expanded")
                            expandedIds = if (expanded) expandedIds - q.id else expandedIds + q.id
                        }
                        .padding(8.dp),
                ) {
                    // 收起态：题面 + 状态。操作全部收进展开详情，列表保持一眼可扫
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "$order",
                            modifier = Modifier.width(28.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Theme.Muted,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                q.q,
                                fontSize = store.settings.questionFontSize.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (expanded) Theme.MdH2 else MaterialTheme.colorScheme.onSurface,
                            )
                            if (q.tags.isNotEmpty()) Text("标签：${q.tags.joinToString("、")}", fontSize = 10.sp, color = Theme.Muted, maxLines = 1)
                        }
                        StatusChip(
                            q.status,
                            when (q.status) { "已稳定" -> Theme.OkGreen; "待复测" -> Theme.WarnOrange; else -> Theme.Muted },
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(6.dp))
                        VDivider()
                        Spacer(Modifier.height(6.dp))
                        // 展开后直接显示答案，不再增加“显示答案”的二次点击。
                        if (q.answer.isNotBlank()) {
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text("答案", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Theme.MdH2)
                                Spacer(Modifier.height(6.dp))
                                CompositionLocalProvider(
                                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                                ) {
                                    // 题库答案行宽随卡面内容列（QuizContentWidth）收窄：长行是阅读疲劳主因
                                    MarkdownText(q.answer, maxWidth = QuizContentWidth)
                                }
                            }
                        } else {
                            Text("还没有答案 · 点「编辑」补全（转闪卡与复习要用）", fontSize = 11.sp, color = Theme.WarnOrange)
                        }
                        if (q.ref.isNotBlank()) Text("参考要点:${q.ref.take(120)}", fontSize = 10.sp, color = Theme.Muted, maxLines = 3)
                        q.logs.takeLast(3).forEach { Text("- $it", fontSize = 10.sp, color = Theme.Muted, maxLines = 1) }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("转闪卡复习", Modifier.clickable { store.questionToCard(q) }, fontSize = 13.sp, color = Theme.OkGreen)
                            Text("编辑", Modifier.clickable { Log.d("打开题目编辑对话框 ${q.q.take(16)}"); editing = q }, fontSize = 13.sp, color = Theme.Muted)
                            Text("删除", Modifier.clickable { Log.d("打开题目删除确认 ${q.q.take(16)}"); deleting = q }, fontSize = 13.sp, color = Theme.BadRed)
                        }
                    }
                }
            }
            }
        }
    }
    if (showImport) ImportQuestionsDialog(store) { showImport = false }
    if (showGenerate) GenerateQuestionSetDialog(store.questions, tags) { showGenerate = false }
    editing?.let { q -> EditQuestionDialog(store, q) { editing = null } }
    deleting?.let { q ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除题目") },
            text = { Text("确定删除「${q.q.take(30)}」？") },
            confirmButton = { Button(onClick = { store.deleteQuestion(q.id); deleting = null }) { Text("删除") } },
            dismissButton = { OutlinedButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

/** 同源题目页：左侧映射 knowledge-base 文档，题目内容直接来自当前源 Markdown。 */
private sealed interface SourceDocumentItem {
    val startOffset: Int

    data class Question(val entry: SourceQuestions.Entry) : SourceDocumentItem {
        override val startOffset: Int get() = entry.startOffset
    }

    data class Section(val heading: SourceQuestions.SectionHeading) : SourceDocumentItem {
        override val startOffset: Int get() = heading.startOffset
    }
}

private enum class QuestionSearchScope(val label: String) {
    CURRENT("当前文件"),
    ALL("整个目录树"),
}

/** 列表 key 必须区分同一源文件中重复题号的不同题目。 */
internal fun sourceQuestionKey(entry: SourceQuestions.Entry): String =
    "${entry.sourcePath}#${entry.startOffset}"

internal fun adjacentQuestionIndex(currentIndex: Int, total: Int, delta: Int): Int? {
    val next = currentIndex + delta
    return next.takeIf { total > 0 && it in 0 until total }
}

internal fun safeQuestionIndex(currentIndex: Int, total: Int): Int? =
    currentIndex.takeIf { total > 0 }?.coerceIn(0, total - 1)

internal fun canNavigateQuestionEditor(isSaving: Boolean, targetIndex: Int, total: Int): Boolean =
    !isSaving && targetIndex in 0 until total

@Composable
fun QuestionSection(store: AppStore, rootFocus: FocusRequester) {
    val ui = atlasUiTokens()
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedDirs by remember { mutableStateOf(setOf("knowledge-base")) }
    var renameTarget by remember { mutableStateOf<KnowledgeTreeNode?>(null) }
    var editingEntry by remember { mutableStateOf<SourceQuestions.Entry?>(null) }
    var deletingEntry by remember { mutableStateOf<SourceQuestions.Entry?>(null) }
    var movingEntry by remember { mutableStateOf<SourceQuestions.Entry?>(null) }
    var showCreateSingle by remember { mutableStateOf(false) }
    var showCreateBatch by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    var searchScope by remember { mutableStateOf(QuestionSearchScope.ALL) }
    var sidebarExpanded by remember { mutableStateOf(true) }
    var sidebarWidth by remember { mutableStateOf(320.dp) }
    var sidebarDragging by remember { mutableStateOf(false) }
    var reorderMode by remember { mutableStateOf(false) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    var dragPointerY by remember { mutableStateOf(0f) }
    var dragGrabOffset by remember { mutableStateOf(0f) }
    // 搜索栏默认隐藏，Ctrl+Shift+F 召出/收起（状态在 store：跨页签保留，根窗口统一处理按键）。
    // 收起即清词与范围——否则会留下看不见的过滤条件继续生效。
    val searchFocusRequester = remember { FocusRequester() }
    // 搜索区与页面根的窗口坐标：供"点击搜索区之外自动收起"判定
    var searchRectInWindow by remember { mutableStateOf<Rect?>(null) }
    var pageOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(store.questionSearchVisible.value) {
        if (store.questionSearchVisible.value) {
            searchFocusRequester.requestFocus()
        } else {
            query = ""
            searchScope = QuestionSearchScope.ALL
            // 收起可能来自键盘、点击外部等多条路径，但都汇到这一个状态：无论哪条都要把焦点
            // 交还根节点——焦点悬空后后续按键到不了任何处理层，Ctrl+Shift+F 会"失灵"
            runCatching { rootFocus.requestFocus() }
        }
    }
    // 拖拽几何一律现读 layoutInfo：它只含本帧已组合的项，随滚动自然失效，不会残留陈旧坐标。
    // 绝不要为它再挂一份逐卡缓存——卡片滚出组合范围后缓存会冻结，目标位次就会跟着漂移。
    val listState = rememberLazyListState()
    val rowSpacingPx = with(LocalDensity.current) { 6.dp.toPx() }
    var listViewportHeight by remember { mutableStateOf(0f) }
    // AppStore 在原地 clear/addAll 题目列表；取不可变快照作为缓存与手势 key，确保换文档后失效。
    val sourceQuestionsSnapshot = store.sourceQuestions.toList()
    val searchPool = if (searchScope == QuestionSearchScope.ALL) store.allSourceQuestions else sourceQuestionsSnapshot
    val visible = if (query.isBlank()) sourceQuestionsSnapshot else searchPool.filter { entry ->
        entry.question.contains(query.trim(), ignoreCase = true) ||
            entry.tags.any { it.contains(query.trim(), ignoreCase = true) }
    }
    // layoutInfo 的下标是 documentItems 的下标，其中夹着章节行，与题目下标并不一致；
    // 一律经 key 换算，避免「非排序模式下多出章节行」导致位次整体错位。
    val questionIndexByKey = remember(sourceQuestionsSnapshot) {
        sourceQuestionsSnapshot.mapIndexed { index, entry -> sourceQuestionKey(entry) to index }.toMap()
    }
    val canReorderList = reorderMode && query.isBlank() && visible.size == sourceQuestionsSnapshot.size
    val dragging = reorderMode && draggingKey != null && query.isBlank()
    val renderedQuestions = if (dragging) {
        val from = visible.indexOfFirst { sourceQuestionKey(it) == draggingKey }
        val to = dragTargetIndex
        if (from >= 0 && to != null && to in visible.indices) {
            visible.toMutableList().apply { add(to, removeAt(from)) }
        } else visible
    } else visible
    val documentItems = buildList<SourceDocumentItem> {
        renderedQuestions.forEach { add(SourceDocumentItem.Question(it)) }
        if (query.isBlank() && !dragging) {
            store.sourceSections.forEach { add(SourceDocumentItem.Section(it)) }
        }
    }
        // 兜底去重：并发重载的竞态若漏进重复条目，key 冲突会让整个列表崩溃
        .distinctBy {
            when (it) {
                is SourceDocumentItem.Question -> sourceQuestionKey(it.entry)
                is SourceDocumentItem.Section -> "section:${it.heading.startOffset}"
            }
        }
        .let { items ->
            // 拖拽中必须保持实时渲染顺序；按 startOffset 排序会把顺序打回文档序，其他卡片永远不动
            if (dragging) items else items.sortedBy { it.startOffset }
    }
    val mappedDocuments = remember(store.knowledgeDocuments.toList(), store.settings.sourceQuestionPaths) {
        SourceQuestions.supportedDocuments(store.knowledgeDocuments, store.settings.sourceQuestionPaths)
    }
    val mappedReadmeDocuments = remember(store.knowledgeDocuments.toList(), store.settings.sourceQuestionPaths) {
        SourceQuestions.supportedReadmeDocuments(store.knowledgeDocuments, store.settings.sourceQuestionPaths)
    }
    val treeDocuments = remember(mappedDocuments, mappedReadmeDocuments) {
        (mappedDocuments + mappedReadmeDocuments).distinct().sorted()
    }
    val knowledgeTree = remember(treeDocuments) {
        KnowledgeTree.build(treeDocuments)
    }
    val selectedMappedDocument = store.selectedSourcePath.takeIf { it in mappedDocuments }.orEmpty()
    val selectedReadmeDocument = store.selectedSourcePath.takeIf { it in mappedReadmeDocuments }
    LaunchedEffect(selectedReadmeDocument) {
        if (selectedReadmeDocument != null) store.questionSearchVisible.value = false
    }

    fun locateSource(path: String) {
        expandedDirs = expandedDirs + setOf("knowledge-base") + KnowledgeTree.ancestorPaths(path)
        store.selectSourceDocument(path)
    }

    fun resolveDragTarget() {
        val key = draggingKey ?: return
        val base = sourceQuestionsSnapshot
        val from = questionIndexByKey[key] ?: return
        val current = dragTargetIndex ?: from
        val target = QuestionReorder.resolveTargetIndex(
            current = current,
            grabY = dragPointerY - dragGrabOffset,
            slots = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                val itemKey = info.key as? String ?: return@mapNotNull null
                val questionIndex = questionIndexByKey[itemKey] ?: return@mapNotNull null
                ReorderSlot(questionIndex, info.offset.toFloat(), info.size)
            },
            total = base.size,
            hysteresis = rowSpacingPx / 2f,
        )
        if (target != current) dragTargetIndex = target
    }

    LaunchedEffect(store.selectedSourcePath, mappedDocuments) {
        expandedDirs = expandedDirs + KnowledgeTree.ancestorPaths(store.selectedSourcePath)
    }

    val treeWidth by animateDpAsState(
        targetValue = if (sidebarExpanded) sidebarWidth else 0.dp,
        // 拖拽调宽时 snap 跟手，收起/展开仍走 tween
        animationSpec = if (sidebarDragging) snap() else tween(200),
        label = "tree-sidebar-width",
    )
    val sidebarChevronRotation by animateFloatAsState(
        targetValue = if (sidebarExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "tree-sidebar-chevron",
    )
    // 目录侧栏通栏到窗沿（无页面留白、无圆角），右缘分隔线即面板边界——与设置/工具页侧栏同构；
    // 页面留白改由内容区自担，避免「圆角浮岛」四周割离的观感（2026-09-29 用户反馈）。
    Row(
        Modifier.fillMaxSize()
            .onGloballyPositioned { pageOriginInWindow = it.positionInWindow() }
            // 搜索栏可见时，点击搜索区之外（树、题卡、空白处）即收起；不消费事件，点击照常生效
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val press = event.changes.firstOrNull()
                        if (
                            event.type == PointerEventType.Press &&
                            store.questionSearchVisible.value &&
                            press != null
                        ) {
                            val rect = searchRectInWindow
                            if (rect?.contains(pageOriginInWindow + press.position) != true) {
                                Log.d("点击搜索区之外，收起题库搜索栏")
                                store.questionSearchVisible.value = false
                            }
                        }
                    }
                }
            },
    ) {
        Column(
            Modifier.width(treeWidth).clipToBounds().fillMaxHeight()
                .background(Theme.Panel)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (sidebarExpanded) {
                Text("知识库文档", fontWeight = FontWeight.Bold, color = Theme.MdH1)
                Text("题目与 README · ${treeDocuments.size} 篇", fontSize = 12.sp, color = Theme.Muted)
                if (treeDocuments.isEmpty()) {
                    Text("当前配置没有匹配的 Markdown 文档，请到设置中添加文件或目录。", fontSize = 11.sp, color = Theme.WarnOrange)
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item(key = knowledgeTree.path) {
                        KnowledgeTreeNodeView(
                            node = knowledgeTree,
                            depth = 0,
                            expandedDirs = expandedDirs,
                            selectedPath = store.selectedSourcePath,
                            onToggleDirectory = { path ->
                                expandedDirs = if (path in expandedDirs) expandedDirs - path else expandedDirs + path
                            },
                            onSelectFile = { path ->
                                expanded = emptySet()
                                query = ""
                                locateSource(path)
                            },
                            onRename = { node -> if (node.path != "knowledge-base") renameTarget = node },
                        )
                    }
                }
            }
        }
        // 拖拽手柄：贴着目录右缘，中间画 1dp 分隔线，热区加宽到 9dp；悬停/拖拽时高亮
        val resizeCursor = remember { PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR)) }
        val handleInteraction = remember { MutableInteractionSource() }
        val handleHovered by handleInteraction.collectIsHoveredAsState()
        val sidebarDensity = LocalDensity.current
        Box(
            Modifier.width(9.dp).fillMaxHeight()
                .hoverable(handleInteraction)
                .pointerHoverIcon(if (sidebarExpanded) resizeCursor else PointerIcon.Default)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { sidebarDragging = true },
                        onDragEnd = { sidebarDragging = false },
                        onDragCancel = { sidebarDragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        sidebarWidth = with(sidebarDensity) { sidebarWidth + dragAmount.toDp() }
                            .coerceIn(220.dp, 520.dp)
                    }
                },
        ) {
            Box(
                Modifier.width(1.dp).fillMaxHeight().align(Alignment.Center)
                    .background(
                        if (sidebarDragging || handleHovered) Theme.Accent
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                    ),
            )
        }
        Box(Modifier.fillMaxHeight().width(24.dp), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier.padding(top = 2.dp)
                    .size(24.dp)
                    .clickable { sidebarExpanded = !sidebarExpanded },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    TreeChevronIcon,
                    contentDescription = if (sidebarExpanded) "收起目录" else "展开目录",
                    modifier = Modifier.size(14.dp).rotate(sidebarChevronRotation),
                    tint = Theme.Muted,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            // 侧栏改通栏后页面留白由内容区自担（左侧间距已由手柄+间隔提供）
            // 底部只留 4dp：滚到底的余量由列表 contentPadding（12dp）一层提供，
            // 两层叠加会让最后一张卡片与底边之间出现大段空白（2026-09-29 用户反馈）
            Modifier.weight(1f).fillMaxHeight()
                .padding(top = ui.spacing.page, end = ui.spacing.page, bottom = 4.dp),
        ) {
            Column(
                // 页面列（搜索/工具行/题卡）与库内阅读流共用 1040dp 阅读网格，宽窗下整列居中；
                // 卡片边框因此贴合内容，不再出现 1240 宽卡 + 卡内 1040 文字的两侧空带
                Modifier.widthIn(max = ui.readingMaxWidth).fillMaxWidth().fillMaxHeight().align(Alignment.Center),
            ) {
        if (selectedReadmeDocument != null) {
            key(selectedReadmeDocument) {
                ReadmeDocumentView(
                    store = store,
                    path = selectedReadmeDocument,
                    content = store.sourceReadmeContent.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
        // 搜索栏（输入框 + 范围行）默认隐藏，Ctrl+Shift+F 召出并聚焦；点击其外任意区域自动收起
        if (store.questionSearchVisible.value) {
            Column(
                Modifier.fillMaxWidth().onGloballyPositioned { searchRectInWindow = it.boundsInWindow() },
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                    placeholder = { Text("搜索题目关键词…") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Theme.InputBg,
                        unfocusedContainerColor = Theme.InputBg,
                        focusedBorderColor = Theme.Accent.copy(alpha = 0.82f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f),
                        cursorColor = Theme.Accent,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("搜索范围", style = ui.typography.secondary, color = Theme.Muted)
                    QuestionSearchScope.entries.forEach { scope ->
                        val active = searchScope == scope
                        Text(
                            scope.label,
                            Modifier
                                .clickable { searchScope = scope }
                                .background(if (active) Theme.Selected else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.small)
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            fontSize = ui.typography.secondary.fontSize,
                            color = if (active) Theme.Accent else Theme.Muted,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("同源题库", style = ui.typography.sectionTitle, color = Theme.MdH1)
            Text("${if (query.isBlank()) store.sourceQuestions.size else visible.size} 题", style = ui.typography.caption, color = Theme.Muted)
            Spacer(Modifier.weight(1f))
            if (query.isBlank()) {
                OutlinedButton(
                    onClick = {
                        reorderMode = !reorderMode
                        if (reorderMode) expanded = emptySet() // 收起展开的答案，拖拽行高一致
                        draggingKey = null
                        dragTargetIndex = null
                        dragPointerY = 0f
                        dragGrabOffset = 0f
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(if (reorderMode) "完成排序" else "调整顺序", fontSize = 12.sp)
                }
            }
            Box {
                OutlinedButton(
                    onClick = { showMoreActions = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                ) { Text("更多", fontSize = 12.sp) }
                DropdownMenu(
                    expanded = showMoreActions,
                    onDismissRequest = { showMoreActions = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("新建单个题目") },
                        onClick = { showMoreActions = false; showCreateSingle = true },
                    )
                    DropdownMenuItem(
                        text = { Text("批量新建题目") },
                        onClick = { showMoreActions = false; showCreateBatch = true },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            SelectionContainer {
                Text(
                    store.selectedSourcePath,
                    style = ui.typography.caption,
                    color = Theme.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "复制",
                style = ui.typography.caption,
                color = Theme.Accent,
                modifier = Modifier.clickable {
                    val absolute = store.sourceQuestionFile().absolutePath
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(absolute), null)
                    Log.i("题库源文档绝对路径已复制 $absolute")
                    store.showToast("已复制绝对路径")
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        if (SourceQuestions.isSupportedPath(store.selectedSourcePath, store.settings.sourceQuestionPaths)) {
            Text(
                if (reorderMode) "排序模式：按住任意题目卡片拖动换位，松开后自动保存并重新编号。"
                else if (query.isBlank()) "点击题目显示答案；橙色标记 = 内容相对 git 最近提交有改动；需要调整顺序时点击右上角「调整顺序」。"
                else "点击题目显示答案；搜索结果仅供查看，清空搜索后可调整顺序。",
                fontSize = 11.sp,
                color = Theme.Muted,
            )
        } else {
            Text("该文档已纳入目录映射，但当前版本暂未接入 Q 题目解析。", fontSize = 11.sp, color = Theme.WarnOrange)
        }
        Spacer(Modifier.height(8.dp))
        // 拖拽手势挂在列表容器上，不能挂在卡片上：LazyColumn 会回收视口外的 item，卡片一旦被回收，
        // 挂在它身上的 pointerInput 节点随之销毁，onDragCancel 触发——用户「抓着卡片滚动」滚到一半，
        // 拖拽就断了。挂在容器上则与单个卡片的存亡无关。
        LazyColumn(
            Modifier.fillMaxSize()
                .onGloballyPositioned { listViewportHeight = it.size.height.toFloat() }
                .pointerInput(reorderMode, query, sourceQuestionsSnapshot) {
                    if (!canReorderList) return@pointerInput
                    detectDragGestures(
                        onDragStart = { pos ->
                            // 指针落在哪个槽位就抓哪张卡；章节行不是题目，不参与重排
                            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                pos.y >= info.offset && pos.y < info.offset + info.size
                            }
                            val key = hit?.key as? String
                            val from = key?.let { questionIndexByKey[it] }
                            if (hit == null || from == null) return@detectDragGestures
                            draggingKey = key
                            dragTargetIndex = from
                            dragGrabOffset = pos.y - hit.offset
                            dragPointerY = hit.offset + dragGrabOffset
                        },
                        onDragEnd = {
                            val key = draggingKey
                            val target = dragTargetIndex
                            if (key != null && target != null) {
                                val from = questionIndexByKey[key]
                                if (from != null && target != from) {
                                    store.reorderSourceQuestions(sourceQuestionsSnapshot, from, target)
                                }
                            }
                            draggingKey = null
                            dragTargetIndex = null
                            dragPointerY = 0f
                            dragGrabOffset = 0f
                        },
                        onDragCancel = {
                            draggingKey = null
                            dragTargetIndex = null
                            dragPointerY = 0f
                            dragGrabOffset = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragPointerY += dragAmount.y
                            resolveDragTarget()
                        },
                    )
                },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(7.dp),
            // 底部留白：滚到底时最后一张卡片不贴死视口底边（否则看起来像被截断）
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (visible.isEmpty()) item { Text("没有匹配的题目。", fontSize = 13.sp, color = Theme.Muted) }
            items(documentItems, key = { item ->
                when (item) {
                    is SourceDocumentItem.Question -> sourceQuestionKey(item.entry)
                    is SourceDocumentItem.Section -> "section:${item.heading.startOffset}"
                }
            }) { item ->
                if (item is SourceDocumentItem.Section) {
                    // 章节分隔：标签式细线（小竖条 + 强调色小标题 + 延伸细线），不再是整宽填充横幅——
                    // 章节是文档结构元数据，安静但可扫读，不与题目卡片争层级（2026-09-29 用户反馈）。
                    Row(
                        Modifier.animateItem()
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(3.dp).height(13.dp).background(Theme.Accent.copy(alpha = 0.8f), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(item.heading.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Theme.Accent)
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.weight(1f).height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
                        )
                    }
                    return@items
                }
                val entry = (item as SourceDocumentItem.Question).entry
                val entryKey = sourceQuestionKey(entry)
                val displayIndex = renderedQuestions.indexOfFirst { sourceQuestionKey(it) == entryKey }
                val isExpanded = entryKey in expanded
                val isDragging = draggingKey == entryKey
                val cardTopTapHeightPx = with(LocalDensity.current) { 12.dp.toPx() }
                // 内容相对 git HEAD 有未提交改动：橙色边框 + Q 标签 + 行内着色（比对异步完成，加载中不标色）
                val gitDiff = store.sourceQuestionGitDiffs[sourceQuestionGitKey(entry)]
                val gitDirty = gitDiff?.changed == true
                val cardElevation by animateDpAsState(
                    targetValue = if (isDragging) 12.dp else 0.dp,
                    animationSpec = tween(180),
                    label = "question-card-elevation",
                )
                val cardScale by animateFloatAsState(
                    targetValue = if (isDragging) 1.015f else 1f,
                    animationSpec = tween(180),
                    label = "question-card-scale",
                )
                val cardInteraction = remember { MutableInteractionSource() }
                val cardHovered by cardInteraction.collectIsHoveredAsState()
                Column(
                    Modifier
                        // 让位卡片：拖拽进行中全部即时吸附换位，与被拖卡片锁步——弹簧在连续换位时追不上
                        // 指针，会产生缝隙和叠影；非拖拽的布局变化（展开收起等）保留弹簧滑动。
                        // 被拖卡片自己始终即时（位置由手势全权控制）。
                        .animateItem(
                            placementSpec = if (dragging || isDragging) null else spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMedium,
                                visibilityThreshold = IntOffset.VisibilityThreshold,
                            ),
                        )
                        .fillMaxWidth()
                        .hoverable(cardInteraction)
                        .zIndex(if (isDragging) 2f else 0f)
                        .graphicsLayer {
                            scaleX = cardScale
                            scaleY = cardScale
                            // 槽位取自 layoutInfo 的本帧实测值：绘制期现读，组合期读到的还是上一帧布局，
                            // 换位那一帧会差一行。同时把视觉位置钳制在列表视口内，拖到上下边缘时卡片顶住边界。
                            translationY = if (isDragging) {
                                val slot = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entryKey }
                                if (slot == null) {
                                    0f
                                } else {
                                    val visualTop = (dragPointerY - dragGrabOffset)
                                        .coerceIn(0f, (listViewportHeight - slot.size).coerceAtLeast(0f))
                                    visualTop - slot.offset
                                }
                            } else {
                                0f
                            }
                        }
                        .shadow(cardElevation, MaterialTheme.shapes.small)
                        .background(
                            when {
                                // 展开态抬升为 Elevated 内容面：与收起卡一眼可辨（2026-09-29 用户反馈），
                                // 悬停仍只作用于收起卡；拖拽中不透明浮起（下层文字不透出重影）。
                                isExpanded -> Theme.Elevated
                                isDragging -> Theme.Pressed
                                cardHovered -> Theme.Hover
                                else -> Theme.Panel
                            },
                            MaterialTheme.shapes.small,
                        )
                        .border(
                            if (isExpanded) 1.dp else 1.dp,
                            if (isDragging) Theme.Accent
                            else if (reorderMode) Theme.Accent.copy(alpha = 0.42f)
                            else if (isExpanded) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
                            else if (gitDirty) Theme.WarnOrange.copy(alpha = 0.36f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
                            MaterialTheme.shapes.small,
                        )
                        // 内容列从 12dp 顶部内边距之后才开始；让这段卡片留白也能展开题目。
                        .singleClickWithoutConsumingSelection(
                            accept = { !reorderMode && it.y < cardTopTapHeightPx },
                        ) {
                            locateSource(entry.sourcePath)
                            expanded = if (isExpanded) expanded - entryKey else expanded + entryKey
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    // 卡片内侧宽度最多 992dp；题面与下方答案都从同一内边距起排。
                    // 不能仅将题面收至 880dp，否则展开答案会比题面左移。
                    Row(
                        Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = SourceQuestionContentWidth),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (reorderMode) {
                            Surface(
                                color = if (isDragging) Theme.Pressed else Theme.Selected,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    "${displayIndex + 1}",
                                    Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    color = Theme.Accent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    if (gitDirty) "Q${entry.number} ·有改动" else "Q${entry.number}",
                                    modifier = if (reorderMode) Modifier else Modifier.singleClickWithoutConsumingSelection {
                                        locateSource(entry.sourcePath)
                                        expanded = if (isExpanded) expanded - entryKey else expanded + entryKey
                                    },
                                    fontSize = 11.sp,
                                    color = if (gitDirty) Theme.WarnOrange else Theme.Muted,
                                )
                                QuestionStatusMark(entry.status)
                                if (entry.tags.isNotEmpty() || !reorderMode) Spacer(Modifier.width(4.dp))
                                entry.tags.take(3).forEach { tag ->
                                    SourceQuestionTag(tag, !reorderMode, store.settings.questionTagFontSize) { editingEntry = entry }
                                }
                                if (entry.tags.size > 3) {
                                    Text("+${entry.tags.size - 3}", fontSize = store.settings.questionTagFontSize.sp, color = Theme.Muted)
                                }
                                if (entry.tags.isEmpty() && !reorderMode) {
                                    SourceQuestionTag(
                                        "＋ 标签",
                                        true,
                                        store.settings.questionTagFontSize,
                                        isPlaceholder = true,
                                        visible = cardHovered || isExpanded,
                                    ) { editingEntry = entry }
                                }
                                Spacer(
                                    Modifier.weight(1f).height(18.dp).then(
                                        if (reorderMode) Modifier else Modifier.singleClickWithoutConsumingSelection {
                                            locateSource(entry.sourcePath)
                                            expanded = if (isExpanded) expanded - entryKey else expanded + entryKey
                                        },
                                    ),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            // key 绑定内容：文件重载/保存换入新文本时销毁并重建选区容器，
                            // 旧选区锚点不会残留到长度已变的文本上（否则 Compose 选区绘制
                            // getPathForRange 会抛 Start>End 越界，2026-09-28 弹窗复现）。
                            // 排序模式下不放 SelectionContainer：文字选区手势会消费拖动事件，
                            // 按在题干文字上时卡片抓不起来（2026-09-28 Q17 拖不动）；
                            // 排序时文字选择无意义，整卡都是拖拽热区。
                            // 题面用较亮的阅读标题色 + 较轻的 Medium 字重，
                            // 与答案正文区分层级，同时避免 SemiBold 在深色卡上显得过厚。
                            if (reorderMode) {
                                Text(
                                    remember(entry.question, gitDiff) { annotatedQuestionDiff(entry.question, gitDiff) },
                                    style = ui.typography.itemTitle.copy(fontWeight = FontWeight.Medium),
                                    color = Theme.MdH1,
                                )
                            } else {
                                key(entryKey, entry.question) {
                                    SelectionContainer(
                                        modifier = Modifier.singleClickWithoutConsumingSelection {
                                            locateSource(entry.sourcePath)
                                            expanded = if (isExpanded) expanded - entryKey else expanded + entryKey
                                        },
                                    ) {
                                        Text(
                                            remember(entry.question, gitDiff) { annotatedQuestionDiff(entry.question, gitDiff) },
                                            // 题面字号随设置；同源题库标题按 itemTitle 的 15/13 比例跟随工作台基准
                                            style = ui.typography.itemTitle.copy(
                                                fontSize = (store.settings.questionFontSize * 15f / 13f).sp,
                                                lineHeight = (store.settings.questionFontSize * 22f / 13f).sp,
                                                fontWeight = FontWeight.Medium,
                                            ),
                                            color = Theme.MdH1,
                                        )
                                    }
                                }
                            }
                            if (searchScope == QuestionSearchScope.ALL && query.isNotBlank()) {
                                Text(entry.sourcePath, fontSize = 10.sp, color = Theme.Accent, maxLines = 1)
                            }
                        }
                    }
                    // 展开/收起必须带高度动画：直接增删答案块会让卡片高度瞬间跳变，下方卡片只能靠弹簧
                    // 滑过来补位，过渡期盖在答案上互相重叠。高度连续变化后，跟随卡片才能同步滑动不脱节。
                    // 弹簧参数与让位卡片一致，卡片底边与下方卡片作为一个系统运动；从顶部展开对齐题干。
                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = expandVertically(
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMedium,
                                visibilityThreshold = IntSize.VisibilityThreshold,
                            ),
                            expandFrom = Alignment.Top,
                        ) + fadeIn(tween(120)),
                        exit = shrinkVertically(
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMedium,
                                visibilityThreshold = IntSize.VisibilityThreshold,
                            ),
                            shrinkTowards = Alignment.Top,
                        ) + fadeOut(tween(90)),
                    ) {
                        Column {
                            if (entry.answer.isBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text("暂无答案", fontSize = 12.sp, color = Theme.WarnOrange)
                            } else {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .singleClickWithoutConsumingSelection {
                                            if (store.settings.clickAnswerToEdit) editingEntry = entry
                                    },
                                    shape = MaterialTheme.shapes.small,
                                    color = Color.Transparent,
                                ) {
                                    Column(Modifier.padding(top = 14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("答案", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Theme.Muted)
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        CompositionLocalProvider(
                                            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                                            LocalMarkdownReadingColors provides MarkdownReadingColors(
                                                body = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f),
                                                bold = Theme.MdBold,
                                                boldWeight = FontWeight.SemiBold,
                                                inlineCode = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.98f),
                                                inlineCodeBackground = lerp(
                                                    Theme.Elevated,
                                                    MaterialTheme.colorScheme.onSurface,
                                                    0.14f,
                                                ),
                                            ),
                                        ) {
                                            // 同题面：内容变化时重建 Markdown 根选择容器，避免旧选区残留。
                                            key(entryKey, entry.answer) {
                                                MarkdownText(
                                                    entry.answer,
                                                    dirtyLines = gitDiff?.answerDirtyLines ?: emptySet(),
                                                    maxWidth = SourceQuestionContentWidth,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("编辑", Modifier.clickable { editingEntry = entry }, fontSize = 13.sp, color = Theme.Accent)
                                Text(
                                    "移动",
                                    Modifier.clickable { Log.d("打开同源题目移动对话框 Q${entry.number}"); movingEntry = entry },
                                    fontSize = 13.sp,
                                    // 橙色语义保留给 git 改动标记与警告；移动按次级操作着色
                                    color = Theme.Muted,
                                )
                                Text(
                                    "删除",
                                    Modifier.clickable { Log.d("打开同源题目删除确认 Q${entry.number}"); deletingEntry = entry },
                                    fontSize = 13.sp,
                                    color = Theme.BadRed,
                                )
                            }
                        }
                    }
                }
            }
        }
        }
        }
    }
        }
    renameTarget?.let { node ->
        RenameKnowledgeNodeDialog(store, node) { renameTarget = null }
    }
    if (showCreateSingle) {
        CreateSourceQuestionDialog(store, selectedMappedDocument) { showCreateSingle = false }
    }
    if (showCreateBatch) {
        BatchCreateSourceQuestionsDialog(store, selectedMappedDocument) { showCreateBatch = false }
    }
    editingEntry?.let { entry ->
        EditSourceQuestionDialog(
            store = store,
            entries = visible,
            initialIndex = visible.indexOfFirst { sourceQuestionKey(it) == sourceQuestionKey(entry) },
            onDismiss = { editingEntry = null },
        )
    }
    deletingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deletingEntry = null },
            title = { Text("删除题目") },
            text = {
                Text(
                    "确定删除 Q${entry.number}「${entry.question.take(30)}」？题目和答案会从源文档中整块移除，剩余题目自动重新编号。",
                )
            },
            confirmButton = {
                Button(onClick = {
                    deletingEntry = null
                    store.deleteSourceQuestion(entry)
                }) { Text("删除") }
            },
            dismissButton = { OutlinedButton(onClick = { deletingEntry = null }) { Text("取消") } },
        )
    }
    movingEntry?.let { entry ->
        MoveSourceQuestionDialog(
            store = store,
            entry = entry,
            targetCandidates = mappedDocuments.filter { it != entry.sourcePath },
            onDismiss = { movingEntry = null },
        )
    }
}

@Composable
private fun ReadmeDocumentView(
    store: AppStore,
    path: String,
    content: String,
    modifier: Modifier = Modifier,
) {
    val ui = atlasUiTokens()
    var editing by remember(path) { mutableStateOf(false) }
    var draft by remember(path) { mutableStateOf(content) }
    var baseContent by remember(path) { mutableStateOf(content) }
    var externalConflict by remember(path) { mutableStateOf<String?>(null) }
    var saveFailed by remember(path) { mutableStateOf(false) }
    val savePendingOnLeave = rememberUpdatedState {
        if (externalConflict == null && draft != baseContent) {
            when (store.saveReadme(path, baseContent, draft)) {
                atlas.ReadmeSaveResult.CONFLICT -> store.showToast("README.md 有外部修改；重新打开后选择保留本地编辑或载入外部版本")
                atlas.ReadmeSaveResult.FAILED,
                atlas.ReadmeSaveResult.UNAVAILABLE -> store.showToast("README.md 自动保存失败")
                atlas.ReadmeSaveResult.SAVED -> Unit
            }
        }
    }
    DisposableEffect(path) {
        onDispose { savePendingOnLeave.value() }
    }

    LaunchedEffect(path, content) {
        if (draft == baseContent) {
            draft = content
            baseContent = content
            externalConflict = null
        } else if (content != baseContent && content != draft) {
            externalConflict = content
        }
    }

    LaunchedEffect(path, draft, baseContent, externalConflict) {
        if (externalConflict != null || draft == baseContent) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        when (store.saveReadme(path, baseContent, draft)) {
            atlas.ReadmeSaveResult.SAVED -> {
                baseContent = draft
                saveFailed = false
            }
            atlas.ReadmeSaveResult.CONFLICT -> {
                externalConflict = store.sourceReadmeContent ?: content
            }
            atlas.ReadmeSaveResult.UNAVAILABLE -> {
                saveFailed = true
            }
            atlas.ReadmeSaveResult.FAILED -> {
                saveFailed = true
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("README.md", style = ui.typography.sectionTitle, color = Theme.MdH1)
                Text(path, style = ui.typography.caption, color = Theme.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (editing) {
                Text(
                    when {
                        externalConflict != null -> "发现外部修改"
                        saveFailed -> "自动保存失败"
                        draft != baseContent -> "正在自动保存…"
                        else -> "已保存"
                    },
                    style = ui.typography.caption,
                    color = if (externalConflict != null || saveFailed) Theme.WarnOrange else Theme.Muted,
                )
            }
            OutlinedButton(onClick = { editing = !editing }) {
                Text(if (editing) "预览" else "编辑")
            }
        }

        externalConflict?.let { external ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .background(Theme.WarnOrange.copy(alpha = 0.10f), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("README.md 已在其他位置修改。自动写回已暂停。", Modifier.weight(1f), color = Theme.WarnOrange, fontSize = 12.sp)
                OutlinedButton(onClick = {
                    baseContent = external
                    externalConflict = null
                    saveFailed = false
                }) { Text("保留本地编辑") }
                Button(onClick = {
                    draft = external
                    baseContent = external
                    externalConflict = null
                    saveFailed = false
                }) { Text("载入外部版本") }
            }
        }

        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                minLines = 16,
                maxLines = Int.MAX_VALUE,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                placeholder = { Text("README.md 为空") },
            )
        } else {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (draft.isBlank()) Text("README.md 为空。", color = Theme.Muted)
                else LazyMarkdownText(draft, Modifier.fillMaxSize())
            }
        }
    }
}

/** 题面行内 diff 着色：相对 git HEAD 变化的字符标橙字；纯删除没有 new 侧区间则整段原样。 */
private fun annotatedQuestionDiff(text: String, diff: SourceQuestionGitDiff?): AnnotatedString = buildAnnotatedString {
    append(text)
    TextDiff.coalesceForHighlight(diff?.questionRanges.orEmpty(), text.length).forEach { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) {
            addStyle(SpanStyle(color = Theme.WarnOrange.copy(alpha = 0.72f)), start, end)
        }
    }
}

/** 单击打开编辑，拖动时把鼠标事件留给 SelectionContainer 做划词。 */
@Composable
private fun Modifier.singleClickWithoutConsumingSelection(
    accept: (Offset) -> Boolean = { true },
    onClick: () -> Unit,
): Modifier {
    // 文档切换后的题目/Git 标记/悬停重组会换入新的 lambda。以 onClick 为 pointerInput key
    // 会在 Press 与 Release 之间取消手势协程，导致新文档第一次点击题目没有反应。
    val currentAccept by rememberUpdatedState(accept)
    val currentOnClick by rememberUpdatedState(onClick)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            var pressed = false
            var moved = false
            var acceptedPress = false
            var downX = 0f
            var downY = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Press -> {
                        pressed = true
                        moved = false
                        acceptedPress = currentAccept(change.position)
                        downX = change.position.x
                        downY = change.position.y
                    }
                    PointerEventType.Move -> if (pressed && ((change.position.x - downX) * (change.position.x - downX) + (change.position.y - downY) * (change.position.y - downY) > 36f)) moved = true
                    PointerEventType.Release -> {
                        if (pressed && acceptedPress && !moved) currentOnClick()
                        pressed = false
                    }
                }
            }
        }
    }
}

/** Markdown 编辑快捷键（Ctrl+B/I/`）：在按键隧道阶段消费，避免字符落入正文。 */
private fun Modifier.markdownFormatKeys(onToggle: (String, String) -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
            markdownShortcutWrap(event.key)?.let { (prefix, suffix) ->
                onToggle(prefix, suffix)
                true
            } ?: false
        } else {
            false
        }
    }

@Composable
private fun KnowledgeTreeNodeView(
    node: KnowledgeTreeNode,
    depth: Int,
    expandedDirs: Set<String>,
    selectedPath: String,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    onRename: (KnowledgeTreeNode) -> Unit,
) {
    val isExpanded = node.path in expandedDirs
    val selected = !node.isDirectory && node.path == selectedPath
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) bringIntoViewRequester.bringIntoView()
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(160),
        label = "tree-chevron-rotation",
    )
    // 只有名称被省略号截断才挂载 TooltipArea，未截断的条目不弹提示
    var nameTruncated by remember { mutableStateOf(false) }
    val row: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth()
                .height(29.dp)
                .bringIntoViewRequester(bringIntoViewRequester)
                .hoverable(interactionSource)
                .background(
                    when {
                        selected -> Theme.Selected
                        hovered -> Theme.Hover
                        else -> androidx.compose.ui.graphics.Color.Transparent
                    },
                    RoundedCornerShape(7.dp),
                )
                .clickable {
                    if (node.isDirectory) onToggleDirectory(node.path) else onSelectFile(node.path)
                }
                .pointerInput(node.path) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                onRename(node)
                            }
                        }
                    }
                }
                .padding(start = (depth * 12).dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 箭头槽位对目录和文件等宽，保证各级名称左对齐；文件占位不画箭头
            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                if (node.isDirectory) {
                    Icon(
                        TreeChevronIcon,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp).rotate(chevronRotation),
                        tint = if (selected) Theme.Accent else Theme.Muted,
                    )
                }
            }
            Icon(
                if (node.isDirectory) TreeFolderIcon else TreeFileIcon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (selected) Theme.Accent else Theme.Muted,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                node.name,
                onTextLayout = { nameTruncated = it.hasVisualOverflow },
                // 树行三级层次：目录=结构锚点用正文色，文件常态压到次级灰避免整列亮字，
                // 选中项才用强调色提亮（低眩光导航，悬停/选中底色不变）
                color = when {
                    selected -> Theme.Accent
                    node.isDirectory -> MaterialTheme.colorScheme.onSurface
                    else -> Theme.Muted
                },
                fontSize = 13.sp,
                fontWeight = when {
                    selected -> FontWeight.SemiBold
                    node.isDirectory -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (nameTruncated) {
        TooltipArea(
            tooltip = { KnowledgeTreeNameTip(node.name) },
            delayMillis = 500,
            // CursorPoint 默认 BottomEnd 对齐：提示框左上角贴光标向右下展开；
            // 小偏移让光标箭头不压住提示框，窗口右/下边缘放不下时自动翻到另一侧
            tooltipPlacement = TooltipPlacement.CursorPoint(
                offset = DpOffset(10.dp, 12.dp),
            ),
        ) {
            row()
        }
    } else {
        row()
    }
    if (node.isDirectory && isExpanded) {
        node.children.forEach { child ->
            KnowledgeTreeNodeView(child, depth + 1, expandedDirs, selectedPath, onToggleDirectory, onSelectFile, onRename)
        }
    }
}

@Composable
private fun KnowledgeTreeNameTip(name: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Theme.CodeBg,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
    ) {
        Text(
            name,
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun CreateSourceQuestionDialog(
    store: AppStore,
    targetPath: String,
    onDismiss: () -> Unit,
) {
    var question by remember { mutableStateOf("") }
    val nextNumber = remember(targetPath) { store.nextSourceQuestionNumber(targetPath) }
    var numberText by remember(targetPath) { mutableStateOf(nextNumber.toString()) }
    val number = numberText.toIntOrNull()
    val duplicate = question.trim().isNotBlank() && question.trim() in remember(targetPath) { store.sourceQuestionTexts(targetPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(760.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("新建题目") },
        confirmButton = {
            Button(
                enabled = targetPath.isNotBlank() && question.isNotBlank() && number != null && number > 0 && !duplicate,
                onClick = {
                    if (number != null && store.createSourceQuestionAt(targetPath, SourceQuestions.Draft(question, ""), number)) onDismiss()
                },
            ) { Text("新建题目") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("当前文档", fontWeight = FontWeight.SemiBold)
                Text(targetPath.ifBlank { "请先在左侧选择 Markdown 文档" }, fontSize = 12.sp, color = if (targetPath.isBlank()) Theme.WarnOrange else Theme.Accent)
                Text("输入插入位置，默认填入第 $nextNumber 题；已有题目会向后顺延。", fontSize = 11.sp, color = Theme.Muted)
                OutlinedTextField(
                    value = numberText,
                    onValueChange = { value -> if (value.all { it.isDigit() } && value.length <= 6) numberText = value },
                    modifier = Modifier.width(180.dp),
                    label = { Text("题目序号") },
                    singleLine = true,
                )
                if (number != null && number <= 0) Text("题目序号必须是正整数。", fontSize = 12.sp, color = Theme.WarnOrange)
                OutlinedTextField(
                    question,
                    { question = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("题目") },
                    minLines = 3,
                )
                if (question.isNotBlank()) {
                    Surface(color = Theme.Selected, shape = MaterialTheme.shapes.small) {
                        Text("将新建题目：${question.trim()}", Modifier.padding(10.dp), color = Theme.Accent)
                    }
                }
                if (duplicate) Text("该文档已有相同题目，请修改题面。", fontSize = 12.sp, color = Theme.WarnOrange)
            }
        },
    )
}

@Composable
private fun BatchCreateSourceQuestionsDialog(
    store: AppStore,
    targetPath: String,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val drafts = remember(input) { SourceQuestions.parseBatch(input) }
    val duplicateCount = drafts.groupingBy { it.question }.eachCount().count { it.value > 1 }
    val existingQuestions = remember(targetPath) { store.sourceQuestionTexts(targetPath) }
    val existingDuplicateCount = drafts.count { it.question.trim() in existingQuestions }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(820.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("批量新建题目") },
        confirmButton = {
            Button(
                enabled = targetPath.isNotBlank() && drafts.isNotEmpty() && duplicateCount == 0 && existingDuplicateCount == 0,
                onClick = {
                    if (store.createSourceQuestions(targetPath, drafts)) onDismiss()
                },
            ) { Text("写入 ${drafts.size} 道题目") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("当前文档", fontWeight = FontWeight.SemiBold)
                Text(targetPath.ifBlank { "请先在左侧选择 Markdown 文档" }, fontSize = 12.sp, color = if (targetPath.isBlank()) Theme.WarnOrange else Theme.Accent)
                Text("每行输入一道题目，系统会自动生成题号和空答案。", fontSize = 11.sp, color = Theme.Muted)
                OutlinedTextField(
                    input,
                    { input = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("题目列表（一行一道）") },
                    minLines = 14,
                    maxLines = 24,
                )
                when {
                    input.isBlank() -> Text("尚未输入题目。", fontSize = 12.sp, color = Theme.Muted)
                    drafts.isEmpty() -> Text("请输入至少一道题目，每行一道。", fontSize = 12.sp, color = Theme.WarnOrange)
                    duplicateCount > 0 -> Text("发现重复题目，请修改后再写入。", fontSize = 12.sp, color = Theme.WarnOrange)
                    existingDuplicateCount > 0 -> Text("目标文档中已有 $existingDuplicateCount 道同名题目，请修改后再写入。", fontSize = 12.sp, color = Theme.WarnOrange)
                    else -> Text("已识别 ${drafts.size} 道题目，写入时会从源文档最大题号之后连续编号。", fontSize = 12.sp, color = Theme.OkGreen)
                }
            }
        },
    )
}

@Composable
private fun RenameKnowledgeNodeDialog(
    store: AppStore,
    node: KnowledgeTreeNode,
    onDismiss: () -> Unit,
) {
    var name by remember(node.path) { mutableStateOf(node.name) }
    fun save() {
        if (store.renameKnowledgeNode(node.path, name)) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (node.isDirectory) "重命名目录" else "重命名文件") },
        confirmButton = {
            Button(onClick = ::save) { Text("保存") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                            save()
                            true
                        } else {
                            false
                        }
                    },
                    singleLine = true,
                    label = { Text(if (node.isDirectory) "目录名" else "文件名") },
                )
                Text(
                    if (node.isDirectory) "不能包含 / 或 \\。" else "文件名会保留 .md 后缀。",
                    fontSize = 11.sp,
                    color = Theme.Muted,
                )
            }
        },
    )
}

@Composable
private fun MoveSourceQuestionDialog(
    store: AppStore,
    entry: SourceQuestions.Entry,
    targetCandidates: List<String>,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf("") }
    var targetQuery by remember(entry.id) { mutableStateOf("") }
    val filteredTargetCandidates = targetCandidates.filter { it.contains(targetQuery.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(680.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("移动题目到其他文档") },
        confirmButton = {
            Button(
                enabled = selected.isNotBlank(),
                onClick = {
                    store.moveSourceQuestion(entry, selected)
                    onDismiss()
                },
            ) { Text("移动") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("题目", fontWeight = FontWeight.SemiBold)
                Text(entry.question, fontSize = 13.sp)
                Text("当前文档", fontWeight = FontWeight.SemiBold)
                Text(entry.sourcePath, fontSize = 12.sp, color = Theme.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("目标文档", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    OutlinedTextField(
                        value = targetQuery,
                        onValueChange = { targetQuery = it },
                        modifier = Modifier.width(280.dp),
                        placeholder = { Text("搜索目标文档…") },
                        singleLine = true,
                    )
                }
                if (selected.isNotBlank() && selected !in filteredTargetCandidates) {
                    Text("已选目标：$selected", fontSize = 11.sp, color = Theme.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (targetCandidates.isEmpty()) {
                    Text("没有其他纳入题库映射的文档可作目标。", fontSize = 12.sp, color = Theme.WarnOrange)
                } else if (filteredTargetCandidates.isEmpty()) {
                    Text("没有匹配的目标文档。", fontSize = 12.sp, color = Theme.Muted)
                }
                filteredTargetCandidates.forEach { path ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { selected = path }
                            .background(
                                if (selected == path) Theme.Selected else androidx.compose.ui.graphics.Color.Transparent,
                                MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            TreeFileIcon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (selected == path) Theme.Accent else Theme.Muted,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            path,
                            fontSize = 12.sp,
                            color = if (selected == path) Theme.Accent else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "移动后题目会追加到目标文档末尾，题号按目标文档现有最大题号续排；源文档剩余题目自动重新编号。",
                    fontSize = 11.sp,
                    color = Theme.Muted,
                )
            }
        },
    )
}

@Composable
private fun EditSourceQuestionDialog(
    store: AppStore,
    entries: List<SourceQuestions.Entry>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    // 保存会触发 AppStore 重载；编辑会话必须使用稳定快照，不能跟着外层 visible 短暂清空。
    var stableEntries by remember { mutableStateOf(entries.toList()) }
    var currentIndex by remember { mutableStateOf(initialIndex.coerceIn(0, (stableEntries.size - 1).coerceAtLeast(0))) }
    var isSaving by remember { mutableStateOf(false) }
    val safeIndex = safeQuestionIndex(currentIndex, stableEntries.size)
    if (safeIndex == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val entry = stableEntries[safeIndex]
    var question by remember(entry.id) { mutableStateOf(entry.question) }
    var answer by remember(entry.id) { mutableStateOf(TextFieldValue(entry.answer)) }
    var status by remember(entry.id) { mutableStateOf(entry.status) }
    var tagsText by remember(entry.id) { mutableStateOf(entry.tags.joinToString("，")) }
    fun saveAndMove(target: Int): Boolean {
        if (question.isBlank()) return false
        if (!store.saveSourceQuestion(entry, question, answer.text, status, SourceQuestions.normalizeTags(listOf(tagsText)))) return false
        val updatedDocument = store.sourceQuestionFile().readText(Charsets.UTF_8)
        val updatedByNumber = SourceQuestions.parse(entry.sourcePath, updatedDocument, listOf(entry.sourcePath))
            .associateBy { it.number }
        stableEntries = stableEntries.map { updatedByNumber[it.number] ?: it }
        currentIndex = target
        return true
    }
    fun saveAndMoveOnce(target: Int) {
        if (!canNavigateQuestionEditor(isSaving, target, stableEntries.size)) return
        isSaving = true
        try {
            saveAndMove(target)
        } finally {
            isSaving = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(820.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("编辑 Q${entry.number}（${safeIndex + 1}/${stableEntries.size}）") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val previous = adjacentQuestionIndex(safeIndex, stableEntries.size, -1)
                val next = adjacentQuestionIndex(safeIndex, stableEntries.size, 1)
                OutlinedButton(
                    enabled = !isSaving && previous != null,
                    onClick = { previous?.let { saveAndMoveOnce(it) } },
                ) {
                    Text("保存并上一个")
                }
                OutlinedButton(
                    enabled = !isSaving && next != null,
                    onClick = { next?.let { saveAndMoveOnce(it) } },
                ) {
                    Text("保存并下一个")
                }
                Button(onClick = {
                    if (!isSaving && question.isNotBlank()) {
                        isSaving = true
                        try {
                            if (store.saveSourceQuestion(entry, question, answer.text, status, SourceQuestions.normalizeTags(listOf(tagsText)))) onDismiss()
                        } finally {
                            isSaving = false
                        }
                    }
                }) { Text("保存到源文档") }
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("题目") }, minLines = 2)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("完成状态（写进 Q 行的 [key] 前缀，如 [done]；todo 不写标记）", fontSize = 11.sp, color = Theme.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuestionStatus.values().forEach { candidate ->
                            QuestionStatusChoice(candidate, candidate == status) { status = candidate }
                        }
                    }
                }
                OutlinedTextField(
                    tagsText,
                    { tagsText = it.replace('\n', ' ').replace('\r', ' ') },
                    Modifier.fillMaxWidth(),
                    label = { Text("分类标签") },
                    placeholder = { Text("例如 init，启动流程") },
                    supportingText = { Text("用逗号分隔；标签显示在题号旁，随题目保存在 Markdown 中") },
                    singleLine = true,
                )
                val previewTags = SourceQuestions.normalizeTags(listOf(tagsText))
                if (previewTags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        previewTags.take(5).forEach { tag -> SourceQuestionTag(tag, false, store.settings.questionTagFontSize) {} }
                        if (previewTags.size > 5) Text("+${previewTags.size - 5}", fontSize = 11.sp, color = Theme.Muted)
                    }
                }
                OutlinedTextField(
                    answer,
                    { answer = it },
                    Modifier.fillMaxWidth().markdownFormatKeys { prefix, suffix ->
                        answer = answer.toggleMarkdownWrap(prefix, suffix)
                    },
                    label = { Text("答案 Markdown") },
                    minLines = 8,
                    maxLines = 16,
                )
                Text(
                    "快捷键：选中文字按 Ctrl+B 加粗 · Ctrl+I 斜体 · Ctrl+` 行内代码（再按一次取消）",
                    fontSize = 11.sp,
                    color = Theme.Muted,
                )
                Text("保存后直接修改 ${entry.sourcePath}", fontSize = 11.sp, color = Theme.Muted)
            }
        },
    )
}

@Composable
private fun SourceQuestionTag(
    label: String,
    editable: Boolean,
    fontSizeSp: Int,
    isPlaceholder: Boolean = false,
    visible: Boolean = true,
    onClick: () -> Unit,
) {
    val displayText = if (isPlaceholder) AnnotatedString("+ 标签") else buildAnnotatedString {
        append("#$label")
        addStyle(SpanStyle(color = Theme.Muted.copy(alpha = 0.82f)), 0, 1)
    }
    Text(
        text = displayText,
        modifier = Modifier
            .widthIn(max = 144.dp)
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .then(if (editable) Modifier.singleClickWithoutConsumingSelection(onClick = onClick) else Modifier)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        fontSize = fontSizeSp.sp,
        color = if (isPlaceholder) Theme.Muted.copy(alpha = 0.62f) else Theme.Tag,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun questionStatusAccent(status: QuestionStatus): Color = when (status) {
    QuestionStatus.DONE -> Theme.OkGreen
    QuestionStatus.LEARNING -> Theme.WarnOrange
    QuestionStatus.TODO -> Theme.Muted
}

/**
 * 列表里的状态标记用「圆点 + 文字」而不是圆角药丸：药丸底色在深色卡片上发灰发脏，
 * 而一屏几十道题里绝大多数都是默认态，所以默认态直接不渲染——缺省即未完成，
 * 只有需要被看见的进行中/已完成才占位。文案直接用文件里的英文 key，
 * 界面上看到的词就是 Q 行里写的词，不用在脑子里维护一层翻译。
 */
@Composable
private fun QuestionStatusMark(status: QuestionStatus) {
    if (status == QuestionStatus.TODO) return
    val accent = questionStatusAccent(status)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Text(status.key, fontSize = 11.sp, color = accent)
    }
}

@Composable
private fun QuestionStatusChoice(status: QuestionStatus, selected: Boolean, onClick: () -> Unit) {
    val accent = questionStatusAccent(status)
    Surface(
        color = if (selected) accent.copy(alpha = 0.14f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) accent else Theme.Muted.copy(alpha = 0.3f)),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            Text(
                status.key,
                fontSize = 13.sp,
                color = if (selected) accent else Theme.Muted,
            )
        }
    }
}
