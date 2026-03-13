"""Tools the agent can call - they invoke HiveSight REST API."""
import requests
from config import HIVESIGHT_BASE_URL


def _get(url: str, params: dict = None) -> dict:
    """GET request to HiveSight API."""
    r = requests.get(f"{HIVESIGHT_BASE_URL}{url}", params=params or {}, timeout=30)
    r.raise_for_status()
    return r.json()


def list_subscriptions(has_scheduled_changes: bool = False) -> dict:
    """List Chargebee subscriptions. Set has_scheduled_changes=True to filter for subs with ramps."""
    return _get("/api/subscriptions", {"has_scheduled_changes": str(has_scheduled_changes).lower()})


def run_simulation(
    subscription_id: str,
    start_month: str = None,
    end_month: str = None,
    timezone: str = "Asia/Kolkata",
) -> dict:
    """Run subscription simulation over a period. start_month and end_month in YYYY-MM format."""
    params = {"timezone": timezone}
    if start_month:
        params["start_month"] = start_month
    if end_month:
        params["end_month"] = end_month
    return _get(f"/api/simulate/{subscription_id}", params)


def get_subscription_details(subscription_id: str) -> dict:
    """Get subscription details including ramps, contract, billing."""
    return _get(f"/api/subscription/{subscription_id}/details")


def validate_ghost_of_march(
    subscription_id: str,
    expected_cancel: str,
    start_month: str = None,
    end_month: str = None,
    timezone: str = "Asia/Kolkata",
) -> dict:
    """Validate that a subscription cancels on the expected date. expected_cancel in YYYY-MM-DD."""
    params = {"expected_cancel": expected_cancel, "timezone": timezone}
    if start_month:
        params["start_month"] = start_month
    if end_month:
        params["end_month"] = end_month
    return _get(f"/api/validate/ghost-of-march/{subscription_id}", params)


# Tool definitions for LLM function calling (OpenAI/Groq format)
TOOL_DEFINITIONS = [
    {
        "type": "function",
        "function": {
            "name": "list_subscriptions",
            "description": "List Chargebee subscriptions. Use when user asks to see subscriptions, list subs, or find subscriptions with scheduled changes.",
            "parameters": {
                "type": "object",
                "properties": {
                    "has_scheduled_changes": {
                        "type": "boolean",
                        "description": "If true, only return subscriptions that have scheduled changes (ramps)",
                        "default": False,
                    }
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_simulation",
            "description": "Run a subscription simulation over a time period. Returns events, monthly breakdown, total revenue. Use when user wants to simulate, project revenue, or see billing timeline.",
            "parameters": {
                "type": "object",
                "properties": {
                    "subscription_id": {"type": "string", "description": "Chargebee subscription ID"},
                    "start_month": {"type": "string", "description": "Start month YYYY-MM (e.g. 2026-01)"},
                    "end_month": {"type": "string", "description": "End month YYYY-MM (e.g. 2027-12)"},
                    "timezone": {"type": "string", "description": "Timezone (default Asia/Kolkata)"},
                },
                "required": ["subscription_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_subscription_details",
            "description": "Get subscription details: plan, ramps, contract, billing period. Use when user asks about a specific subscription's setup.",
            "parameters": {
                "type": "object",
                "properties": {
                    "subscription_id": {"type": "string", "description": "Chargebee subscription ID"},
                },
                "required": ["subscription_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "validate_ghost_of_march",
            "description": "Validate that a subscription cancels on the expected date. Use when user wants to verify cancellation date or Ghost of March.",
            "parameters": {
                "type": "object",
                "properties": {
                    "subscription_id": {"type": "string", "description": "Chargebee subscription ID"},
                    "expected_cancel": {"type": "string", "description": "Expected cancellation date YYYY-MM-DD"},
                    "start_month": {"type": "string", "description": "Simulation start YYYY-MM"},
                    "end_month": {"type": "string", "description": "Simulation end YYYY-MM"},
                },
                "required": ["subscription_id", "expected_cancel"],
            },
        },
    },
]

TOOL_MAP = {
    "list_subscriptions": list_subscriptions,
    "run_simulation": run_simulation,
    "get_subscription_details": get_subscription_details,
    "validate_ghost_of_march": validate_ghost_of_march,
}
