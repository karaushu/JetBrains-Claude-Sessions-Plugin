package dev.andy.claudesessions.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Built against the layout git actually writes, taken from a real repository:
 * `<main>/.git/worktrees/<name>/gitdir` contains the path of the worktree's own `.git`.
 */
class GitWorktreesTest {

    private fun mainRepo(root: Path, name: String = "repo"): Path =
        root.resolve(name).also { it.resolve(".git").createDirectories() }

    private fun register(main: Path, name: String, worktree: Path) {
        worktree.createDirectories()
        val entry = main.resolve(".git/worktrees/$name").also { it.createDirectories() }
        entry.resolve("gitdir").writeText("${worktree.resolve(".git")}\n")
        // A linked worktree's .git is a file pointing back at the registry entry.
        worktree.resolve(".git").writeText("gitdir: $entry\n")
    }

    @Test
    fun `a repository with no worktrees reports none`(@TempDir root: Path) {
        assertTrue(GitWorktrees.of(mainRepo(root)).isEmpty())
    }

    @Test
    fun `a directory that is not a repository reports none`(@TempDir root: Path) {
        assertTrue(GitWorktrees.of(root.resolve("not-a-repo")).isEmpty())
    }

    @Test
    fun `finds a worktree inside the project`(@TempDir root: Path) {
        val main = mainRepo(root)
        register(main, "worktree-1", main.resolve(".claude/worktrees/worktree-1"))

        val found = GitWorktrees.of(main)
        assertEquals(listOf("worktree-1"), found.map { it.name })
        assertEquals(main.resolve(".claude/worktrees/worktree-1"), found.single().path)
    }

    @Test
    fun `finds a worktree anywhere on disk`(@TempDir root: Path) {
        // The case that motivated this: a worktree that is not under the project at all.
        val main = mainRepo(root)
        val elsewhere = root.resolve("repo-worktrees/feature-1")
        register(main, "feature-1", elsewhere)

        val found = GitWorktrees.of(main)
        assertEquals(listOf("feature-1"), found.map { it.name })
        assertEquals(elsewhere, found.single().path)
    }

    @Test
    fun `finds several worktrees in different places`(@TempDir root: Path) {
        val main = mainRepo(root)
        register(main, "inside", main.resolve(".claude/worktrees/inside"))
        register(main, "outside", root.resolve("elsewhere/outside"))

        assertEquals(setOf("inside", "outside"), GitWorktrees.of(main).map { it.name }.toSet())
    }

    @Test
    fun `opening a worktree finds its siblings and the main tree`(@TempDir root: Path) {
        val main = mainRepo(root)
        val first = root.resolve("wt/first")
        val second = root.resolve("wt/second")
        register(main, "first", first)
        register(main, "second", second)

        // The open project is itself a worktree: .git is a file, not a directory.
        val found = GitWorktrees.of(first)
        assertEquals(setOf("repo", "second"), found.map { it.name }.toSet())
        assertTrue(found.none { it.path == first }, "must not list the project as its own worktree")
    }

    @Test
    fun `a registry entry pointing nowhere is skipped`(@TempDir root: Path) {
        val main = mainRepo(root)
        val entry = main.resolve(".git/worktrees/stale").also { it.createDirectories() }
        // A deleted worktree can leave its registry entry behind.
        entry.resolve("gitdir").writeText("${root.resolve("gone/.git")}\n")

        assertTrue(GitWorktrees.of(main).isEmpty())
    }

    @Test
    fun `an entry with no gitdir file is skipped`(@TempDir root: Path) {
        val main = mainRepo(root)
        main.resolve(".git/worktrees/broken").createDirectories()
        assertTrue(GitWorktrees.of(main).isEmpty())
    }

    @Test
    fun `names a session's worktree by its cwd`(@TempDir root: Path) {
        val main = mainRepo(root)
        val elsewhere = root.resolve("repo-worktrees/feature-1")
        register(main, "feature-1", elsewhere)
        val worktrees = GitWorktrees.of(main)

        assertEquals("feature-1", GitWorktrees.nameForCwd(elsewhere.toString(), worktrees))
        assertEquals(null, GitWorktrees.nameForCwd(main.toString(), worktrees))
        assertEquals(null, GitWorktrees.nameForCwd("/somewhere/unrelated", worktrees))
    }
}
