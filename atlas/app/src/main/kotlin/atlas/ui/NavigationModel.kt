package atlas.ui

data class WorkbenchAction(
    val label: String,
    val count: Int,
    val action: String,
    val target: String,
)

enum class WorkbenchRegion { SCROLLABLE_PAGE }

/** 闪卡/学习入口暂时隐藏；题库直接读取同源 Markdown；工具页图形化集成本地小工具。 */
fun topLevelTabs(): List<String> = listOf("工作台", "题库", "工具")

fun settingsTabLabel(): String = "设置"

fun workbenchRegions(): List<WorkbenchRegion> = listOf(
    WorkbenchRegion.SCROLLABLE_PAGE,
)

fun workbenchActions(due: Int, untested: Int, retest: Int, inbox: Int): List<WorkbenchAction> = buildList {
    if (untested > 0) add(WorkbenchAction("题目未测", untested, "发起模拟面试", "题库"))
    if (retest > 0) add(WorkbenchAction("题目待复测", retest, "去复测", "题库"))
    if (inbox > 0) add(WorkbenchAction("待确认候选", inbox, "去确认", "工作台"))
}
