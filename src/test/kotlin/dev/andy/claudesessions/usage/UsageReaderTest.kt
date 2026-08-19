package dev.andy.claudesessions.usage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.writeText

class UsageReaderTest {

    private val reader = UsageReader()

    /** Shaped exactly like the real `~/.claude.json` payload. */
    private val real = """
        {
          "cachedUsageUtilization": {
            "fetchedAtMs": 1785151156469,
            "accountUuid": "abc",
            "utilization": {
              "five_hour": {"utilization": 40, "resets_at": "2026-07-27T11:49:59.500040+00:00"},
              "limits": [
                {"kind": "session", "group": "session", "percent": 40, "severity": "normal",
                 "resets_at": "2026-07-27T11:49:59.500040+00:00", "scope": null, "is_active": false},
                {"kind": "weekly_all", "group": "weekly", "percent": 33, "severity": "normal",
                 "resets_at": "2026-07-28T13:59:59.500064+00:00", "scope": null, "is_active": false},
                {"kind": "weekly_scoped", "group": "weekly", "percent": 44, "severity": "normal",
                 "resets_at": "2026-07-28T14:00:00.500407+00:00",
                 "scope": {"model": {"id": null, "display_name": "Fable"}, "surface": null},
                 "is_active": true}
              ]
            }
          }
        }
    """.trimIndent()

    private fun parse(json: String) = reader.parse(JsonParser.parseString(json) as JsonObject)

    @Test
    fun `reads the three windows the desktop app shows`() {
        val snapshot = parse(real)!!
        assertEquals(3, snapshot.limits.size)
        assertEquals(listOf("session", "weekly_all", "weekly_scoped"), snapshot.limits.map { it.kind })
        assertEquals(listOf(40, 33, 44), snapshot.limits.map { it.percent })
    }

    @Test
    fun `labels match the desktop wording`() {
        val snapshot = parse(real)!!
        assertEquals("5-hour limit", snapshot.limits[0].label)
        assertEquals("Weekly · all models", snapshot.limits[1].label)
        assertEquals("Weekly · Fable", snapshot.limits[2].label)
    }

    @Test
    fun `the session window is the one surfaced in the toolbar`() {
        assertEquals(40, parse(real)!!.sessionLimit()!!.percent)
    }

    @Test
    fun `reset timestamps are parsed`() {
        val session = parse(real)!!.limits[0]
        assertEquals(Instant.parse("2026-07-27T11:49:59.500040Z"), session.resetsAt)
    }

    @Test
    fun `staleness uses the same one-hour bound the CLI itself applies`() {
        val snapshot = parse(real)!!
        val fresh = snapshot.fetchedAt.plus(Duration.ofMinutes(30))
        val old = snapshot.fetchedAt.plus(Duration.ofMinutes(90))
        assertEquals(false, snapshot.isStale(fresh))
        assertEquals(true, snapshot.isStale(old))
    }

    @Test
    fun `a window whose reset has passed is flagged as rolled over`() {
        val session = parse(real)!!.limits[0]
        assertTrue(session.hasRolledOver(Instant.parse("2026-07-27T13:00:00Z")))
        assertEquals(false, session.hasRolledOver(Instant.parse("2026-07-27T11:00:00Z")))
    }

    @Test
    fun `missing or truncated data yields null rather than throwing`() {
        assertNull(parse("""{}"""))
        assertNull(parse("""{"cachedUsageUtilization": {}}"""))
        // fetchedAtMs present but no utilization object at all.
        val noLimits = parse("""{"cachedUsageUtilization":{"fetchedAtMs":1785151156469}}""")!!
        assertTrue(noLimits.limits.isEmpty())
    }

    @Test
    fun `refreshing inside Claude's five-minute write floor is pointless`() {
        // Verified against the CLI: two /usage runs 3s apart left fetchedAtMs identical,
        // because it will not rewrite a cache younger than five minutes.
        val snapshot = parse(real)!!
        assertEquals(false, snapshot.canBeRefreshed(snapshot.fetchedAt.plusSeconds(60)))
        assertEquals(false, snapshot.canBeRefreshed(snapshot.fetchedAt.plusSeconds(4 * 60)))
        assertEquals(true, snapshot.canBeRefreshed(snapshot.fetchedAt.plusSeconds(5 * 60)))
        assertEquals(true, snapshot.canBeRefreshed(snapshot.fetchedAt.plusSeconds(20 * 60)))
    }

    @Test
    fun `the write floor is well inside the staleness bound`() {
        // Otherwise a reading could be called stale while also being unrefreshable.
        assertTrue(UsageSnapshot.CLAUDE_WRITE_FLOOR < UsageSnapshot.MAX_TRUSTED_AGE)
    }

    @Test
    fun `an unknown kind is shown rather than dropped`() {
        // The server adds windows over time; the schema is passthrough on its side too.
        val snapshot = parse(
            """
            {"cachedUsageUtilization":{"fetchedAtMs":1,"utilization":{"limits":[
              {"kind":"monthly_special","group":"monthly","percent":7,"resets_at":null}
            ]}}}
            """.trimIndent(),
        )!!
        assertEquals("Monthly special", snapshot.limits.single().label)
        assertEquals(7, snapshot.limits.single().percent)
    }

    @Test
    fun `a missing file yields null`(@TempDir dir: Path) {
        assertNull(UsageReader(dir.resolve("absent.json")).read())
    }

    @Test
    fun `a torn write yields null and the next read recovers`(@TempDir dir: Path) {
        val file = dir.resolve("claude.json")
        file.writeText(real.take(50))
        val onDisk = UsageReader(file)
        assertNull(onDisk.read())

        file.writeText(real)
        assertEquals(3, onDisk.read()!!.limits.size)
    }

    @Test
    fun `an unchanged file is served from the cache, not re-parsed`(@TempDir dir: Path) {
        val file = dir.resolve("claude.json")
        file.writeText(real)
        val onDisk = UsageReader(file)

        val first = onDisk.read()!!
        assertSame(first, onDisk.read(), "same (size, mtime) must return the cached snapshot")
    }

    @Test
    fun `a rewritten file is re-read`(@TempDir dir: Path) {
        val file = dir.resolve("claude.json")
        file.writeText(real)
        val onDisk = UsageReader(file)
        assertEquals(40, onDisk.read()!!.limits.first().percent)

        // A different length guarantees a new (size, mtime) stamp on any filesystem.
        file.writeText(real.replace(""""percent": 40""", """"percent": 4"""))
        assertEquals(4, onDisk.read()!!.limits.first().percent)
    }

    @Test
    fun `a limit missing its percent is skipped, not defaulted to zero`() {
        val snapshot = parse(
            """
            {"cachedUsageUtilization":{"fetchedAtMs":1,"utilization":{"limits":[
              {"kind":"session","group":"session","resets_at":null},
              {"kind":"weekly_all","group":"weekly","percent":12,"resets_at":null}
            ]}}}
            """.trimIndent(),
        )!!
        assertEquals(listOf("weekly_all"), snapshot.limits.map { it.kind })
    }
}
