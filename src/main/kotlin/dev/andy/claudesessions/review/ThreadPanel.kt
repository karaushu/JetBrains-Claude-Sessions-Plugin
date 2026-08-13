package dev.andy.claudesessions.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import dev.andy.claudesessions.ui.UiText
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A note and the conversation on it, drawn under the line it belongs to.
 *
 * Agent replies are shown in a plain text area, never in an HTML-capable component. A reply is
 * untrusted model output, and an `<a href>` or `<img src>` in it would otherwise become a live
 * link or a network fetch inside the user's editor.
 */
internal class ThreadPanel(
    private val thread: ReviewThread,
    private val targetTitle: String?,
    private val parentDisposable: Disposable,
    private val onReply: (String) -> Unit,
    private val onClose: () -> Unit,
    private val onDraftChange: (String) -> Unit,
    private val draft: String?,
    private val onResize: () -> Unit,
) : JPanel(VerticalLayout(JBUI.scale(6))) {

    private var replyPanel: NewCommentPanel? = null

    init {
        isOpaque = true
        background = ReviewColors.blockBackground
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(ReviewColors.border, 1, 1, 1, 1),
            // The accent stripe on the left is how the state reads at a glance.
            JBUI.Borders.customLine(ReviewColors.accentFor(thread), 0, 3, 0, 0),
            JBUI.Borders.empty(6, 10, 8, 10),
        )

        add(header())
        thread.comments.forEach { add(comment(it)) }
        add(footer())

        if (draft != null) showReplyBox(draft)
    }

    fun focusReply() {
        replyPanel?.focusText()
    }

    private fun header(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(
            JBLabel(ReviewLabels.threadHeader(thread)).apply {
                font = JBFont.smallOrNewUiMedium().asBold()
                foreground = NamedColorUtil.getInactiveTextColor()
            },
            BorderLayout.WEST,
        )
        add(
            InplaceButton(
                IconButton("Close this note", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered),
            ) { onClose() },
            BorderLayout.EAST,
        )
    }

    private fun comment(comment: ReviewComment): JComponent =
        JPanel(VerticalLayout(JBUI.scale(2))).apply {
            isOpaque = false
            add(
                JBLabel(ReviewLabels.author(comment)).apply {
                    font = JBFont.small()
                    foreground = NamedColorUtil.getInactiveTextColor()
                },
            )
            add(body(comment.text))
        }

    /** Read-only and non-opaque, so it reads as text in the editor rather than as a field. */
    private fun body(text: String): JComponent = JBTextArea(UiText.multiLine(text)).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.label()
        border = JBUI.Borders.empty()
    }

    private fun footer(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(
            JBLabel(ReviewLabels.status(thread, targetTitle)).apply {
                font = JBFont.small()
                foreground = NamedColorUtil.getInactiveTextColor()
            },
            BorderLayout.WEST,
        )
        // No reply link while a round is in flight: the agent is still answering this one.
        if (thread.status != ReviewThreadStatus.SENT) {
            add(ActionLink("Reply") { showReplyBox("") }, BorderLayout.EAST)
        }
    }

    private fun showReplyBox(initialText: String) {
        if (replyPanel != null) return
        val panel = NewCommentPanel(
            initialText = initialText,
            placeholder = "What still needs doing?",
            submitLabel = "Add reply",
            parentDisposable = parentDisposable,
            onChange = onDraftChange,
            onSubmit = { onReply(it) },
            onCancel = {
                onDraftChange("")
                hideReplyBox()
            },
            onResize = onResize,
        )
        replyPanel = panel
        add(panel)
        onResize()
        panel.focusText()
    }

    private fun hideReplyBox() {
        replyPanel?.let { remove(it) }
        replyPanel = null
        onResize()
    }
}
