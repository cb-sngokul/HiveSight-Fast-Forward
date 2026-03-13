"""Configuration for HiveSight Agent."""
import os
import re
from dotenv import load_dotenv

load_dotenv()

# HiveSight API (must be running)
HIVESIGHT_BASE_URL = os.getenv("HIVESIGHT_BASE_URL", "http://localhost:8766")

# LLM - Groq or OpenAI (Groq is free)
# Use same key as HiveSight: GROQ_API_KEY, AI_GROQ_API_KEY, or ai.groq.api-key from application.properties
AI_PROVIDER = os.getenv("AI_PROVIDER", "groq")


def _read_hivesight_groq_key():
    """Read ai.groq.api-key from HiveSight's application.properties (sibling folder)."""
    base = os.path.dirname(os.path.abspath(__file__))
    props_path = os.path.join(base, "..", "src", "main", "resources", "application.properties")
    try:
        with open(props_path) as f:
            for line in f:
                line = line.strip()
                if line.startswith("ai.groq.api-key="):
                    val = line.split("=", 1)[1].strip()
                    # Parse ${AI_GROQ_API_KEY:default} format
                    m = re.match(r'\$\{[^:]+:(.+?)\}', val)
                    if m:
                        return m.group(1).strip()
                    return val
    except Exception:
        pass
    return ""


def _get_groq_key():
    """Get Groq API key, preferring explicit env, then HiveSight config, then default."""
    key = (
        (os.getenv("GROQ_API_KEY") or "").strip()
        or (os.getenv("AI_GROQ_API_KEY") or "").strip()
        or _read_hivesight_groq_key()
    )
    # Reject placeholder/invalid keys (e.g. from unedited .env.example)
    if not key or "your_" in key.lower() or "placeholder" in key.lower() or len(key) < 40:
        key = "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"
    return key


GROQ_API_KEY = _get_groq_key()
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
# llama-3.1-70b-versatile was decommissioned Jan 2025; use llama-3.3-70b-versatile or llama-3.1-8b-instant
GROQ_MODEL = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
