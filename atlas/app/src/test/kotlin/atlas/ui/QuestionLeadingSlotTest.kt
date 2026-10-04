package atlas.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class QuestionLeadingSlotTest {
    @Test
    fun `normal browsing puts status in the leading slot`() {
        assertEquals(
            QuestionLeadingSlot.STATUS,
            questionLeadingSlot(batchTagMode = false, reorderMode = false),
        )
    }

    @Test
    fun `temporary modes replace status in the leading slot`() {
        assertEquals(
            QuestionLeadingSlot.BATCH_SELECTION,
            questionLeadingSlot(batchTagMode = true, reorderMode = false),
        )
        assertEquals(
            QuestionLeadingSlot.REORDER_POSITION,
            questionLeadingSlot(batchTagMode = false, reorderMode = true),
        )
        assertEquals(
            QuestionLeadingSlot.BATCH_SELECTION,
            questionLeadingSlot(batchTagMode = true, reorderMode = true),
        )
    }

    @Test
    fun `reorder position text shrinks when reaching three digits`() {
        assertEquals(11f, reorderPositionFontSizeSp(9))
        assertEquals(11f, reorderPositionFontSizeSp(99))
        assertEquals(9f, reorderPositionFontSizeSp(100))
        assertEquals(8f, reorderPositionFontSizeSp(1_000))
    }

    @Test
    fun `status icon sits inside the card and shifts the whole question body`() {
        val metrics = questionCardHorizontalMetrics()

        assertEquals(16f, metrics.cardStartInsetDp)
        assertEquals(24f, metrics.statusSlotWidthDp)
        assertEquals(12f, metrics.statusGapDp)
        assertEquals(52f, metrics.contentStartDp)
        assertEquals(24f, metrics.contentEndDp)
        assertEquals(964f, metrics.contentWidthDp(cardWidthDp = 1_040f))
    }
}
