package com.konexio.bank.site.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * When something happened, said the way a person would.
 *
 * <p>"Today, 09:14" beats a date every customer has to compare against today's,
 * and stops being useful the moment it is not true — so the relative form only
 * covers yesterday and today, and everything older gets its date.
 */
final class Moments {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_AND_TIME =
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter FULL =
            DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.ENGLISH);

    private Moments() {}

    static String relative(Instant instant, ZoneId zone) {
        ZonedDateTime moment = instant.atZone(zone);
        LocalDate today = LocalDate.now(zone);
        LocalDate day = moment.toLocalDate();

        if (day.equals(today)) {
            return "Today, " + TIME.format(moment);
        }
        if (day.equals(today.minusDays(1))) {
            return "Yesterday, " + TIME.format(moment);
        }
        return DAY_AND_TIME.format(moment);
    }

    /** {@code 22 Sep 2026, 09:14} — a moment stated in full, for a receipt. */
    static String dateAndTime(Instant instant, ZoneId zone) {
        return instant == null ? "" : FULL.format(instant.atZone(zone));
    }

    /**
     * The heading over a day's worth of history — {@code Today},
     * {@code Yesterday}, or the date. The same rule as {@link #relative}, without
     * the time: the rows underneath carry that.
     */
    static String dayHeading(Instant instant, ZoneId zone) {
        LocalDate day = instant.atZone(zone).toLocalDate();
        LocalDate today = LocalDate.now(zone);

        if (day.equals(today)) {
            return "Today";
        }
        if (day.equals(today.minusDays(1))) {
            return "Yesterday";
        }
        return DAY.format(day);
    }

    /** {@code 09:14} — the time alone, under a heading that already gave the day. */
    static String time(Instant instant, ZoneId zone) {
        return instant == null ? "" : TIME.format(instant.atZone(zone));
    }

    static String day(LocalDate date) {
        return date == null ? "" : DAY.format(date);
    }
}
