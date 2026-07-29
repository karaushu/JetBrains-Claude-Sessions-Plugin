package dev.andy.claudesessions.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Asserts the declared size of every shipped icon.
 *
 * `IconLoader` sizes an SVG from its `width`/`height` attributes, not from its `viewBox`, and
 * nothing in the build or the previous 219 tests noticed when all fourteen were flattened to
 * 40×40 — the tool window looked plausible while every list row and editor tab drew its icon
 * at two and a half times the right size. The only way to catch that without a screenshot is
 * to assert the attribute.
 */
class IconResourcesTest {

    /** Path to expected declared size, per the sizes the platform draws each one at. */
    private val expected = mapOf(
        // Plugin icon, shown in the plugins list and on the Marketplace.
        "/META-INF/pluginIcon.svg" to 40,
        "/META-INF/pluginIcon_dark.svg" to 40,
        // Tool window stripe button: 13 in the classic UI, 20 in the new one.
        "/icons/claudeSessions.svg" to 13,
        "/icons/claudeSessions_dark.svg" to 13,
        "/icons/expui/claudeSessions.svg" to 20,
        "/icons/expui/claudeSessions_dark.svg" to 20,
        // List rows and editor tabs.
        "/icons/session.svg" to 16,
        "/icons/session_dark.svg" to 16,
        "/icons/sessionLive.svg" to 16,
        "/icons/sessionLive_dark.svg" to 16,
        "/icons/expui/session.svg" to 16,
        "/icons/expui/session_dark.svg" to 16,
        "/icons/expui/sessionLive.svg" to 16,
        "/icons/expui/sessionLive_dark.svg" to 16,
    )

    private fun read(path: String): String =
        (javaClass.getResource(path) ?: error("missing icon resource $path")).readText()

    private fun attr(svg: String, name: String): String? =
        Regex("""\b$name="([^"]+)"""").find(svg)?.groupValues?.get(1)

    @Test
    fun `every icon declares the size the platform draws it at`() {
        for ((path, size) in expected) {
            val svg = read(path)
            assertEquals(size.toString(), attr(svg, "width"), "width of $path")
            assertEquals(size.toString(), attr(svg, "height"), "height of $path")
        }
    }

    @Test
    fun `every icon carries the same artwork, so the whole plugin reads as one thing`() {
        val geometries = expected.keys
            .map { read(it) }
            .mapNotNull { Regex("""\bd="([^"]+)"""").find(it)?.groupValues?.get(1) }
            .map { it.replace(Regex("\\s+"), "") }
            .toSet()

        assertEquals(14, expected.size)
        assertEquals(1, geometries.size, "expected one shared path, found ${geometries.size}")
    }

    @Test
    fun `light and dark variants differ only in colour`() {
        for (path in expected.keys.filter { it.endsWith("_dark.svg") }) {
            val light = read(path.replace("_dark.svg", ".svg"))
            val dark = read(path)
            assertEquals(
                attr(light, "d"),
                attr(dark, "d"),
                "$path should share its light variant's geometry",
            )
            assertEquals(attr(light, "width"), attr(dark, "width"), "$path width")
        }
    }

    @Test
    fun `a live session is drawn in the accent colour and a historical one in the theme grey`() {
        assertEquals("#D97757", attr(read("/icons/sessionLive.svg"), "fill"))
        // The accent reads on both backgrounds, so it is deliberately not themed.
        assertEquals("#D97757", attr(read("/icons/sessionLive_dark.svg"), "fill"))
        assertEquals("#6C707E", attr(read("/icons/session.svg"), "fill"))
        assertEquals("#CED0D6", attr(read("/icons/session_dark.svg"), "fill"))
    }

    @Test
    fun `every icon scales from a viewBox rather than relying on its declared size`() {
        // Without a viewBox the path coordinates would be read as pixels and the glyph would
        // be cropped at 13 and 16.
        for (path in expected.keys) {
            assertTrue(
                attr(read(path), "viewBox") != null,
                "$path must declare a viewBox",
            )
        }
    }
}
