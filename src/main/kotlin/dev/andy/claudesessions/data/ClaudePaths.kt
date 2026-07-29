package dev.andy.claudesessions.data

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Locations inside `~/.claude`.
 */
internal object ClaudePaths {

    val home: Path
        get() = Path(System.getProperty("user.home"), ".claude")

    val projects: Path get() = home.resolve("projects")

    val sessions: Path get() = home.resolve("sessions")

    /** Our own directory inside ~/.claude, written by the hooks we install. */
    val pluginDir: Path get() = home.resolve("claudesessions")

    val hookEventLog: Path get() = pluginDir.resolve("events.jsonl")

    /**
     * Claude Code derives a project directory name by replacing every non-alphanumeric
     * character of the absolute cwd with `-`, so `/Users/a/.x/y` becomes `-Users-a--x-y`.
     *
     * The mapping is **lossy and not reversible**: `-Users-dev-projects-acme-apps-admin`
     * could decode to either `.../acme/apps/admin` or `.../acme-apps/admin`. Use this
     * to find the candidate directory, then confirm each session by its `cwd` field.
     */
    fun encodeProjectDir(absolutePath: String): String =
        buildString(absolutePath.length) {
            for (ch in absolutePath) {
                append(if (ch.isLetterOrDigit() && ch.code < 128) ch else '-')
            }
        }
}
