package org.devvikram.societymanagement.common.export

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// An inclusive range of calendar days for a report, read in the societies' time zone (app.report.zone),
// so "from 1 Oct to 31 Oct" means local days, not UTC ones. Report timestamps are printed in that zone too.
data class ReportRange(val start: Instant, val endExclusive: Instant, val zone: ZoneId) {

    fun format(instant: Instant?): String? = instant?.atZone(zone)?.format(TIMESTAMP)

    companion object {
        const val MAX_DAYS = 366L
        // Upper bound on rows in one export; past it the caller is asked to narrow the range.
        const val MAX_ROWS = 10_000

        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun of(from: LocalDate, to: LocalDate, zone: ZoneId): ReportRange {
            require(!to.isBefore(from)) { "'to' must not be before 'from'" }
            require(ChronoUnit.DAYS.between(from, to) < MAX_DAYS) { "A report can cover at most $MAX_DAYS days" }
            return ReportRange(
                start = from.atStartOfDay(zone).toInstant(),
                endExclusive = to.plusDays(1).atStartOfDay(zone).toInstant(),
                zone = zone,
            )
        }

        fun requireWithinLimit(rows: Int) =
            require(rows <= MAX_ROWS) { "More than $MAX_ROWS records in this range; narrow the dates" }
    }
}
