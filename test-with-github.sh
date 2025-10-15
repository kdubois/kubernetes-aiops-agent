#!/bin/bash
# Test Kubernetes AI Agent with GitHub PR creation
# This script simulates a real canary rollout failure and asks the agent to create a PR

set -e

# Configuration - CUSTOMIZE THESE
AGENT_URL="${AGENT_URL:-http://localhost:8080}"
GITHUB_REPO="${GITHUB_REPO:-carlossg/demo-app}"  # Change to your test repo
NAMESPACE="${NAMESPACE:-argo-rollouts}"
POD_NAME="${POD_NAME:-demo-app-canary-abc123}"

echo "🚀 Testing Kubernetes AI Agent with GitHub PR Creation"
echo "======================================================="
echo "Agent URL: $AGENT_URL"
echo "GitHub Repo: $GITHUB_REPO"
echo "Namespace: $NAMESPACE"
echo ""

# Step 1: Health Check
echo "1️⃣  Checking agent health..."
if ! curl -s -f "$AGENT_URL/a2a/health" > /dev/null; then
	echo "❌ Agent is not responding. Make sure it's running and accessible."
	echo "   If running in cluster: kubectl port-forward -n argo-rollouts svc/kubernetes-agent 8080:8080"
	exit 1
fi
echo "✅ Agent is healthy"
echo ""

# Step 2: Send analysis request with realistic failure scenario
echo "2️⃣  Sending analysis request for database connection failure..."
cat > /tmp/github-test-request.json <<EOF
{
  "userId": "rollouts-plugin",
  "prompt": "A canary deployment is failing with database connection errors. Please analyze the logs and pod information, identify the root cause, and create a GitHub PR with a fix.",
  "context": {
    "namespace": "$NAMESPACE",
    "podName": "$POD_NAME",
    "rolloutName": "demo-app",
    "canaryVersion": "v2.1.0",
    "stableVersion": "v2.0.0",
    "failureReason": "CrashLoopBackOff",
    "errorRate": 0.95,
    "repoUrl": "https://github.com/$GITHUB_REPO",
    "logs": "2024-01-15T10:30:45Z ERROR Failed to connect to database\\n2024-01-15T10:30:45Z ERROR Connection refused: localhost:5432\\n2024-01-15T10:30:45Z INFO Using database host from env: localhost\\n2024-01-15T10:30:45Z FATAL Application startup failed\\n2024-01-15T10:30:45Z ERROR Exit code: 1",
    "podStatus": {
      "phase": "Running",
      "conditions": [
        {
          "type": "Ready",
          "status": "False",
          "reason": "ContainersNotReady"
        }
      ],
      "containerStatuses": [
        {
          "name": "app",
          "ready": false,
          "restartCount": 5,
          "state": "waiting",
          "reason": "CrashLoopBackOff"
        }
      ]
    },
    "events": [
      {
        "type": "Warning",
        "reason": "BackOff",
        "message": "Back-off restarting failed container",
        "count": 5
      },
      {
        "type": "Warning",
        "reason": "Failed",
        "message": "Error: connection refused",
        "count": 5
      }
    ],
    "deployment": {
      "env": [
        {
          "name": "DATABASE_HOST",
          "value": "localhost"
        },
        {
          "name": "DATABASE_PORT",
          "value": "5432"
        }
      ]
    },
    "analysisMetrics": {
      "successRate": 0.05,
      "errorCount": 47,
      "totalRequests": 50
    }
  }
}
EOF

echo "Request payload:"
cat /tmp/github-test-request.json | jq . 2>/dev/null || cat /tmp/github-test-request.json
echo ""
echo "Sending request (this may take 30-60 seconds as the AI analyzes)..."
echo ""

RESPONSE=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -d @/tmp/github-test-request.json \
  "$AGENT_URL/a2a/analyze")

echo "📋 Analysis Response:"
echo "===================="
echo "$RESPONSE" | jq . 2>/dev/null || echo "$RESPONSE"
echo ""

# Check if PR was created
if echo "$RESPONSE" | grep -q "prUrl" 2>/dev/null; then
	PR_URL=$(echo "$RESPONSE" | jq -r '.prUrl // empty' 2>/dev/null)
	if [ -n "$PR_URL" ]; then
		echo "✅ SUCCESS! Pull Request created:"
		echo "   $PR_URL"
		echo ""
		echo "   Check your GitHub repository for the automated fix PR!"
	fi
else
	echo "ℹ️  Analysis completed. Check the response above for recommendations."
fi

echo ""
echo "✅ Test completed!"
echo ""
echo "💡 Tips:"
echo "   - Check the agent logs: kubectl logs -n argo-rollouts -l app=kubernetes-agent -f"
echo "   - View ADK web UI: http://localhost:8080 (after port-forward)"
echo "   - Make sure GITHUB_TOKEN is set in the argo-rollouts secret"

