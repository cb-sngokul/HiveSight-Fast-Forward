package com.hivesight.engine;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class Simulator {

    private static final int SIMULATION_MONTHS = 18;
    private static final long SECONDS_PER_DAY = 86400;

    public record SimulationResult(
            String subscriptionId,
            String customerId,
            long simulationStart,
            long simulationEnd,
            List<TimelineEvent> events,
            Long chargebeeUiNextBilling,
            Long hivesightNextBilling,
            Boolean validationPassed,
            String validationMessage
    ) {}

    public static String formatDate(long ts) {
        return Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static SimulatedSubscription toSimulatedSubscription(Map<String, Object> sub) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sub.getOrDefault("subscription_items", List.of());
        var planItem = items.stream()
                .filter(i -> "plan".equals(i.get("item_type")))
                .findFirst()
                .orElse(Map.<String, Object>of());

        int billingPeriod = ((Number) planItem.getOrDefault("billing_period", sub.getOrDefault("billing_period", 1))).intValue();
        String billingPeriodUnit = (String) planItem.getOrDefault("billing_period_unit",
                sub.getOrDefault("billing_period_unit", "month"));

        var subscriptionItems = items.stream()
                .map(i -> new SimulatedSubscription.SubscriptionItem(
                        (String) i.get("item_price_id"),
                        (String) i.get("item_type"),
                        ((Number) i.getOrDefault("quantity", 1)).intValue(),
                        ((Number) i.getOrDefault("unit_price", 0)).longValue(),
                        ((Number) i.getOrDefault("amount", 0)).longValue(),
                        i.get("billing_period") != null ? ((Number) i.get("billing_period")).intValue() : null,
                        (String) i.get("billing_period_unit")
                ))
                .toList();

        Object cancelledAt = sub.get("cancelled_at");
        return new SimulatedSubscription(
                (String) sub.get("id"),
                (String) sub.get("customer_id"),
                (String) sub.getOrDefault("status", "active"),
                ((Number) sub.getOrDefault("current_term_start", 0)).longValue(),
                ((Number) sub.getOrDefault("current_term_end", 0)).longValue(),
                ((Number) sub.getOrDefault("next_billing_at", 0)).longValue(),
                billingPeriod,
                billingPeriodUnit,
                subscriptionItems,
                cancelledAt != null ? ((Number) cancelledAt).longValue() : null,
                Boolean.TRUE.equals(sub.get("has_scheduled_changes")),
                (String) sub.getOrDefault("currency_code", "USD")
        );
    }

    public static SimulationResult simulate(SimulatedSubscription subscription, List<Ramp> ramps, int months) {
        long now = Instant.now().getEpochSecond();
        long endDate = BillingPeriodUtil.addBillingPeriod(now, months, "month");
        List<TimelineEvent> events = new ArrayList<>();

        SimulatedSubscription state = subscription;
        Set<String> appliedRampIds = new HashSet<>();

        List<Ramp> sortedRamps = ramps.stream()
                .filter(r -> "scheduled".equals(r.status()) && !appliedRampIds.contains(r.id()))
                .sorted(Comparator.comparingLong(Ramp::effectiveFrom))
                .toList();

        long chargebeeUiNextBilling = subscription.nextBillingAt();
        long currentDate = Math.max(state.currentTermEnd(), now);
        int iterations = 0;
        final int maxIterations = 1000;

        while (currentDate < endDate && iterations < maxIterations) {
            iterations++;

            for (Ramp ramp : sortedRamps) {
                if (ramp.effectiveFrom() <= currentDate && !appliedRampIds.contains(ramp.id())) {
                    state = applyRamp(state, ramp);
                    appliedRampIds.add(ramp.id());
                    events.add(new TimelineEvent(
                            TimelineEvent.TYPE_RAMP_APPLIED,
                            ramp.effectiveFrom(),
                            formatDate(ramp.effectiveFrom()),
                            "Ramp applied: " + (ramp.id()),
                            ramp
                    ));
                }
            }

            if (state.cancelledAt() != null && state.cancelledAt() <= currentDate) {
                events.add(new TimelineEvent(
                        TimelineEvent.TYPE_CANCELLED,
                        state.cancelledAt(),
                        formatDate(state.cancelledAt()),
                        "Subscription cancelled",
                        null
                ));
                break;
            }

            if ("cancelled".equals(state.status())) {
                break;
            }

            long nextTermEnd = BillingPeriodUtil.addBillingPeriod(
                    state.currentTermEnd(),
                    state.billingPeriod(),
                    state.billingPeriodUnit()
            );

            // Add the current renewal first (invoice for this term)
            events.add(new TimelineEvent(
                    TimelineEvent.TYPE_RENEWAL,
                    currentDate,
                    formatDate(currentDate),
                    "Renewal: " + state.billingPeriod() + " " + state.billingPeriodUnit() + "(s)",
                    null
            ));

            state = new SimulatedSubscription(
                    state.id(),
                    state.customerId(),
                    state.status(),
                    currentDate,
                    nextTermEnd,
                    nextTermEnd + SECONDS_PER_DAY,
                    state.billingPeriod(),
                    state.billingPeriodUnit(),
                    state.subscriptionItems(),
                    state.cancelledAt(),
                    state.hasScheduledChanges(),
                    state.currencyCode()
            );
            currentDate = nextTermEnd + SECONDS_PER_DAY;

            // After advancing: if cancellation falls within the term we just completed,
            // add CANCELLED and stop (e.g. Aug renewal done, cancel Sept 10)
            if (state.cancelledAt() != null && state.cancelledAt() <= nextTermEnd) {
                events.add(new TimelineEvent(
                        TimelineEvent.TYPE_CANCELLED,
                        state.cancelledAt(),
                        formatDate(state.cancelledAt()),
                        "Subscription cancelled",
                        null
                ));
                break;
            }
        }

        return new SimulationResult(
                subscription.id(),
                subscription.customerId(),
                now,
                endDate,
                events,
                chargebeeUiNextBilling,
                state.nextBillingAt(),
                null,
                null
        );
    }

    private static SimulatedSubscription applyRamp(SimulatedSubscription sub, Ramp ramp) {
        List<SimulatedSubscription.SubscriptionItem> items = new ArrayList<>(sub.subscriptionItems());

        if (ramp.itemsToRemove() != null) {
            items.removeIf(i -> ramp.itemsToRemove().contains(i.itemPriceId()));
        }

        if (ramp.itemsToUpdate() != null) {
            for (Ramp.ItemToUpdate update : ramp.itemsToUpdate()) {
                if ("plan".equals(update.itemType()) && update.billingPeriod() != null) {
                    return new SimulatedSubscription(
                            sub.id(),
                            sub.customerId(),
                            sub.status(),
                            sub.currentTermStart(),
                            sub.currentTermEnd(),
                            sub.nextBillingAt(),
                            update.billingPeriod(),
                            update.billingPeriodUnit() != null ? update.billingPeriodUnit() : sub.billingPeriodUnit(),
                            items,
                            sub.cancelledAt(),
                            sub.hasScheduledChanges(),
                            sub.currencyCode()
                    );
                }
            }
        }

        if (ramp.itemsToAdd() != null) {
            for (Ramp.ItemToAdd add : ramp.itemsToAdd()) {
                if ("plan".equals(add.itemType()) && add.billingPeriod() != null) {
                    return new SimulatedSubscription(
                            sub.id(),
                            sub.customerId(),
                            sub.status(),
                            sub.currentTermStart(),
                            sub.currentTermEnd(),
                            sub.nextBillingAt(),
                            add.billingPeriod(),
                            add.billingPeriodUnit() != null ? add.billingPeriodUnit() : sub.billingPeriodUnit(),
                            items,
                            sub.cancelledAt(),
                            sub.hasScheduledChanges(),
                            sub.currencyCode()
                    );
                }
            }
        }

        return sub;
    }
}
