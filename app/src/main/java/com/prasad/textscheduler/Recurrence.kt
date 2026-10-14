package com.prasad.textscheduler

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object Recurrence {

    /**
     * The first send time strictly after [afterMillis], or null if the schedule is finished.
     * Times are in local wall-clock time, so DST changes keep the same time of day.
     */
    fun next(m: ScheduledMessage, afterMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val after = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDateTime()
        val n = m.interval.coerceAtLeast(1)
        val candidate: LocalDateTime? = when (m.repeat) {
            Repeat.NONE -> m.start.takeIf { it.isAfter(after) }
            Repeat.DAILY -> stepped(m.start, after) { k -> m.start.plusDays(k * n) }
            Repeat.MONTHLY -> stepped(m.start, after) { k -> plusMonthsClamped(m.start, k * n) }
            Repeat.YEARLY -> stepped(m.start, after) { k -> plusMonthsClamped(m.start, k * n * 12) }
            Repeat.WEEKLY -> nextWeekly(m, after, n)
        }
        if (candidate == null) return null
        if (m.endDate != null && candidate.toLocalDate().isAfter(m.endDate)) return null
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }

    /** Keeps the original day-of-month; uses the month's last day when it's shorter (e.g. the 31st). */
    private fun plusMonthsClamped(start: LocalDateTime, months: Long): LocalDateTime {
        val ym = YearMonth.from(start).plusMonths(months)
        val day = minOf(start.dayOfMonth, ym.lengthOfMonth())
        return LocalDateTime.of(ym.atDay(day), start.toLocalTime())
    }

    /** Finds the smallest k >= 0 with occurrence(k) > after (occurrences only increase with k). */
    private fun stepped(
        start: LocalDateTime,
        after: LocalDateTime,
        occurrence: (Long) -> LocalDateTime,
    ): LocalDateTime {
        if (start.isAfter(after)) return start
        // Exponential jump so years of history don't mean thousands of steps.
        var probe = 1L
        while (!occurrence(probe).isAfter(after)) probe *= 2
        var k = probe / 2
        while (!occurrence(k).isAfter(after)) k++
        return occurrence(k)
    }

    private fun nextWeekly(m: ScheduledMessage, after: LocalDateTime, n: Int): LocalDateTime? {
        val days = m.weekDays.ifEmpty { setOf(m.start.dayOfWeek) }
        val startWeek = m.start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var date: LocalDate = if (after.toLocalDate().isAfter(m.start.toLocalDate())) after.toLocalDate() else m.start.toLocalDate()
        // Within 2 full cycles there is always a match.
        repeat(7 * n * 2 + 7) {
            val week = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weeksApart = ChronoUnit.WEEKS.between(startWeek, week)
            if (date.dayOfWeek in days && weeksApart % n == 0L) {
                val dt = LocalDateTime.of(date, m.time)
                if (dt.isAfter(after) && !dt.isBefore(m.start)) return dt
            }
            date = date.plusDays(1)
        }
        return null
    }

    // ---------- Human-readable text ----------

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
    private val shortDateFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

    fun describe(m: ScheduledMessage): String {
        val t = m.time.format(timeFmt)
        val n = m.interval
        val every = if (n == 1) "Every" else "Every $n"
        val base = when (m.repeat) {
            Repeat.NONE -> "Once on ${m.start.toLocalDate().format(dateFmt)} at $t"
            Repeat.DAILY -> if (n == 1) "Every day at $t" else "$every days at $t"
            Repeat.WEEKLY -> {
                val days = m.weekDays.ifEmpty { setOf(m.start.dayOfWeek) }.sorted()
                val names = if (days.size == 7) "every day" else days.joinToString(", ") {
                    it.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }
                if (n == 1) "Weekly on $names at $t" else "$every weeks on $names at $t"
            }
            Repeat.MONTHLY -> {
                val d = m.start.dayOfMonth
                val dayText = if (d >= 29) "the ${ordinal(d)} (or last day)" else "the ${ordinal(d)}"
                if (n == 1) "Monthly on $dayText at $t" else "$every months on $dayText at $t"
            }
            Repeat.YEARLY -> {
                val md = m.start.toLocalDate().format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
                if (n == 1) "Every year on $md at $t" else "$every years on $md at $t"
            }
        }
        return if (m.endDate != null && m.repeat != Repeat.NONE)
            "$base, until ${m.endDate.format(shortDateFmt)}" else base
    }

    fun formatWhen(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
        val today = LocalDate.now(zone)
        val day = when (dt.toLocalDate()) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> dt.toLocalDate().format(shortDateFmt)
        }
        return "$day, ${dt.toLocalTime().format(timeFmt)}"
    }

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$n$suffix"
    }
}
