package com.hivesight.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class Simulator {

    private static final long SECONDS_PER_DAY = 86400;

    /** Parse YYYY-MM to epoch seconds at start of month (in zone). */
    public static long parseStartOfMonth(String yyyyMm, ZoneId zone) {
        YearMonth ym = YearMonth.parse(yyyyMm);
        return ym.atDay(1).atStartOfDay(zone != null ? zone : ZoneOffset.UTC).toEpochSecond();
    }

    /** Parse YYYY-MM to epoch seconds at end of month (last second, in zone). */
    public static long parseEndOfMonth(String yyyyMm, ZoneId zone) {
        YearMonth ym = YearMonth.parse(yyyyMm);
        return ym.atEndOfMonth().atTime(23, 59, 59).atZone(zone != null ? zone : ZoneOffset.UTC).toEpochSecond();
    }

    /** Start of calendar month (day 1 00:00:00) for the given epoch, in zone. */
    private static long startOfMonth(long epochSeconds, ZoneId zone) {
        LocalDate d = Instant.ofEpochSecond(epochSeconds).atZone(zone != null ? zone : ZoneOffset.UTC).toLocalDate();
        return d.withDayOfMonth(1).atStartOfDay(zone != null ? zone : ZoneOffset.UTC).toEpochSecond();
    }

    /** End of calendar month (last day 23:59:59) for the given epoch, in zone. */
    private static long endOfMonth(long epochSeconds, ZoneId zone) {
        LocalDate d = Instant.ofEpochSecond(epochSeconds).atZone(zone != null ? zone : ZoneOffset.UTC).toLocalDate();
        return d.withDayOfMonth(d.lengthOfMonth()).atTime(23, 59, 59).atZone(zone != null ? zone : ZoneOffset.UTC).toEpochSecond();
    }

    /** Number of days (inclusive) between start and end (epoch seconds, same zone assumed). */
    private static int daysInRange(long startSeconds, long endSeconds) {
        return Math.max(1, (int) ((endSeconds - startSeconds) / SECONDS_PER_DAY) + 1);
    }

    /** Days in the month of the given epoch. */
    private static int daysInMonth(long epochSeconds, ZoneId zone) {
        LocalDate d = Instant.ofEpochSecond(epochSeconds).atZone(zone != null ? zone : ZoneOffset.UTC).toLocalDate();
        return d.lengthOfMonth();
    }

    /** Human-readable period label e.g. "Nov 1–25". */
    private static String formatPeriodLabel(long startSeconds, long endSeconds, ZoneId zone) {
        ZoneId z = zone != null ? zone : ZoneOffset.UTC;
        LocalDate start = Instant.ofEpochSecond(startSeconds).atZone(z).toLocalDate();
        LocalDate end = Instant.ofEpochSecond(endSeconds).atZone(z).toLocalDate();
        String mon = start.format(DateTimeFormatter.ofPattern("MMM yyyy"));
        return mon + " " + start.getDayOfMonth() + "–" + end.getDayOfMonth();
    }

    public record SimulationResult(
            String subscriptionId,
            String customerId,
            long simulationStart,
            long simulationEnd,
            Long subscriptionEndDate,
            List<TimelineEvent> events,
            List<MonthlyBreakdown> monthlyBreakdowns,
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
            // Fallback: Chargebee returns contract_term_billing_cycle_on_renewal at subscription level
            if (contractRenewalBillingCycles == null && sub.get("contract_term_billing_cycle_on_renewal") != null) {
                contractRenewalBillingCycles = ((Number) sub.get("contract_term_billing_cycle_on_renewal")).intValue();
            }
            // Fallback: use billing_cycle from contract_term for renewed contract
            if (contractRenewalBillingCycles == null && ctMap.get("billing_cycle") != null) {
                contractRenewalBillingCycles = ((Number) ctMap.get("billing_cycle")).intValue();
            }
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

    /**
     * Computes the effective contract end date considering ramps (billing period changes).
     * Projects forward from current state, applying ramps as they take effect, until remaining cycles are exhausted.
     */
    public static Long computeEffectiveContractEnd(SimulatedSubscription subscription, List<Ramp> ramps, ZoneId zone) {
        ZoneId z = zone != null ? zone : ZoneOffset.UTC;
        SimulatedSubscription state = subscription;
        Set<String> appliedRampIds = new HashSet<>();
        List<Ramp> sortedRamps = ramps.stream()
                .filter(r -> "scheduled".equals(r.status()))
                .sorted(Comparator.comparingLong(Ramp::effectiveFrom))
                .toList();

        long currentDate = state.currentTermEnd();
        int remainingCycles = state.remainingBillingCycles() != null ? state.remainingBillingCycles() : -1;
        if (remainingCycles < 0) return state.contractTermEnd(); // unlimited, use raw contract_end if any
        if (remainingCycles == 0) return currentDate; // already at end

        for (int i = 0; i < 1000 && remainingCycles > 0; i++) {
            // Apply ramps due at or before currentDate
            for (Ramp ramp : sortedRamps) {
                if (ramp.effectiveFrom() <= currentDate && !appliedRampIds.contains(ramp.id())) {
                    state = applyRamp(state, ramp);
                    appliedRampIds.add(ramp.id());
                }
            }

            long nextTermEnd = BillingPeriodUtil.addBillingPeriod(
                    state.currentTermEnd(),
                    state.billingPeriod(),
                    state.billingPeriodUnit()
            );
            remainingCycles--;
            state = new SimulatedSubscription(
                    state.id(), state.customerId(), state.status(),
                    currentDate, nextTermEnd, nextTermEnd + 86400,
                    state.billingPeriod(), state.billingPeriodUnit(), state.subscriptionItems(),
                    state.cancelledAt(), state.hasScheduledChanges(), state.currencyCode(),
                    state.trialEnd(), state.pauseDate(), state.resumeDate(),
                    remainingCycles >= 0 ? remainingCycles : null,
                    state.contractTermEnd(), state.contractActionAtTermEnd(), state.contractRenewalBillingCycles()
            );
            currentDate = nextTermEnd;
        }
        return remainingCycles == 0 ? currentDate : state.contractTermEnd();
    }

    /**
     * Computes the subscription end date from Chargebee API response.
     * Returns null if the subscription renews indefinitely (no end).
     * Sources: cancelled_at (scheduled cancel), contract_end when action is "cancel".
     */
    public static Long subscriptionEndDate(Map<String, Object> sub) {
        Object cancelledAt = sub.get("cancelled_at");
        if (cancelledAt != null) {
            return ((Number) cancelledAt).longValue();
        }
        if (sub.get("contract_term") instanceof Map<?, ?> ct) {
            Map<String, Object> ctMap = (Map<String, Object>) ct;
            String action = (String) ctMap.get("action_at_term_end");
            if ("cancel".equals(action)) {
                Object ce = ctMap.get("contract_end");
                if (ce != null) return ((Number) ce).longValue();
            }
        }
        return null;
    }

    public static SimulationResult simulate(SimulatedSubscription subscription, List<Ramp> ramps, long simulationStart, long simulationEnd, Long subscriptionEndDate, ZoneId siteTimezone, Integer taxRatePercent) {
        ZoneId zone = siteTimezone != null ? siteTimezone : ZoneOffset.UTC;
        int taxRate = taxRatePercent != null ? taxRatePercent : 0;
        // Use subscription end date when available; otherwise use user's simulation end
        long effectiveEnd = subscriptionEndDate != null
                ? Math.min(simulationEnd, subscriptionEndDate)
                : simulationEnd;
        List<TimelineEvent> events = new ArrayList<>();
        List<MonthlyBreakdown> monthlyBreakdowns = new ArrayList<>();
        MonthlyBreakdown lastBreakdown = null;

        SimulatedSubscription state = subscription;
        Set<String> appliedRampIds = new HashSet<>();

        List<Ramp> sortedRamps = ramps.stream()
                .filter(r -> "scheduled".equals(r.status()) && !appliedRampIds.contains(r.id()))
                .sorted(Comparator.comparingLong(Ramp::effectiveFrom))
                .toList();

        long chargebeeUiNextBilling = subscription.nextBillingAt();
        long currentDate = Math.max(state.currentTermEnd(), simulationStart);
        int remainingCycles = state.remainingBillingCycles() != null ? state.remainingBillingCycles() : -1;
        int iterations = 0;
        final int maxIterations = 1000;

        // If in trial, first renewal is after trial_end
        if (state.trialEnd() != null && state.trialEnd() > simulationStart) {
            events.add(new TimelineEvent(TimelineEvent.TYPE_TRIAL_END, state.trialEnd(), formatDate(state.trialEnd(), zone), "Trial ends", null, null, null));
            currentDate = Math.max(currentDate, state.trialEnd());
        }

        while (currentDate < effectiveEnd && iterations < maxIterations) {
            iterations++;

            SimulatedSubscription stateBeforeRamps = state;
            List<Ramp> rampsAppliedThisIteration = new ArrayList<>();
            // Apply ramps due at or before currentDate
            for (Ramp ramp : sortedRamps) {
                if (ramp.effectiveFrom() <= currentDate && !appliedRampIds.contains(ramp.id())) {
                    state = applyRamp(state, ramp);
                    appliedRampIds.add(ramp.id());
                    rampsAppliedThisIteration.add(ramp);
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
                    // Reset remaining cycles for the new contract; preserve renew action so it keeps renewing
                    int newCycles = state.contractRenewalBillingCycles() != null ? state.contractRenewalBillingCycles() : (remainingCycles > 0 ? remainingCycles : -1);
                    remainingCycles = newCycles;
                    // Preserve contractActionAtTermEnd and contractRenewalBillingCycles so subscription keeps renewing
                    state = new SimulatedSubscription(
                            state.id(), state.customerId(), state.status(),
                            state.currentTermStart(), state.currentTermEnd(), state.nextBillingAt(),
                            state.billingPeriod(), state.billingPeriodUnit(), state.subscriptionItems(),
                            state.cancelledAt(), state.hasScheduledChanges(), state.currencyCode(),
                            state.trialEnd(), state.pauseDate(), state.resumeDate(),
                            remainingCycles >= 0 ? remainingCycles : null,
                            null, "renew", state.contractRenewalBillingCycles()  // keep renew behavior
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

            // Check remaining billing cycles: 0 = end of current contract
            // If action_at_term_end is "renew" or "evergreen", renew instead of cancelling
            if (remainingCycles == 0) {
                if ("renew".equals(state.contractActionAtTermEnd())) {
                    events.add(new TimelineEvent(TimelineEvent.TYPE_CONTRACT_END, currentDate, formatDate(currentDate, zone), "Contract renewed – new term started (cycles exhausted)", null, null, null));
                    int newCycles = state.contractRenewalBillingCycles() != null ? state.contractRenewalBillingCycles() : -1;
                    remainingCycles = newCycles;
                    // Preserve renew behavior so subscription keeps renewing until simulation end
                    state = new SimulatedSubscription(
                            state.id(), state.customerId(), state.status(),
                            state.currentTermStart(), state.currentTermEnd(), state.nextBillingAt(),
                            state.billingPeriod(), state.billingPeriodUnit(), state.subscriptionItems(),
                            state.cancelledAt(), state.hasScheduledChanges(), state.currencyCode(),
                            state.trialEnd(), state.pauseDate(), state.resumeDate(),
                            remainingCycles >= 0 ? remainingCycles : null,
                            null, "renew", state.contractRenewalBillingCycles()  // keep renew behavior
                    );
                } else if ("evergreen".equals(state.contractActionAtTermEnd())) {
                    events.add(new TimelineEvent(TimelineEvent.TYPE_CONTRACT_END, currentDate, formatDate(currentDate, zone), "Contract ended – subscription continues (evergreen)", null, null, null));
                    remainingCycles = -1;
                    state = new SimulatedSubscription(
                            state.id(), state.customerId(), state.status(),
                            state.currentTermStart(), state.currentTermEnd(), state.nextBillingAt(),
                            state.billingPeriod(), state.billingPeriodUnit(), state.subscriptionItems(),
                            state.cancelledAt(), state.hasScheduledChanges(), state.currencyCode(),
                            state.trialEnd(), state.pauseDate(), state.resumeDate(),
                            null, null, null, null
                    );
                } else if ("renew_once".equals(state.contractActionAtTermEnd())) {
                    events.add(new TimelineEvent(TimelineEvent.TYPE_CONTRACT_END, currentDate, formatDate(currentDate, zone), "Contract renewed once – will cancel at end of new term", null, null, null));
                    remainingCycles = state.contractRenewalBillingCycles() != null ? state.contractRenewalBillingCycles() : 1;
                    state = new SimulatedSubscription(
                            state.id(), state.customerId(), state.status(),
                            state.currentTermStart(), state.currentTermEnd(), state.nextBillingAt(),
                            state.billingPeriod(), state.billingPeriodUnit(), state.subscriptionItems(),
                            state.cancelledAt(), state.hasScheduledChanges(), state.currencyCode(),
                            state.trialEnd(), state.pauseDate(), state.resumeDate(),
                            remainingCycles, null, null, null
                    );
                } else if (subscriptionEndDate == null) {
                    // No subscription end date: renew forever on contract term end (evergreen)
                    events.add(new TimelineEvent(TimelineEvent.TYPE_CONTRACT_END, currentDate, formatDate(currentDate, zone), "No end date – subscription continues (renew forever)", null, null, null));
                    remainingCycles = -1;
                    state = new SimulatedSubscription(
                            state.id(), state.customerId(), state.status(),
                            state.currentTermStart(), state.currentTermEnd(), state.nextBillingAt(),
                            state.billingPeriod(), state.billingPeriodUnit(), state.subscriptionItems(),
                            state.cancelledAt(), state.hasScheduledChanges(), state.currencyCode(),
                            state.trialEnd(), state.pauseDate(), state.resumeDate(),
                            null, null, null, null
                    );
                } else {
                    // cancel or no contract with end date: treat as non-renewing
                    events.add(new TimelineEvent(TimelineEvent.TYPE_NON_RENEWING, currentDate, formatDate(currentDate, zone), "Final billing cycle – subscription ends", null, null, null));
                    events.add(new TimelineEvent(TimelineEvent.TYPE_CANCELLED, currentDate, formatDate(currentDate, zone), "Subscription cancelled", null, null, null));
                    break;
                }
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

            // After applying ramps: add "tail" period rows first for any ramp that took effect in a previous calendar month (keeps same-month rows together)
            for (Ramp ramp : rampsAppliedThisIteration) {
                long eff = ramp.effectiveFrom();
                long effMonthStart = startOfMonth(eff, zone);
                long effMonthEnd = endOfMonth(eff, zone);
                if (effMonthEnd < renewalDate && eff >= effMonthStart) {
                    MonthlyBreakdown tail = buildMonthlyBreakdown(state, List.of(ramp), stateBeforeRamps, eff, zone, state.currencyCode(), taxRate, lastBreakdown, eff, effMonthEnd);
                    monthlyBreakdowns.add(tail);
                    lastBreakdown = tail;
                }
            }

            // Check if a ramp will take effect later in this calendar month (proration split)
            long monthStart = startOfMonth(renewalDate, zone);
            long monthEnd = endOfMonth(renewalDate, zone);
            Ramp rampLaterInMonth = sortedRamps.stream()
                    .filter(r -> !appliedRampIds.contains(r.id()))
                    .filter(r -> r.effectiveFrom() > renewalDate && r.effectiveFrom() <= monthEnd)
                    .findFirst()
                    .orElse(null);

            if (rampLaterInMonth != null) {
                // First segment: month start → renewal date (prorated)
                MonthlyBreakdown firstSegment = buildMonthlyBreakdown(state, rampsAppliedThisIteration, stateBeforeRamps, renewalDate, zone, state.currencyCode(), taxRate, lastBreakdown, monthStart, renewalDate);
                monthlyBreakdowns.add(firstSegment);
                lastBreakdown = firstSegment;
            } else {
                MonthlyBreakdown bd = buildMonthlyBreakdown(state, rampsAppliedThisIteration, stateBeforeRamps, renewalDate, zone, state.currencyCode(), taxRate, lastBreakdown, null, null);
                monthlyBreakdowns.add(bd);
                lastBreakdown = bd;
            }

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
                simulationStart,
                effectiveEnd,
                subscriptionEndDate,
                events,
                monthlyBreakdowns,
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

    private static SimulatedSubscription.SubscriptionItem getPlanItem(SimulatedSubscription sub) {
        return sub.subscriptionItems().stream()
                .filter(i -> "plan".equals(i.itemType()))
                .findFirst()
                .orElse(sub.subscriptionItems().isEmpty() ? null : sub.subscriptionItems().get(0));
    }

    private static long computeDiscountFromRamps(List<Ramp> ramps, long subtotalCents) {
        if (ramps == null) return 0;
        long total = 0;
        for (Ramp ramp : ramps) {
            if (ramp.discountsToAdd() != null) {
                for (Ramp.DiscountToAdd d : ramp.discountsToAdd()) {
                    if (d.amount() != null) total += d.amount();
                    else if (d.percentage() != null) total += (long) (subtotalCents * d.percentage() / 100.0);
                }
            }
        }
        return total;
    }

    private static MonthlyBreakdown buildMonthlyBreakdown(
            SimulatedSubscription state,
            List<Ramp> rampsApplied,
            SimulatedSubscription stateBeforeRamps,
            long eventDate,
            ZoneId zone,
            String currencyCode,
            int taxRate,
            MonthlyBreakdown lastBreakdown,
            Long periodStartSeconds,
            Long periodEndSeconds) {
        SimulatedSubscription.SubscriptionItem plan = getPlanItem(state);
        long unitPrice = plan != null ? plan.unitPrice() : 0;
        System.out.println(unitPrice);
        int quantity = plan != null ? plan.quantity() : 1;

        long subtotalCents = computeRecurringAmount(state);
        long discountCents = computeDiscountFromRamps(rampsApplied, subtotalCents);
        long taxableCents = subtotalCents - discountCents;
        long taxCents = taxRate > 0 ? (taxableCents * taxRate) / 100 : 0;
        long totalCents = taxableCents + taxCents;

        String monthKey = formatDate(eventDate, zone).substring(0, 7);
        String monthLabel = Instant.ofEpochSecond(eventDate).atZone(zone)
                .format(DateTimeFormatter.ofPattern("MMMM yyyy"));

        boolean isPeriod = periodStartSeconds != null && periodEndSeconds != null;
        if (isPeriod) {
            int periodDays = daysInRange(periodStartSeconds, periodEndSeconds);
            int monthDays = daysInMonth(periodStartSeconds, zone);
            double ratio = (double) periodDays / (double) monthDays;
            subtotalCents = Math.round(subtotalCents * ratio);
            discountCents = Math.round(discountCents * ratio);
            taxableCents = subtotalCents - discountCents;
            taxCents = taxRate > 0 ? (taxableCents * taxRate) / 100 : 0;
            totalCents = taxableCents + taxCents;
        }

        List<String> changes = new ArrayList<>();
        if (isPeriod) {
            changes.add("Proration");
        } else if (rampsApplied != null && !rampsApplied.isEmpty()) {
            if (stateBeforeRamps != null) {
                SimulatedSubscription.SubscriptionItem prevPlan = getPlanItem(stateBeforeRamps);
                if (prevPlan != null && plan != null) {
                    if (prevPlan.unitPrice() != plan.unitPrice()) {
                        changes.add(String.format("Price changed from %s → %s",
                                formatAmountShort(prevPlan.unitPrice(), currencyCode),
                                formatAmountShort(plan.unitPrice(), currencyCode)));
                    }
                    if (prevPlan.quantity() != plan.quantity()) {
                        changes.add(String.format("Quantity changed from %d → %d", prevPlan.quantity(), plan.quantity()));
                    }
                }
            }
            boolean hasDiscount = rampsApplied.stream().anyMatch(r -> r.discountsToAdd() != null && !r.discountsToAdd().isEmpty());
            if (hasDiscount) changes.add("One-time manual discount applied");
            if (rampsApplied.stream().anyMatch(r -> r.itemsToAdd() != null && !r.itemsToAdd().isEmpty())) {
                changes.add("Item(s) added");
            }
            if (rampsApplied.stream().anyMatch(r -> r.itemsToRemove() != null && !r.itemsToRemove().isEmpty())) {
                changes.add("Item(s) removed");
            }
        }
        if (changes.isEmpty()) {
            changes.add(lastBreakdown == null ? "Initial billing" : "No changes");
        }

        Long impactVsPrevious = lastBreakdown != null ? totalCents - lastBreakdown.totalCents() : null;

        String periodLabel = isPeriod ? formatPeriodLabel(periodStartSeconds, periodEndSeconds, zone) : null;

        return new MonthlyBreakdown(
                monthKey,
                monthLabel,
                periodLabel,
                unitPrice,
                quantity,
                subtotalCents,
                discountCents,
                taxRate > 0 ? taxRate : null,
                taxCents,
                totalCents,
                currencyCode,
                changes,
                impactVsPrevious
        );
    }

    private static String formatAmountShort(long cents, String currencyCode) {
        String cur = (currencyCode != null ? currencyCode : "USD").toUpperCase();
        boolean zeroDec = List.of("JPY", "KRW", "VND", "CLP", "XOF", "XAF").contains(cur);
        double val = zeroDec ? cents : cents / 100.0;
        return String.format("$%,.0f", val);
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
