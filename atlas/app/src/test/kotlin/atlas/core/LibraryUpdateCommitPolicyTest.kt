package atlas.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryUpdateCommitPolicyTest {

    @Test
    fun `ordinary HEAD is not amended`() {
        assertFalse(LibraryUpdateCommitPolicy.shouldAmend("update", remoteContainsHead = false))
    }

    @Test
    fun `marked local Atlas update is amended`() {
        val message = "update\n\n${LibraryUpdateCommitPolicy.TRAILER}"

        assertTrue(LibraryUpdateCommitPolicy.shouldAmend(message, remoteContainsHead = false))
    }

    @Test
    fun `published Atlas update is not amended`() {
        val message = "update\n\n${LibraryUpdateCommitPolicy.TRAILER}"

        assertFalse(LibraryUpdateCommitPolicy.shouldAmend(message, remoteContainsHead = true))
    }

    @Test
    fun `marker must be a complete commit message line`() {
        assertFalse(
            LibraryUpdateCommitPolicy.shouldAmend(
                "note: ${LibraryUpdateCommitPolicy.TRAILER}",
                remoteContainsHead = false,
            ),
        )
    }
}
