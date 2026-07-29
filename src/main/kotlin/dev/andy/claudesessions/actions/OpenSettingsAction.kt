package dev.andy.claudesessions.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import dev.andy.claudesessions.settings.ClaudeSessionsConfigurable

/** Opens the plugin's settings page; without this it is only findable by searching. */
internal class OpenSettingsAction : DumbAwareAction(
    "Claude Sessions Settings",
    "Background usage fetching and session status",
    AllIcons.General.Settings,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ClaudeSessionsConfigurable::class.java)
    }
}
