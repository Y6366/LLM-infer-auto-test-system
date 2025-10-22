package com.example.shop.common.util;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public final class DateTimeUtil {
    private DateTimeUtil() {}

    public static final ZoneId UTC = ZoneId.of("UTC");
    public static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;
    public static final DateTimeFormatter ISO_LOCAL_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    // now
    public static Instant nowUtc() { return Instant.now(); }
    public static ZonedDateTime now(ZoneId zone) { return ZonedDateTime.now(zone); }

    // format
    public static String formatIso(Instant instant) { return ISO_INSTANT.format(instant); }
    public static String formatLocal(ZonedDateTime zdt) { return ISO_LOCAL_MS.format(zdt); }

    // parse
    public static Instant parseIsoInstant(String s) { return Instant.parse(s); }

    // epoch
    public static long toEpochMilli(Instant i) { return i.toEpochMilli(); }
    public static Instant fromEpochMilli(long ms) { return Instant.ofEpochMilli(ms); }

    // day boundaries
    public static ZonedDateTime startOfDay(LocalDate date, ZoneId zone) { return date.atStartOfDay(zone); }
    public static ZonedDateTime endOfDay(LocalDate date, ZoneId zone) {
        return date.plusDays(1).atStartOfDay(zone).minusNanos(1);
    }

    // add/sub
    public static Instant plusMinutes(Instant i, long minutes) { return i.plus(minutes, ChronoUnit.MINUTES); }
    public static Instant plusSeconds(Instant i, long seconds) { return i.plus(seconds, ChronoUnit.SECONDS); }

    // human-readable duration: 1d 2h 3m 4s
    public static String pretty(Duration d) {
        if (d.isNegative()) d = d.negated();
        long days = d.toDays();
        long hours = d.minusDays(days).toHours();
        long minutes = d.minusDays(days).minusHours(hours).toMinutes();
        long seconds = d.minusDays(days).minusHours(hours).minusMinutes(minutes).getSeconds();
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append("s");
        return sb.toString().trim();
    }
}
