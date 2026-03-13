#!/bin/bash
# Start HiveSight and AI Agent together.
# HiveSight must start first; agent calls its API.
# Press Ctrl+C to stop both.

set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

HIVESIGHT_PID=""
AGENT_PID=""

cleanup() {
  echo ""
  echo "Stopping..."
  [ -n "$AGENT_PID" ] && kill $AGENT_PID 2>/dev/null || true
  [ -n "$HIVESIGHT_PID" ] && kill $HIVESIGHT_PID 2>/dev/null || true
  exit 0
}
trap cleanup SIGINT SIGTERM

echo "Starting HiveSight..."
./mvnw spring-boot:run &
HIVESIGHT_PID=$!

echo "Waiting for HiveSight to be ready..."
for i in $(seq 1 30); do
  sleep 2
  if curl -s http://localhost:8766/api/health >/dev/null 2>&1; then
    echo "HiveSight ready at http://localhost:8766"
    break
  fi
  if [ $i -eq 30 ]; then
    echo "HiveSight failed to start within 60s. Check logs."
    kill $HIVESIGHT_PID 2>/dev/null || true
    exit 1
  fi
done

echo "Starting AI Agent..."
cd "$ROOT/agent"
if [ -d venv ]; then
  source venv/bin/activate
else
  echo "Creating agent venv and installing deps (first-time setup)..."
  python3 -m venv venv
  source venv/bin/activate
  pip install -q -r requirements.txt
fi
python app.py &
AGENT_PID=$!

echo ""
echo "Both running:"
echo "  HiveSight: http://localhost:8766"
echo "  AI Agent:  http://localhost:8767"
echo ""
echo "Press Ctrl+C to stop both."
wait
