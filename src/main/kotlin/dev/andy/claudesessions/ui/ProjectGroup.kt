package dev.andy.claudesessions.ui

import dev.andy.claudesessions.model.SessionItem
import java.nio.file.Paths

/**
 * A project heading in the tree, shown when sessions from all projects are listed.
 */
internal data class ProjectGroup(
    /** The session `cwd` these sessions share; also the stable key for expansion state. */
    val path: String,
    val sessionCount: Int,
    val liveCount: Int,
    val isCurrentProject: Boolean,
) {
    /** Last path segment, which is what people actually recognise. */
    val name: String
        get() = runCatching { Paths.get(path).fileName?.toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: path

    companion object {
        private const val UNKNOWN_PATH = "Unknown location"

        /** The one keying rule: [group] and in-place count refreshes must agree on it. */
        fun pathOf(item: SessionItem): String = item.summary.cwd ?: UNKNOWN_PATH

        /** Live sessions per heading path, for refreshing counts without regrouping. */
        fun liveCounts(items: List<SessionItem>): Map<String, Int> =
            items.filter { it.isLive }.groupingBy(::pathOf).eachCount()

        /**
         * Groups sessions by working directory, current project first, then by most
         * recent activity. Session order within a group is preserved.
         */
        fun group(items: List<SessionItem>, currentProjectPath: String?): List<Pair<ProjectGroup, List<SessionItem>>> {
            val byPath = items.groupBy(::pathOf)

            return byPath.entries
                .map { (path, sessions) ->
                    ProjectGroup(
                        path = path,
                        sessionCount = sessions.size,
                        liveCount = sessions.count { it.isLive },
                        isCurrentProject = path == currentProjectPath,
                    ) to sessions
                }
                .sortedWith(
                    compareByDescending<Pair<ProjectGroup, List<SessionItem>>> { it.first.isCurrentProject }
                        .thenByDescending { it.second.maxOfOrNull { s -> s.summary.lastActivity } },
                )
        }
    }
}
