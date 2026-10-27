package net.snakepit.sidewinder.core.schedule

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** DatePicker (UTC-midnight) <-> local-instant conversions. */
class ScheduleDatesTest {

    private val losAngeles = ZoneId.of("America/Los_Angeles")

    @Test
    fun `utc midnight for a calendar date`() {
        assertEquals(
            LocalDate.of(2026, 10, 25).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            ScheduleDates.utcMidnight(LocalDate.of(2026, 10, 25)),
        )
    }

    @Test
    fun `a picker value reads back as the same calendar date`() {
        assertEquals(
            LocalDate.of(2026, 10, 25),
            ScheduleDates.localDateFromPicker(ScheduleDates.utcMidnight(LocalDate.of(2026, 10, 25))),
        )
    }

    @Test
    fun `a late-evening local instant seeds the picker with that local day`() {
        val evening = LocalDate.of(2026, 10, 25)
            .atTime(23, 30)
            .atZone(losAngeles)
            .toInstant()
            .toEpochMilli()
        val seed = ScheduleDates.pickerSeed(evening, losAngeles)
        assertEquals(ScheduleDates.utcMidnight(LocalDate.of(2026, 10, 25)), seed)
    }

    @Test
    fun `a picker date plus local time round-trips the instant`() {
        val instant = LocalDate.of(2026, 10, 25)
            .atTime(23, 30)
            .atZone(losAngeles)
            .toInstant()
            .toEpochMilli()
        val seed = ScheduleDates.pickerSeed(instant, losAngeles)
        assertEquals(instant, ScheduleDates.localInstant(seed, 23, 30, losAngeles))
    }

    @Test
    fun `an early-morning UTC instant does not shift the local day back`() {
        // 05:00Z is 22:00 the previous day in Los Angeles; the picker must
        // show Oct 25, not Oct 26.
        val earlyUtc = LocalDate.of(2026, 10, 26)
            .atTime(5, 0)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        assertEquals(
            ScheduleDates.utcMidnight(LocalDate.of(2026, 10, 25)),
            ScheduleDates.pickerSeed(earlyUtc, losAngeles),
        )
    }

    @Test
    fun `schedule lead time includes the five-minute boundary`() {
        val now = 1_000_000L
        assertEquals(false, ScheduleDates.isFutureEnough(now + ScheduleDates.MIN_LEAD_MS - 1, now))
        assertEquals(true, ScheduleDates.isFutureEnough(now + ScheduleDates.MIN_LEAD_MS, now))
    }

    @Test
    fun `UTC zone round-trips unchanged`() {
        val instant = LocalDate.of(2026, 1, 2).atTime(9, 15).toInstant(ZoneOffset.UTC).toEpochMilli()
        val seed = ScheduleDates.pickerSeed(instant, ZoneOffset.UTC)
        assertEquals(instant, ScheduleDates.localInstant(seed, 9, 15, ZoneOffset.UTC))
    }
}
