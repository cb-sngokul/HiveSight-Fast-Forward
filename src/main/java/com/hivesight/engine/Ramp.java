package com.hivesight.engine;

import java.util.List;

public record Ramp(
        String id,
        String subscriptionId,
        long effectiveFrom,
        String status,
        List<ItemToAdd> itemsToAdd,
        List<ItemToUpdate> itemsToUpdate,
        List<String> itemsToRemove
) {
    public record ItemToAdd(
            String itemPriceId,
            String itemType,
            Integer quantity,
            Long unitPrice,
            Integer billingPeriod,
            String billingPeriodUnit
    ) {}

    public record ItemToUpdate(
            String itemPriceId,
            String itemType,
            Integer quantity,
            Long unitPrice,
            Integer billingPeriod,
            String billingPeriodUnit
    ) {}
}
