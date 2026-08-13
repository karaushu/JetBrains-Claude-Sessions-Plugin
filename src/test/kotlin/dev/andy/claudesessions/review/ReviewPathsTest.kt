package dev.andy.claudesessions.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

class ReviewPathsTest {

    @Test
    fun `two projects never share a review directory`() {
        val one = ReviewPaths.directory("/Users/dev/projects/web")
        val other = ReviewPaths.directory("/Users/dev/projects/api")

        assertNotEquals(one, other)
    }

    @Test
    fun `a round id sorts by time and carries a salt`() {
        val id = ReviewPaths.newRoundId(1_700_000_000_000, salt = 0xBEEF)

        assertTrue(Regex("^\\d{8}-\\d{6}-[0-9a-f]{4}$").matches(id), "unexpected round id: $id")
        assertNotEquals(id, ReviewPaths.newRoundId(1_700_000_000_000, salt = 1))
    }

    @Test
    fun `round file names contain no whitespace because they end up in a terminal`() {
        val id = ReviewPaths.newRoundId(1_700_000_000_000, salt = 1)
        val review = ReviewPaths.reviewFile("/Users/dev/my projects/web", id)
        val replies = ReviewPaths.repliesFile("/Users/dev/my projects/web", id)

        assertFalse(review.toString().any { it.isWhitespace() })
        assertNotEquals(review, replies)
        assertEquals(review.parent, replies.parent)
    }

    @Test
    fun `a path inside the project becomes relative`() {
        assertEquals(
            "src/api/client.ts",
            ReviewPaths.relativise("/Users/dev/web", "/Users/dev/web/src/api/client.ts"),
        )
    }

    @Test
    fun `a trailing slash on the project root does not confuse it`() {
        assertEquals("a.ts", ReviewPaths.relativise("/Users/dev/web/", "/Users/dev/web/a.ts"))
    }

    @Test
    fun `a path outside the project is refused`() {
        assertNull(ReviewPaths.relativise("/Users/dev/web", "/Users/dev/other/a.ts"))
        assertNull(ReviewPaths.relativise("/Users/dev/web", "/Users/dev/web"))
        assertNull(ReviewPaths.relativise("", "/Users/dev/web/a.ts"))
    }

    @Test
    fun `a path that climbs out with dot dot is refused`() {
        assertNull(ReviewPaths.relativise("/Users/dev/web", "/Users/dev/web/../secrets"))
    }

    @Test
    fun `the fence language comes from the extension`() {
        assertEquals("ts", ReviewPaths.languageId("src/a.ts"))
        assertEquals("kotlin", ReviewPaths.languageId("Main.kt"))
        assertNull(ReviewPaths.languageId("Makefile"))
    }

    @Test
    fun `pruning drops stale rounds and keeps the recent ones`(@TempDir dir: Path) {
        val stale = dir.resolve("review-old.md").apply { writeText("x") }
        val staleReplies = dir.resolve("replies-old.jsonl").apply { writeText("x") }
        val fresh = dir.resolve("review-new.md").apply { writeText("x") }
        val unrelated = dir.resolve("notes.txt").apply { writeText("x") }
        val ancient = java.nio.file.attribute.FileTime.fromMillis(1_000)
        stale.setLastModifiedTime(ancient)
        staleReplies.setLastModifiedTime(ancient)

        ReviewPaths.prune(dir, nowMillis = 30L * 24 * 60 * 60 * 1000)

        assertFalse(stale.exists())
        assertFalse(staleReplies.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists(), "pruning only touches files it wrote itself")
    }

    @Test
    fun `pruning keeps only the newest rounds when there are many`(@TempDir dir: Path) {
        repeat(60) { index ->
            val file = dir.resolve("review-$index.md")
            file.writeText("x")
            file.setLastModifiedTime(java.nio.file.attribute.FileTime.fromMillis(1_000L + index))
        }

        ReviewPaths.prune(dir, nowMillis = 2_000)

        val kept = dir.toFile().listFiles()!!.map { it.name }
        assertEquals(40, kept.size)
        assertTrue(kept.contains("review-59.md"))
        assertFalse(kept.contains("review-0.md"))
    }

    @Test
    fun `pruning a directory that is not there is not an error`(@TempDir dir: Path) {
        ReviewPaths.prune(dir.resolve("absent"), nowMillis = 0)
    }
}
