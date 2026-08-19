package dev.andy.claudesessions.notify

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.SystemNotifications
import dev.andy.claudesessions.data.SessionIndexer
import dev.andy.claudesessions.hooks.HookEvent
import dev.andy.claudesessions.hooks.HookEventBus
import dev.andy.claudesessions.settings.ClaudeSessionsSettings
import dev.andy.claudesessions.terminal.ClaudeTerminalTabs
import dev.andy.claudesessions.terminal.SessionOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Announces that Claude finished, or that it is waiting on you.
 *
 * Two notifications are posted for one event, and they do not overlap in practice:
 *
 * - An OS notification, which the platform drops when the IDE is the active application.
 *   That gate is exactly the behaviour wanted here, so it is left to the platform rather
 *   than re-implemented — the point of this feature is being told while you are elsewhere.
 * - An IDE balloon, which is what you see when the IDE *is* focused, and which persists in
 *   the Notifications tool window so a turn that ended while you were in another editor is
 *   not lost. Its display can be tuned per group under Settings | Notifications.
 *
 * The hook log is one file per machine, so every Claude on it arrives here: the desktop app, a
 * plain terminal, another IDE window. By default only sessions running in one of this IDE's own
 * tabs are announced — see [ownerOf] — because those are the ones this window is watching for
 * you. `notifyOnlyIdeSessions` turns that filter off.
 */
@Service(Service.Level.APP)
internal class SessionNotifier(private val scope: CoroutineScope) {

    private val indexer = SessionIndexer()

    private val settings get() = service<ClaudeSessionsSettings>()

    init {
        scope.launch {
            service<HookEventBus>().events.collect { event -> handle(event) }
        }
    }

    private suspend fun handle(event: HookEvent) {
        // Cheap rejections first: most events are neither a turn end nor a prompt, and
        // resolving a session's name is the only expensive part of this path.
        val interesting = event.eventName == "Stop" ||
            event.eventName == "Notification"
        if (!interesting || !settings.notifiesAnything) return

        // Before resolving the name, which is the only part of this path that reads a file.
        val owner = ownerOf(event)
        if (owner == null && settings.notifyOnlyIdeSessions) return

        val notice = SessionNotices.from(
            event = event,
            sessionName = withContext(Dispatchers.IO) { nameFor(event) },
            notifyOnTurnEnd = settings.notifyOnTurnEnd,
            notifyOnPrompt = settings.notifyOnPrompt,
        ) ?: return

        // The tab's own project where there is one: it owns the session outright, whereas
        // matching directories only says the session is somewhere inside the project.
        show(notice, owner ?: projectFor(notice.cwd))
    }

    /**
     * The open project whose tab is running this session, or null if no window here is.
     *
     * A tab is the only honest evidence that the IDE started a session: the terminal was
     * launched from this window and dies with it. A `+` tab whose session id is not known yet
     * counts too, on the strength of its launch directory — see
     * [ClaudeTerminalTabs.hasPendingLinkIn] — or the first turn of every new session would go
     * unannounced.
     *
     * Sessions opened as a fork or through the background-agents view keep a synthetic tab id
     * and so are not recognised, exactly as they are not recognised as review targets.
     */
    private fun ownerOf(event: HookEvent): Project? =
        ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .firstOrNull { project ->
                val tabs = project.service<ClaudeTerminalTabs>()
                tabs.find(event.sessionId) != null || tabs.hasPendingLinkIn(event.cwd)
            }

    /**
     * What to call the session.
     *
     * Claude's own title, where a hook event has carried one, then the transcript, then the
     * directory it is running in. The transcript read is cached by [SessionIndexer] and only
     * reached for a session whose title was never announced — a session older than the log's
     * last truncation, typically.
     */
    private fun nameFor(event: HookEvent): String? {
        service<HookEventBus>().titleFor(event.sessionId)?.let { return it }

        event.transcriptPath
            ?.let { runCatching { Path.of(it) }.getOrNull() }
            ?.let { indexer.summarise(it) }
            ?.title
            ?.let { return it }

        return event.cwd?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }
    }

    /**
     * The open project a session belongs to, by longest matching base path.
     *
     * Longest wins because a monorepo and one of its packages can both be open, and the
     * notification belongs to the window that most closely owns the directory. A session in a
     * worktree outside its project matches nothing and is announced without a project, which
     * costs the "Open Session" action but still tells you what happened.
     */
    private fun projectFor(cwd: String?): Project? {
        if (cwd == null) return null
        return ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .filter { project ->
                val base = project.basePath ?: return@filter false
                cwd == base || cwd.startsWith("$base/")
            }
            .maxByOrNull { it.basePath?.length ?: 0 }
    }

    private fun show(notice: SessionNotice, project: Project?) {
        // Kind and session, deliberately not the wording: Claude's closing message and the
        // session title are conversation content, and the IDE log is not the place for it.
        thisLogger().info(
            "Announcing ${notice.kind} for ${notice.sessionId.take(8)} " +
                "in ${project?.name ?: "no open project"}",
        )

        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
        val notification = group
            .createNotification(notice.title, notice.body, NotificationType.INFORMATION)
            // Collapses repeats in the Notifications tool window rather than stacking a row
            // per turn, and is what per-notification settings are keyed on.
            .setDisplayId("claude.session.${notice.kind.name.lowercase()}")

        if (project != null) {
            notification.addAction(
                NotificationAction.createSimpleExpiring("Open Session") {
                    openSession(project, notice)
                },
            )
        }
        notification.notify(project)

        // No-op while the IDE is focused; see the class comment.
        SystemNotifications.getInstance().notify(GROUP, notice.title, notice.body) {
            ApplicationManager.getApplication().invokeLater {
                if (project != null && !project.isDisposed) openSession(project, notice)
            }
        }
    }

    private fun openSession(project: Project, notice: SessionNotice) {
        ToolWindowManager.getInstance(project).getToolWindow("Claude Sessions")?.activate(null)
        // Through the guarded path: an unguarded --resume here could seize a session that
        // an external terminal still owns, or one the CLI refuses to resume.
        SessionOpener.openById(project, notice.sessionId, notice.cwd, notice.sessionName)
    }

    private companion object {
        /** Its own group, so its balloons can be tuned without touching the plugin's others. */
        const val GROUP = "Claude Session Status"
    }
}
