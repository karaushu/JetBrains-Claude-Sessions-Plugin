package dev.andy.claudesessions.notify

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Brings the notifier up without waiting for the tool window to be opened.
 *
 * Application services are created on first request, and nothing else asks for this one:
 * being told that Claude finished is most useful precisely when you have not touched the
 * session list. Both services are singletons, so running this per project is idempotent.
 */
internal class SessionNotifierStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<SessionNotifier>()
    }
}
