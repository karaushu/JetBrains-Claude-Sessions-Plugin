package dev.andy.claudesessions.review

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import dev.andy.claudesessions.hooks.HookEventBus
import dev.andy.claudesessions.terminal.ClaudeTerminalTabs
import dev.andy.claudesessions.ui.SessionStateIcons
import javax.swing.JComponent

/**
 * Sends every note written in the diff gutters to the session the button names.
 *
 * One click, and no picker in the way: the target is nearly always the same session, and being
 * made to choose it every time was the first thing that grated in use.
 * [PickReviewTargetAction] is the arrow that sits beside this and changes the target, so the two
 * read as a split button while staying two ordinary toolbar buttons.
 *
 * Two details are load-bearing, both learned the hard way. The action carries an icon and a
 * template text, because a text-only button is created before the first `update()` gives it
 * anything to measure, and one born zero-wide never grows. And `isVisible` is never touched: a
 * custom component created while its action is hidden stays absent from the toolbar afterwards,
 * which is how this button once managed to be both configured and nowhere to be seen.
 */
internal class SendReviewNotesAction : DumbAwareAction(), CustomComponentAction {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            e.presentation.text = ReviewLabels.sendButton(0, null, 0)
            e.presentation.description = ReviewLabels.sendDescription(0, null)
            return
        }

        val store = project.service<ReviewStore>()
        val open = store.threads.value.count { it.status != ReviewThreadStatus.CLOSED }
        val count = store.sendableThreads().size
        val sender = project.service<ReviewSender>()
        val target = sender.resolveTarget()
        val title = sender.titleOf(target)

        val full = ReviewLabels.sendButton(count, title?.let { ReviewTargets.title(it) }, open)
        e.presentation.isEnabled = count > 0 && target != null
        // The tool window is narrow and shares its toolbar with the usage figure, so there the
        // label shrinks to a count and the sentence moves into the tooltip.
        e.presentation.text =
            if (e.place == ActionPlaces.TOOLWINDOW_CONTENT) ReviewLabels.sendButtonCompact(count)
            else full
        e.presentation.description =
            if (count == 0) ReviewLabels.sendDescription(count, title) else full
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = project.service<ReviewSender>().resolveTarget()
        if (target == null) {
            ReviewSendUi.report(project, "Open a Claude session to send these notes to")
            return
        }
        ReviewSendUi.send(project, target)
    }

    /** Text rather than an icon alone: the count and the target session are the point. */
    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        ActionButtonWithText(this, presentation, place, JBUI.size(UNSET_SIZE))

    private companion object {
        /** Lets the button size itself to its text, as toolbar buttons normally do. */
        const val UNSET_SIZE = -1
    }
}

/**
 * The arrow beside the send button: pick another session, or clear the notes.
 *
 * Choosing a session both remembers it and sends straight away — a picker that only remembered
 * would put the second click back.
 */
internal class PickReviewTargetAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val notes = project.service<ReviewStore>().threads.value
            .count { it.status != ReviewThreadStatus.CLOSED }
        e.presentation.isEnabled = notes > 0
        e.presentation.description =
            if (notes > 0) "Send the notes to another session, or clear them"
            else "No review notes yet"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewSendUi.pickTarget(project, e)
    }
}

/**
 * The one path to sending, the one place a refusal is explained, and the popup behind the arrow.
 *
 * Shared so a review can never leave by a route that skips the send gate.
 */
internal object ReviewSendUi {

    fun send(project: Project, sessionId: String, confirmed: Boolean = false) {
        project.service<ReviewSender>().send(sessionId, confirmed) { result ->
            when (result) {
                // The notes themselves now read "sent to …"; a balloon would say it twice.
                is SendResult.Sent -> Unit
                is SendResult.Refused -> refused(project, sessionId, result.gate)
            }
        }
    }

    fun pickTarget(project: Project, e: AnActionEvent) {
        val tabs = project.service<ClaudeTerminalTabs>()
        val sender = project.service<ReviewSender>()

        val options = ReviewTargets.options(
            openSessionIds = tabs.openSessionIds(),
            focusOrder = tabs.focusedSessionIds(),
            titles = tabs.openSessionIds().associateWith { sender.titleOf(it) },
            states = service<HookEventBus>().states(),
            chosen = project.service<ReviewStore>().targetSessionId.value,
        )

        val group = DefaultActionGroup()
        options.forEach { option ->
            group.add(
                object : DumbAwareAction(option.title, null, SessionStateIcons.forTab(option.state)) {
                    override fun actionPerformed(event: AnActionEvent) = send(project, option.sessionId)
                },
            )
        }
        if (options.isEmpty()) {
            group.add(
                object : DumbAwareAction("No Claude session open") {
                    override fun actionPerformed(event: AnActionEvent) = Unit
                }.apply { templatePresentation.isEnabled = false },
            )
        }
        group.add(Separator.getInstance())
        group.add(ClearReviewNotesAction(project))

        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Review Notes",
            group,
            SimpleDataContext.getProjectContext(project),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )

        // Anchored to the button that was clicked. Without this the popup lands in the middle of
        // the screen, because a toolbar widget's data context carries no position of its own.
        val anchor = e.inputEvent?.component
            ?: e.presentation.getClientProperty(CustomComponentAction.COMPONENT_KEY)
        if (anchor != null) popup.show(RelativePoint.getSouthWestOf(anchor as JComponent))
        else popup.showInBestPositionFor(e.dataContext)
    }

    private fun refused(project: Project, sessionId: String, gate: SendGate) {
        val problem = gate.problem() ?: return
        if (!gate.needsConfirmation) {
            report(project, problem)
            return
        }
        val proceed = MessageDialogBuilder
            .yesNo("Send Review Notes?", "$problem. Send them anyway?")
            .asWarning()
            .ask(project)
        if (proceed) send(project, sessionId, confirmed = true)
    }

    fun report(project: Project, problem: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification("Review notes not sent", problem, NotificationType.WARNING)
            .setDisplayId("claude.review.notSent")
            .notify(project)
    }

    private const val GROUP = "Claude Sessions"
}

/**
 * Throws away every note in the project.
 *
 * Behind the arrow rather than on the toolbar, and behind a confirmation, because it discards the
 * user's own writing — including answers the agent has already given. The count is in the
 * question so nobody discards more than they meant to.
 */
private class ClearReviewNotesAction(private val project: Project) :
    DumbAwareAction("Clear All Notes…", "Discard every review note in this project", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val store = project.service<ReviewStore>()
        val notes = store.threads.value.size
        if (notes == 0) return

        val confirmed = MessageDialogBuilder
            .yesNo("Clear Review Notes?", "Discard ${ReviewLabels.notes(notes)} and every reply on them?")
            .asWarning()
            .yesText("Clear")
            .noText("Keep")
            .ask(project)
        if (!confirmed) return
        store.clearAll()
        // Written out now: a confirmed clear-all must not come back because the IDE went down
        // before its next scheduled save.
        ReviewPersistence.scheduleSave(project)
    }
}
