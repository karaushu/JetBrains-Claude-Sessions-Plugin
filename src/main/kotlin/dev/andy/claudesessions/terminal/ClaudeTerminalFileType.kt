package dev.andy.claudesessions.terminal

import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import dev.andy.claudesessions.model.SessionState
import dev.andy.claudesessions.ui.SessionStateIcons
import javax.swing.Icon

/**
 * Editor tab icons come from the file's *type*, and [FakeFileType] is the mechanism the
 * platform's own terminal-in-editor uses for this.
 *
 * A type's icon is fixed, so to make the tab icon track the session's state we keep one
 * type per [SessionState] and swap the file's type when the state changes. These types are
 * deliberately never registered in `plugin.xml`; they match by instance and are applied
 * programmatically.
 */
internal class ClaudeTerminalFileType private constructor(
    private val state: SessionState?,
) : FakeFileType() {

    override fun getName(): String = "Claude Session (${state?.name ?: "UNKNOWN"})"

    override fun getDescription(): String = "Claude Code session terminal"

    override fun getIcon(): Icon = SessionStateIcons.forTab(state)

    override fun isMyFileType(file: VirtualFile): Boolean = file is ClaudeTerminalFile

    companion object {
        /** `null` covers a tab whose session has no status yet — a brand-new terminal. */
        private val byState: Map<SessionState?, ClaudeTerminalFileType> =
            (SessionState.entries + listOf(null)).associateWith { ClaudeTerminalFileType(it) }

        fun of(state: SessionState?): ClaudeTerminalFileType = byState.getValue(state)
    }
}
