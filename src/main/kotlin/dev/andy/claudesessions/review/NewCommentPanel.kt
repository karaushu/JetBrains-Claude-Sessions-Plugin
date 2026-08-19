package dev.andy.claudesessions.review

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapUtil
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
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
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

        // Enter submits the note. A note is a sentence or two, so the key under the finger
        // is the one that sends; Alt+Enter and Cmd/Ctrl+Enter make a new line.
        //
        // Two mechanisms, each for its own reason. Enter goes through the text area's own
        // input map, because a JTextArea binds Enter to insert-break itself and an IDE
        // action would be competing with that binding. The newline keys go through IDE
        // actions, because Alt+Enter is Show Intentions: an action registered on this
        // component shadows the global one, which an input-map entry does not.
        submitOnEnter()
        DumbAwareAction.create { textArea.replaceSelection("\n") }
            .registerCustomShortcutSet(newLineShortcuts(), textArea, parentDisposable)
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
                    toolTipText = keyHint()
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

    /**
     * Binds Enter to [submit] in the text area's own input map.
     *
     * The map put on the component itself sits in front of the one the text-area UI
     * installs, so this replaces `insert-break` for Enter alone. Every other key, including
     * the modified Enters below, still reaches the UI's own bindings.
     */
    private fun submitOnEnter() {
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SUBMIT_KEY)
        textArea.actionMap.put(
            SUBMIT_KEY,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = submit()
            },
        )
    }

    /** What the tooltip says, in the platform's own notation for the keys. */
    private fun keyHint(): String {
        val enter = KeymapUtil.getKeystrokeText(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
        val newLine = newLineKeystrokes().joinToString(" or ", transform = KeymapUtil::getKeystrokeText)
        return "$enter to add it, $newLine for a new line"
    }

    private companion object {
        const val MIN_ROWS = 3

        /** Our own action-map key, so nothing else in the text area answers to it. */
        const val SUBMIT_KEY = "claudesessions.submitNote"

        /**
         * The keys that insert a newline instead of submitting.
         *
         * `getCtrlEnter` is Cmd+Enter on macOS and Ctrl+Enter elsewhere, which is what this
         * field used to submit on. It stays bound rather than being dropped: it is the habit
         * anyone who used the field before this change already has, and it now does the other
         * half of the job.
         */
        fun newLineKeystrokes(): List<KeyStroke> = buildList {
            add(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK))
            CommonShortcuts.getCtrlEnter().shortcuts
                .filterIsInstance<KeyboardShortcut>()
                .forEach { add(it.firstKeyStroke) }
        }

        fun newLineShortcuts(): ShortcutSet = CustomShortcutSet(
            *newLineKeystrokes().map { KeyboardShortcut(it, null) }.toTypedArray(),
        )
    }
}
