package dev.andy.claudesessions.data

/**
 * Decides whether a live process looks like Claude before we trust — or signal — its pid.
 *
 * The executable path alone is not enough: an npm/pnpm-installed CLI runs under `node`
 * (or `bun`), and the claude script's path only shows up in the arguments. So the check
 * accepts a match anywhere in the command, the arguments, or the full command line.
 *
 * When the OS exposes no executable at all, the check stays lenient — the pid-reuse
 * start-time guard in the callers is the real protection there. When the executable is
 * visible but nothing mentions the needle, the process is not Claude.
 */
internal object ClaudeProcesses {

    fun looksLike(handle: ProcessHandle, needle: String = "claude"): Boolean {
        val info = handle.info()
        val command = info.command().orElse(null) ?: return true
        if (command.contains(needle, ignoreCase = true)) return true

        val arguments = info.arguments().orElse(null)
        if (arguments != null) return arguments.any { it.contains(needle, ignoreCase = true) }
        return info.commandLine().orElse(null)?.contains(needle, ignoreCase = true) ?: false
    }
}
