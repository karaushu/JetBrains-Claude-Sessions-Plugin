package dev.andy.claudesessions.terminal

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener

/**
 * Remembers which session tab the user looked at last.
 *
 * Sending review notes has to pick a session, and the honest default is the one the user was
 * just in. Nothing else in the plugin needed a focus order, so this is the only listener it
 * registers; `selectionChanged` is the right event because it covers a click, a tab switch and
 * an `openFile` alike.
 *
 * Registered in `plugin.xml` rather than subscribing from a service constructor, so the platform
 * instantiates it lazily on the first event instead of something having to be loaded eagerly.
 */
internal class ClaudeTabFocusTracker : FileEditorManagerListener {

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val file = event.newFile as? ClaudeTerminalFile ?: return
        event.manager.project.service<ClaudeTerminalTabs>().noteFocused(file.sessionId)
    }
}
