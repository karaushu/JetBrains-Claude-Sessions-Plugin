package dev.andy.claudesessions.data

import dev.andy.claudesessions.model.SessionSummary

/**
 * Decides which sessions belong to the open project.
 *
 * Worktrees make this more than a cwd comparison. A session run in a worktree has the
 * worktree as its cwd, so it lands in its own transcript directory and would otherwise be
 * invisible from the project it was started for. Claude records `originalCwd` on its
 * `worktree-state` record, which is what ties it back.
 */
internal object ProjectScope {

    /**
     * Marker left by encoding `<project>/.claude/worktrees/<name>`: every non-alphanumeric
     * character becomes `-`, so the path segment survives as this.
     */
    private const val WORKTREE_MARKER = "--claude-worktrees-"

    /**
     * Directory names worth scanning for a project: its own, plus its worktrees.
     *
     * The worktree marker must follow the project's own prefix *immediately*. Merely
     * requiring the prefix and the marker somewhere later would hand a parent its children's
     * worktrees: `acme-apps-admin--claude-worktrees-x` starts with `acme-` and does
     * contain the marker, but belongs to `acme/apps/admin`.
     *
     * Worktrees at arbitrary locations cannot be recognised from a directory name at all, so
     * their paths are supplied by [GitWorktrees] and encoded directly.
     */
    fun candidateDirNames(
        basePath: String,
        allDirNames: Collection<String>,
        worktreePaths: Collection<String> = emptyList(),
    ): List<String> {
        val own = ClaudePaths.encodeProjectDir(basePath)
        val worktreePrefix = own + WORKTREE_MARKER
        val encodedWorktrees = worktreePaths.mapTo(HashSet()) { ClaudePaths.encodeProjectDir(it) }
        return allDirNames.filter {
            it == own || it.startsWith(worktreePrefix) || it in encodedWorktrees
        }
    }

    /** Whether a session found in one of those directories is really this project's. */
    fun belongsTo(
        summary: SessionSummary,
        basePath: String,
        worktreePaths: Set<String> = emptySet(),
    ): Boolean {
        // A session that recorded no cwd was found where we looked; take it.
        val cwd = summary.cwd ?: return true
        if (cwd == basePath) return true
        // A worktree session: Claude says which project it came from.
        if (summary.originalCwd == basePath) return true
        // A worktree git registers for this repository, wherever it happens to live.
        if (cwd in worktreePaths) return true
        // A worktree we did not get a record for, but which lives inside the project.
        return summary.worktreeName != null && cwd.startsWith("$basePath/")
    }
}
