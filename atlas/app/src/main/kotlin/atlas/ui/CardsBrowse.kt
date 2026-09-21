package atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atlas.AppStore
import atlas.core.Log
import atlas.core.MdStores.CardEntry
import atlas.fsrs.FsrsEngine

/** 卡片浏览：搜索 + 卡组筛选 + 编辑/删除/暂停 */
@Composable
fun CardsBrowseDialog(store: AppStore, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var deck by remember { mutableStateOf("全部") }
    var editing by remember { mutableStateOf<CardEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<CardEntry?>(null) }
    val decks = listOf("全部") + store.deckNames().filter { it != "全部" }
    val list = store.cards.filter { c ->
        (deck == "全部" || c.deck == deck) &&
            (query.isBlank() || c.front.contains(query, true) || c.back.contains(query, true) || c.deck.contains(query, true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("浏览卡片（${store.cards.size} 张）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索卡面/卡背/卡组…") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    decks.forEach { d ->
                        Text(d, Modifier
                            .clickable { deck = d }
                            .background(if (deck == d) Theme.Accent.copy(alpha = 0.18f) else Color.Transparent, MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp,
                            color = if (deck == d) Theme.Accent else Theme.Muted)
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list, key = { it.id }) { c ->
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), MaterialTheme.shapes.small).padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.front, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                                if (c.suspended) StatusChip("已暂停", Theme.WarnOrange)
                            }
                            Text("卡组：${c.deck} · 来源：${c.source}", fontSize = 11.sp, color = Theme.Muted, maxLines = 1)
                            if (c.back.isNotBlank()) Text(c.back.take(80), fontSize = 11.sp, color = Theme.Muted, maxLines = 2)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("编辑", Modifier.clickable { editing = c }, fontSize = 12.sp, color = Theme.Accent)
                                Text(if (c.suspended) "恢复" else "暂停", Modifier.clickable { store.suspendCard(c.id, !c.suspended) }, fontSize = 12.sp, color = Theme.WarnOrange)
                                Text("删除", Modifier.clickable { confirmDelete = c }, fontSize = 12.sp, color = Theme.BadRed)
                            }
                        }
                    }
                    if (list.isEmpty()) item { Text("没有匹配的卡片。", color = Theme.Muted, fontSize = 12.sp) }
                }
            }
        },
    )
    editing?.let { c -> EditCardDialog(store, c) { editing = null } }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除卡片") },
            text = { Text("确定删除「${c.front.take(30)}」？删除后不可恢复。") },
            confirmButton = { Button(onClick = { store.deleteCard(c.id); confirmDelete = null }) { Text("删除") } },
            dismissButton = { OutlinedButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
fun EditCardDialog(store: AppStore, c: CardEntry, onDismiss: () -> Unit) {
    var front by remember { mutableStateOf(c.front) }
    var back by remember { mutableStateOf(c.back) }
    var deck by remember { mutableStateOf(c.deck) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑卡片") },
        confirmButton = {
            Button(onClick = {
                if (front.isNotBlank()) store.updateCard(c.copy(front = front.trim(), back = back.trim(), deck = deck.ifBlank { "默认" }))
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(front, { front = it }, Modifier.fillMaxWidth(), label = { Text("卡面") })
                OutlinedTextField(back, { back = it }, Modifier.fillMaxWidth(), label = { Text("卡背") }, minLines = 3)
                OutlinedTextField(deck, { deck = it }, Modifier.fillMaxWidth(), label = { Text("卡组") })
            }
        },
    )
}

/** 记忆参数：查看/切换 desired retention（PRD FR-C3） */
@Composable
fun FsrsParamsDialog(onDismiss: () -> Unit) {
    var current by remember { mutableStateOf(FsrsEngine.desiredRetention()) }
    val presets = listOf(0.85, 0.90, 0.95, 0.97)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记忆参数") },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("完成") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("目标记住率：${"%.0f".format(current * 100)}%", fontWeight = FontWeight.SemiBold)
                Text("越高复习间隔越长、单次负担越重；90% 是社区推荐值，切换立即生效。", fontSize = 12.sp, color = Theme.Muted, lineHeight = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { p ->
                        OutlinedButton(onClick = { Log.i("FSRS retention → $p"); FsrsEngine.schedulerFor(p); current = FsrsEngine.desiredRetention() }) {
                            Text("%.0f%%".format(p * 100))
                        }
                    }
                }
                OutlinedButton(onClick = { Log.i("FSRS 参数重置为 0.90"); FsrsEngine.resetParams(); current = FsrsEngine.desiredRetention() }) { Text("重置为 90%") }
            }
        },
    )
}
