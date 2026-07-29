package dev.andy.claudesessions.terminal

/**
 * The commands this plugin runs, in one place so they are testable and so a session is
 * never handed a command that cannot work for it.
 */
internal object ClaudeCommands {

    private const val CLAUDE = "claude"

    fun newSession(): String = CLAUDE

    fun resume(sessionId: String): String = "$CLAUDE --resume $sessionId"

    /**
     * Branches a copy of a session under a new id.
     *
     * The only way to open a background agent's conversation: `--resume` on its own refuses,
     * because the session is still running as a background agent and cannot have two owners.
     */
    fun resumeForked(sessionId: String): String = "$CLAUDE --resume $sessionId --fork-session"

    /**
     * Claude's own background-agent view, which is where a running agent can be attached to.
     * It is an interactive picker — there is no `attach <id>` subcommand to call directly.
     */
    fun agents(): String = "$CLAUDE agents"
}
