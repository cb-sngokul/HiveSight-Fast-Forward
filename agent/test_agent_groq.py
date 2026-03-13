#!/usr/bin/env python3
"""Test agent with Groq - same call as real agent."""
import json
from config import GROQ_API_KEY
from openai import OpenAI
from tools import TOOL_DEFINITIONS

# Same as agent.py
client = OpenAI(api_key=GROQ_API_KEY, base_url="https://api.groq.com/openai/v1")
model = "llama-3.3-70b-versatile"  # agent default

print("Testing Groq with tools (model:", model, ")")
print("Key prefix:", GROQ_API_KEY[:15] + "...")
try:
    r = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": "List subscriptions with scheduled changes"}],
        tools=TOOL_DEFINITIONS,
        tool_choice="auto",
    )
    print("SUCCESS")
    c = r.choices[0]
    print("Finish reason:", c.finish_reason)
    if c.message.tool_calls:
        print("Tool calls:", [tc.function.name for tc in c.message.tool_calls])
    else:
        print("Content:", c.message.content[:200] if c.message.content else "None")
except Exception as e:
    print("ERROR:", type(e).__name__, e)
