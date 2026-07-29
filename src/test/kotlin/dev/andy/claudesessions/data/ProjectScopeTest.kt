package dev.andy.claudesessions.data

import dev.andy.claudesessions.model.SessionSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class ProjectScopeTest {

    private val project = "/Users/dev/projects/acme"

    /** Real directory names from ~/.claude/projects. */
    private val dirs = listOf(
        "-Users-dev-projects-acme",
        "-Users-dev-projects-acme--claude-worktrees-worktree-1",
        "-Users-dev-projects-acme-apps-admin",
        "-Users-dev-projects-acme-apps-admin--claude-worktrees-prettier-canon",
        "-Users-dev-projects-acme-worktrees-feature-1",
        "-Users-dev-projects-web-admin",
    )

    private fun summary(
        cwd: String?,
        worktreeName: String? = null,
        originalCwd: String? = null,
    ) = SessionSummary(
        sessionId = "s", transcript = Path.of("/tmp/s.jsonl"), cwd = cwd,
        gitBranch = null, title = null, startedAt = null,
        lastActivity = Instant.EPOCH, sizeBytes = 0,
        worktreeName = worktreeName, originalCwd = originalCwd,
    )

    @Test
    fun `scans the project's own directory and its worktrees`() {
        assertEquals(
            listOf(
                "-Users-dev-projects-acme",
                "-Users-dev-projects-acme--claude-worktrees-worktree-1",
            ),
            ProjectScope.candidateDirNames(project, dirs),
        )
    }

    @Test
    fun `does not pull in nested projects`() {
        // Opening `acme` must not index every transcript under acme/apps/admin.
        val picked = ProjectScope.candidateDirNames(project, dirs)
        assertFalse(picked.any { it == "-Users-dev-projects-acme-apps-admin" })
        assertFalse(picked.any { it.contains("prettier-canon") }, "that is admin's worktree, not ours")
    }

    @Test
    fun `a nested project gets its own worktrees, not its parent's`() {
        val picked = ProjectScope.candidateDirNames("/Users/dev/projects/acme/apps/admin", dirs)
        assertEquals(
            listOf(
                "-Users-dev-projects-acme-apps-admin",
                "-Users-dev-projects-acme-apps-admin--claude-worktrees-prettier-canon",
            ),
            picked,
        )
    }

    @Test
    fun `a worktree at an unrelated path is scanned when git registers it`() {
        // Not discoverable from the directory name: acme-worktrees is a sibling of acme,
        // not a child, so only git's registry can vouch for it.
        val picked = ProjectScope.candidateDirNames(
            project,
            dirs,
            worktreePaths = listOf("/Users/dev/projects/acme-worktrees/feature-1"),
        )
        assertTrue("-Users-dev-projects-acme-worktrees-feature-1" in picked)
    }

    @Test
    fun `an unregistered sibling is still not scanned`() {
        // Without git vouching for it, that directory belongs to something else.
        val picked = ProjectScope.candidateDirNames(project, dirs)
        assertFalse("-Users-dev-projects-acme-worktrees-feature-1" in picked)
    }

    @Test
    fun `a session in a git-registered worktree belongs to the project`() {
        val elsewhere = "/Users/dev/projects/acme-worktrees/feature-1"
        assertTrue(
            ProjectScope.belongsTo(
                summary(cwd = elsewhere, worktreeName = "feature-1"),
                project,
                worktreePaths = setOf(elsewhere),
            ),
        )
        // Same session, but git does not register it for this repository.
        assertFalse(ProjectScope.belongsTo(summary(cwd = elsewhere), project))
    }

    @Test
    fun `a session in the project belongs to it`() {
        assertTrue(ProjectScope.belongsTo(summary(cwd = project), project))
    }

    @Test
    fun `a worktree session belongs to the project it came from`() {
        // Claude records originalCwd on its worktree-state record; that is the link.
        val worktree = summary(
            cwd = "$project/.claude/worktrees/worktree-1",
            worktreeName = "worktree-1",
            originalCwd = project,
        )
        assertTrue(ProjectScope.belongsTo(worktree, project))
    }

    @Test
    fun `a worktree inside the project counts even without an originalCwd record`() {
        val worktree = summary(cwd = "$project/.claude/worktrees/worktree-2", worktreeName = "worktree-2")
        assertTrue(ProjectScope.belongsTo(worktree, project))
    }

    @Test
    fun `another project's session does not belong`() {
        assertFalse(ProjectScope.belongsTo(summary(cwd = "/Users/dev/projects/web/admin"), project))
    }

    @Test
    fun `a nested project's session does not belong to its parent`() {
        assertFalse(ProjectScope.belongsTo(summary(cwd = "$project/apps/admin"), project))
    }

    @Test
    fun `another project's worktree does not belong`() {
        val other = summary(
            cwd = "/Users/dev/projects/other/.claude/worktrees/w",
            worktreeName = "w",
            originalCwd = "/Users/dev/projects/other",
        )
        assertFalse(ProjectScope.belongsTo(other, project))
    }

    @Test
    fun `a session with no recorded cwd is kept, since it was found where we looked`() {
        assertTrue(ProjectScope.belongsTo(summary(cwd = null), project))
    }
}
