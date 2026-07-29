package dev.andy.claudesessions.data

import com.intellij.openapi.diagnostic.thisLogger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Reads git's own worktree registry, so worktrees anywhere on disk can be found — not only
 * the ones Claude creates under `<project>/.claude/worktrees`.
 *
 * Read directly rather than by running `git worktree list`: the data is a few small files, a
 * subprocess per refresh would be wasteful, and this works whether or not git is on the PATH
 * the IDE inherited.
 *
 * Layout, for a main working tree at `/repo`:
 * ```
 * /repo/.git/worktrees/<name>/gitdir   ->  "/elsewhere/wt/.git"
 * ```
 * The worktree is the parent of what `gitdir` names. A linked worktree instead has a `.git`
 * *file* reading `gitdir: /repo/.git/worktrees/<name>`, which is how the shared directory is
 * found when the open project is itself a worktree.
 */
internal object GitWorktrees {

    data class Worktree(val name: String, val path: Path)

    fun of(projectBasePath: Path): List<Worktree> {
        val commonGitDir = commonGitDir(projectBasePath) ?: return emptyList()
        val found = mutableListOf<Worktree>()

        // If the open project is itself a worktree, the main tree is a sibling worth listing.
        commonGitDir.parent?.let { main ->
            if (main != projectBasePath && main.isDirectory()) found += Worktree(main.name, main)
        }

        val registry = commonGitDir.resolve("worktrees")
        if (registry.isDirectory()) {
            val entries = runCatching { registry.listDirectoryEntries() }.getOrElse { emptyList() }
            for (entry in entries) {
                val path = linkedWorktreePath(entry) ?: continue
                if (path == projectBasePath) continue
                found += Worktree(entry.name, path)
            }
        }
        return found.distinctBy { it.path }
    }

    /** Name git knows a worktree by, for a session whose cwd is inside it. */
    fun nameForCwd(cwd: String, worktrees: List<Worktree>): String? =
        worktrees.firstOrNull { it.path.toString() == cwd }?.name

    /**
     * The `.git` directory shared by every worktree of a repository.
     *
     * For a main working tree that is `<project>/.git`. For a linked worktree, `.git` is a
     * file pointing at `<main>/.git/worktrees/<name>`, two levels below the shared directory.
     */
    private fun commonGitDir(projectBasePath: Path): Path? {
        val dotGit = projectBasePath.resolve(".git")
        if (dotGit.isDirectory()) return dotGit
        if (!dotGit.isRegularFile()) return null

        val pointer = readFirstLine(dotGit)?.removePrefix("gitdir:")?.trim() ?: return null
        val perWorktreeDir = runCatching { Path.of(pointer) }.getOrNull() ?: return null
        // <main>/.git/worktrees/<name> -> <main>/.git
        return perWorktreeDir.parent?.parent?.takeIf { it.isDirectory() }
    }

    private fun linkedWorktreePath(registryEntry: Path): Path? {
        val gitdir = registryEntry.resolve("gitdir").takeIf { it.isRegularFile() } ?: return null
        val target = readFirstLine(gitdir)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // The file names the worktree's own .git; the worktree is its parent.
        return runCatching { Path.of(target).parent }.getOrNull()?.takeIf { it.isDirectory() }
    }

    private fun readFirstLine(file: Path): String? = runCatching {
        Files.readAllLines(file, StandardCharsets.UTF_8).firstOrNull()
    }.onFailure { thisLogger().debug("Cannot read $file", it) }.getOrNull()
}
