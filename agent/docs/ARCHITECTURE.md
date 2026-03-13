# HiveSight Agent — Layered Architecture

> For hackathon presentation

---

## High-Level Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         HIVESIGHT AGENT (Port 8767)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│  Layer 1: PRESENTATION     │  Chat UI (HTML, Bootstrap, JS)                  │
│  Layer 2: API              │  Flask REST (/api/chat, /api/health)             │
│  Layer 3: AGENT            │  LLM Loop + Tool Calling (Groq/OpenAI)           │
│  Layer 4: TOOLS            │  HiveSight API wrappers                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    HIVESIGHT CORE (Port 8766)                                │
│  Spring Boot │ Chargebee API │ Simulator Engine │ Billing Logic              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Layer Diagram (For Slides)

### Layer 1 — Presentation Layer
**Purpose:** User-facing chat interface

| Component | Tech | Responsibility |
|-----------|------|----------------|
| Chat UI | HTML, Bootstrap 5, Vanilla JS | Renders chat, sends messages, displays agent responses |
| `app.py` (routes `/`) | Flask | Serves the chat page |

**Flow:** User types → Form submit → `fetch('/api/chat')` → Display response

---

### Layer 2 — API Layer
**Purpose:** HTTP endpoints for the frontend

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/` | GET | Chat UI |
| `/api/chat` | POST | Main agent entry point |
| `/api/health` | GET | Health check |
| `/api/debug` | GET | Config status (no secrets) |

**Flow:** `POST /api/chat` → `run_agent(message)` → JSON response

---

### Layer 3 — Agent Layer
**Purpose:** AI orchestration — understand intent, decide tools, loop until answer

| Component | File | Responsibility |
|-----------|------|----------------|
| System prompt | `agent.py` | Billing assistant persona, tool usage rules |
| LLM client | `agent.py` | Groq or OpenAI (OpenAI-compatible API) |
| Tool-calling loop | `agent.py` | Send message → LLM returns tool calls → Execute → Repeat |
| Tool executor | `agent.py` | Maps tool name → function → JSON result |

**Flow:**
1. User: "List subscriptions with scheduled changes"
2. LLM decides: `list_subscriptions(has_scheduled_changes=True)`
3. Execute tool → Get JSON
4. LLM formats answer → Return to user

---

### Layer 4 — Tool Layer
**Purpose:** Bridge between agent and HiveSight REST API

| Tool | HiveSight Endpoint | Use Case |
|------|--------------------|----------|
| `list_subscriptions` | `GET /api/subscriptions` | List subs, filter by ramps |
| `run_simulation` | `GET /api/simulate/{id}` | Project revenue, billing timeline |
| `get_subscription_details` | `GET /api/subscription/{id}/details` | Plan, ramps, contract |
| `validate_ghost_of_march` | `GET /api/validate/ghost-of-march/{id}` | Verify cancellation date |

**Flow:** Tool called with args → HTTP GET to HiveSight → Parse JSON → Return to agent

---

### External Dependencies

| Service | Role |
|---------|------|
| **HiveSight API** (localhost:8766) | Subscription data, simulation, validation |
| **Groq API** | LLM (llama-3.3-70b-versatile) — tool calling |
| **OpenAI API** (optional) | Alternative LLM (gpt-4o-mini) |

---

## Data Flow (End-to-End)

```
User                    Agent                     HiveSight
  │                        │                           │
  │  "List subs with       │                           │
  │   scheduled changes"   │                           │
  │──────────────────────>│                           │
  │                        │  list_subscriptions(     │
  │                        │    has_scheduled_changes  │
  │                        │    =True)                 │
  │                        │─────────────────────────>│
  │                        │                           │
  │                        │  JSON (subscriptions)     │
  │                        │<─────────────────────────│
  │                        │                           │
  │                        │  LLM formats answer       │
  │  "Here are 3 subs..."  │                           │
  │<──────────────────────│                           │
```

---

## File Map

| File | Layer | Purpose |
|------|-------|---------|
| `app.py` | 1, 2 | Flask app, HTML, `/api/chat` |
| `agent.py` | 3 | LLM loop, tool execution |
| `tools.py` | 4 | Tool definitions + HiveSight HTTP calls |
| `config.py` | Config | API keys, URLs, model selection |

---

## One-Liner for Judges

> **HiveSight Agent** is a 4-layer AI assistant: a chat UI talks to a Flask API, which runs an LLM agent loop (Groq/OpenAI) that calls tools. Those tools hit the HiveSight REST API to list subscriptions, run simulations, and validate billing—so users can ask natural-language questions instead of clicking through UIs.
