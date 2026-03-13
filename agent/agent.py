"""AI Agent with tool-calling loop. Uses Groq or OpenAI."""
import json
import re
from typing import Optional, Tuple
from openai import OpenAI
from config import AI_PROVIDER, GROQ_API_KEY, OPENAI_API_KEY, GROQ_MODEL, OPENAI_MODEL
from tools import TOOL_DEFINITIONS, TOOL_MAP


SYSTEM_PROMPT = """You are a helpful billing assistant for HiveSight, a Chargebee subscription simulation tool.
You have access to tools that call the HiveSight API. Use them to answer user questions about subscriptions.

When the user asks to simulate, validate, list, or get details - use the appropriate tool.
When comparing subscriptions, run simulations for each and compare the results.
Always use tools to get real data - do not make up subscription IDs or results.
Be concise and accurate. Amounts are in cents in raw API responses; convert to dollars for display (e.g. 96000 cents = $960).

Format your responses in Markdown for a clean display:
- For monthly breakdowns or revenue timelines, use a Markdown table with columns like | Month | Amount |
- Use **bold** for key numbers (e.g. total revenue).
- Use bullet lists for short items. Keep tables compact and readable."""


def get_client():
    """Get OpenAI client configured for Groq or OpenAI."""
    if AI_PROVIDER == "groq":
        if not GROQ_API_KEY:
            raise ValueError("GROQ_API_KEY not set. Set it in .env or environment.")
        return OpenAI(api_key=GROQ_API_KEY, base_url="https://api.groq.com/openai/v1")
    else:
        if not OPENAI_API_KEY:
            raise ValueError("OPENAI_API_KEY not set. Set it in .env or environment.")
        return OpenAI(api_key=OPENAI_API_KEY)


def get_model():
    return GROQ_MODEL if AI_PROVIDER == "groq" else OPENAI_MODEL


def execute_tool(name: str, arguments: dict) -> str:
    """Execute a tool and return result as string."""
    fn = TOOL_MAP.get(name)
    if not fn:
        return f"Unknown tool: {name}"
    try:
        result = fn(**arguments)
        return json.dumps(result, indent=2, default=str)
    except Exception as e:
        return f"Error: {str(e)}"


def _parse_tool_call_from_text(text):
    """If model output tool call as text (e.g. <function/run_simulation>{...}), parse it."""
    text = (text or "").strip()
    m = re.match(r"<function/(\w+)>\s*(\{.*\})", text, re.DOTALL)
    if not m:
        return None
    name = m.group(1)
    try:
        args = json.loads(m.group(2))
    except json.JSONDecodeError:
        return None
    if name not in TOOL_MAP:
        return None
    return (name, args)


def run_agent(user_message: str, conversation_history: list = None) -> str:
    """
    Run agent loop: send message to LLM, execute tool calls, repeat until final response.
    """
    client = get_client()
    model = get_model()
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]

    if conversation_history:
        messages.extend(conversation_history)

    messages.append({"role": "user", "content": user_message})

    max_iterations = 10
    for _ in range(max_iterations):
        response = client.chat.completions.create(
            model=model,
            messages=messages,
            tools=TOOL_DEFINITIONS,
            tool_choice="auto",
        )

        choice = response.choices[0]
        if choice.finish_reason == "stop":
            content = choice.message.content or ""
            # Workaround: some models output tool call as text instead of using tool_calls.
            # If we detect that, execute the tool and ask for a summary.
            parsed = _parse_tool_call_from_text(content)
            if parsed:
                name, args = parsed
                result = execute_tool(name, args)
                messages.append({"role": "assistant", "content": content})
                messages.append({
                    "role": "user",
                    "content": f"Tool {name} returned:\n{result[:6000]}\n\nSummarize this cleanly for the user in Markdown. Use a table for monthly breakdowns. Convert cents to dollars."
                })
                # Call without tools so model returns plain summary, not another tool call
                resp2 = client.chat.completions.create(model=model, messages=messages)
                return (resp2.choices[0].message.content or "").strip() or result[:2000]
            return content or "I couldn't generate a response."

        if not choice.message.tool_calls:
            return choice.message.content or "Done."

        messages.append(choice.message)

        for tc in choice.message.tool_calls:
            name = tc.function.name
            try:
                args = json.loads(tc.function.arguments) if tc.function.arguments else {}
            except json.JSONDecodeError:
                args = {}
            result = execute_tool(name, args)
            messages.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": result[:8000],
            })

    return "I reached the maximum number of steps. Please try a simpler question."
