# Kubernetes Agent Tests

## Overview

This directory contains unit tests for the Kubernetes AI Agent, focusing on the A2A (Agent-to-Agent) communication layer.

## Test Files

### A2AResponseTest.java (Unit Tests)

**Fast unit tests** for the A2A request/response data structures. These tests run without external dependencies and validate the request/response objects.

### A2AControllerIntegrationTest.java (Integration Tests)

**Integration tests** that actually call the controller's `analyze()` method with real pod failure scenarios. These tests:
- Create a real `InMemoryRunner` with a test agent
- Call `controller.analyze()` with realistic failure scenarios
- Verify the actual AI analysis responses
- **Require `GOOGLE_API_KEY` environment variable** to run
- Are automatically skipped if the API key is not available

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

### Run All Tests
```bash
mvn test
```

### Run Only Unit Tests (Fast, No API Key Required)
```bash
mvn test -Dtest=A2AResponseTest
```

### Run Integration Tests (Requires GOOGLE_API_KEY)
```bash
# Set your API key
export GOOGLE_API_KEY="your-key-here"

# Run integration tests
mvn test -Dtest=A2AControllerIntegrationTest

# Run a specific integration test
mvn test -Dtest=A2AControllerIntegrationTest#testAnalyzePodFailure_CrashLoopBackOff
```

### Run Specific Test Method
```bash
mvn test -Dtest=A2AResponseTest#testCreatePodFailureRequest_CrashLoopBackOff
```

### Run with Coverage
```bash
mvn test jacoco:report
```

### What Gets Tested

| Test Class | Tests What | Requires API Key | Speed |
|------------|-----------|------------------|-------|
| `A2AResponseTest` | Data structures & validation | ❌ No | ⚡ Fast |
| `A2AControllerIntegrationTest` | Actual controller logic with AI | ✅ Yes | 🐌 Slow |

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

## Future Improvements

- Integration tests with real InMemoryRunner
- End-to-end tests with actual Gemini API calls
- Performance tests for response parsing
- Mock Kubernetes API responses

