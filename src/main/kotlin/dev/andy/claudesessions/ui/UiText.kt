package dev.andy.claudesessions.ui

/**
 * Session titles, `waitingFor` reasons and cwds are untrusted: they come from model
 * output and arbitrary paths, and are not constrained to any enum. Everything shown in
 * the UI goes through here first.
 */
internal object UiText {

    private const val MAX_LENGTH = 160

    /** Collapses whitespace and control characters onto one line and truncates. */
    fun oneLine(raw: String?, max: Int = MAX_LENGTH): String {
        if (raw.isNullOrBlank()) return ""

        val collapsed = buildString(raw.length) {
            var lastWasSpace = false
            for (ch in raw) {
                val isSpace = ch.isWhitespace() || ch.isISOControl()
                if (isSpace) {
                    if (!lastWasSpace && isNotEmpty()) append(' ')
                    lastWasSpace = true
                } else {
                    append(ch)
                    lastWasSpace = false
                }
            }
        }.trim()

        return if (collapsed.length <= max) collapsed else collapsed.take(max - 1).trimEnd() + "…"
    }

    /**
     * The same treatment for text meant to be read as prose rather than as a label.
     *
     * An agent's account of what it changed is a paragraph or two, and [oneLine] would collapse
     * it into a single unreadable run. Line breaks therefore survive; everything else that could
     * misrender — control characters, runs of blank lines, trailing spaces — does not.
     */
    fun multiLine(raw: String?, maxChars: Int = MAX_BODY_LENGTH, maxLines: Int = MAX_LINES): String {
        if (raw.isNullOrBlank()) return ""

        val lines = raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { line -> line.filterNot { it.isISOControl() }.trimEnd() }

        val kept = mutableListOf<String>()
        for (line in lines) {
            // One blank line separates paragraphs; more is just a gap the model left behind.
            if (line.isBlank() && (kept.isEmpty() || kept.last().isBlank())) continue
            kept += line
            if (kept.size >= maxLines) break
        }

        val text = kept.joinToString("\n").trim()
        val truncated = if (kept.size >= maxLines && lines.size > maxLines) "$text\n…" else text
        return if (truncated.length <= maxChars) truncated
        else truncated.take(maxChars - 1).trimEnd() + "…"
    }

    private const val MAX_BODY_LENGTH = 4_000

    private const val MAX_LINES = 40
}
