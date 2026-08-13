package dev.andy.claudesessions.review

import com.intellij.openapi.diagnostic.thisLogger
import dev.andy.claudesessions.data.ClaudePaths
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * File names and paths for review rounds, keyed by project.
 *
 * Two projects reviewing at once must never meet, so each gets its own directory named the way
 * Claude itself names project directories — see [ClaudePaths.encodeProjectDir]. That encoding
 * is lossy, which does not matter here: the name only has to be unique, stable and free of
 * whitespace, because it ends up in a path pasted into a terminal.
 */
internal object ReviewPaths {

    fun directory(projectBasePath: String): Path =
        ClaudePaths.reviews.resolve(ClaudePaths.encodeProjectDir(projectBasePath))

    fun reviewFile(projectBasePath: String, roundId: String): Path =
        directory(projectBasePath).resolve("review-$roundId.md")

    fun repliesFile(projectBasePath: String, roundId: String): Path =
        directory(projectBasePath).resolve("replies-$roundId.jsonl")

    /**
     * `20260812-104233-a1b2`. Sortable, and unique without a persisted counter so that a round
     * started right after a crash cannot collide with the one before it.
     */
    fun newRoundId(nowMillis: Long, salt: Int): String {
        val stamp = STAMP.format(java.time.Instant.ofEpochMilli(nowMillis))
        return "$stamp-%04x".format(salt and 0xFFFF)
    }

    /**
     * Turns an absolute path into the project-relative form the agent works in, or null when
     * the file is outside the project and therefore not something a session rooted in it
     * should be asked to edit.
     */
    fun relativise(projectBasePath: String, absolutePath: String): String? {
        val base = projectBasePath.trimEnd('/').ifEmpty { return null }
        val normalised = absolutePath.replace('\\', '/')
        if (!normalised.startsWith("$base/")) return null
        val relative = normalised.removePrefix("$base/")
        if (relative.isEmpty() || relative.split('/').any { it == ".." }) return null
        return relative
    }

    /** The markdown fence language for a hunk, so the agent reads it as code and not prose. */
    fun languageId(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "ts" -> "ts"
        "tsx" -> "tsx"
        "js", "mjs", "cjs" -> "js"
        "jsx" -> "jsx"
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "go" -> "go"
        "rs" -> "rust"
        "rb" -> "ruby"
        "php" -> "php"
        "vue" -> "vue"
        "svelte" -> "svelte"
        "css", "scss", "less" -> "css"
        "html" -> "html"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "sql" -> "sql"
        "sh", "bash", "zsh" -> "bash"
        "md" -> "markdown"
        else -> null
    }

    /**
     * Deletes this project's older rounds, so the plugin cleans up after itself without a
     * setting to forget about. Same spirit as the hook log truncating itself once read.
     */
    fun prune(directory: Path, nowMillis: Long) {
        if (!directory.isDirectory()) return
        runCatching {
            val files = directory.listDirectoryEntries()
                .filter { it.name.startsWith("review-") || it.name.startsWith("replies-") }
            val stale = files.filter { nowMillis - it.getLastModifiedTime().toMillis() > MAX_AGE_MS }
            val surplus = files.sortedByDescending { it.getLastModifiedTime().toMillis() }
                .drop(MAX_FILES)
            (stale + surplus).distinct().forEach { it.deleteIfExists() }
        }.onFailure { thisLogger().debug("Cannot prune review files in $directory", it) }
    }

    /** A formatter rather than `SimpleDateFormat`: rounds can be minted off any thread. */
    private val STAMP: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneId.systemDefault())

    private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

    /** A review file and its replies file count separately, so this is 20 rounds. */
    private const val MAX_FILES = 40
}
