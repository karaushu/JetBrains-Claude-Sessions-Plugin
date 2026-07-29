package dev.andy.claudesessions.usage

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formatting for the usage popup. 24-hour clock throughout.
 */
internal object UsageFormat {

    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayAndTime = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH)

    /**
     * When the window resets, with no "Resets" prefix — it shares a line with the label,
     * where the word would just be noise.
     *
     * "19:49" today, "tomorrow 16:59" the next day, "30 Jul 17:00" beyond that. The choice
     * is made on the local calendar date, not elapsed hours, so a reset shortly after
     * midnight reads as tomorrow rather than as today.
     */
    fun resetShort(resetsAt: Instant?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        if (resetsAt == null) return "reset time unknown"

        val resetDate = resetsAt.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()

        return when (resetDate) {
            today -> timeOnly.format(resetsAt.atZone(zone))
            today.plusDays(1) -> "tomorrow ${timeOnly.format(resetsAt.atZone(zone))}"
            else -> dayAndTime.format(resetsAt.atZone(zone))
        }
    }

    /** Whether a reset lands today or tomorrow, which is what picks the wording above. */
    fun isWithinTomorrow(resetsAt: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val resetDate: LocalDate = resetsAt.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return resetDate == today || resetDate == today.plusDays(1)
    }

    fun percent(value: Int): String = "$value%"

    /** How out of date the reading is, for the popup footer. */
    fun asOf(fetchedAt: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val minutes = Duration.between(fetchedAt, now).toMinutes()
        val clock = timeOnly.format(fetchedAt.atZone(zone))
        return when {
            minutes < 1 -> "Updated just now"
            minutes < 60 -> "Updated $minutes min ago"
            minutes < 60 * 24 -> "As of $clock — ${minutes / 60} h old"
            else -> "As of ${dayAndTime.format(fetchedAt.atZone(zone))} — stale"
        }
    }
}
