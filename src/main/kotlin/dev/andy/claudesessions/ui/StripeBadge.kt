package dev.andy.claudesessions.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.BadgeIconSupplier
import dev.andy.claudesessions.ClaudeSessionsIcons
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.model.SessionState

/**
 * Badges the tool window's stripe icon so a session needing attention is visible even
 * while the tool window is collapsed.
 *
 * Per the UI guidelines the badge decorates the *same* icon; the glyph never changes.
 */
internal class StripeBadge(private val project: Project) {

    private val icons = BadgeIconSupplier(ClaudeSessionsIcons.ToolWindow)

    private var lastApplied: Aggregate? = null

    private enum class Aggregate { NEEDS_ATTENTION, RUNNING, QUIET }

    fun update(items: List<SessionItem>) {
        val aggregate = when {
            items.any { it.state == SessionState.NEEDS_INPUT } -> Aggregate.NEEDS_ATTENTION
            items.any { it.state == SessionState.RUNNING } -> Aggregate.RUNNING
            else -> Aggregate.QUIET
        }
        if (aggregate == lastApplied) return
        lastApplied = aggregate

        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(ClaudeSessionsToolWindowFactory.TOOL_WINDOW_ID) ?: return

        toolWindow.setIcon(
            when (aggregate) {
                Aggregate.NEEDS_ATTENTION -> icons.warningIcon
                Aggregate.RUNNING -> icons.liveIndicatorIcon
                Aggregate.QUIET -> icons.originalIcon
            },
        )
    }
}
