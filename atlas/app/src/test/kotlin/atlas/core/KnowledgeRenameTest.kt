package atlas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnowledgeRenameTest {
    @Test
    fun `builds a safe renamed path and preserves markdown extension`() {
        assertEquals("knowledge-base/android/new-name.md", KnowledgeTree.renamedPath("knowledge-base/android/old.md", "new-name", false))
        assertEquals("knowledge-base/android/new-dir", KnowledgeTree.renamedPath("knowledge-base/android/old-dir", "new-dir", true))
        assertNull(KnowledgeTree.safeRenameName("bad/name", false))
    }
}
