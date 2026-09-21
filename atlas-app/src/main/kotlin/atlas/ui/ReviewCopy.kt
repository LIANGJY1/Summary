package atlas.ui

/** 复习页的用户可见文案集中在这里，避免把具体日期写死进界面。 */
fun reviewProgressLabel(index: Int, total: Int): String = "本轮第 $index 张 / 共 $total 张"

fun answerPromptLabel(): String = "还没看到答案？点击按钮或按 Space 查看"

/** 卡组名称在侧栏中使用紧凑文案，避免长名称挤压到期徽标。 */
fun compactDeckLabel(deck: String, maxChars: Int = 18): String {
    val value = deck.trim()
    if (value.length <= maxChars || maxChars <= 1) return value
    return value.take(maxChars - 1) + "…"
}
