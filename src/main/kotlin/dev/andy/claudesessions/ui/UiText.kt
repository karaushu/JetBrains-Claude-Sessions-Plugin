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
}
