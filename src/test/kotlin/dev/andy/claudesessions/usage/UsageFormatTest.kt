package dev.andy.claudesessions.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class UsageFormatTest {

    private val zone = ZoneId.of("Europe/Kyiv")
    private val now = Instant.parse("2026-07-27T13:29:00Z") // 16:29 local

    @Test
    fun `a reset later today is just the time, in 24-hour form`() {
        val resets = Instant.parse("2026-07-27T16:49:59Z") // 19:49 local
        assertEquals("19:49", UsageFormat.resetShort(resets, now, zone))
    }

    @Test
    fun `a reset tomorrow says tomorrow rather than a date`() {
        val resets = Instant.parse("2026-07-28T13:59:59Z") // 16:59 local, next day
        assertEquals("tomorrow 16:59", UsageFormat.resetShort(resets, now, zone))
    }

    @Test
    fun `a reset further out shows the date as DD MMM`() {
        val resets = Instant.parse("2026-07-30T14:00:00Z") // 17:00 local
        assertEquals("30 Jul 17:00", UsageFormat.resetShort(resets, now, zone))
    }

    @Test
    fun `midnight boundaries are judged by local date, not by elapsed hours`() {
        // 00:30 local tomorrow is only ~8 hours away but is still "tomorrow".
        val resets = Instant.parse("2026-07-27T21:30:00Z")
        assertEquals("tomorrow 00:30", UsageFormat.resetShort(resets, now, zone))
    }

    @Test
    fun `a missing reset time is stated rather than faked`() {
        assertEquals("reset time unknown", UsageFormat.resetShort(null, now, zone))
    }

    @Test
    fun `today and tomorrow are recognised, later dates are not`() {
        assertEquals(true, UsageFormat.isWithinTomorrow(Instant.parse("2026-07-27T16:49:00Z"), now, zone))
        assertEquals(true, UsageFormat.isWithinTomorrow(Instant.parse("2026-07-28T13:59:00Z"), now, zone))
        assertEquals(false, UsageFormat.isWithinTomorrow(Instant.parse("2026-07-30T14:00:00Z"), now, zone))
    }

    @Test
    fun `age is described in the units that read best`() {
        assertEquals("Updated just now", UsageFormat.asOf(now, now, zone))
        assertEquals("Updated 25 min ago", UsageFormat.asOf(now.minusSeconds(25 * 60), now, zone))
        assertEquals("As of 13:29 — 3 h old", UsageFormat.asOf(now.minusSeconds(3 * 3600), now, zone))
    }

    @Test
    fun `percent is rendered plainly`() {
        assertEquals("40%", UsageFormat.percent(40))
        assertEquals("0%", UsageFormat.percent(0))
        assertEquals("100%", UsageFormat.percent(100))
    }
}
