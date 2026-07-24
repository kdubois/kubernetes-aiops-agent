#!/bin/bash
# Quick local test of the agent with current .env settings

set -e

echo "🧪 Local Agent Function Calling Test"
echo "====================================="
echo ""

# Unset any existing env vars to avoid conflicts
unset ANALYSIS_API_KEY ANALYSIS_MODEL ANALYSIS_BASE_URL GITHUB_TOKEN

# Source .env file
if [ ! -f .env ]; then
    echo "❌ .env file not found"
    exit 1
fi

set -a
source .env
set +a

echo "📋 Configuration:"
echo "   Model: $ANALYSIS_MODEL"
echo "   Base URL: $ANALYSIS_BASE_URL"
echo "   API Key: ${ANALYSIS_API_KEY:0:10}..."
echo ""

# Kill any existing agent on port 8080
lsof -ti:8080 | xargs kill -9 2>/dev/null || true

echo "🚀 Starting agent in background..."
mvn quarkus:dev > /tmp/agent-test.log 2>&1 &
AGENT_PID=$!

echo "   PID: $AGENT_PID"
echo "   Waiting for agent to start..."

# Wait for agent (max 60s)
MAX_WAIT=60
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    if curl -sf http://localhost:8080/q/health > /dev/null 2>&1; then
        echo "   ✅ Agent ready!"
        break
    fi
    sleep 2
    WAITED=$((WAITED + 2))
    echo -n "."
done

if [ $WAITED -ge $MAX_WAIT ]; then
    echo ""
    echo "❌ Agent failed to start"
    kill $AGENT_PID 2>/dev/null || true
    exit 1
fi

echo ""
echo ""
echo "📤 Test 1: Simple request that SHOULD trigger tool calls..."
echo "   Request: Get the current weather in Boston"
echo ""

# Test with a simple weather request that should definitely trigger a tool call
curl -sf -X POST http://localhost:8080/a2a/analyze \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "test-weather",
    "prompt": "What is the current weather in Boston? Use the available tools to check.",
    "context": {
      "namespace": "default",
      "repoUrl": "https://github.com/kdubois/argo-rollouts-quarkus-demo",
      "baseBranch": "main"
    }
  }' > /tmp/test1-response.json

echo "Response saved to /tmp/test1-response.json"
echo ""

echo "📤 Test 2: Kubernetes-specific request..."
echo "   Request: Debug pod issues in default namespace"
echo ""

# Test with Kubernetes request
curl -sf -X POST http://localhost:8080/a2a/analyze \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "test-k8s",
    "prompt": "Check the status of pods in namespace default. Use debugPod or inspectResources tools.",
    "context": {
      "namespace": "default",
      "repoUrl": "https://github.com/kdubois/argo-rollouts-quarkus-demo",
      "baseBranch": "main"
    }
  }' > /tmp/test2-response.json

echo "Response saved to /tmp/test2-response.json"
echo ""
echo ""

echo "📋 Analyzing responses for tool_calls..."
echo ""
echo "Test 1 - tool_calls field:"
jq -r '.choices[0].message.tool_calls // "null"' /tmp/test1-response.json 2>/dev/null || echo "No tool_calls found"
echo ""
echo "Test 2 - tool_calls field:"
jq -r '.choices[0].message.tool_calls // "null"' /tmp/test2-response.json 2>/dev/null || echo "No tool_calls found"
echo ""

echo "📋 Checking agent logs for tool execution..."
grep -i "tool_calls\|finish_reason" /tmp/agent-test.log | tail -20 || echo "No tool mentions found"

echo ""
echo "🧹 Cleaning up..."
kill $AGENT_PID 2>/dev/null || true

echo "✅ Test complete!"
echo ""
echo "Full logs: /tmp/agent-test.log"
