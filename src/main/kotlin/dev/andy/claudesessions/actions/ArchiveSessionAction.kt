package dev.andy.claudesessions.actions

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.andy.claudesessions.data.SessionArchiver
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.ui.UiText

/**
 * Removes a session from the list, without a confirmation dialog.
 *
 * No dialog is deliberate, and so is the fact that this *moves* rather than deletes: an
 * unconfirmed click has to be recoverable. The transcript lands in
 * `~/.claude/claudesessions/archive/`, and the notification offers to put it back.
 */
internal class ArchiveSessionAction(
    private val item: SessionItem,
    private val onArchived: () -> Unit,
) : DumbAwareAction("Delete", "Move this session out of Claude's history", AllIcons.Actions.GC) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val live = item.isLive
        e.presentation.isEnabled = SessionArchiver.canArchive(item)
        e.presentation.description = if (live) {
            "This session is still running — stop it first"
        } else {
            "Move this session out of Claude's history"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val archived = SessionArchiver.archive(item) ?: run {
            notify(project, "Could not delete session", NotificationType.WARNING)
            return
        }
        onArchived()

        val title = UiText.oneLine(item.summary.title, max = 48).ifBlank { item.sessionId.take(8) }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification("Deleted \"$title\"", NotificationType.INFORMATION)
            .setSubtitle("Moved to ${SessionArchiver.archiveDir}")
            .addAction(
                NotificationAction.createSimpleExpiring("Undo") {
                    if (SessionArchiver.restore(archived)) {
                        onArchived()
                    } else {
                        notify(project, "Could not restore session", NotificationType.WARNING)
                    }
                },
            )
            .notify(project)
    }

    private fun notify(project: com.intellij.openapi.project.Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(text, type)
            .notify(project)
    }

    private companion object {
        const val GROUP = "Claude Sessions"
    }
}
