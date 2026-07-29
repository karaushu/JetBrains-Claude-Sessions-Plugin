package dev.andy.claudesessions.hooks

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HookInstallerTest {

    private fun parse(json: String) = JsonParser.parseString(json) as JsonObject

    private val foreignHook = """
        {
          "hooks": {
            "Stop": [
              {"hooks": [{"type": "command", "command": "afplay /System/Library/Sounds/Glass.aiff"}]}
            ],
            "Notification": [
              {"matcher": "permission_prompt", "hooks": [{"type": "command", "command": "afplay x.aiff"}]}
            ]
          },
          "theme": "dark"
        }
    """.trimIndent()

    @Test
    fun `installs on every subscribed event`() {
        val result = HookInstaller.withHooksInstalled(JsonObject())
        val hooks = result.getAsJsonObject("hooks")

        assertEquals(HookEvent.SUBSCRIBED_EVENTS.toSet(), hooks.keySet())
        assertTrue(HookInstaller.isInstalled(result))
    }

    @Test
    fun `is idempotent`() {
        val once = HookInstaller.withHooksInstalled(JsonObject())
        val twice = HookInstaller.withHooksInstalled(once)

        for (event in HookEvent.SUBSCRIBED_EVENTS) {
            assertEquals(1, twice.getAsJsonObject("hooks").getAsJsonArray(event).size(), event)
        }
    }

    @Test
    fun `leaves other tools' hooks and unrelated settings untouched`() {
        val result = HookInstaller.withHooksInstalled(parse(foreignHook))
        val hooks = result.getAsJsonObject("hooks")

        // The sound hook survives, alongside ours.
        assertEquals(2, hooks.getAsJsonArray("Stop").size())
        assertTrue(hooks.getAsJsonArray("Stop").toString().contains("afplay"))
        // And its matcher is preserved verbatim.
        assertTrue(hooks.getAsJsonArray("Notification").toString().contains("\"matcher\":\"permission_prompt\""))
        assertEquals("dark", result.get("theme").asString)
    }

    @Test
    fun `does not modify the settings it is given`() {
        val original = parse(foreignHook)
        val snapshot = original.toString()
        HookInstaller.withHooksInstalled(original)
        assertEquals(snapshot, original.toString())
    }

    @Test
    fun `uninstall removes only our entries`() {
        val installed = HookInstaller.withHooksInstalled(parse(foreignHook))
        val cleaned = HookInstaller.withHooksRemoved(installed)
        val hooks = cleaned.getAsJsonObject("hooks")

        assertFalse(HookInstaller.isInstalled(cleaned))
        assertFalse(cleaned.toString().contains(HookInstaller.MARKER))
        // Someone else's hooks are still there.
        assertEquals(1, hooks.getAsJsonArray("Stop").size())
        assertTrue(hooks.getAsJsonArray("Stop").toString().contains("afplay"))
    }

    @Test
    fun `uninstall drops an event key it emptied, and the hooks object if empty`() {
        val installed = HookInstaller.withHooksInstalled(JsonObject())
        val cleaned = HookInstaller.withHooksRemoved(installed)
        assertFalse(cleaned.has("hooks"))
    }

    @Test
    fun `not reported installed when only some events carry our hook`() {
        val partial = HookInstaller.withHooksInstalled(JsonObject())
        partial.getAsJsonObject("hooks").remove("Stop")
        assertFalse(HookInstaller.isInstalled(partial))
    }

    @Test
    fun `the command cannot stall Claude`() {
        // A Notification hook is awaited with a 600-second default timeout, so both a short
        // explicit timeout and async are required, not optional.
        val entry = HookInstaller.withHooksInstalled(JsonObject())
            .getAsJsonObject("hooks")
            .getAsJsonArray("Notification")[0].asJsonObject
            .getAsJsonArray("hooks")[0].asJsonObject

        assertEquals(5, entry.get("timeout").asInt)
        assertTrue(entry.get("async").asBoolean)
        assertEquals("command", entry.get("type").asString)
    }

    @Test
    fun `no matcher is set, so every notification type is observed`() {
        // notification_type is an open string in Claude's schema; a matcher would silently
        // miss values added later.
        val wrapper = HookInstaller.withHooksInstalled(JsonObject())
            .getAsJsonObject("hooks")
            .getAsJsonArray("Notification")[0].asJsonObject

        assertFalse(wrapper.has("matcher"))
    }

    @Test
    fun `preview shows exactly what would be added`() {
        val preview = parse(HookInstaller.previewJson())
        assertEquals(
            HookEvent.SUBSCRIBED_EVENTS.toSet(),
            preview.getAsJsonObject("hooks").keySet(),
        )
        assertTrue(preview.toString().contains(HookInstaller.MARKER))
    }
}
