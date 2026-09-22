package atlas

import atlas.ui.WorkbenchAction
import atlas.ui.WorkbenchRegion
import atlas.ui.topLevelTabs
import atlas.ui.settingsTabLabel
import atlas.ui.workbenchActions
import atlas.ui.workbenchRegions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NavigationModelTest {

    @Test
    fun `顶层导航把设置独立放到右侧入口`() {
        assertEquals(listOf("工作台", "题库"), topLevelTabs())
        assertEquals("设置", settingsTabLabel())
    }

    @Test
    fun `工作台只呈现有数量的行动项`() {
        val actions = workbenchActions(due = 2, untested = 0, retest = 1, inbox = 3)

        assertEquals(
            listOf(
                WorkbenchAction("题目待复测", 1, "去复测", "题库"),
                WorkbenchAction("待确认候选", 3, "去确认", "工作台"),
            ),
            actions,
        )
    }

    @Test
    fun `工作台摘要固定而详情区域负责滚动`() {
        assertEquals(
            listOf(WorkbenchRegion.SCROLLABLE_PAGE),
            workbenchRegions(),
        )
    }
}
