package dev.andy.claudesessions.model

import java.nio.file.Path
import java.time.Instant

/**
 * Live state of a session, derived from `~/.claude/sessions/<pid>.json`.
 *
 * The CLI writes exactly four status values; see [LiveStatus.status].
 */
enum class SessionState {
    /** No live process. The session exists only as a transcript on disk. */
    HISTORICAL,

    /** `status: busy` — the agent is working. Shows an animated spinner. */
    RUNNING,

    /** `status: waiting` — blocked on you. Shows an attention icon. */
    NEEDS_INPUT,

    /** `status: idle` or `shell` — alive and sitting at the prompt. */
    LIVE_IDLE,

    /**
     * The process is alive but wrote no `status` field.
     *
     * Only the interactive TUI writes status, so sessions started from Claude Desktop
     * or the agent SDK land here. Deliberately distinct from [LIVE_IDLE] — we know it
     * is running but not what it is doing.
     */
    RUNNING_UNKNOWN,
}

/**
 * A parsed `~/.claude/sessions/<pid>.json` entry whose process was confirmed alive.
 */
data class LiveStatus(
    val pid: Long,
    val sessionId: String,
    val cwd: String?,
    /** One of `busy`, `shell`, `idle`, `waiting`; null for non-TUI entrypoints. */
    val status: String?,
    /** Reason the session is blocked. Present only while [status] is `waiting`. */
    val waitingFor: String?,
    val entrypoint: String?,
    /**
     * `interactive`, `bg`, `daemon` or `daemon-worker`. Background jobs the daemon spawns
     * report `entrypoint = cli` just like a terminal session, so this is the only field that
     * tells them apart.
     */
    val kind: String?,
    val name: String?,
    /** When the CLI recorded this session starting; used to link a freshly spawned tab. */
    val startedAtMillis: Long?,
) {
    val state: SessionState
        get() = when (status) {
            "busy" -> SessionState.RUNNING
            "waiting" -> SessionState.NEEDS_INPUT
            "idle", "shell" -> SessionState.LIVE_IDLE
            else -> SessionState.RUNNING_UNKNOWN
        }
}

/**
 * A session transcript on disk, summarised without reading the whole file.
 */
data class SessionSummary(
    val sessionId: String,
    val transcript: Path,
    val cwd: String?,
    val gitBranch: String?,
    val title: String?,
    val startedAt: Instant?,
    /** Transcript mtime. Files are append-only, so this is the last activity. */
    val lastActivity: Instant,
    val sizeBytes: Long,
    /**
     * False for a session that is running but has not written a transcript yet — Claude
     * writes nothing until the first message. Such a session is real and worth showing, but
     * it has no title, branch or history to show with it.
     */
    val hasTranscript: Boolean = true,
    /**
     * Name of the git worktree this session runs in, when it is not the main working tree.
     * Taken from Claude's own `worktree-state` record rather than inspected from git.
     */
    val worktreeName: String? = null,
    /**
     * The project a worktree session came from, per `worktreeSession.originalCwd`. A worktree
     * has its own cwd and therefore its own transcript directory, so this is what ties the
     * session back to the project it belongs to.
     */
    val originalCwd: String? = null,
)

/**
 * A row in the tool window: transcript summary joined with live status, if any.
 */
data class SessionItem(
    val summary: SessionSummary,
    val live: LiveStatus?,
    /**
     * State derived from Claude's hooks. Consulted for a session whose pid file carries no
     * `status` — Claude Desktop and agent-SDK sessions write a pid file but never a status,
     * so hooks are the only thing that knows what they are doing.
     *
     * Never evidence that a session is *alive*; see [isLive].
     */
    val hookState: SessionState? = null,
) {
    val sessionId: String get() = summary.sessionId

    val state: SessionState
        get() {
            val live = live ?: return SessionState.HISTORICAL
            if (live.status != null) return live.state
            // A pid file with no status is a non-TUI entrypoint, which is exactly what hooks
            // are for. Falling back to RUNNING_UNKNOWN says "alive, doing something" rather
            // than inventing a state.
            return hookState?.takeIf { it != SessionState.HISTORICAL }
                ?: SessionState.RUNNING_UNKNOWN
        }

    /**
     * Whether a process is running this session.
     *
     * The pid file alone decides, and [LiveStatus] is only produced after the process was
     * confirmed alive. Hooks cannot answer this: they are a record of things that happened,
     * with no event for "killed". A session interrupted mid-turn leaves a `UserPromptSubmit`
     * with no `Stop` after it, so treating hook state as liveness left a dead session
     * spinning at the top of the list until the log next rotated.
     *
     * Nothing is lost by ignoring hooks here — every entrypoint writes a pid file, including
     * non-interactive `-p` runs. Only `status` is exclusive to the interactive TUI.
     */
    val isLive: Boolean get() = live != null

    val needsAttention: Boolean get() = state == SessionState.NEEDS_INPUT

    /**
     * A session running as a background agent. `--resume` refuses these — it cannot have two
     * owners — so they can only be attached to via Claude's agent view or branched with
     * `--fork-session`.
     */
    val isBackgroundAgent: Boolean get() = live?.kind == "bg"

    /**
     * What to call a session Claude has not named yet.
     *
     * The entrypoint matters, and getting this wrong is not cosmetic: an IDE agent and a
     * terminal session both lack a transcript, so describing every such session as a new
     * terminal session made a running IDE agent look like an abandoned one — enough to talk
     * someone into killing it.
     */
    val untitledDescription: String
        get() = when {
            summary.hasTranscript -> "Untitled session"
            live == null -> "Untitled session"
            live.entrypoint == "cli" -> "New session — nothing sent yet"
            live.entrypoint?.startsWith("sdk") == true -> "IDE agent — not started from this list"
            live.entrypoint == "claude-desktop" -> "Claude Desktop session"
            else -> "Untitled session"
        }
}
