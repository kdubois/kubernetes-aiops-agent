# Kubernetes Agent Tests

## Overview

This directory contains comprehensive tests for the Kubernetes AI Agent, including unit tests, integration tests, and end-to-end tests.

## Test Files

### ComprehensiveE2ETest.java (End-to-End Integration Test) ⭐ NEW

**Complete end-to-end integration test** that simulates a real-world scenario:
1. Creates a failing pod in Kubernetes with database connection errors
2. Simulates Argo Rollouts plugin calling the agent
3. Verifies agent analyzes logs and identifies root cause
4. Tests GitHub PR creation with automated fixes
5. Validates conversation memory across multiple requests

**Requirements:**
- `OPENAI_API_KEY` environment variable (required)
- `GITHUB_TOKEN` environment variable (optional, for PR creation test)
- `TEST_GITHUB_REPO` environment variable (optional, for PR creation test)
- Access to a Kubernetes cluster (in-cluster or via kubeconfig)

**Quick Start:**
```bash
# Set required environment variable
export OPENAI_API_KEY="sk-..."

# Run the test using the convenient script
./run-e2e-test.sh

# Or run directly with Maven
mvn test -Dtest=ComprehensiveE2ETest
```

📖 **[Full E2E Test Documentation](java/dev/kevindubois/rollout/agent/README_E2E_TEST.md)**

### KubernetesAgentResourceIT.java (Integration Tests)

**Integration tests** that test the actual REST API endpoints with real AI analysis. These tests:
- Call the `/a2a/analyze` endpoint with realistic failure scenarios
- Verify the actual AI analysis responses
- Test various pod failure types (CrashLoopBackOff, OOMKilled, ImagePullBackOff)
- **Require `OPENAI_API_KEY` environment variable** to run
- Are automatically skipped if the API key is not available

### A2AResponseTest.java (Unit Tests)

**Fast unit tests** for the A2A request/response data structures. These tests run without external dependencies and validate the request/response objects.

### Other Integration Tests

Additional integration tests for specific components:
- `A2AAgentExecutorTest.java` - Tests the A2A agent executor
- `A2AMemoryIdIntegrationTest.java` - Tests conversation memory functionality
- `KubernetesAgentResponseTest.java` - Tests response parsing and formatting

#### Test Coverage

1. **Health Endpoint**
   - Verifies the health check endpoint returns correct status

2. **Pod Failure Scenarios**
   - **CrashLoopBackOff**: Tests request creation for pods stuck in crash loop
     - Database connection failures
     - Service configuration errors
   
   - **OOMKilled**: Tests memory-related pod failures
     - Out of Memory errors
     - Heap space exhaustion
   
   - **ImagePullBackOff**: Tests image pull failures
     - Repository not found
     - Authentication issues

3. **Response Decision Logic**
   - Promote/Don't Promote decision handling
   - Confidence level tracking
   - Error scenario responses

4. **Event Handling**
   - Single event processing
   - Multiple event aggregation
   - Event context preservation

## Running Tests

### Quick Start - Run E2E Test (Recommended)

The comprehensive E2E test validates the entire workflow:

```bash
# Set required environment variable
export OPENAI_API_KEY="sk-..."

# Optional: Enable GitHub PR creation test
export GITHUB_TOKEN="ghp_..."
export TEST_GITHUB_REPO="https://github.com/your-org/your-test-repo"

# Run using the convenient script
cd kubernetes-agent
./run-e2e-test.sh
```

### Run All Tests
```bash
mvn test
```

### Run Only Unit Tests (Fast, No API Key Required)
```bash
mvn test -Dtest=A2AResponseTest
```

### Run Integration Tests (Requires OPENAI_API_KEY)
```bash
# Set your API key
export OPENAI_API_KEY="sk-..."

# Run all integration tests
mvn test -Dtest=KubernetesAgentResourceIT

# Run comprehensive E2E test
mvn test -Dtest=ComprehensiveE2ETest

# Run a specific test method
mvn test -Dtest=ComprehensiveE2ETest#test3_verifyLogAnalysis
```

### Run with Coverage
```bash
mvn test jacoco:report
```

### What Gets Tested

| Test Class | Tests What | Requires API Key | Requires K8s | Speed |
|------------|-----------|------------------|--------------|-------|
| `A2AResponseTest` | Data structures & validation | ❌ No | ❌ No | ⚡ Fast |
| `KubernetesAgentResourceIT` | REST API with AI analysis | ✅ Yes | ❌ No | 🐌 Slow |
| `ComprehensiveE2ETest` | Full workflow with K8s & GitHub | ✅ Yes | ✅ Yes | 🐢 Very Slow |

## Test Scenarios Covered

| Scenario | Failure Reason | Expected Outcome |
|----------|----------------|------------------|
| Database Connection Error | CrashLoopBackOff | Don't promote, provide remediation |
| Out of Memory | OOMKilled | Analysis with memory recommendations |
| Image Not Found | ImagePullBackOff | Don't promote, fix image reference |
| Generic Error | Various | Safe fallback response |

## Test Data

Tests use realistic pod failure scenarios similar to what would be encountered in production:

- Kubernetes events (Warning, Error, etc.)
- Container logs with stack traces
- Resource constraints and limits
- Service connectivity issues

## Test Scenarios Covered by E2E Test

The comprehensive E2E test covers:

1. **Pod Creation & Failure Simulation**
   - Creates a pod with intentional database connection errors
   - Waits for pod to enter CrashLoopBackOff state
   - Verifies pod restart count increases

2. **Argo Rollouts Plugin Simulation**
   - Sends analysis request with full rollout context
   - Includes metrics, versions, and failure information
   - Measures agent response time

3. **Log Analysis Verification**
   - Verifies agent identifies database/connection issues
   - Checks confidence score is reasonable (>= 50%)
   - Confirms agent recommends NOT promoting failing canary

4. **GitHub PR Creation** (Optional)
   - Tests automated fix generation
   - Verifies PR is created with proper description
   - Validates PR link is returned

5. **Conversation Memory**
   - Tests multiple sequential requests
   - Verifies context is maintained across calls
   - Validates memory isolation per session

## Troubleshooting

### Common Issues

**"OPENAI_API_KEY not set"**
```bash
export OPENAI_API_KEY="sk-proj-..."
```

**"Cannot connect to Kubernetes cluster"**
```bash
# Check cluster connectivity
kubectl cluster-info

# Verify permissions
kubectl auth can-i create namespaces
```

**"Test namespace not cleaned up"**
```bash
# Manual cleanup
kubectl delete namespace k8s-agent-e2e-test
```

**"Agent takes too long to respond"**
- Expected: 30-60 seconds for AI analysis
- Check OpenAI API rate limits
- Verify network connectivity

### Getting Help

- 📖 [E2E Test Documentation](java/dev/kevindubois/rollout/agent/README_E2E_TEST.md)
- 📖 [Main Project README](../../README.md)
- 📖 [Development Guide](../../docs/development/TESTING.md)

## CI/CD Integration

The E2E test can be integrated into CI/CD pipelines:

```yaml
# GitHub Actions example
- name: Run E2E Test
  env:
    OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
  run: |
    cd kubernetes-agent
    ./run-e2e-test.sh
```

