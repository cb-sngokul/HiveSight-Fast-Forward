# HiveSight: Temporal Billing Validation Engine — Hackathon Implementation Plan

> "Because Hindsight is too expensive for Enterprise Data."

## Executive Summary

HiveSight simulates the next **18 months** of a Chargebee subscription lifecycle to catch "time-bomb" errors before they occur. It reveals truths the standard Chargebee UI misses—particularly around **scheduled changes (Ramps)** that haven't triggered yet.

---

## 1. Chargebee API Landscape (Research Summary)

### Key APIs for HiveSight

| API | Purpose | Endpoint |
|-----|---------|----------|
| **List Subscriptions** | Fetch subscriptions (filter: `has_scheduled_changes[is]=true`) | `GET /subscriptions` |
| **Retrieve Subscription** | Get full subscription details | `GET /subscriptions/{id}` |
| **Retrieve with Scheduled Changes** | Subscription with **next ramp applied** (billing_period, item_price_id, coupons) | `GET /subscriptions/{id}/retrieve_with_scheduled_changes` |
| **List Ramps** | All ramps for a subscription (sorted by effective_from) | `GET /ramps?subscription_id[in]=["{id}"]` |
| **Subscription Renewal Estimate** | Next invoice estimate (use `ignore_scheduled_changes=false` to include ramps) | `GET /subscriptions/{id}/renewal_estimate` |
| **Upcoming Invoices Estimate** | Customer-level upcoming invoices | `GET /customers/{id}/upcoming_invoices_estimate` |
| **List Item Prices** | Resolve item_price_id → billing_period, period_unit | `GET /item_prices` |

### Critical Limitation

**`retrieve_with_scheduled_changes`** returns subscription with the **upcoming ramp applied** but:
- `next_billing_at` and `status` are **NOT** changed—they reflect current values
- For multiple ramps, only the **next** ramp is applied
- We must **simulate** the timeline ourselves

---

## 2. Architecture: Temporal Simulation Engine

### Core Concept

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    HiveSight Simulation Engine                           │
├─────────────────────────────────────────────────────────────────────────┤
│  1. FETCH: Subscription + Ramps (sorted by effective_from)               │
│  2. BUILD: Timeline of events (ramps, renewals, cancellations)            │
│  3. SIMULATE: For each time step, apply ramps & compute next_billing_at  │
│  4. VALIDATE: Compare simulated vs. expected (e.g., cancel on Nov 30)    │
│  5. VISUALIZE: Timeline UI showing "Chargebee UI" vs "HiveSight Reality"  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Simulation Algorithm

```python
def simulate_subscription_lifecycle(subscription, ramps, months=18):
    now = current_time()
    end_date = now + 18_months
    timeline = []
    current_state = clone(subscription)
    
    # Sort ramps by effective_from
    ramps_sorted = sorted(ramps, key=lambda r: r.effective_from)
    
    while current_state.date < end_date:
        # 1. Check for ramp that applies at this moment
        for ramp in ramps_sorted:
            if ramp.effective_from <= current_state.date and ramp.status == "scheduled":
                apply_ramp(current_state, ramp)
                timeline.append(Event("ramp_applied", ramp.effective_from, ramp))
        
        # 2. Check for scheduled cancellation
        if current_state.cancelled_at and current_state.cancelled_at <= current_state.date:
            timeline.append(Event("cancelled", current_state.cancelled_at))
            break
        
        # 3. Compute next billing date
        next_billing = compute_next_billing(current_state)
        timeline.append(Event("renewal", next_billing, current_state))
        
        # 4. Advance to next_billing_date
        current_state.date = next_billing
        current_state.current_term_end = next_billing
        
    return timeline
```

### Billing Period Logic (from Chargebee docs)

- `billing_period` + `billing_period_unit` (day/week/month/year)
- Add period to `current_term_end` to get next renewal
- For `month`: use calendar month arithmetic (e.g., Jan 31 + 1 month → Feb 28/29)

---

## 3. Case Study: "Ghost of March 31" — Implementation

### Scenario

| Attribute | Value |
|-----------|-------|
| Current billing | Quarterly |
| Ramp (Oct 1) | Shift to Monthly |
| Cancel date | Nov 30, 2027 |
| **Chargebee UI** | Shows next renewal March 31, 2028 (wrong) |
| **HiveSight** | Correctly shows Nov 30 termination |

### Validation Logic

```python
def validate_ghost_of_march(scenario):
    expected_cancel = "2027-11-30"
    timeline = simulate_subscription_lifecycle(...)
    
    if timeline[-1].type == "cancelled" and timeline[-1].date == expected_cancel:
        return ValidationResult(status="PASS", message="Subscription terminates correctly on Nov 30")
    else:
        return ValidationResult(status="FAIL", message=f"Expected cancel on {expected_cancel}, got {timeline[-1]}")
```

---

## 4. Tech Stack (Hackathon)

| Layer | Technology | Rationale |
|-------|-------------|-----------|
| **Backend** | Java 17 + Spring Boot | REST API, Chargebee HTTP client |
| **Frontend** | HTML + CSS + Bootstrap 5 | Simple, no build step, CDN-based |
| **API** | Spring Web (RestTemplate) | Direct Chargebee REST API calls |

