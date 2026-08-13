package dev.andy.claudesessions.terminal

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.andy.claudesessions.model.SessionItem
import java.util.UUID

/**
 * Opens (or re-focuses) a Claude session as a tab in the main editor area.
 *
 * The terminal is created through the platform's Terminal API but with
 * `shouldAddToToolWindow(false)`, so the session starts without ever appearing in the
 * Terminal tool window. Its [com.intellij.terminal.frontend.view.TerminalView] is then
 * hosted by [ClaudeTerminalFileEditor].
 *
 * We deliberately do not reuse the platform's `Terminal.MoveToEditor` path: its virtual
 * file is Kotlin-internal, and terminals hosted by it are terminated when the tab is
 * dragged (IJPL-165734, fixed only in 2026.2.1).
 */
internal object ClaudeTerminalLauncher {

    @RequiresEdt
    fun isOpen(project: Project, sessionId: String): Boolean =
        project.service<ClaudeTerminalTabs>().find(sessionId) != null

    /** Resumes an existing session, or focuses its tab if it is already open. */
    @RequiresEdt
    fun openOrFocus(
        project: Project,
        sessionId: String,
        workingDirectory: String?,
        sessionTitle: String?,
    ) {
        project.service<ClaudeTerminalTabs>().find(sessionId)?.let { existing ->
            focus(project, existing)
            return
        }
        launch(
            project = project,
            key = sessionId,
            workingDirectory = workingDirectory,
            title = sessionTitle,
            command = ClaudeCommands.resume(sessionId),
        )
    }

    /**
     * Starts a brand-new session in its own tab.
     *
     * The real session id does not exist until Claude creates it, so the tab is keyed by a
     * synthetic id. Once Claude writes its transcript the session appears in the list as
     * usual; it just is not linked back to this tab.
     */
    @RequiresEdt
    fun openNewSession(project: Project) {
        val cwd = project.basePath
        val file = launch(
            project = project,
            key = "new:${UUID.randomUUID()}",
            workingDirectory = cwd,
            title = "New session",
            command = ClaudeCommands.newSession(),
        )
        project.service<ClaudeTerminalTabs>()
            .awaitLink(file, cwd, System.currentTimeMillis())
    }

    /** Branches a copy of a session that cannot be resumed in place. */
    @RequiresEdt
    fun openForked(project: Project, sessionId: String, workingDirectory: String?, title: String?) {
        launch(
            project = project,
            key = "fork:${UUID.randomUUID()}",
            workingDirectory = workingDirectory,
            title = title?.let { "Copy of $it" } ?: "Forked session",
            command = ClaudeCommands.resumeForked(sessionId),
        )
    }

    /** Opens Claude's background-agent view, the only way to attach to a running agent. */
    @RequiresEdt
    fun openAgentsView(project: Project, workingDirectory: String?) {
        launch(
            project = project,
            key = "agents:${UUID.randomUUID()}",
            workingDirectory = workingDirectory,
            title = "Background agents",
            command = ClaudeCommands.agents(),
        )
    }

    @RequiresEdt
    private fun launch(
        project: Project,
        key: String,
        workingDirectory: String?,
        title: String?,
        command: String,
    ): ClaudeTerminalFile {
        val tab = TerminalToolWindowTabsManager.getInstance(project)
            .createTabBuilder()
            .apply { workingDirectory?.let { workingDirectory(it) } }
            // Neutralise any Claude session markers the IDE inherited, or the CLI treats
            // this as a nested child and writes neither a transcript nor a status file.
            .envVariables(ClaudeEnvironment.overridesFor())
            // Start the session but never add it to the Terminal tool window.
            .shouldAddToToolWindow(false)
            // deferSessionStartUntilUiShown is left at its default of true on purpose.
            // That path waits for the *component* to be shown — which an editor tab
            // satisfies — and only then starts the shell, sizing the PTY from the laid-out
            // component. Forcing it false starts the shell against an unsized component, so
            // the grid is a fallback width and the CLI renders into a narrow column for the
            // life of the session.
            .closeOnProcessTermination(true)
            .tabName(ClaudeTerminalFile.displayName(key, title))
            .createTab()

        val file = ClaudeTerminalFile(key, tab.view, title)
        project.service<ClaudeTerminalTabs>().remember(file)
        focus(project, file)

        // sendText buffers until the shell process is ready, so this cannot race. Do not
        // await shell integration — it may never complete on fish or nushell.
        tab.view.createSendTextBuilder()
            .shouldExecute()
            .useBracketedPasteMode()
            .send(command)

        return file
    }

