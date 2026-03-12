package com.hivesight.engine;

import java.util.List;

public record Ramp(
        String id,
        String subscriptionId,
        long effectiveFrom,
        String status,
        List<ItemToAdd> itemsToAdd,
        List<ItemToUpdate> itemsToUpdate,
        List<String> itemsToRemove,
        List<CouponToAdd> couponsToAdd,
        List<String> couponsToRemove,
        List<DiscountToAdd> discountsToAdd,
        List<String> discountsToRemove
) {
    public record ItemToAdd(
            String itemPriceId,
            String itemType,
            Integer quantity,
            Long unitPrice,
            Integer billingPeriod,
            String billingPeriodUnit,
            Integer billingCycles
    ) {}

    public record ItemToUpdate(
            String itemPriceId,
            String itemType,
            Integer quantity,
            Long unitPrice,
            Integer billingPeriod,
            String billingPeriodUnit,
            Integer billingCycles
    ) {}

    public record CouponToAdd(String couponId, Long applyTill) {}

    public record DiscountToAdd(String id, String durationType, Double percentage, Long amount, String applyOn, String itemPriceId) {}
}
