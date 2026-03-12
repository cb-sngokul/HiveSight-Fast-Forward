package com.hivesight.engine;

import java.time.Instant;
import java.time.ZoneId;
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
            String currencyCode,
            String timezone,
            Boolean validationPassed,
            String validationMessage
    ) {}

    public static String formatDate(long ts, ZoneId zone) {
        return Instant.ofEpochSecond(ts).atZone(zone != null ? zone : ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /** Full timestamp for display in site timezone. */
    public static String formatDateTime(long ts, ZoneId zone) {
        ZoneId z = zone != null ? zone : ZoneOffset.UTC;
        String zoneName = z.getId();
        return Instant.ofEpochSecond(ts).atZone(z)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + zoneName;
    }

    @SuppressWarnings("unchecked")
    public static SimulatedSubscription toSimulatedSubscription(Map<String, Object> sub) {
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
                        (String) i.get("billing_period_unit"),
                        i.get("billing_cycles") != null ? ((Number) i.get("billing_cycles")).intValue() : null
                ))
                .toList();

        Object cancelledAt = sub.get("cancelled_at");
        Long trialEnd = sub.get("trial_end") != null ? ((Number) sub.get("trial_end")).longValue() : null;
        Long pauseDate = sub.get("pause_date") != null ? ((Number) sub.get("pause_date")).longValue() : null;
        Long resumeDate = sub.get("resume_date") != null ? ((Number) sub.get("resume_date")).longValue() : null;
        Integer remainingBillingCycles = sub.get("remaining_billing_cycles") != null ? ((Number) sub.get("remaining_billing_cycles")).intValue() : null;
        Long contractTermEnd = null;
        String contractActionAtTermEnd = null;
        Integer contractRenewalBillingCycles = null;
        if (sub.get("contract_term") instanceof Map<?, ?> ct) {
            Map<String, Object> ctMap = (Map<String, Object>) ct;
            contractTermEnd = ctMap.get("contract_end") != null ? ((Number) ctMap.get("contract_end")).longValue() : null;
            contractActionAtTermEnd = (String) ctMap.get("action_at_term_end");
            contractRenewalBillingCycles = ctMap.get("renewal_billing_cycles") != null ? ((Number) ctMap.get("renewal_billing_cycles")).intValue() : null;
        }

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
                (String) sub.getOrDefault("currency_code", "USD"),
                trialEnd,
                pauseDate,
                resumeDate,
                remainingBillingCycles,
                contractTermEnd,
                contractActionAtTermEnd,
                contractRenewalBillingCycles
        );
    }

    public static SimulationResult simulate(SimulatedSubscription subscription, List<Ramp> ramps, int months, ZoneId siteTimezone) {
        ZoneId zone = siteTimezone != null ? siteTimezone : ZoneOffset.UTC;
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
        int remainingCycles = state.remainingBillingCycles() != null ? state.remainingBillingCycles() : -1;
        int iterations = 0;
        final int maxIterations = 1000;

        // If in trial, first renewal is after trial_end
        if (state.trialEnd() != null && state.trialEnd() > now) {
            events.add(new TimelineEvent(TimelineEvent.TYPE_TRIAL_END, state.trialEnd(), formatDate(state.trialEnd(), zone), "Trial ends", null, null, null));
            currentDate = Math.max(currentDate, state.trialEnd());
        }

        while (currentDate < endDate && iterations < maxIterations) {
            iterations++;

            // Apply ramps due at or before currentDate
            for (Ramp ramp : sortedRamps) {
                if (ramp.effectiveFrom() <= currentDate && !appliedRampIds.contains(ramp.id())) {
                    state = applyRamp(state, ramp);
                    appliedRampIds.add(ramp.id());
                    long rampAmount = computeRecurringAmount(state);
                    events.add(new TimelineEvent(
                            TimelineEvent.TYPE_RAMP_APPLIED,
                            ramp.effectiveFrom(),
                            formatDate(ramp.effectiveFrom(), zone),
                            "Ramp applied: " + ramp.id(),
                            ramp,
                            rampAmount,
                            state.currencyCode()
                    ));
                }
            }

            // Check pause: if we're in a pause window, skip to resume_date
            if (state.pauseDate() != null && state.pauseDate() <= currentDate) {
                events.add(new TimelineEvent(TimelineEvent.TYPE_PAUSED, state.pauseDate(), formatDate(state.pauseDate(), zone), "Subscription paused", null, null, null));
                if (state.resumeDate() != null && state.resumeDate() > currentDate) {
                    events.add(new TimelineEvent(TimelineEvent.TYPE_RESUMED, state.resumeDate(), formatDate(state.resumeDate(), zone), "Subscription resumed", null, null, null));
                    currentDate = state.resumeDate() + SECONDS_PER_DAY;
                    continue;
                }
            }

            // Check cancellation
            if (state.cancelledAt() != null && state.cancelledAt() <= currentDate) {
                events.add(new TimelineEvent(TimelineEvent.TYPE_CANCELLED, state.cancelledAt(), formatDate(state.cancelledAt(), zone), "Subscription cancelled", null, null, null));
                break;
            }

            if ("cancelled".equals(state.status())) {
                break;
            }

            // Check contract term end
            if (state.contractTermEnd() != null && state.contractTermEnd() <= currentDate) {
                if ("cancel".equals(state.contractActionAtTermEnd())) {
                    events.add(new TimelineEvent(TimelineEvent.TYPE_CONTRACT_END, state.contractTermEnd(), formatDate(state.contractTermEnd(), zone), "Contract ended – subscription cancelled", null, null, null));
                    events.add(new TimelineEvent(TimelineEvent.TYPE_CANCELLED, state.contractTermEnd(), formatDate(state.contractTermEnd(), zone), "Subscription cancelled", null, null, null));
                    break;
                }
                if ("renew".equals(state.contractActionAtTermEnd())) {
                    events.add(new TimelineEvent(TimelineEvent.TYPE_CONTRACT_END, state.contractTermEnd(), formatDate(state.contractTermEnd(), zone), "Contract renewed – new term started", null, null, null));
                    // Reset remaining cycles for the new contract; continue without cancelling
                    int newCycles = state.contractRenewalBillingCycles() != null ? state.contractRenewalBillingCycles() : (remainingCycles > 0 ? remainingCycles : -1);
                    remainingCycles = newCycles;
                    // Clear contract so we don't re-trigger; subscription continues
                    state = new SimulatedSubscription(
                            state.id(), state.customerId(), state.status(),
                            state.currentTermStart(), state.currentTermEnd(), state.nextBillingAt(),
                            state.billingPeriod(), state.billingPeriodUnit(), state.subscriptionItems(),
                            state.cancelledAt(), state.hasScheduledChanges(), state.currencyCode(),
                            state.trialEnd(), state.pauseDate(), state.resumeDate(),
                            remainingCycles >= 0 ? remainingCycles : null,
                            null, null, null  // clear contract_term
                    );
                } else if ("evergreen".equals(state.contractActionAtTermEnd())) {
                    events.add(new TimelineEvent(TimelineEvent.TYPE_CONTRACT_END, state.contractTermEnd(), formatDate(state.contractTermEnd(), zone), "Contract ended – subscription continues (evergreen)", null, null, null));
                    remainingCycles = -1;  // unlimited
                    state = new SimulatedSubscription(
                            state.id(), state.customerId(), state.status(),
                            state.currentTermStart(), state.currentTermEnd(), state.nextBillingAt(),
                            state.billingPeriod(), state.billingPeriodUnit(), state.subscriptionItems(),
                            state.cancelledAt(), state.hasScheduledChanges(), state.currencyCode(),
                            state.trialEnd(), state.pauseDate(), state.resumeDate(),
                            null, null, null, null  // no contract, unlimited
                    );
                } else if ("renew_once".equals(state.contractActionAtTermEnd())) {
                    events.add(new TimelineEvent(TimelineEvent.TYPE_CONTRACT_END, state.contractTermEnd(), formatDate(state.contractTermEnd(), zone), "Contract renewed once – will cancel at end of new term", null, null, null));
                    remainingCycles = state.contractRenewalBillingCycles() != null ? state.contractRenewalBillingCycles() : 1;
                    state = new SimulatedSubscription(
                            state.id(), state.customerId(), state.status(),
                            state.currentTermStart(), state.currentTermEnd(), state.nextBillingAt(),
                            state.billingPeriod(), state.billingPeriodUnit(), state.subscriptionItems(),
                            state.cancelledAt(), state.hasScheduledChanges(), state.currencyCode(),
                            state.trialEnd(), state.pauseDate(), state.resumeDate(),
                            remainingCycles, null, null, null
                    );
                }
            }

            // Check remaining billing cycles (non-renewing): 0 = no more renewals, cancel now
            if (remainingCycles == 0) {
                events.add(new TimelineEvent(TimelineEvent.TYPE_NON_RENEWING, currentDate, formatDate(currentDate, zone), "Final billing cycle – subscription ends", null, null, null));
                events.add(new TimelineEvent(TimelineEvent.TYPE_CANCELLED, currentDate, formatDate(currentDate, zone), "Subscription cancelled", null, null, null));
                break;
            }

            long nextTermEnd = BillingPeriodUtil.addBillingPeriod(
                    state.currentTermEnd(),
                    state.billingPeriod(),
                    state.billingPeriodUnit()
            );

            // Renewal invoice is raised at term end (not the day after)
            long renewalDate = state.currentTermEnd();
            long renewalAmount = computeRecurringAmount(state);
            events.add(new TimelineEvent(
                    TimelineEvent.TYPE_RENEWAL,
                    renewalDate,
                    formatDate(renewalDate, zone),
                    "Renewal: " + state.billingPeriod() + " " + state.billingPeriodUnit() + "(s)",
                    null,
                    renewalAmount,
                    state.currencyCode()
            ));

            if (remainingCycles > 0) remainingCycles--;

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
                    state.currencyCode(),
                    null, // trial ended
                    state.pauseDate(),
                    state.resumeDate(),
                    remainingCycles >= 0 ? remainingCycles : null,
                    state.contractTermEnd(),
                    state.contractActionAtTermEnd(),
                    state.contractRenewalBillingCycles()
            );
            currentDate = nextTermEnd;

            if (state.cancelledAt() != null && state.cancelledAt() <= nextTermEnd) {
                events.add(new TimelineEvent(TimelineEvent.TYPE_CANCELLED, state.cancelledAt(), formatDate(state.cancelledAt(), zone), "Subscription cancelled", null, null, null));
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
                subscription.currencyCode(),
                zone.getId(),
                null,
                null
        );
    }

    /** Returns recurring amount in minor units (cents for USD) from subscription items. */
    private static long computeRecurringAmount(SimulatedSubscription sub) {
        return sub.subscriptionItems().stream()
                .mapToLong(i -> (long) i.quantity() * i.unitPrice())
                .sum();
    }

    private static SimulatedSubscription applyRamp(SimulatedSubscription sub, Ramp ramp) {
        List<SimulatedSubscription.SubscriptionItem> items = new ArrayList<>(sub.subscriptionItems());
        int billingPeriod = sub.billingPeriod();
        String billingPeriodUnit = sub.billingPeriodUnit();

        // Remove items
        if (ramp.itemsToRemove() != null) {
            items.removeIf(i -> ramp.itemsToRemove().contains(i.itemPriceId()));
        }

        // Update items (plan or addon)
        if (ramp.itemsToUpdate() != null) {
            for (Ramp.ItemToUpdate update : ramp.itemsToUpdate()) {
                if ("plan".equals(update.itemType())) {
                    if (update.billingPeriod() != null) billingPeriod = update.billingPeriod();
                    if (update.billingPeriodUnit() != null) billingPeriodUnit = update.billingPeriodUnit();
                }
                for (int i = 0; i < items.size(); i++) {
                    var it = items.get(i);
                    if (it.itemPriceId().equals(update.itemPriceId())) {
                        int qty = update.quantity() != null ? update.quantity() : it.quantity();
                        long price = update.unitPrice() != null ? update.unitPrice() : it.unitPrice();
                        items.set(i, new SimulatedSubscription.SubscriptionItem(
                                it.itemPriceId(),
                                it.itemType(),
                                qty,
                                price,
                                qty * price,
                                update.billingPeriod() != null ? update.billingPeriod() : it.billingPeriod(),
                                update.billingPeriodUnit() != null ? update.billingPeriodUnit() : it.billingPeriodUnit(),
                                update.billingCycles() != null ? update.billingCycles() : it.billingCycles()
                        ));
                        break;
                    }
                }
            }
        }

        // Add items (plan or addon)
        if (ramp.itemsToAdd() != null) {
            for (Ramp.ItemToAdd add : ramp.itemsToAdd()) {
                if ("plan".equals(add.itemType())) {
                    if (add.billingPeriod() != null) billingPeriod = add.billingPeriod();
                    if (add.billingPeriodUnit() != null) billingPeriodUnit = add.billingPeriodUnit();
                    // Plan replacement: remove existing plan before adding new one
                    items.removeIf(i -> "plan".equals(i.itemType()));
                }
                if (items.stream().noneMatch(i -> i.itemPriceId().equals(add.itemPriceId()))) {
                    int qty = add.quantity() != null ? add.quantity() : 1;
                    long price = add.unitPrice() != null ? add.unitPrice() : 0;
                    items.add(new SimulatedSubscription.SubscriptionItem(
                            add.itemPriceId(),
                            add.itemType() != null ? add.itemType() : "addon",
                            qty,
                            price,
                            qty * price,
                            add.billingPeriod(),
                            add.billingPeriodUnit(),
                            add.billingCycles()
                    ));
                }
            }
        }

        // Coupons and discounts are tracked for display; we don't change timeline for them
        return new SimulatedSubscription(
                sub.id(),
                sub.customerId(),
                sub.status(),
                sub.currentTermStart(),
                sub.currentTermEnd(),
                sub.nextBillingAt(),
                billingPeriod,
                billingPeriodUnit,
                items,
                sub.cancelledAt(),
                sub.hasScheduledChanges(),
                sub.currencyCode(),
                sub.trialEnd(),
                sub.pauseDate(),
                sub.resumeDate(),
                sub.remainingBillingCycles(),
                sub.contractTermEnd(),
                sub.contractActionAtTermEnd(),
                sub.contractRenewalBillingCycles()
        );
    }
}
