package dev.andy.claudesessions.terminal

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.impl.EditorAutoClosingHandler
import com.intellij.openapi.fileEditor.impl.EditorComposite
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

internal class ClaudeTerminalFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is ClaudeTerminalFile

    // Nothing here touches PSI or the index.
    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        ClaudeTerminalFileEditor(project, file as ClaudeTerminalFile)

    override fun getEditorTypeId(): String = "claude-session-terminal"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * Keeps a live session's tab from being evicted when the editor tab limit is exceeded.
 * The platform's own terminal does the same thing.
 */
internal class ClaudeTerminalAutoClosingHandler : EditorAutoClosingHandler {

    override fun isClosingAllowed(composite: EditorComposite): Boolean =
        composite.file !is ClaudeTerminalFile
}
