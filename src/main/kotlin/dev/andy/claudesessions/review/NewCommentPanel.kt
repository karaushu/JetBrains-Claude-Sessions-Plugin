package dev.andy.claudesessions.review

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.BorderLayout
import java.awt.Cursor
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The box that opens under a line when the `+` is clicked.
 *
 * The primary button says "Add note", not "Send". Nothing reaches Claude until the toolbar
 * button goes, and a button here that said Send would break the whole point of collecting five
 * or ten notes before handing them over in one round.
 *
 * Every keystroke is written through to [ReviewDrafts], so the agent rewriting the file under
 * the diff — which makes the viewer rebuild what it draws — cannot eat a half-typed note.
 */
internal class NewCommentPanel(
    initialText: String,
    placeholder: String,
    submitLabel: String,
    parentDisposable: Disposable,
    /** Shown above the field. Null inside a thread, where the thread's own header says it. */
    header: String? = null,
    private val onChange: (String) -> Unit,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit,
    private val onResize: () -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {

    private val textArea = JBTextArea(initialText).apply {
        lineWrap = true
        wrapStyleWord = true
        rows = MIN_ROWS
        // Prose, not code: the label font rather than the editor's.
        font = JBFont.label()
        emptyText.text = placeholder
    }

    /** Gives the field the IDE's own rounded outline and focus ring; see [ReviewTextField]. */
    private val field = ReviewTextField(textArea)

    init {
        isOpaque = true
        // Only the field itself should show an I-beam; the block's own padding must not.
        cursor = Cursor.getDefaultCursor()
        // A box on the editor's own background reads as an empty stretch of code. It needs a line
        // all the way round, a tinted fill and the same accent stripe a thread carries, or it
        // simply is not there as far as the eye is concerned.
        background = ReviewColors.blockBackground
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(ReviewColors.border, 1, 1, 1, 1),
            JBUI.Borders.customLine(ReviewColors.pending, 0, 3, 0, 0),
            JBUI.Borders.empty(6, 10, 8, 10),
        )

        header?.let { add(headerLabel(it), BorderLayout.NORTH) }
        add(field, BorderLayout.CENTER)
        add(buttons(submitLabel), BorderLayout.SOUTH)

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        })

        // Scoped to the text area so they never reach the diff frame. Plain Enter stays a
        // newline; getCtrlEnter is Cmd+Enter on macOS and Ctrl+Enter elsewhere.
        DumbAwareAction.create { submit() }
            .registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), textArea, parentDisposable)
        DumbAwareAction.create { onCancel() }
            .registerCustomShortcutSet(CommonShortcuts.ESCAPE, textArea, parentDisposable)
    }

    /**
     * Deferred: the diff processor wraps applying a request in `runPreservingFocus`, and would
     * take focus straight back from a component that grabbed it too early.
     */
    fun focusText() {
        ApplicationManager.getApplication().invokeLater {
            if (isDisplayable) {
                textArea.requestFocusInWindow()
                textArea.caretPosition = textArea.document.length
            }
        }
    }

    private fun headerLabel(text: String): JComponent =
        JBLabel(text).apply {
            font = JBFont.smallOrNewUiMedium().asBold()
            foreground = NamedColorUtil.getInactiveTextColor()
            border = JBUI.Borders.emptyBottom(2)
        }

    /** Right-aligned, which `HorizontalLayout` cannot do on its own. */
    private fun buttons(submitLabel: String): JPanel {
        val row = JPanel(HorizontalLayout(JBUI.scale(8))).apply {
            isOpaque = false
            add(ActionLink("Cancel") { onCancel() })
            add(
                JButton(submitLabel).apply {
                    isDefaultCapable = false
                    // The IDE's own primary style — filled in the accent colour, the way Commit
                    // looks. A plain button's white face read as a stray frame on the block.
                    putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                    // The fill is rounded, so an opaque button leaves its own square background
                    // showing at the corners — white, on our tinted block. Nobody notices this in
                    // a dialog, where that background matches the panel behind it.
                    isOpaque = false
                    // A pointer, not the I-beam the text field would otherwise lend the whole row.
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener { submit() }
                },
            )
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(2)
            cursor = Cursor.getDefaultCursor()
            add(row, BorderLayout.EAST)
        }
    }

    private fun changed() {
        onChange(textArea.text)
        onResize()
    }

    private fun submit() {
        val text = textArea.text.trim()
        if (text.isEmpty()) return
        onSubmit(text)
    }

    private companion object {
        const val MIN_ROWS = 3
    }
}
