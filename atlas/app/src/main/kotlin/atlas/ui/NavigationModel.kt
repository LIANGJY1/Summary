package atlas.ui

data class WorkbenchAction(
    val label: String,
    val count: Int,
    val action: String,
    val target: String,
)

enum class WorkbenchRegion { SCROLLABLE_PAGE }

fun topLevelTabs(): List<String> = listOf("工作台", "题库", "学习")

fun settingsTabLabel(): String = "设置"

fun workbenchRegions(): List<WorkbenchRegion> = listOf(
    WorkbenchRegion.SCROLLABLE_PAGE,
)

fun workbenchActions(due: Int, untested: Int, retest: Int, inbox: Int): List<WorkbenchAction> = buildList {
    if (due > 0) add(WorkbenchAction("复习到期卡", due, "开始复习", "学习"))
    if (untested > 0) add(WorkbenchAction("题目未测", untested, "发起模拟面试", "题库"))
    if (retest > 0) add(WorkbenchAction("题目待复测", retest, "去复测", "题库"))
    if (inbox > 0) add(WorkbenchAction("待确认候选", inbox, "去确认", "工作台"))
}
