package com.hivesight.engine;

import java.util.List;

public record SimulatedSubscription(
        String id,
        String customerId,
        String status,
        long currentTermStart,
        long currentTermEnd,
        long nextBillingAt,
        int billingPeriod,
        String billingPeriodUnit,
        List<SubscriptionItem> subscriptionItems,
        Long cancelledAt,
        boolean hasScheduledChanges,
        String currencyCode
) {
    public record SubscriptionItem(
            String itemPriceId,
            String itemType,
            int quantity,
            long unitPrice,
            long amount,
            Integer billingPeriod,
            String billingPeriodUnit
    ) {}
}
