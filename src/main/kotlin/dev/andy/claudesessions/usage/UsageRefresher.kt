package dev.andy.claudesessions.usage

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.thisLogger
import dev.andy.claudesessions.terminal.ClaudeEnvironment
import java.io.File

/**
 * Why a refresh did not produce new figures. Reported in the UI rather than swallowed:
 * showing a stale reading as though it were current is worse than saying it failed.
 */
internal sealed interface UsageRefreshResult {
    object Success : UsageRefreshResult

    /** Claude refuses to refresh a cache younger than five minutes; not an error. */
    object AlreadyFresh : UsageRefreshResult

    object ClaudeNotFound : UsageRefreshResult

    object TimedOut : UsageRefreshResult

    data class Failed(val exitCode: Int, val output: String) : UsageRefreshResult

    data class Crashed(val message: String) : UsageRefreshResult

    /** Short, user-facing reason; null when there is nothing to report. */
    fun problem(): String? = when (this) {
        Success, AlreadyFresh -> null
        ClaudeNotFound -> "Could not find the claude command"
        TimedOut -> "Claude did not answer in time"
        is Failed -> "Claude exited with code $exitCode"
        is Crashed -> "Could not run claude: $message"
    }
}

/**
 * Refreshes Claude's cached usage figures by asking Claude for them.
 *
 * The numbers come from an authenticated endpoint whose token lives in the macOS keychain.
 * Rather than read that token — which would mean this plugin handling a live credential —
 * we run Claude's own `/usage`, which fetches and rewrites `~/.claude.json` as a side
 * effect. Its output is discarded; the file is what we read.
 */
internal object UsageRefresher {

    private const val TIMEOUT_MS = 30_000

    fun refresh(): UsageRefreshResult {
        val executable = findClaude() ?: run {
            thisLogger().warn("Usage refresh: no claude executable on PATH or in the usual locations")
            return UsageRefreshResult.ClaudeNotFound
        }

        val command = GeneralCommandLine(executable.absolutePath, "-p", "/usage")
            .withWorkDirectory(System.getProperty("user.home"))
            .withEnvironment(refreshEnvironment())
            // Without a closed stdin the CLI waits three seconds for piped input.
            .withInput(File("/dev/null"))
            .withRedirectErrorStream(true)

        return runCatching {
            val output = ExecUtil.execAndGetOutput(command, TIMEOUT_MS)
            when {
                output.isTimeout -> {
                    thisLogger().warn("Usage refresh timed out after ${TIMEOUT_MS}ms")
                    UsageRefreshResult.TimedOut
                }
                output.exitCode != 0 -> {
                    thisLogger().warn("Usage refresh exited ${output.exitCode}: ${output.stdout.take(400)}")
                    UsageRefreshResult.Failed(output.exitCode, output.stdout.take(400))
                }
                else -> UsageRefreshResult.Success
            }
        }.getOrElse {
            thisLogger().warn("Usage refresh could not run ${executable.absolutePath}", it)
            UsageRefreshResult.Crashed(it.message ?: it::class.java.simpleName)
        }
    }

    /**
     * Environment for a throwaway usage query.
     *
     * `CLAUDE_CODE_SKIP_PROMPT_HISTORY` is the point of this: without it every refresh
     * leaves a session transcript behind, so checking every few minutes would litter
     * `~/.claude/projects` with hundreds of empty sessions a day. It suppresses persistence
     * regardless of interactivity, unlike `CLAUDE_CODE_CHILD_SESSION`, which only applies to
     * interactive sessions and therefore does nothing under `-p`.
     *
     * The inherited-marker scrub still applies, so a session id or entrypoint leaked into
     * the IDE's environment cannot confuse the query.
     */
    fun refreshEnvironment(): Map<String, String> =
        ClaudeEnvironment.overridesFor() + mapOf("CLAUDE_CODE_SKIP_PROMPT_HISTORY" to "1")

    /**
     * A GUI-launched IDE inherits a minimal PATH that often lacks `~/.local/bin`, so the
     * usual install locations are checked explicitly as well.
     */
    fun findClaude(): File? {
        PathEnvironmentVariableUtil.findInPath("claude")?.takeIf { it.canExecute() }?.let { return it }
        return candidatePaths().map(::File).firstOrNull { it.canExecute() }
    }

    fun candidatePaths(): List<String> {
        val home = System.getProperty("user.home")
        return listOf(
            "$home/.local/bin/claude",
            "$home/.claude/local/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
        )
    }
}
