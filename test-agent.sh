#!/bin/bash
# Test script for Kubernetes AI Agent
# Supports both Kubernetes and local modes
# Usage:
#   ./test-agent.sh k8s     # Test agent running in Kubernetes (default)
#   ./test-agent.sh local   # Test agent running locally on localhost:8080

set -e

# Parse mode argument
MODE="${1:-k8s}"
if [[ "$MODE" != "k8s" && "$MODE" != "local" ]]; then
	echo "❌ Invalid mode: $MODE"
	echo "Usage: $0 [k8s|local]"
	echo "  k8s   - Test agent running in Kubernetes (default)"
	echo "  local - Test agent running locally on localhost:8080"
	exit 1
fi

# Configuration
AGENT_NAMESPACE="argo-rollouts"
TEST_NAMESPACE="${NAMESPACE:-default}"
TEST_POD_NAME="${POD_NAME:-test-pod}"
CONTEXT="${CONTEXT:-kind-rollouts-plugin-metric-ai-test-e2e}"
LOCAL_URL="${LOCAL_URL:-http://localhost:8080}"

function _kubectl() {
	kubectl --context "$CONTEXT" -n "$AGENT_NAMESPACE" "$@"
}

function _curl() {
	if [[ "$MODE" == "local" ]]; then
		curl "$@"
	else
		_kubectl exec deployment/kubernetes-agent -- curl "$@"
	fi
}

function _get_base_url() {
	if [[ "$MODE" == "local" ]]; then
		echo "$LOCAL_URL"
	else
		echo "http://localhost:8080"
	fi
}

echo "🧪 Testing Kubernetes AI Agent"
echo "📍 Mode: $MODE"
if [[ "$MODE" == "local" ]]; then
	echo "🔗 URL: $LOCAL_URL"
else
	echo "☸️  Context: $CONTEXT"
	echo "📦 Namespace: $AGENT_NAMESPACE"
fi
echo ""

# Test 1: Health Check
echo "1️⃣  Testing health endpoint..."
BASE_URL=$(_get_base_url)
health_url="$BASE_URL/a2a/health"
if ! HEALTH_RESPONSE=$(_curl -sf "$health_url"); then
	echo "❌ Health check failed. Agent may not be ready."
	if [[ "$MODE" == "k8s" ]]; then
		echo "   Check agent logs: kubectl logs --context $CONTEXT -n $AGENT_NAMESPACE deployment/kubernetes-agent"
	else
		echo "   Check if agent is running on $health_url"
	fi
	exit 1
fi
echo "Response: $HEALTH_RESPONSE"
echo ""

# Test 2: Simple Analysis Request
echo "2️⃣  Testing analysis endpoint with sample pod failure..."
REQUEST_PAYLOAD=$(cat <<EOF
{
  "userId": "test-user",
  "prompt": "Analyze this pod failure and suggest a fix",
  "context": {
    "namespace": "$TEST_NAMESPACE",
    "podName": "$TEST_POD_NAME",
    "rolloutName": "test-rollout",
    "canaryVersion": "v2",
    "stableVersion": "v1",
    "failureReason": "CrashLoopBackOff",
    "logs": "Error: Cannot connect to database at localhost:5432\\nConnection refused\\nExiting...",
    "events": [
      {
        "type": "Warning",
        "reason": "BackOff",
        "message": "Back-off restarting failed container"
      }
    ],
    "repoUrl": "https://github.com/YOUR_USERNAME/YOUR_REPO"
  }
}
EOF
)

echo "Sending request..."
if [[ "$MODE" == "local" ]]; then
	if ! ANALYSIS_RESPONSE=$(curl -sSf -X POST \
		-H 'Content-Type: application/json' \
		-d "$REQUEST_PAYLOAD" \
		"$BASE_URL/a2a/analyze"); then
		echo "❌ Request failed. Agent may be unresponsive or rate limited."
		echo "   Check if agent is running on $LOCAL_URL"
		exit 1
	fi
else
	if ! ANALYSIS_RESPONSE=$(_kubectl exec deployment/kubernetes-agent -- sh -c "curl -sSf -X POST \
		-H 'Content-Type: application/json' \
		-d '$REQUEST_PAYLOAD' \
		http://localhost:8080/a2a/analyze"); then
		echo "❌ Request failed. Agent may be unresponsive or rate limited."
		echo "   Check agent logs: kubectl logs --context $CONTEXT -n $AGENT_NAMESPACE deployment/kubernetes-agent"
		exit 1
	fi
fi

echo "Response:"
echo "$ANALYSIS_RESPONSE" | jq . 2>/dev/null || echo "$ANALYSIS_RESPONSE"
echo ""

# Test 3: Check for errors in logs
echo "3️⃣  Checking agent logs for errors..."
if [[ "$MODE" == "k8s" ]]; then
	ERROR_COUNT=$(_kubectl logs deployment/kubernetes-agent | grep -c " ERROR " || true)
	if [ "$ERROR_COUNT" -eq "0" ]; then
		echo "✅ No ERROR lines found in agent logs"
	else
		echo "⚠️  Found $ERROR_COUNT ERROR lines in logs:"
		_kubectl logs deployment/kubernetes-agent | grep " ERROR " | tail -5
	fi
else
	echo "ℹ️  Log checking skipped in local mode"
	echo "   View logs in your local terminal where the agent is running"
fi
echo ""
echo "✅ Test completed!"
echo ""
