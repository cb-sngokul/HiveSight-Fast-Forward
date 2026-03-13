#!/usr/bin/env python3
"""Quick test: verify Groq API key works."""
import sys
from config import GROQ_API_KEY, AI_PROVIDER
from openai import OpenAI

def main():
    print(f"AI_PROVIDER: {AI_PROVIDER}")
    print(f"GROQ_API_KEY length: {len(GROQ_API_KEY) if GROQ_API_KEY else 0}")
    print(f"GROQ_API_KEY prefix: {GROQ_API_KEY[:15]}..." if GROQ_API_KEY else "NOT SET")
    if not GROQ_API_KEY:
        print("ERROR: No API key")
        sys.exit(1)
    try:
        client = OpenAI(api_key=GROQ_API_KEY, base_url="https://api.groq.com/openai/v1")
        r = client.chat.completions.create(
            model="llama-3.1-8b-instant",
            messages=[{"role": "user", "content": "Say 'ok' only."}],
        )
        print("SUCCESS:", r.choices[0].message.content)
    except Exception as e:
        print("ERROR:", e)
        sys.exit(1)

if __name__ == "__main__":
    main()
