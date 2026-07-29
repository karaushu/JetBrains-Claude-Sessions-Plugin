package dev.andy.claudesessions.actions

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import dev.andy.claudesessions.data.SessionStopper
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.ui.UiText

/**
 * Stops a running session.
 *
 * Unlike delete, this always confirms: terminating a session can lose an in-flight turn,
 * and unlike an archived transcript there is nothing to undo afterwards.
 */
internal class StopSessionAction(
    private val item: SessionItem,
    private val onStopped: () -> Unit,
) : DumbAwareAction("Stop", "Terminate this running session", AllIcons.Actions.Suspend) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = SessionStopper.canStop(item)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val live = item.live ?: return
        val label = UiText.oneLine(item.summary.title, max = 48).ifBlank { item.sessionId.take(8) }

        val proceed = MessageDialogBuilder
            .yesNo(
                "Stop Session",
                buildString {
                    appendLine("Stop \"$label\"?")
                    appendLine()
                    append("Its process (pid ${live.pid}) will be terminated")
                    if (item.state == dev.andy.claudesessions.model.SessionState.RUNNING) {
                        append(" while it is working, so the current turn will be lost")
                    }
                    appendLine(".")
                    if (item.summary.hasTranscript) {
                        appendLine("The transcript is kept, so the session can be resumed later.")
                    } else {
                        appendLine("Nothing has been saved, so this session cannot be resumed afterwards.")
                    }
                    if (item.isBackgroundAgent) {
                        append("This is a background agent — Claude's daemon may start a replacement.")
                    }
                },
            )
            .yesText("Stop")
            .noText("Cancel")
            .asWarning()
            .ask(project)
        if (!proceed) return

        // Signalling and waiting must not run on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SessionStopper.stop(live.pid)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                report(project, label, result)
                onStopped()
            }
        }
    }

    private fun report(project: Project, label: String, result: SessionStopper.Result) {
        val (text, type) = when (result) {
            SessionStopper.Result.Stopped -> "Stopped \"$label\"" to NotificationType.INFORMATION
            SessionStopper.Result.AlreadyGone -> "\"$label\" had already exited" to NotificationType.INFORMATION
            // A stale pid file pointing at a recycled pid; refusing is the safe outcome.
            SessionStopper.Result.NotClaude ->
                "Did not stop \"$label\": that process is no longer Claude" to NotificationType.WARNING
            is SessionStopper.Result.Failed ->
                "Could not stop \"$label\": ${result.message}" to NotificationType.WARNING
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Sessions")
            .createNotification(text, type)
            .notify(project)
    }
}
