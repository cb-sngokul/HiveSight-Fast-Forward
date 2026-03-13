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
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Sora:wght@400;500;600;700&display=swap" rel="stylesheet">
    <script src="https://cdn.jsdelivr.net/npm/marked@4.3.0/marked.min.js"></script>
    <style>
        :root {
            --cb-mature-blue: #012A38;
            --cb-confident-orange: #FF3300;
            --cb-brilliant-yellow: #BFF90B;
            --cb-clarity-blue: #A2C1C4;
            --cb-clarity-white: #EFEFEF;
            --cb-light-silver: #D3D9DC;
            --cb-cadet-grey: #92A1A8;
            --cb-storm-cloud: #4F6169;
        }
        body { font-family: 'Inter', sans-serif; background: linear-gradient(180deg, #fafbfc 0%, var(--cb-clarity-white) 50%, #f0f4f5 100%); min-height: 100vh; color: var(--cb-storm-cloud); }
        .agent-nav { background: var(--cb-mature-blue); box-shadow: 0 2px 12px rgba(1,42,56,0.15); }
        .agent-nav .brand { font-family: 'Sora', sans-serif; font-size: 1.4rem; font-weight: 700; color: white; }
        .agent-nav .brand span { color: var(--cb-brilliant-yellow); }
        .agent-nav .tagline { color: var(--cb-clarity-blue); font-size: 0.85rem; }
        .agent-hero { background: linear-gradient(135deg, var(--cb-mature-blue) 0%, #023a4a 100%); border-radius: 1rem; padding: 1.5rem 2rem; margin-bottom: 1.5rem; color: white; }
        .agent-hero h1 { font-family: 'Sora', sans-serif; font-size: 1.5rem; margin-bottom: 0.5rem; color: white !important; }
        .agent-hero p { color: var(--cb-clarity-blue); font-size: 0.9rem; margin: 0; }
        .agent-card { border: 1px solid var(--cb-light-silver); border-radius: 0.75rem; box-shadow: 0 2px 8px rgba(79,97,105,0.06); overflow: hidden; }
        .agent-card .card-header { background: var(--cb-mature-blue); color: white; font-family: 'Sora', sans-serif; font-weight: 600; padding: 1rem 1.25rem; border: none; }
        .agent-card .card-body { padding: 1.25rem; }
        #chatMessages { min-height: 320px; max-height: 480px; overflow-y: auto; background: #fff; border-radius: 0.5rem; }
        .agent-response table { font-size: 0.9em; margin: 0.5em 0; border-collapse: collapse; }
        .agent-response th, .agent-response td { padding: 0.4em 0.75em; border: 1px solid var(--cb-light-silver); }
        .agent-response th { background: rgba(162,193,196,0.2); font-weight: 600; color: var(--cb-mature-blue); }
        .agent-response ul { margin-bottom: 0.5em; padding-left: 1.25em; }
        .agent-response strong { color: var(--cb-mature-blue); }
        .agent-response code { background: rgba(162,193,196,0.15); padding: 0.15em 0.4em; border-radius: 0.25rem; font-size: 0.9em; }
        .msg-user .badge { background: var(--cb-mature-blue) !important; }
        .msg-agent .badge { background: var(--cb-clarity-blue) !important; color: var(--cb-mature-blue) !important; }
        .msg-agent .agent-response { color: var(--cb-storm-cloud); }
        .agent-input:focus { border-color: var(--cb-clarity-blue); box-shadow: 0 0 0 3px rgba(162,193,196,0.25); }
        .btn-agent { background: var(--cb-confident-orange) !important; border-color: var(--cb-confident-orange) !important; color: white !important; font-weight: 600; }
        .btn-agent:hover { background: #e62e00 !important; border-color: #e62e00 !important; color: white !important; }
        .welcome-msg { background: rgba(162,193,196,0.15); border: 1px solid var(--cb-clarity-blue); border-radius: 0.5rem; color: var(--cb-mature-blue); }
        body.embed-mode .agent-nav, body.embed-mode .agent-hero, body.embed-mode footer { display: none !important; }
        body.embed-mode main { padding-top: 0.5rem !important; padding-bottom: 0.5rem !important; }
        body.embed-mode .agent-card { margin: 0 !important; }
    </style>
</head>
<body>
    <nav class="agent-nav navbar navbar-expand-lg">
        <div class="container">
            <a class="navbar-brand brand" href="/"><span>Hive</span>Sight Agent</a>
            <span class="tagline navbar-text">AI-powered billing assistant</span>
        </div>
    </nav>

    <main class="container py-4">
        <div class="agent-hero">
            <h1>Billing Assistant</h1>
            <p>Simulate subscriptions, validate cancellations, and answer billing questions. Uses HiveSight API. Ensure HiveSight is running on localhost:8766.</p>
        </div>

        <div class="agent-card card">
            <div class="card-header">Chat</div>
            <div class="card-body">
                <div id="chatMessages" class="mb-3">
                    <div class="welcome-msg alert py-3 small mb-0">
                        <strong>Try:</strong> "List subscriptions with scheduled changes" · "Simulate subscription X from 2026-01 to 2027-12" · "Validate that subscription X cancels on 2027-11-30"
                    </div>
                </div>
                <form id="chatForm" class="d-flex gap-2">
                    <input type="text" id="chatInput" class="form-control agent-input" placeholder="Ask about subscriptions..." autocomplete="off">
                    <button type="submit" class="btn btn-agent">Send</button>
                </form>
            </div>
        </div>

        <footer class="mt-4 py-3 text-center small" style="background: #012A38; color: #A2C1C4; border-radius: 0.5rem;">
            <a href="http://localhost:8766" target="_blank" rel="noopener" style="color: #A2C1C4;">← Back to HiveSight</a>
        </footer>
    </main>
    <script>
        (function() {
            const embed = new URLSearchParams(window.location.search).get('embed') === '1' || window.self !== window.top;
            if (embed) document.body.classList.add('embed-mode');
        })();
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
            userDiv.className = 'mb-3 msg-user';
            userDiv.innerHTML = '<span class="badge me-2">You</span><div class="agent-response mt-1">' + escapeHtml(msg).replace(/\\n/g, '<br>') + '</div>';
            messages.appendChild(userDiv);

            // Add agent placeholder - keep ref to content div for direct update
            const agentDiv = document.createElement('div');
            agentDiv.className = 'mb-3 msg-agent';
            agentDiv.innerHTML = '<span class="badge me-2">Agent</span><div class="agent-response mt-1">Thinking...</div>';
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
