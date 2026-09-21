package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.Log
import atlas.core.MdStores.CardEntry

/** 复习子页（「学习」页默认节）：卡组侧栏 + Anki 键位翻卡 */
@Composable
fun ReviewSection(store: AppStore) {
    var showAdd by remember { mutableStateOf(false) }
    var showBrowse by remember { mutableStateOf(false) }
    val deck = store.reviewDeckFilter
    val decks = listOf("全部") + store.deckNames().filter { it != "全部" }
    val stats = store.deckStats()
    val focusRequester = remember { FocusRequester() }
    val card = store.currentCard()

    LaunchedEffect(card?.id) { if (card != null) runCatching { focusRequester.requestFocus() } }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(248.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                .padding(16.dp),
        ) {
            Text("复习卡组", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("选择一个范围开始复习", fontSize = 11.sp, color = Theme.Muted)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                decks.forEach { d ->
                    val st = if (d == "全部") null else stats.firstOrNull { it.deck == d }
                    val due = if (d == "全部") store.dueCount("全部") else st?.due ?: 0
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                if (deck == d) Theme.Accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { Log.i("学习页切换卡组 → $d"); store.reviewDeckFilter = d; store.rebuildDueQueue() }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).widthIn(min = 0.dp)) {
                            Text(
                                compactDeckLabel(d), fontSize = 13.sp,
                                fontWeight = if (deck == d) FontWeight.Bold else FontWeight.Normal,
                                color = if (deck == d) Theme.Accent else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (d == "全部") "${store.cards.size} 张卡片" else "${st?.total ?: 0} 张卡片",
                                fontSize = 10.sp, color = Theme.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (due > 0) StatusChip("$due 待复习", Theme.WarnOrange)
                        else if (st != null && st.suspended > 0) Text("${st.suspended} 已暂停", fontSize = 10.sp, color = Theme.Muted)
                        else Text("—", fontSize = 12.sp, color = Theme.Muted)
                        }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { Log.d("打开手动建卡对话框"); showAdd = true }, Modifier.fillMaxWidth()) { Text("+ 新建闪卡") }
            OutlinedButton(onClick = { Log.d("打开浏览卡片"); showBrowse = true }, Modifier.fillMaxWidth()) { Text("管理闪卡") }
        }

        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 20.dp)) {
            val total = store.dueQueue.size
            if (card == null) {
                ReviewDonePanel(store)
            } else {
                val position = store.reviewIdx + 1
                val progress = if (total == 0) 0f else position.toFloat() / total.toFloat()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("复习", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("${card.deck} · ${reviewProgressLabel(position, total)}", fontSize = 12.sp, color = Theme.Muted)
                    }
                    StatusChip("$total 张待复习", Theme.Accent)
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(18.dp))
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 30.dp, vertical = 26.dp)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyUp) {
                                when (e.key) {
                                    Key.Spacebar -> {
                                        if (!store.showingBack) { Log.d("显示答案（Space）"); store.showingBack = true }
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                        .focusRequester(focusRequester)
                        .focusable()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("问题", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Theme.Accent)
                    Spacer(Modifier.height(10.dp))
                    Text(card.front, fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
                    if (store.showingBack) {
                        Spacer(Modifier.height(22.dp))
                        VDivider()
                        Spacer(Modifier.height(20.dp))
                        Text("参考答案", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Theme.OkGreen)
                        Spacer(Modifier.height(8.dp))
                        MarkdownText(card.back)
                    } else {
                        Spacer(Modifier.height(22.dp))
                        Text(answerPromptLabel(), fontSize = 13.sp, color = Theme.Muted)
                    }
                    Spacer(Modifier.height(28.dp))
                    SourceRow(store, card)
                }
                Spacer(Modifier.height(12.dp))
                if (!store.showingBack) {
                    Button(onClick = { store.showingBack = true }, Modifier.fillMaxWidth().height(50.dp)) { Text("显示答案  ·  Space") }
                } else {
                    Text("浏览卡片：可以前后切换，不会修改复习状态", Modifier.align(Alignment.CenterHorizontally), fontSize = 12.sp, color = Theme.Muted)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrowseButton("上一题", enabled = store.reviewIdx > 0) { store.previousReviewCard() }
                        BrowseButton("下一题", enabled = store.reviewIdx < store.dueQueue.lastIndex) { store.nextReviewCard() }
                    }
                }
            }
        }
    }
    if (showAdd) AddCardDialog(store) { showAdd = false }
    if (showBrowse) CardsBrowseDialog(store) { showBrowse = false }
}

@Composable
private fun RowScope.BrowseButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).height(56.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

/** 来源行：source 指向库内 `path##小节` 时提供跳回原文（PRD FR-C3 超越点） */
@Composable
private fun SourceRow(store: AppStore, card: CardEntry) {
    val relPath = card.source.substringBefore("##").trim()
    val jumpable = relPath.isNotBlank() && store.notes.any { it.relPath == relPath }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("来源：${card.source}", fontSize = 11.sp, color = Theme.Accent, maxLines = 1)
        if (jumpable) {
            Text("跳回原文", Modifier.clickable { store.requestPreview(relPath) }, fontSize = 11.sp, color = Theme.Accent, fontWeight = FontWeight.Bold)
        }
    }
}

/** 队列清空后的完成态：本轮统计 + 再来一轮 */
@Composable
private fun ReviewDonePanel(store: AppStore) {
    val s = store.sessionSummary()
    Column(
        Modifier.fillMaxWidth().padding(top = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.count > 0) {
            Text("这一轮复习完成", fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text("已复习 ${s.count} 张 · 用时约 ${s.minutes} 分钟", fontSize = 14.sp, color = Theme.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AGAIN" to Theme.BadRed, "HARD" to Theme.WarnOrange, "GOOD" to Theme.Accent, "EASY" to Theme.OkGreen).forEach { (g, color) ->
                    val n = s.dist[g] ?: 0
                    if (n > 0) StatusChip("$g ×$n", color)
                }
            }
            Button(onClick = { store.rebuildDueQueue() }, Modifier.width(220.dp).height(48.dp)) { Text("再来一轮") }
        } else {
            Text("暂时没有待复习卡片", color = Theme.Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("可以新建闪卡，或切换左侧卡组查看其他复习范围。", fontSize = 13.sp, color = Theme.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { store.rebuildDueQueue() }) { Text("刷新复习队列") }
            }
        }
    }
}

@Composable
fun AddCardDialog(store: AppStore, onDismiss: () -> Unit) {
    var front by remember { mutableStateOf("") }
    var back by remember { mutableStateOf("") }
    var deck by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动建卡") },
        confirmButton = {
            Button(onClick = {
                if (front.isNotBlank()) store.addCardManual(front, back, deck, "手动")
                onDismiss()
            }) { Text("建卡") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(front, { front = it }, Modifier.fillMaxWidth(), label = { Text("卡面（问题）") })
                OutlinedTextField(back, { back = it }, Modifier.fillMaxWidth(), label = { Text("卡背（答案）") }, minLines = 3)
                OutlinedTextField(deck, { deck = it }, Modifier.fillMaxWidth(), label = { Text("卡组（如 Android/面试题，留空=默认）") })
            }
        },
    )
}
