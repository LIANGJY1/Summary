package atlas.ui

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownImagePreviewTest {
    @Test
    fun `image click opens preview and overlay or escape dismisses it`() {
        val opened = reduceImagePreviewState(ImagePreviewState.Closed, ImagePreviewEvent.ImageClicked)

        assertEquals(ImagePreviewState.Open, opened)
        assertEquals(
            ImagePreviewState.Closed,
            reduceImagePreviewState(opened, ImagePreviewEvent.OverlayDismissed),
        )
        assertEquals(
            ImagePreviewState.Closed,
            reduceImagePreviewState(opened, ImagePreviewEvent.EscapePressed),
        )
    }

    @Test
    fun `preview hit target is constrained to the fitted image rectangle`() {
        assertEquals(IntSize(1000, 563), fitImagePreviewSize(1920, 1080, 1000, 800))
        assertEquals(IntSize(450, 800), fitImagePreviewSize(900, 1600, 1000, 800))
    }
}
