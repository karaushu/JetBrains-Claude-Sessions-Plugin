package dev.andy.claudesessions.terminal

/**
 * Environment overrides applied to every `claude` we spawn.
 *
 * The IDE inherits its environment from whatever launched it. If that was itself a Claude
 * Code session (or anything it spawned), the IDE — and therefore every terminal it opens —
 * carries session-identity markers. `CLAUDE_CODE_CHILD_SESSION=1` is the damaging one: the
 * CLI treats a marked interactive session as a nested child and disables persistence, which
 * means **no transcript is written and no `~/.claude/sessions/<pid>.json` is created**. The
 * session then cannot appear in our list and has no readable status.
 *
 * Blanking is equivalent to unsetting here: the CLI reads these through a schema that
 * trims and maps `""` to `false`/`undefined`, so an empty value restores the default. That
 * matters because the terminal API only lets us *add* variables, never remove them.
 *
 * Deliberately not touched: `ANTHROPIC_*` (the user's real model/auth config) and
 * `CLAUDE_CONFIG_DIR` / `ANTHROPIC_CONFIG_DIR`, which tell us where `~/.claude` lives.
 */
internal object ClaudeEnvironment {

    /** Session-identity and lifecycle markers a parent process can leak. */
    private val INHERITED_MARKERS = listOf(
        // The one that disables persistence.
        "CLAUDE_CODE_CHILD_SESSION",
        // Identity of the parent session.
        "CLAUDECODE",
        "CLAUDE_CODE_SESSION_ID",
        "CLAUDE_CODE_BRIDGE_SESSION_ID",
        "CLAUDE_CODE_REMOTE_SESSION_ID",
        "CLAUDE_CODE_HOST_SESSION_ID",
        "CLAUDE_PID",
        "CLAUDE_CODE_EXECPATH",
        "AI_AGENT",
        "TRACEPARENT",
        // Would make the new session non-interactive or headless.
        "CLAUDE_CODE_SESSION_KIND",
        "CLAUDE_CODE_SESSION_NAME",
        "CLAUDE_CODE_ENTRYPOINT",
        "CLAUDE_CODE_COORDINATOR_MODE",
        "CLAUDE_CODE_MAX_TURNS",
        "CLAUDE_CODE_EXIT_AFTER_FIRST_RENDER",
        // Would suppress history independently of the child-session marker.
        "CLAUDE_CODE_SKIP_PROMPT_HISTORY",
        // Parent turn/agent context.
        "CLAUDE_EFFORT",
        "CLAUDE_CODE_INVOKED_SKILLS",
        "CLAUDE_CODE_AGENT",
        "CLAUDE_CODE_SESSION_LOG",
        "CLAUDE_RUNNER_ACTIVITY_FD",
        // Auto-resume handshakes that would fire a turn on startup.
        "CLAUDE_CODE_RESUME_PROMPT",
        "CLAUDE_CODE_RESUME_INTERRUPTED_TURN",
        "CLAUDE_CODE_RESUME_INTERRUPTED_TURN_MAX_AGE_MS",
        "CLAUDE_CODE_RESUME_SOURCE_ALIVE",
        // Background-worker identity and credentials.
        "CLAUDE_JOB_DIR",
        "CLAUDE_BG_SOURCE",
        "CLAUDE_BG_ISOLATION",
        "CLAUDE_BG_BACKEND",
        "CLAUDE_BG_AUTH_SNAPSHOT_PATH",
        "CLAUDE_BG_RV_AUTH",
        "CLAUDE_BG_PTY_AUTH",
        "CLAUDE_BG_SOCKET_TOKENS_PATH",
        "CLAUDE_BG_CLAIM_AUTH",
        "CLAUDE_BG_RENDEZVOUS_SOCK",
        "CLAUDE_BG_SESSION_PERMISSION_RULES",
        "CLAUDE_BG_MEMORY_TOGGLED_OFF",
        "CLAUDE_BG_POST_CLEAR_RESPAWN",
    )

    /** Only overrides markers that are actually present, so a clean IDE gets a clean map. */
    fun overridesFor(inherited: Map<String, String> = System.getenv()): Map<String, String> =
        INHERITED_MARKERS.filter { inherited.containsKey(it) }.associateWith { "" }

    /** Names we would blank; exposed for diagnostics and tests. */
    fun markers(): List<String> = INHERITED_MARKERS
}
