package dev.andy.claudesessions.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import dev.andy.claudesessions.usage.UsageFormat
import dev.andy.claudesessions.usage.UsageRefreshResult
import dev.andy.claudesessions.usage.UsagePanel
import dev.andy.claudesessions.usage.UsageService
import java.time.Instant
import javax.swing.JComponent

/**
 * Toolbar widget showing the 5-hour usage percentage, with the full breakdown on click.
 *
 * The figures come from Claude's own cache in `~/.claude.json`, so nothing here touches a
 * credential. Opening the dropdown also asks Claude to refresh that cache, because nothing
 * else does while Claude is idle.
 */
internal class UsageLimitsAction : DumbAwareAction(), CustomComponentAction {

    // Reads a file, so it must not run on the EDT.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val service = service<UsageService>()
        val snapshot = service.snapshot()
        val session = snapshot?.sessionLimit()
        val now = Instant.now()

        e.presentation.isEnabled = true

        if (service.isRefreshing()) {
            e.presentation.description = "Refreshing usage limits…"
        }
        if (snapshot == null || session == null) {
            e.presentation.text = "usage"
            e.presentation.description = "Usage limits — click to fetch from Claude"
            return
        }

        // A trailing dot marks a reading we would not present as current.
        val stale = snapshot.isStale(now) || session.hasRolledOver(now)
        e.presentation.text = UsageFormat.percent(session.percent) + if (stale) " ·" else ""
        e.presentation.description = buildString {
            append("5-hour limit ${UsageFormat.percent(session.percent)}")
            if (!session.hasRolledOver(now)) {
                append(" · resets ${UsageFormat.resetShort(session.resetsAt, now)}")
            }
            append(" · ${UsageFormat.asOf(snapshot.fetchedAt, now)}")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val service = service<UsageService>()
        val panel = UsagePanel(service.cachedSnapshot())

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setResizable(false)
            .setMovable(false)
            .setRequestFocus(false)
            .createPopup()

        val anchor = e.presentation.getClientProperty(CustomComponentAction.COMPONENT_KEY)
        if (anchor != null) {
            popup.show(RelativePoint.getSouthWestOf(anchor))
        } else {
            popup.showInBestPositionFor(e.dataContext)
        }

        // Show what we have straight away, then update in place once Claude answers.
        panel.showRefreshing(service.cachedSnapshot())
        popup.pack(true, true)
        service.refreshInBackground { fresh, result ->
            if (popup.isDisposed) return@refreshInBackground
            panel.showResult(fresh, result.problem(), atFloor = result is UsageRefreshResult.AlreadyFresh)
            popup.pack(true, true)
        }
    }

    /** Text rather than an icon: the percentage is the whole point of the widget. */
    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        ActionButtonWithText(this, presentation, place, JBUI.size(UNSET_SIZE))

    private companion object {
        /** Lets the button size itself to its text, the way toolbar buttons normally do. */
        const val UNSET_SIZE = -1
    }
}
