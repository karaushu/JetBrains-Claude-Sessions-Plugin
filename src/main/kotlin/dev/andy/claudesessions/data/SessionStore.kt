package dev.andy.claudesessions.data

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.andy.claudesessions.hooks.HookEventBus
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.model.SessionState
import dev.andy.claudesessions.model.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Merges transcript summaries with live process status into the rows shown in the tool window.
 */
@Service(Service.Level.PROJECT)
internal class SessionStore(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val indexer = SessionIndexer()
    private val liveWatcher = LiveSessionWatcher()

    /** Shared with the notifier: one tail of the hook log for the whole application. */
    private val hooks get() = service<HookEventBus>()

    private val _items = MutableStateFlow<List<SessionItem>>(emptyList())
    val items: StateFlow<List<SessionItem>> = _items.asStateFlow()

    private val _showAllProjects = MutableStateFlow(false)
    val showAllProjects: StateFlow<Boolean> = _showAllProjects.asStateFlow()

    /**
     * Pid files are re-read roughly every second and transcripts every few seconds. A hook
     * event arriving out of band also forces a refresh on the spot, so a status change Claude
     * reports shows up without waiting for the next scheduled poll.
     */
    private val tickInterval = 300L
    private val statusEveryTicks = 3
    private val reindexEveryTicks = 16

    /**
     * The state review features should treat [sessionId] as being in: the row's own state
     * while a process is live, else whatever the hooks last said. One home for the fallback
     * rule — the sender and the reply watcher must never disagree on it.
     */
    fun effectiveState(sessionId: String): SessionState? {
        val item = items.value.firstOrNull { it.sessionId == sessionId }
        return item?.state?.takeIf { it != SessionState.HISTORICAL } ?: hooks.states()[sessionId]
    }

    fun setShowAllProjects(value: Boolean) {
        if (_showAllProjects.value == value) return
        _showAllProjects.value = value
        cachedSummaries = null
        requestRefresh()
    }

    fun requestRefresh() {
        scope.launch { refresh(forceReindex = true) }
    }

    /**
     * Polls until cancelled. Driven from the panel via `launchOnShow`, so it only runs
     * while the tool window is actually visible.
     */
    suspend fun watch() {
        hooks.retain()
        try {
            var tick = 0
            var seenRevision = hooks.revision.value
            while (currentCoroutineContext().isActive) {
                val revision = hooks.revision.value
                val hooksChanged = revision != seenRevision
                seenRevision = revision

                if (hooksChanged || tick % statusEveryTicks == 0) {
                    refresh(forceReindex = tick % reindexEveryTicks == 0)
                }
                tick++
                delay(tickInterval)
            }
        } finally {
            hooks.release()
        }
    }

    @Volatile
    private var cachedSummaries: List<SessionSummary>? = null

    /**
     * The tick loop and [requestRefresh] can race; unserialized, a slow all-projects load
     * finishing after a toggle to project-only would overwrite the newer result with
     * cross-project rows.
     */
    private val refreshLock = Mutex()

    private suspend fun refresh(forceReindex: Boolean): Unit = refreshLock.withLock {
        // One mode for the whole pass, so summaries and placeholders cannot disagree.
        val showAll = _showAllProjects.value

        val summaries: List<SessionSummary>
        if (forceReindex || cachedSummaries == null) {
            val loaded = withContext(Dispatchers.IO) { loadSummaries(showAll) }
            // The toggle flipped mid-load: this list describes the wrong mode. Drop it —
            // the refresh the toggle requested is already waiting on the lock.
            if (_showAllProjects.value != showAll) return
            cachedSummaries = loaded
            summaries = loaded
        } else {
            summaries = cachedSummaries.orEmpty()
        }

        val live = withContext(Dispatchers.IO) { liveWatcher.poll() }
        val hookStates = hooks.states()

        val placeholders = SessionPlaceholders.forUntranscribed(
            live = live.values,
            knownSessionIds = summaries.mapTo(HashSet()) { it.sessionId },
            projectBasePath = project.basePath,
            allProjects = showAll,
        )

        _items.value = (summaries + placeholders)
            .map {
                SessionItem(
                    summary = it,
                    live = live[it.sessionId],
                    hookState = hookStates[it.sessionId],
                )
            }
            .sortedWith(
                // Anything alive floats to the top, most recently active first.
                compareByDescending<SessionItem> { it.isLive }
                    .thenByDescending { it.summary.lastActivity },
            )
    }

    private fun loadSummaries(showAll: Boolean): List<SessionSummary> {
        val projects = ClaudePaths.projects
        if (!projects.isDirectory()) return emptyList()

        if (showAll) {
            val dirs = runCatching { projects.listDirectoryEntries().filter { it.isDirectory() } }
                .getOrElse { emptyList() }
            return dirs.flatMap { indexer.indexDirectory(it) }
        }

        val basePath = project.basePath ?: return emptyList()
        val allDirNames = runCatching {
            projects.listDirectoryEntries().filter { it.isDirectory() }.map { it.fileName.toString() }
        }.getOrElse { emptyList() }

        // Git knows about worktrees at arbitrary paths; Claude's own live under the project
        // and are found from the directory name. Both are scanned.
        val worktrees = GitWorktrees.of(Path.of(basePath))
        val worktreePaths = worktrees.mapTo(HashSet()) { it.path.toString() }

        return ProjectScope.candidateDirNames(basePath, allDirNames, worktreePaths)
            .flatMap { indexer.indexDirectory(projects.resolve(it)) }
            .filter { ProjectScope.belongsTo(it, basePath, worktreePaths) }
            .map { withWorktreeName(it, worktrees) }
    }

    /**
     * Labels a session that sits in a worktree but never recorded one.
     *
     * Claude writes `worktree-state` only for worktrees it created itself. A session started
     * by simply working in a worktree has no such record, so the name comes from git.
     */
    private fun withWorktreeName(
        summary: SessionSummary,
        worktrees: List<GitWorktrees.Worktree>,
    ): SessionSummary {
        if (summary.worktreeName != null || summary.cwd == null) return summary
        val name = GitWorktrees.nameForCwd(summary.cwd, worktrees) ?: return summary
        return summary.copy(worktreeName = name)
    }
}