    /**
     * Called on every refresh: adopts newly created sessions into their `+` tabs, then
     * brings each open tab's icon and title in line with its session.
     */
    @RequiresEdt
    fun syncTabs(project: Project, items: List<SessionItem>) {
        linkPendingSessions(project, items)
        syncTabPresentation(project, items)
    }

    /**
     * Keeps each open tab's icon and title matching its session.
     *
     * The title is re-checked every pass rather than set once on adoption: Claude names a
     * session a little after it starts, so a tab adopted immediately would otherwise keep
     * whatever placeholder it was created with.
     */
    @RequiresEdt
    private fun syncTabPresentation(project: Project, items: List<SessionItem>) {
        val tabs = project.service<ClaudeTerminalTabs>()
        if (tabs.openSessionIds().isEmpty()) return

        val itemsById = items.associateBy { it.sessionId }
        for (sessionId in tabs.openSessionIds()) {
            val file = tabs.find(sessionId) ?: continue
            // Not yet adopted: its real id is unknown, so there is nothing to match against.
            val item = itemsById[sessionId] ?: continue

            var changed = file.updateState(item.state)

            // Only rename once Claude has actually named the session. Renaming on a null
            // title would replace a meaningful placeholder with a bare id, then replace that
            // again moments later.
            val title = item.summary.title
            val desiredName = if (title != null) ClaudeTerminalFile.displayName(sessionId, title) else file.name
            if (file.name != desiredName) {
                // rename fires no VFS event, hence the explicit refresh below. Failures are
                // logged rather than swallowed — a silently refused rename is what kept tabs
                // showing 'New session' long after the session had been named.
                runCatching { file.rename(this, desiredName) }
                    .onFailure { thisLogger().warn("Could not retitle tab for $sessionId", it) }
                changed = true
            }

            if (changed) FileEditorManager.getInstance(project).updateFilePresentation(file)
        }
    }

    /**
     * Types a line into a session that is already running, as if the user had pasted it.
     *
     * Used by the diff review to hand a round to an agent. Returns false when the session has
     * no open tab, which is the only failure the caller can do anything about: there is no
     * delivery callback to wait for, because `sendText` buffers until the shell is ready.
     */
    @RequiresEdt
    fun sendToSession(project: Project, sessionId: String, text: String): Boolean {
        val file = project.service<ClaudeTerminalTabs>().find(sessionId) ?: return false
        return runCatching {
            file.view.createSendTextBuilder()
                .shouldExecute()
                .useBracketedPasteMode()
                .send(text)
        }.onFailure { thisLogger().warn("Could not send text to session $sessionId", it) }
            .isSuccess
    }

    /**
     * Binds tabs opened with `+` to the session Claude actually created; see
     * [SessionAdoption] for how the match is made.
     */
    @RequiresEdt
    private fun linkPendingSessions(project: Project, items: List<SessionItem>) {
        val tabs = project.service<ClaudeTerminalTabs>()
        val links = tabs.pendingLinks()
        if (links.isEmpty()) return

        val claimed = tabs.openSessionIds().toMutableSet()
        for (link in links) {
            val match = SessionAdoption.pick(items, link.workingDirectory, link.launchedAtMillis, claimed)
                ?: continue

            tabs.resolveLink(link, match.sessionId)
            claimed += match.sessionId
            thisLogger().info("Adopted session ${match.sessionId} into its '+' tab")
        }

        for (expired in tabs.expirePendingLinks(System.currentTimeMillis())) {
            // Worth a warning, not a debug line: it means a '+' tab will never be linked to
            // its session, so clicking that session reports it as running elsewhere.
            thisLogger().warn(
                "Gave up adopting a session for a '+' tab in ${expired.workingDirectory}; " +
                    "no session started after ${expired.launchedAtMillis} was found",
            )
        }
    }

    @RequiresEdt
    private fun focus(project: Project, file: ClaudeTerminalFile) {
        // The three-argument form is required. `searchForOpen = true` makes the platform
        // reuse the window that already holds the file; the two-argument form targets the
        // *current* window and, because the file forbids splitting, would close the
        // existing tab first — killing the terminal.
        FileEditorManager.getInstance(project).openFile(file, true, true)
    }
}
