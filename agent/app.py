"""Flask app for HiveSight Agent chat interface."""
import os
from flask import Flask, render_template_string, request, jsonify
from agent import run_agent

app = Flask(__name__)

HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HiveSight Agent</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
    <script src="https://cdn.jsdelivr.net/npm/marked@4.3.0/marked.min.js"></script>
    <style>
        .agent-response table { font-size: 0.9em; margin: 0.5em 0; }
        .agent-response th, .agent-response td { padding: 0.35em 0.75em; border: 1px solid #dee2e6; }
        .agent-response th { background: #f8f9fa; font-weight: 600; }
        .agent-response ul { margin-bottom: 0.5em; padding-left: 1.25em; }
    </style>
</head>
<body class="bg-light">
    <div class="container py-4">
        <h1 class="mb-1">HiveSight Agent</h1>
        <p class="text-muted small mb-4">AI agent that can simulate subscriptions, validate cancellations, and answer billing questions. Ensure HiveSight is running on localhost:8766.</p>

        <div class="card shadow-sm">
            <div class="card-body">
                <div id="chatMessages" class="mb-3" style="min-height: 300px; max-height: 450px; overflow-y: auto;">
                    <div class="alert alert-info py-2 small mb-0">
                        Ask me to list subscriptions, run a simulation, validate a cancellation date, or compare revenue. Example: "Simulate subscription BTM47VVDd4vDLIM from 2026-01 to 2027-12"
                    </div>
                </div>
                <form id="chatForm" class="d-flex gap-2">
                    <input type="text" id="chatInput" class="form-control" placeholder="Ask about subscriptions..." autocomplete="off">
                    <button type="submit" class="btn btn-primary">Send</button>
                </form>
            </div>
        </div>
    </div>
    <script>
        const form = document.getElementById('chatForm');
        const input = document.getElementById('chatInput');
        const messages = document.getElementById('chatMessages');
        const submitBtn = form ? form.querySelector('button[type="submit"]') : null;

        function escapeHtml(s) {
            const div = document.createElement('div');
            div.textContent = (s || '').toString();
            return div.innerHTML;
        }

        function toHtml(text, useMarkdown) {
            const t = (text || '').toString();
            if (useMarkdown && typeof marked !== 'undefined' && marked.parse) {
                try { return marked.parse(t); } catch (_) { /* fallback */ }
            }
            return escapeHtml(t).replace(/\\n/g, '<br>');
        }

        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const msg = input.value.trim();
            if (!msg) return;
            input.value = '';
            if (submitBtn) submitBtn.disabled = true;

            // Add user message (plain text)
            const userDiv = document.createElement('div');
            userDiv.className = 'mb-3';
            userDiv.innerHTML = '<span class="badge bg-primary me-2">You</span><div class="agent-response mt-1">' + escapeHtml(msg).replace(/\\n/g, '<br>') + '</div>';
            messages.appendChild(userDiv);

            // Add agent placeholder - keep ref to content div for direct update
            const agentDiv = document.createElement('div');
            agentDiv.className = 'mb-3';
            agentDiv.innerHTML = '<span class="badge bg-secondary me-2">Agent</span><div class="agent-response mt-1">Thinking...</div>';
            messages.appendChild(agentDiv);
            const agentContent = agentDiv.querySelector('.agent-response');

            try {
                const r = await fetch('/api/chat', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ message: msg })
                });
                let j;
                try {
                    j = await r.json();
                } catch (_) {
                    throw new Error('Invalid response (' + r.status + ')');
                }
                const resp = (j.response !== undefined ? j.response : j.error) || 'No response';
                agentContent.innerHTML = toHtml(resp, true);
            } catch (err) {
                agentContent.innerHTML = '<span class="text-danger">Error: ' + escapeHtml(err.message || String(err)) + '</span>';
            }
            submitBtn.disabled = false;
            messages.scrollTop = messages.scrollHeight;
        });
    </script>
</body>
</html>
"""


@app.route("/")
def index():
    return render_template_string(HTML)


@app.route("/api/chat", methods=["POST"])
def chat():
    data = request.get_json() or {}
    message = data.get("message", "").strip()
    if not message:
        return jsonify({"error": "message required"}), 400
    try:
        response = run_agent(message)
        return jsonify({"response": response})
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({"error": str(e)}), 500


@app.route("/api/health")
def health():
    return jsonify({"status": "ok", "service": "hivesight-agent"})


@app.route("/api/debug")
def debug():
    """Debug config (safe - no key exposure)."""
    from config import GROQ_API_KEY, AI_PROVIDER, HIVESIGHT_BASE_URL
    return jsonify({
        "ai_provider": AI_PROVIDER,
        "groq_key_set": bool(GROQ_API_KEY),
        "groq_key_prefix": GROQ_API_KEY[:12] + "..." if GROQ_API_KEY else "not set",
        "hivesight_url": HIVESIGHT_BASE_URL,
    })


if __name__ == "__main__":
    port = int(os.getenv("PORT", 8767))
    app.run(host="0.0.0.0", port=port, debug=True)
