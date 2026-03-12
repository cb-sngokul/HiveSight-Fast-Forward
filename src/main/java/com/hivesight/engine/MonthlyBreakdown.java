package com.hivesight.engine;

import java.util.List;

/**
 * Per-month (or per-period within month for proration) invoice breakdown for the simulation window.
 * When periodLabel is set, this row is a segment of the month (e.g. "Nov 1–25" or "Nov 26–30") with prorated amounts.
 */
public record MonthlyBreakdown(
        String monthKey,
        String monthLabel,
        String periodLabel,
        long unitPrice,
        int quantity,
        long subtotalCents,
        long discountCents,
        Integer taxRatePercent,
        long taxCents,
        long totalCents,
        String currencyCode,
        List<String> changes,
        Long impactVsPreviousCents
) {
    /** For backward compatibility: full month, no period. */
    public static MonthlyBreakdown fullMonth(String monthKey, String monthLabel, long unitPrice, int quantity,
            long subtotalCents, long discountCents, Integer taxRatePercent, long taxCents, long totalCents,
            String currencyCode, List<String> changes, Long impactVsPreviousCents) {
        return new MonthlyBreakdown(monthKey, monthLabel, null, unitPrice, quantity, subtotalCents, discountCents,
                taxRatePercent, taxCents, totalCents, currencyCode, changes, impactVsPreviousCents);
    }
}
