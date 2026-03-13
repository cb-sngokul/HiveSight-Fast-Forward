# HiveSight Agent

AI agent that uses HiveSight as tools. The agent can list subscriptions, run simulations, validate cancellation dates, and answer billing questions by calling the HiveSight REST API.

## Prerequisites

1. **HiveSight must be running** on `http://localhost:8766` (or set `HIVESIGHT_BASE_URL`)
2. **Python 3.9+**
3. **Groq API key** (free at [console.groq.com](https://console.groq.com)) or OpenAI key

## Setup

```bash
cd Hivesight-Agent
python -m venv venv
source venv/bin/activate   # Windows: venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env
# Edit .env and add your GROQ_API_KEY (get free key at https://console.groq.com)
# Or copy from HiveSight's application.properties: ai.groq.api-key value
```

## Run

```bash
# Terminal 1: Start HiveSight (in Hivesight folder)
cd ../Hivesight
./mvnw spring-boot:run

# Terminal 2: Start the Agent
cd Hivesight-Agent
python app.py
```

Open http://localhost:8767

## Agent Tools

| Tool | Description |
|------|-------------|
| `list_subscriptions` | List Chargebee subscriptions (optional: with scheduled changes only) |
| `run_simulation` | Simulate subscription over start_month to end_month |
| `get_subscription_details` | Get subscription details, ramps, contract |
| `validate_ghost_of_march` | Validate cancellation date |

## Example Prompts

- "List subscriptions with scheduled changes"
- "Simulate subscription BTM47VVDd4vDLIM from 2026-01 to 2027-12"
- "Validate that subscription X cancels on 2027-09-10"
- "What's the total projected revenue for subscription Y over the next year?"

## Configuration

| Env Var | Default | Description |
|---------|---------|-------------|
| HIVESIGHT_BASE_URL | http://localhost:8766 | HiveSight API base URL |
| AI_PROVIDER | groq | groq or openai |
| GROQ_API_KEY | - | Required for Groq |
| GROQ_MODEL | llama-3.3-70b-versatile | Groq model (use 70b for tool calling) |
| PORT | 8767 | Agent web server port |

## Troubleshooting

| Error | Fix |
|------|-----|
| **401 Invalid API Key** | Create `.env` with valid `GROQ_API_KEY`. Get a free key at [console.groq.com](https://console.groq.com). Don't use Grok (xAI) key—Groq and Grok are different. |
| **Connection refused to localhost:8766** | Start HiveSight first: `cd Hivesight && ./mvnw spring-boot:run` |

## Architecture

```
User → Agent (Flask) → LLM (Groq/OpenAI) with tool definitions
                    → Tool calls → HiveSight REST API (localhost:8766)
                    → Response to user
```
