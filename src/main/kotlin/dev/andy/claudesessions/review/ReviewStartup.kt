package dev.andy.claudesessions.review

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Starts watching for replies without waiting for anything to be opened.
 *
 * The agent answers on its own schedule, and the user may well have closed the diff — or the
 * tool window — while it works. The watcher costs nothing until a round is in flight, so the
 * honest arrangement is for it to be running whenever the project is.
 */
internal class ReviewStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.service<ReviewReplyWatcher>()
    }
}