---

## 5. Project Structure

```
hivesight/
├── README.md
├── .env.example              # CHARGEBEE_SITE, CHARGEBEE_API_KEY
├── package.json
├── IMPLEMENTATION_PLAN.md     # This file
│
├── src/
│   ├── chargebee/            # Chargebee API wrapper
│   │   ├── client.ts
│   │   ├── subscriptions.ts
│   │   ├── ramps.ts
│   │   └── estimates.ts
│   │
│   ├── engine/               # Temporal simulation engine
│   │   ├── simulator.ts      # Core simulation loop
│   │   ├── billing-period.ts # next_billing_at logic
│   │   ├── ramp-applier.ts   # Apply ramp changes to subscription state
│   │   └── types.ts
│   │
│   ├── validation/           # Validation rules
│   │   ├── ghost-of-march.ts # Case study validator
│   │   └── validators.ts
│   │
│   ├── api/                  # REST API
│   │   ├── routes/
│   │   │   ├── subscriptions.ts
│   │   │   ├── simulate.ts
│   │   │   └── validate.ts
│   │   └── index.ts
│   │
│   └── ui/                   # Frontend (Next.js app)
│       ├── pages/
│       │   ├── index.tsx     # Subscription selector
│       │   ├── simulate/[id].tsx  # Timeline view
│       │   └── validate.tsx  # Validation results
│       └── components/
│           ├── TimelineChart.tsx
│           ├── ChargebeeVsHivsight.tsx  # Side-by-side comparison
│           └── ValidationBadge.tsx
│
└── tests/
    ├── simulator.test.ts
    └── ghost-of-march.test.ts
```

---

## 6. Implementation Phases (Hackathon Timeline)

### Phase 1: MVP (2–3 hours)

1. **Chargebee client setup**
   - Configure with site + API key
   - List subscriptions with `has_scheduled_changes` filter
   - List ramps for a subscription

2. **Simulation engine**
   - `computeNextBillingDate(subscription)` — handle billing_period_unit
   - `applyRamp(subscription, ramp)` — merge items_to_add, items_to_update, items_to_remove
   - `simulate(subscription, ramps, months)` — loop until 18 months or cancel

3. **API endpoint**
   - `GET /api/simulate/:subscriptionId` → returns timeline JSON

### Phase 2: Validation UI (2 hours)

4. **"Ghost of March 31" validator**
   - Input: subscription ID, expected cancel date
   - Output: PASS/FAIL + timeline

5. **Simple UI**
   - Dropdown: select subscription
   - Timeline chart: events over 18 months
   - Badge: "Chargebee UI says X" vs "HiveSight says Y"

### Phase 3: Polish (1–2 hours)

6. **Item price resolution**
   - Fetch item prices to get billing_period for new plans in ramps
   - Handle `items_to_update` with `item_price_id` → plan change

7. **Demo data**
   - Create test subscription with ramp in Chargebee sandbox
   - Document "Ghost of March 31" setup steps

---

## 7. Chargebee API Reference Quick Links

| Resource | URL |
|----------|-----|
| Subscriptions | https://apidocs.chargebee.com/docs/api/subscriptions |
| Ramps | https://apidocs.chargebee.com/docs/api/ramps |
| Retrieve with Scheduled Changes | https://apidocs.chargebee.com/docs/api/subscriptions/retrieve-with-scheduled-changes |
| Renewal Estimate | https://apidocs.chargebee.com/docs/api/estimates/subscription-renewal-estimate |
| List Ramps | https://apidocs.chargebee.com/docs/api/ramps/list-ramps |
| Item Prices | https://apidocs.chargebee.com/docs/api/item_prices |

---

## 8. Prerequisites for Chargebee

1. **Subscription Ramps** must be enabled: [Request Access](https://app.chargebee.com/login?forward=https://app.chargebee.com/request_access/subscription-ramps)
2. **Sandbox site** recommended for testing
3. **API key** (Settings > Configure Chargebee > API Keys)

---

## 9. Demo Script for Judges

1. **Problem**: "Chargebee UI shows next renewal March 31, 2028. But we have a ramp to Monthly on Oct 1 and need to cancel Nov 30. Will it work?"
2. **HiveSight**: Paste subscription ID → Run simulation
3. **Result**: Timeline shows Oct 1 ramp → Monthly renewals → Nov 30 cancel. ✓
4. **Comparison**: Side-by-side "Chargebee UI" vs "HiveSight Reality"

---

## 10. Risks & Mitigations

| Risk | Mitigation |
|------|-------------|
| Ramps not enabled on site | Use demo with mock data; document setup |
| Chargebee billing logic edge cases | Start with month/quarter/year; add complexity later |
| Item price resolution for plan changes | Cache item prices; fallback to subscription items |
| Timezone handling | Use UTC timestamps consistently |

---

*This plan is based on Chargebee API documentation as of March 2025. Verify with latest docs at [apidocs.chargebee.com](https://apidocs.chargebee.com).*
