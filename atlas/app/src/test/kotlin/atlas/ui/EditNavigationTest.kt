package atlas.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import atlas.core.SourceQuestions

class EditNavigationTest {
    @Test
    fun `returns adjacent item and disables at list boundaries`() {
        assertEquals(1, adjacentQuestionIndex(0, 3, 1))
        assertEquals(0, adjacentQuestionIndex(1, 3, -1))
        assertNull(adjacentQuestionIndex(0, 3, -1))
        assertNull(adjacentQuestionIndex(2, 3, 1))
    }

    @Test
    fun `does not produce an index for an empty editing list`() {
        assertNull(safeQuestionIndex(1, 0))
        assertEquals(0, safeQuestionIndex(1, 1))
    }

    @Test
    fun `does not allow a second navigation while saving`() {
        assertEquals(true, canNavigateQuestionEditor(isSaving = false, targetIndex = 1, total = 3))
        assertEquals(false, canNavigateQuestionEditor(isSaving = true, targetIndex = 1, total = 3))
        assertEquals(false, canNavigateQuestionEditor(isSaving = false, targetIndex = 3, total = 3))
    }

    @Test
    fun `duplicate question numbers still produce unique list keys`() {
        val entries = SourceQuestions.parse(
            "knowledge-base/test.md",
            "**Q1: first**\nanswer\n\n**Q1: second**\nanswer",
            listOf("knowledge-base/test.md"),
        )

        assertEquals(2, entries.map { sourceQuestionKey(it) }.distinct().size)
    }
}
