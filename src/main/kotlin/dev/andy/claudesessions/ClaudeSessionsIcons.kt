package dev.andy.claudesessions

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object ClaudeSessionsIcons {
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/claudeSessions.svg", ClaudeSessionsIcons::class.java)

    /** Stopped session: the Claude mark in the IDE's neutral icon colour. */
    @JvmField
    val Session: Icon = IconLoader.getIcon("/icons/session.svg", ClaudeSessionsIcons::class.java)

    /** Live session: the same mark in Claude's terracotta orange. */
    @JvmField
    val SessionLive: Icon = IconLoader.getIcon("/icons/sessionLive.svg", ClaudeSessionsIcons::class.java)
}
