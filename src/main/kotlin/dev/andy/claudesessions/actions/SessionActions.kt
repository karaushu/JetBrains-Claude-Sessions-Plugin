package dev.andy.claudesessions.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import dev.andy.claudesessions.data.SessionStore
import dev.andy.claudesessions.terminal.ClaudeTerminalLauncher

/** Opens a fresh Claude session in a new editor tab. */
internal class NewSessionAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        ClaudeTerminalLauncher.openNewSession(e.project ?: return)
    }
}

internal class RefreshSessionsAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<SessionStore>()?.requestRefresh()
    }
}

internal class ToggleScopeAction : DumbAwareToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.service<SessionStore>()?.showAllProjects?.value ?: false

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.project?.service<SessionStore>()?.setShowAllProjects(state)
    }
}
