package dev.andy.claudesessions.hooks

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.andy.claudesessions.data.ClaudePaths
import dev.andy.claudesessions.data.array
import dev.andy.claudesessions.data.obj
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

/**
 * Installs the hooks that let the plugin see status changes as they happen, for every
 * entrypoint rather than only interactive terminals.
 *
 * Hooks live in the user's own `~/.claude/settings.json`. Claude merges the `hooks` object
 * across settings files and concatenates the per-event arrays, so adding ours leaves any
 * existing hooks — from other tools or from a project's settings — running untouched.
 */
internal object HookInstaller {

    /**
     * Identifies our entries so install is idempotent and uninstall is precise.
     *
     * A trailing shell comment rather than a path fragment: the command builds its path
     * from a variable, so no literal path string appears in it to match on.
     */
    const val MARKER = "claudesessions-hook"

    /**
     * `sh -c` is used for commands without an `args` key, so redirection and `$HOME` work.
     * The hook's stdin is the event JSON, one line, which we append verbatim.
     */
    fun commandFor(): String =
        """d="${'$'}HOME/.claude/claudesessions"; mkdir -p "${'$'}d" && cat >> "${'$'}d/events.jsonl" # $MARKER"""

    /**
     * `async` plus a short `timeout` matter more than they look: a Notification hook is
     * awaited, and the default timeout is 600 seconds, so a hook that blocked would stall
     * Claude's own UI. Ours can never delay anything.
     */
    private fun hookEntry(): JsonObject = JsonObject().apply {
        addProperty("type", "command")
        addProperty("command", commandFor())
        addProperty("timeout", 5)
        addProperty("async", true)
    }

    fun settingsPath(): Path = ClaudePaths.home.resolve("settings.json")

    fun isInstalled(settings: JsonObject): Boolean {
        val hooks = settings.obj("hooks") ?: return false
        return HookEvent.SUBSCRIBED_EVENTS.all { event ->
            hooks.array(event)?.any { it.containsMarker() } == true
        }
    }

    fun readSettings(path: Path = settingsPath()): JsonObject =
        if (path.exists()) {
            runCatching {
                JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)) as? JsonObject
            }.getOrNull() ?: JsonObject()
        } else {
            JsonObject()
        }

    /**
     * Adds our hook to every subscribed event it is not already on. Returns the updated
     * settings; the input is not modified.
     */
    fun withHooksInstalled(settings: JsonObject): JsonObject {
        val updated = settings.deepCopy()
        val hooks = updated.obj("hooks") ?: JsonObject().also { updated.add("hooks", it) }

        for (event in HookEvent.SUBSCRIBED_EVENTS) {
            val entries = hooks.array(event) ?: JsonArray().also { hooks.add(event, it) }
            if (entries.any { it.containsMarker() }) continue
            entries.add(
                JsonObject().apply {
                    add("hooks", JsonArray().apply { add(hookEntry()) })
                },
            )
        }
        return updated
    }

    /** Removes only our entries, leaving every other tool's hooks in place. */
    fun withHooksRemoved(settings: JsonObject): JsonObject {
        val updated = settings.deepCopy()
        val hooks = updated.obj("hooks") ?: return updated

        for (event in HookEvent.SUBSCRIBED_EVENTS) {
            val entries = hooks.array(event) ?: continue
            val kept = JsonArray()
            entries.filterNot { it.containsMarker() }.forEach(kept::add)
            if (kept.isEmpty) hooks.remove(event) else hooks.add(event, kept)
        }
        if (hooks.isEmpty) updated.remove("hooks")
        return updated
    }

    /**
     * Writes settings back, keeping a timestamped backup. The file holds the user's own
     * configuration, so it is never replaced without one.
     */
    fun write(settings: JsonObject, path: Path = settingsPath(), backupSuffix: String) {
        if (path.exists()) {
            Files.copy(
                path,
                path.resolveSibling("${path.fileName}.backup-$backupSuffix"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        Files.createDirectories(path.parent)
        val json = GsonBuilder().setPrettyPrinting().create().toJson(settings)
        Files.writeString(path, json + "\n", StandardCharsets.UTF_8)
    }

    /** The exact JSON we would add, for showing the user before touching their settings. */
    fun previewJson(): String {
        val preview = JsonObject().apply {
            add(
                "hooks",
                JsonObject().apply {
                    for (event in HookEvent.SUBSCRIBED_EVENTS) {
                        add(
                            event,
                            JsonArray().apply {
                                add(JsonObject().apply { add("hooks", JsonArray().apply { add(hookEntry()) }) })
                            },
                        )
                    }
                },
            )
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(preview)
    }

    private fun com.google.gson.JsonElement.containsMarker(): Boolean =
        toString().contains(MARKER)
}
