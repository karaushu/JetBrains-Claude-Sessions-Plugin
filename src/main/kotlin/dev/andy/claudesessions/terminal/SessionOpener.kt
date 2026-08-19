package dev.andy.claudesessions.terminal

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import dev.andy.claudesessions.data.LiveSessionWatcher
import dev.andy.claudesessions.data.SessionStore
import dev.andy.claudesessions.model.LiveStatus
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.ui.UiText

/**
 * The one guarded path to opening a session in a terminal tab.
 *
 * Every entry point goes through the same three checks — nothing to resume yet, running as
 * a background agent, live in another process. A notification's "Open Session" used to call
 * the launcher directly and could `--resume` a session another process still owned.
 */
internal object SessionOpener {

    /** Opens [item] with every guard applied. Call on the EDT. */
    fun open(project: Project, item: SessionItem) {
        openGuarded(
            project = project,
            sessionId = item.sessionId,
            cwd = item.summary.cwd,
            title = item.summary.title,
            live = item.live,
            hasTranscript = item.summary.hasTranscript,
        )
    }

    /**
     * Opens by id when no [SessionItem] is at hand — a notification clicked while the tool
     * window was closed and the store not yet populated. The liveness guard falls back to
     * the pid files, read off the EDT.
     */
    fun openById(project: Project, sessionId: String, cwd: String?, title: String?) {
        val item = project.service<SessionStore>().items.value
            .firstOrNull { it.sessionId == sessionId }
        if (item != null) {
            open(project, item)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val live = LiveSessionWatcher().poll()[sessionId]
            ApplicationManager.getApplication().invokeLater(
                {
                    openGuarded(
                        project = project,
                        sessionId = sessionId,
                        cwd = live?.cwd ?: cwd,
                        title = title,
                        live = live,
                        // The hook event behind the notification means the session has run,
                        // so there is a conversation for --resume to find.
                        hasTranscript = true,
                    )
                },
                project.disposed,
            )
        }
    }

    private fun openGuarded(
        project: Project,
        sessionId: String,
        cwd: String?,
        title: String?,
        live: LiveStatus?,
        hasTranscript: Boolean,
    ) {
        // Already showing in an editor tab: just focus it.
        if (ClaudeTerminalLauncher.isOpen(project, sessionId)) {
            ClaudeTerminalLauncher.openOrFocus(project, sessionId, cwd, title)
            return
        }

        // Nothing was ever written, so there is nothing for --resume to find: Claude would
        // answer "No conversation found with session ID".
        if (!hasTranscript) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP)
                .createNotification(
                    "Nothing to resume yet",
                    "This session has not saved anything, so it cannot be resumed. " +
                        "Send it a message first, or start a new one with +.",
                    NotificationType.INFORMATION,
                )
                .notify(project)
            return
        }

        // A running background agent cannot be resumed in place: it would have two owners.
        // Offer the two things that do work rather than opening a terminal that refuses.
        if (live?.kind == "bg") {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP)
                .createNotification(
                    "Running as a background agent",
                    "This session cannot be resumed while it runs in the background. " +
                        "Attach to it from Claude's agent view, or branch a copy.",
                    NotificationType.INFORMATION,
                )
                .addAction(
                    NotificationAction.createSimpleExpiring("Attach") {
                        ClaudeTerminalLauncher.openAgentsView(project, cwd)
                    },
                )
                .addAction(
                    NotificationAction.createSimpleExpiring("Branch a copy") {
                        ClaudeTerminalLauncher.openForked(project, sessionId, cwd, title)
                    },
                )
                .notify(project)
            return
        }

        // Resuming a session that is live in another process would fight over the transcript.
        if (live != null) {
            val name = live.name ?: sessionId.take(8)
            val proceed = MessageDialogBuilder
                .yesNo(
                    "Session Is Already Running",
                    "Session \"${UiText.oneLine(name)}\" is running in another process (pid ${live.pid}). " +
                        "Resuming it here may conflict with that session.\n\nResume anyway?",
                )
                .asWarning()
                .ask(project)
            if (!proceed) return
        }

        ClaudeTerminalLauncher.openOrFocus(project, sessionId, cwd, title)
    }

    private const val GROUP = "Claude Sessions"
}
