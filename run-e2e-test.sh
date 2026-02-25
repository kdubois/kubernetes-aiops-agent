#!/bin/bash
# Comprehensive E2E Test Runner for Kubernetes Agent
# This script runs the full end-to-end integration test

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║   Kubernetes Agent - Comprehensive E2E Test Runner       ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check prerequisites
echo -e "${YELLOW}📋 Checking prerequisites...${NC}"

# Check if OPENAI_API_KEY is set
if [ -z "$OPENAI_API_KEY" ]; then
    echo -e "${RED}❌ ERROR: OPENAI_API_KEY environment variable is not set${NC}"
    echo -e "${YELLOW}   Please set it with: export OPENAI_API_KEY='sk-...'${NC}"
    exit 1
fi
echo -e "${GREEN}✅ OPENAI_API_KEY is set${NC}"

# Check if kubectl is available and cluster is accessible
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}❌ ERROR: kubectl is not installed${NC}"
    exit 1
fi
echo -e "${GREEN}✅ kubectl is installed${NC}"

if ! kubectl cluster-info &> /dev/null; then
    echo -e "${RED}❌ ERROR: Cannot connect to Kubernetes cluster${NC}"
    echo -e "${YELLOW}   Please ensure your kubeconfig is set up correctly${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Kubernetes cluster is accessible${NC}"

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}❌ ERROR: Maven is not installed${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Maven is installed${NC}"

# Check optional GitHub configuration
echo ""
echo -e "${YELLOW}📋 Checking optional GitHub configuration...${NC}"
if [ -z "$GITHUB_TOKEN" ]; then
    echo -e "${YELLOW}⚠️  GITHUB_TOKEN is not set - Test 4 (PR creation) will be skipped${NC}"
    echo -e "${YELLOW}   To enable: export GITHUB_TOKEN='ghp_...'${NC}"
else
    echo -e "${GREEN}✅ GITHUB_TOKEN is set${NC}"
    
    if [ -z "$TEST_GITHUB_REPO" ]; then
        echo -e "${YELLOW}⚠️  TEST_GITHUB_REPO is not set - using default test repo${NC}"
        echo -e "${YELLOW}   To specify: export TEST_GITHUB_REPO='https://github.com/org/repo'${NC}"
    else
        echo -e "${GREEN}✅ TEST_GITHUB_REPO is set: $TEST_GITHUB_REPO${NC}"
    fi
fi

echo ""
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}Starting E2E Test...${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo ""

# Run the test
cd "$(dirname "$0")"

echo -e "${YELLOW}🧪 Running ComprehensiveE2ETest...${NC}"
echo ""

# Run Maven test with proper output
if mvn test -Dtest=ComprehensiveE2ETest -Dquarkus.log.level=INFO; then
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║                  ✅ ALL TESTS PASSED! ✅                   ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${GREEN}Test Summary:${NC}"
    echo -e "${GREEN}  ✓ Test 1: Pod creation and failure simulation${NC}"
    echo -e "${GREEN}  ✓ Test 2: Argo Rollouts plugin simulation${NC}"
    echo -e "${GREEN}  ✓ Test 3: Log analysis verification${NC}"
    if [ -n "$GITHUB_TOKEN" ]; then
        echo -e "${GREEN}  ✓ Test 4: GitHub PR creation${NC}"
    else
        echo -e "${YELLOW}  ⊘ Test 4: GitHub PR creation (skipped - no GITHUB_TOKEN)${NC}"
    fi
    echo -e "${GREEN}  ✓ Test 5: Memory and multiple requests${NC}"
    echo ""
    echo -e "${BLUE}💡 Tips:${NC}"
    echo -e "${BLUE}  • View test logs: cat target/surefire-reports/dev.kevindubois.rollout.agent.ComprehensiveE2ETest.txt${NC}"
    echo -e "${BLUE}  • Check test namespace: kubectl get all -n k8s-agent-e2e-test${NC}"
    echo -e "${BLUE}  • View agent logs: kubectl logs -n argo-rollouts -l app=kubernetes-agent${NC}"
    echo ""
    exit 0
else
    echo ""
    echo -e "${RED}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║                    ❌ TESTS FAILED ❌                      ║${NC}"
    echo -e "${RED}╚════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${YELLOW}🔍 Troubleshooting:${NC}"
    echo ""
    echo -e "${YELLOW}1. Check test logs:${NC}"
    echo -e "   cat target/surefire-reports/dev.kevindubois.rollout.agent.ComprehensiveE2ETest.txt"
    echo ""
    echo -e "${YELLOW}2. Check test namespace:${NC}"
    echo -e "   kubectl get all -n k8s-agent-e2e-test"
    echo -e "   kubectl describe pod -n k8s-agent-e2e-test"
    echo ""
    echo -e "${YELLOW}3. Check agent logs:${NC}"
    echo -e "   kubectl logs -n argo-rollouts -l app=kubernetes-agent"
    echo ""
    echo -e "${YELLOW}4. Manual cleanup (if needed):${NC}"
    echo -e "   kubectl delete namespace k8s-agent-e2e-test"
    echo ""
    echo -e "${YELLOW}5. Common issues:${NC}"
    echo -e "   • OPENAI_API_KEY not set or invalid"
    echo -e "   • Kubernetes cluster not accessible"
    echo -e "   • Insufficient cluster resources"
    echo -e "   • Network connectivity issues"
    echo ""
    echo -e "${YELLOW}📖 For more help, see:${NC}"
    echo -e "   src/test/java/dev/kevindubois/rollout/agent/README_E2E_TEST.md"
    echo ""
    exit 1
fi

