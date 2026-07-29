package dev.andy.claudesessions.terminal

import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.testFramework.LightVirtualFile
import dev.andy.claudesessions.model.SessionState
import dev.andy.claudesessions.ui.UiText

/**
 * The editor tab's backing file for a Claude session terminal.
 *
 * Identity matters: [com.intellij.openapi.fileEditor.FileEditorManager] keys open editors by
 * `VirtualFile` **instance**, so reusing the same object is what focuses an existing tab
 * instead of opening a second one. Instances are held by [ClaudeTerminalTabs].
 *
 * Note that `LightVirtualFile` lives under a `testFramework` package but is production API.
 */
internal class ClaudeTerminalFile(
    sessionId: String,
    val view: TerminalView,
    sessionTitle: String?,
) : LightVirtualFile(displayName(sessionId, sessionTitle)) {

    /**
     * Not a val: a tab opened with `+` starts under a synthetic id and is rebound once
     * Claude reports the real one. See [ClaudeTerminalTabs.resolveLink].
     */
    var sessionId: String = sessionId
        private set

    fun bindSessionId(realSessionId: String) {
        sessionId = realSessionId
    }

    /**
     * Drives the tab icon; updated by [ClaudeTerminalLauncher.syncTabIcons].
     *
     * Null until a status is actually observed. A session started with the `+` button
     * stays null, because its real id does not exist until Claude creates it.
     */
    var state: SessionState? = null
        private set

    init {
        fileType = ClaudeTerminalFileType.of(state)
        // A JComponent has exactly one parent, so two editors over one TerminalView would
        // tear the first one apart. Forbid splitting rather than let that happen.
        putUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT, true)
        // Writable so the tab can be retitled: LightVirtualFileBase.rename calls
        // assertWritable() before setting the name, so a read-only file silently refuses
        // every rename. The platform's own terminal file does the same for this reason.
        isWritable = true
    }

    /**
     * Returns true when the state actually changed, so the caller only refreshes the tab
     * presentation when there is something to redraw.
     */
    fun updateState(newState: SessionState?): Boolean {
        if (newState == state) return false
        state = newState
        fileType = ClaudeTerminalFileType.of(newState)
        return true
    }

    override fun toString(): String = "ClaudeTerminalFile(${sessionId.take(8)}, $state)"

    companion object {
        /** Session titles are model-generated, so they go through [UiText] before display. */
        fun displayName(sessionId: String, sessionTitle: String?): String {
            val label = UiText.oneLine(sessionTitle, max = 40).ifBlank { sessionId.take(8) }
            return "Claude · $label"
        }
    }
}
