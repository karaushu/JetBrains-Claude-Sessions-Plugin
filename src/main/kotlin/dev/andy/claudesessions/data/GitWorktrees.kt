package dev.andy.claudesessions.data

import com.intellij.openapi.diagnostic.thisLogger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.getLastModifiedTime
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
 *
 * The store re-derives the list every few seconds, so the registry read is cached. Adding
 * or removing a worktree creates or deletes a registry entry, which changes the registry
 * directory's mtime — checked on every call, so those show up at once. Changes the mtime
 * cannot reveal (`git worktree move` rewrites a gitdir file in place; a worktree directory
 * deleted without git) are bounded by the cache's age limit instead.
 */
internal object GitWorktrees {

    data class Worktree(val name: String, val path: Path)

    private class Cached(
        val registryMtime: Long,
        val readAtNanos: Long,
        val worktrees: List<Worktree>,
    )

    private val cache = ConcurrentHashMap<Path, Cached>()

    private val maxCacheNanos = TimeUnit.SECONDS.toNanos(30)

    fun of(projectBasePath: Path): List<Worktree> {
        val commonGitDir = commonGitDir(projectBasePath) ?: return emptyList()
        val registry = commonGitDir.resolve("worktrees")
        val registryMtime = runCatching { registry.getLastModifiedTime().toMillis() }.getOrElse { -1L }

        val now = System.nanoTime()
        cache[projectBasePath]?.let { cached ->
            val fresh = now - cached.readAtNanos < maxCacheNanos
            if (fresh && cached.registryMtime == registryMtime) return cached.worktrees
        }

        val found = scan(projectBasePath, commonGitDir, registry)
        cache[projectBasePath] = Cached(registryMtime, now, found)
        return found
    }

    private fun scan(projectBasePath: Path, commonGitDir: Path, registry: Path): List<Worktree> {
        val found = mutableListOf<Worktree>()

        // If the open project is itself a worktree, the main tree is a sibling worth listing.
        commonGitDir.parent?.let { main ->
            if (main != projectBasePath && main.isDirectory()) found += Worktree(main.name, main)
        }

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
        // Relative pointers (git's worktree.useRelativePaths, the default since 2.51) are
        // relative to the directory holding the .git file; resolve() leaves absolute ones as is.
        val perWorktreeDir = runCatching {
            projectBasePath.resolve(pointer).normalize()
        }.getOrNull() ?: return null
        // <main>/.git/worktrees/<name> -> <main>/.git
        return perWorktreeDir.parent?.parent?.takeIf { it.isDirectory() }
    }

    private fun linkedWorktreePath(registryEntry: Path): Path? {
        val gitdir = registryEntry.resolve("gitdir").takeIf { it.isRegularFile() } ?: return null
        val target = readFirstLine(gitdir)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // The file names the worktree's own .git; the worktree is its parent. A relative
        // target is relative to the registry entry holding the gitdir file.
        return runCatching {
            registryEntry.resolve(target).normalize().parent
        }.getOrNull()?.takeIf { it.isDirectory() }
    }

    private fun readFirstLine(file: Path): String? = runCatching {
        Files.readAllLines(file, StandardCharsets.UTF_8).firstOrNull()
    }.onFailure { thisLogger().debug("Cannot read $file", it) }.getOrNull()
}
