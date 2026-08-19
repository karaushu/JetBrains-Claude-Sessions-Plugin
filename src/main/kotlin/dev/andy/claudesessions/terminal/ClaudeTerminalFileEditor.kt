package dev.andy.claudesessions.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.cancel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Hosts a Claude session's terminal as a tab in the main editor area.
 *
 * The component is the [com.intellij.terminal.frontend.view.TerminalView]'s own component,
 * so input, output, resizing and `sendText` all behave exactly as they do in the Terminal
 * tool window.
 */
internal class ClaudeTerminalFileEditor(
    private val project: Project,
    private val file: ClaudeTerminalFile,
) : UserDataHolderBase(), FileEditor {

    override fun getComponent(): JComponent = file.view.component

    override fun getPreferredFocusedComponent(): JComponent = file.view.preferredFocusableComponent

    override fun getName(): String = file.name

    override fun getFile(): VirtualFile = file

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun setState(state: FileEditorState) = Unit

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    /**
     * Closing the tab terminates the session — but the platform also closes and immediately
     * reopens the file when the tab is dragged, because the file forbids splitting, and it
     * does *not* flag that path as a reopen. Killing the terminal here synchronously is
     * exactly how the IDE's own terminal-in-editor loses sessions on drag (IJPL-165734).
     *
     * So defer the decision: if the file is still open once the pending editor operations
     * have run, it was a move, not a close.
     */
    override fun dispose() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                // Too late for project services, but the terminal's coroutine scope — and
                // the shell in it — is ours to stop regardless.
                file.view.coroutineScope.cancel("Claude Sessions: project closed")
                return@invokeLater
            }
            val stillOpen = FileEditorManager.getInstance(project).openFiles.any { it === file }
            if (stillOpen) return@invokeLater

            // By instance, not by id: the session can be reopened as a new file while this
            // disposal sits in the queue, and forgetting by id would orphan that fresh tab.
            project.service<ClaudeTerminalTabs>().forget(file)
            file.view.coroutineScope.cancel("Claude Sessions: editor tab closed")
        }
    }
}
