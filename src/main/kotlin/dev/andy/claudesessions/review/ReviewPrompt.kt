package dev.andy.claudesessions.review

import java.nio.file.Path

/**
 * The one line typed into a live session to start a review round.
 *
 * Kept in its own object for the reason `ClaudeCommands` gives about shell commands — so it is
 * testable, and so nothing else invents a variation of it. It is not part of `ClaudeCommands`
 * because that holds command lines for a shell, and this is prose typed at a running agent.
 *
 * Short, and a single line, on purpose. It travels through the PTY with bracketed paste (see
 * `ClaudeTerminalLauncher.sendToSession`): an embedded newline would submit early and leave the
 * rest behind as a second prompt, and a long paste is at the mercy of the terminal's line
 * editor. The review itself therefore goes in a file and never down the wire.
 *
 * Two characters are forbidden rather than escaped. A leading `/` would be read as a slash
 * command, and an `@` opens the file-mention popup, which swallows the paste.
 */
internal object ReviewPrompt {

    fun forRound(reviewFile: Path, commentCount: Int): String {
        val noun = if (commentCount == 1) "comment" else "comments"
        return "Code review: read ${quoteIfNeeded(reviewFile)} and follow its instructions " +
            "for all $commentCount $noun, including how to reply."
    }

    /**
     * A path with a space in it would otherwise arrive as several arguments if the agent hands
     * it to a tool. Round directories are space-free by construction, but the user's home
     * directory is not ours to choose.
     */
    private fun quoteIfNeeded(path: Path): String {
        val text = path.toString()
        return if (text.any { it.isWhitespace() }) "'$text'" else text
    }
}
