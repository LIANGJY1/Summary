package atlas.core

internal object LibraryUpdateCommitPolicy {
    const val TRAILER = "Atlas-Library-Update: true"

    fun shouldAmend(headCommitMessage: String?, remoteContainsHead: Boolean): Boolean {
        val isAtlasUpdate = headCommitMessage
            ?.lineSequence()
            ?.any { it.trim() == TRAILER }
            ?: false
        return isAtlasUpdate && !remoteContainsHead
    }
}
