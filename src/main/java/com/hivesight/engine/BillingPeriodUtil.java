package com.hivesight.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public final class BillingPeriodUtil {

    private BillingPeriodUtil() {}

    public static long addBillingPeriod(long timestampSeconds, int period, String unit) {
        LocalDate date = Instant.ofEpochSecond(timestampSeconds)
                .atZone(ZoneOffset.UTC)
                .toLocalDate();

        return switch (unit.toLowerCase()) {
            case "day" -> date.plusDays(period).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            case "week" -> date.plusWeeks(period).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            case "month" -> date.plusMonths(period).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            case "year" -> date.plusYears(period).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            default -> throw new IllegalArgumentException("Unknown billing period unit: " + unit);
        };
    }
}
