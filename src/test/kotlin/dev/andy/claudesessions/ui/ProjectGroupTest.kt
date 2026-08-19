package dev.andy.claudesessions.ui

import dev.andy.claudesessions.model.LiveStatus
import dev.andy.claudesessions.model.SessionItem
import dev.andy.claudesessions.model.SessionSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class ProjectGroupTest {

    private fun session(
        id: String,
        cwd: String?,
        minutesAgo: Long,
        live: Boolean = false,
    ) = SessionItem(
        summary = SessionSummary(
            sessionId = id,
            transcript = Path.of("/tmp/$id.jsonl"),
            cwd = cwd,
            gitBranch = "main",
            title = "Session $id",
            startedAt = null,
            lastActivity = Instant.parse("2026-07-27T12:00:00Z").minusSeconds(minutesAgo * 60),
            sizeBytes = 10,
        ),
        live = if (live) {
            LiveStatus(1, id, cwd, "idle", null, "cli", "interactive", null, null)
        } else {
            null
        },
    )

    @Test
    fun `groups sessions by working directory`() {
        val groups = ProjectGroup.group(
            listOf(
                session("a", "/repo/one", 1),
                session("b", "/repo/two", 2),
                session("c", "/repo/one", 3),
            ),
            currentProjectPath = null,
        )

        assertEquals(2, groups.size)
        val one = groups.first { it.first.path == "/repo/one" }
        assertEquals(listOf("a", "c"), one.second.map { it.sessionId })
        assertEquals(2, one.first.sessionCount)
    }

    @Test
    fun `puts the current project first even when it is less recent`() {
        val groups = ProjectGroup.group(
            listOf(
                session("recent", "/repo/other", 1),
                session("stale", "/repo/mine", 500),
            ),
            currentProjectPath = "/repo/mine",
        )

        assertEquals("/repo/mine", groups.first().first.path)
        assertTrue(groups.first().first.isCurrentProject)
    }

    @Test
    fun `orders the remaining projects by most recent activity`() {
        val groups = ProjectGroup.group(
            listOf(
                session("old", "/repo/old", 100),
                session("new", "/repo/new", 1),
                session("mid", "/repo/mid", 50),
            ),
            currentProjectPath = null,
        )

        assertEquals(listOf("/repo/new", "/repo/mid", "/repo/old"), groups.map { it.first.path })
    }

    @Test
    fun `counts live sessions per project`() {
        val groups = ProjectGroup.group(
            listOf(
                session("a", "/repo/one", 1, live = true),
                session("b", "/repo/one", 2),
                session("c", "/repo/one", 3, live = true),
            ),
            currentProjectPath = null,
        )

        assertEquals(2, groups.single().first.liveCount)
        assertEquals(3, groups.single().first.sessionCount)
    }

    @Test
    fun `sessions with no recorded cwd get their own heading`() {
        val groups = ProjectGroup.group(listOf(session("a", null, 1)), currentProjectPath = null)
        assertEquals("Unknown location", groups.single().first.path)
    }

    @Test
    fun `live counts agree with group on every heading, including the null-cwd one`() {
        val items = listOf(
            session("a", null, 1, live = true),
            session("b", "/repo/one", 2, live = true),
            session("c", "/repo/one", 3),
        )

        val counts = ProjectGroup.liveCounts(items)
        for ((group, _) in ProjectGroup.group(items, currentProjectPath = null)) {
            assertEquals(group.liveCount, counts[group.path] ?: 0, "heading ${group.path}")
        }
        assertEquals(1, counts["Unknown location"])
    }

    @Test
    fun `heading shows the last path segment`() {
        val groups = ProjectGroup.group(
            listOf(session("a", "/Users/dev/projects/acme", 1)),
            currentProjectPath = null,
        )
        assertEquals("acme", groups.single().first.name)
    }
}
