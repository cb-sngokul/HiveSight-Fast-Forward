package com.hivesight.engine;

import java.util.List;

/**
 * Per-month invoice breakdown for the simulation window.
 */
public record MonthlyBreakdown(
        String monthKey,
        String monthLabel,
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
) {}
