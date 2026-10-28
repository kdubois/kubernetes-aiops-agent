#!/bin/bash
# Live cluster smoke test for the kubernetes-agent.
#
# Sends a real analysis request to the running agent and validates the response
# structure. Does NOT mock anything — uses the actual LLM and Kubernetes tools.
#
# Usage:
#   ./smoke-test.sh                        # exec into the pod (default)
#   ./smoke-test.sh local                  # hit localhost:8080 directly
#   CONTEXT=my-context ./smoke-test.sh     # use a specific kubeconfig context
#
# Prerequisites (k8s mode): kubectl access to the openshift-gitops namespace.
# Prerequisites (local mode): agent running on localhost:8080.

set -euo pipefail

MODE="${1:-k8s}"
AGENT_NAMESPACE="openshift-gitops"
TEST_NAMESPACE="${NAMESPACE:-quarkus-demo}"
CONTEXT="${CONTEXT:-}"
LOCAL_URL="${LOCAL_URL:-http://localhost:8080}"

if [[ "$MODE" != "k8s" && "$MODE" != "local" ]]; then
    echo "Usage: $0 [k8s|local]"
    exit 1
fi

# ── Helpers ────────────────────────────────────────────────────────────────────

_kubectl() {
    if [[ -n "$CONTEXT" ]]; then
        kubectl --context "$CONTEXT" -n "$AGENT_NAMESPACE" "$@"
    else
        kubectl -n "$AGENT_NAMESPACE" "$@"
    fi
}

_base_url() {
    if [[ "$MODE" == "local" ]]; then
        echo "$LOCAL_URL"
    else
        echo "http://localhost:8080"
    fi
}

_curl() {
    if [[ "$MODE" == "local" ]]; then
        curl -s "$@"
    else
        _kubectl exec deployment/kubernetes-agent -- curl -s "$@"
    fi
}

pass() { echo "  ✅ $*"; }
fail() { echo "  ❌ $*"; FAILED=1; }
FAILED=0

# ── Mode banner ────────────────────────────────────────────────────────────────

echo "🔬 kubernetes-agent smoke test"
echo "   mode: $MODE"
if [[ "$MODE" == "k8s" ]]; then
    echo "   namespace: $AGENT_NAMESPACE"
    [[ -n "$CONTEXT" ]] && echo "   context: $CONTEXT"
else
    echo "   url: $LOCAL_URL"
fi
echo ""

# ── 1. Health check ────────────────────────────────────────────────────────────

echo "1. Health check"
BASE_URL=$(_base_url)
if _curl -f "$BASE_URL/q/health" > /dev/null; then
    pass "agent is healthy"
else
    echo "  ❌ agent not reachable — aborting"
    exit 1
fi
echo ""

# ── 2. Analysis request ────────────────────────────────────────────────────────

echo "2. Analysis request (real LLM + real K8s tools)"

PAYLOAD=$(cat <<EOF
{
  "userId": "smoke-test",
  "prompt": "Analyze the current state of the canary deployment and decide whether to promote or roll back.",
  "context": {
    "namespace": "$TEST_NAMESPACE"
  }
}
EOF
)

RESPONSE=$(_curl -X POST \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD" \
    "$BASE_URL/a2a/analyze")

if [[ -z "$RESPONSE" ]]; then
    fail "empty response"
    exit 1
fi

# Validate response fields with jq
check_field() {
    local field="$1"
    local value
    value=$(echo "$RESPONSE" | jq -r "$field // empty" 2>/dev/null)
    if [[ -n "$value" ]]; then
        pass "$field = $value"
    else
        fail "$field missing or null"
    fi
}

check_field ".promote"
check_field ".confidence"
check_field ".analysis"
check_field ".rootCause"
check_field ".remediation"

CONFIDENCE=$(echo "$RESPONSE" | jq -r ".confidence // 0" 2>/dev/null)
if [[ "$CONFIDENCE" -ge 0 && "$CONFIDENCE" -le 100 ]]; then
    pass "confidence in valid range [0,100]"
else
    fail "confidence out of range: $CONFIDENCE"
fi
echo ""

# ── 3. Error log check ─────────────────────────────────────────────────────────

echo "3. Error log check"
if [[ "$MODE" == "k8s" ]]; then
    ERROR_COUNT=$(_kubectl logs deployment/kubernetes-agent --tail=100 | grep -c " ERROR " || true)
    if [[ "$ERROR_COUNT" -eq 0 ]]; then
        pass "no ERROR lines in last 100 log lines"
    else
        fail "$ERROR_COUNT ERROR line(s) in recent logs"
        _kubectl logs deployment/kubernetes-agent --tail=100 | grep " ERROR " | tail -5
    fi
else
    echo "  ⏭  skipped in local mode (check your terminal)"
fi
echo ""

# ── Result ─────────────────────────────────────────────────────────────────────

if [[ "$FAILED" -eq 0 ]]; then
    echo "✅ All checks passed"
else
    echo "❌ One or more checks failed"
    exit 1
fi
