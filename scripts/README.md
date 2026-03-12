# HiveSight Chargebee Test Setup

This script creates all resources needed to test HiveSight in your Chargebee test site.

## Prerequisites

- Chargebee Ramps feature enabled on your site
- `curl` and `jq` installed
- Chargebee API key with appropriate permissions

## Usage

```bash
# Edit the script to set your SITE and API_KEY, or export them:
export SITE=sngokulraj-test
export API_KEY=test_0bB85cuLUqzfC8pbm9YN0oDD0PCacdPsZj

./setup-chargebee-test.sh
```

## What It Creates

| Resource | ID | Purpose |
|----------|-----|---------|
| Item Family | `hivesight_family` | Product catalog container |
| Item (Plan) | `hivesight_plan` | Plan product |
| Item Price | `hivesight_plan-USD-yearly` | Yearly billing ($99/mo) |
| Item Price | `hivesight_plan-USD-monthly` | Monthly billing ($33/mo) |
| Customer | `hivesight_test-customer` | Test customer with card |
| Subscription | (auto-generated) | Active subscription on yearly plan |
| Ramp | (auto-generated) | Scheduled change: yearly → monthly (~60 days out) |

## Running HiveSight

```bash
# From project root, with your Chargebee credentials:
CHARGEBEE_SITE=sngokulraj-test CHARGEBEE_API_KEY=your_api_key ./mvnw spring-boot:run

# Then open http://localhost:8766 and use the Subscription ID from the script output
```

## Ghost of March Setup

For testing the "Validate Ghost of March" feature. **Requires** `setup-chargebee-test.sh` to be run first (creates `hivesight_test-customer` with card).

```bash
./setup-ghost-of-march.sh
```

This creates a **new subscription** (reusing the same customer) that:
- Starts on yearly plan
- Has a ramp to switch to monthly on Oct 1, 2027
- Is scheduled to cancel on Sept 10, 2027 (within the 18-month simulation window)

Use the returned Subscription ID and expected date 2027-09-10 in HiveSight to validate.

## Note on Quarterly Billing

Quarterly (3-month) billing requires enabling the 3-month frequency in Chargebee site settings. The script uses yearly + monthly plans instead, which work out of the box.
