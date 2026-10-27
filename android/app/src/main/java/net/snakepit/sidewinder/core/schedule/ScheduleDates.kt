package net.snakepit.sidewinder.core.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Conversions between the Material DatePicker's UTC-midnight millis and
 * the local instants the API takes. The picker represents a calendar date
 * as UTC midnight, so feeding it a local instant directly (or feeding its
 * output to a local parser) shifts the calendar day by a timezone.
 */
object ScheduleDates {

    const val MIN_LEAD_MS = 5 * 60_000L

    /** GTS accepts scheduled posts only when they are at least five minutes ahead. */
    fun isFutureEnough(instantMillis: Long, nowMillis: Long): Boolean =
        instantMillis >= nowMillis + MIN_LEAD_MS

    /** UTC-midnight millis for a calendar date, the DatePicker's format. */
    fun utcMidnight(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The calendar date a DatePicker value stands for. */
    fun localDateFromPicker(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    /**
     * DatePicker seed for an existing local instant: the instant's date in
     * [zone], converted back to UTC midnight, so the picker opens on the
     * day the user sees.
     */
    fun pickerSeed(instantMillis: Long, zone: ZoneId): Long =
        utcMidnight(Instant.ofEpochMilli(instantMillis).atZone(zone).toLocalDate())

    /**
     * The real instant for a picked calendar date and wall-clock time in
     * [zone]. [pickerMillis] is a DatePicker (UTC-midnight) value.
     */
    fun localInstant(pickerMillis: Long, hour: Int, minute: Int, zone: ZoneId): Long =
        localDateFromPicker(pickerMillis)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
