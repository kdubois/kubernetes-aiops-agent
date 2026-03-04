# Kubernetes Agent Tests

## Overview

This directory contains **unit tests only** for the Kubernetes AI Agent. 

## Test Philosophy

- **Unit tests only**: Fast, isolated tests that don't require external dependencies
- **Focus on essential functionality**: Tests cover data models, utilities, and core business logic

## Test Files

### Unit Tests (Fast, No External Dependencies)

| Test File | Purpose | Speed |
|-----------|---------|-------|
| `KubernetesAgentResponseTest.java` | Tests request/response data models | ⚡ Fast |
| `AgentResponseParserTest.java` | Tests parsing of agent responses | ⚡ Fast |
| `AgentResponseFormatterTest.java` | Tests response formatting | ⚡ Fast |
| `RetryHelperTest.java` | Tests retry logic for transient errors | ⚡ Fast |
| `GitHubIssueToolTest.java` | Tests GitHub issue creation logic | ⚡ Fast |
| `GitHubPRToolTest.java` | Tests GitHub PR creation logic | ⚡ Fast |

### Test Fixtures

- `GitHubTestFixtures.java` - Mock GitHub data for testing
- `K8sTestFixtures.java` - Mock Kubernetes resources for testing
- `MockHelpers.java` - Helper methods for creating mocks

## Running Tests

### Run All Unit Tests
```bash
mvn test
```

### Run Specific Test
```bash
mvn test -Dtest=KubernetesAgentResponseTest
```

### Run with Coverage
```bash
mvn test jacoco:report
```

## Manual Testing

For manual end-to-end testing, use the console runner:

```bash
# Set required environment variables
export OPENAI_API_KEY="sk-..."
export GITHUB_TOKEN="ghp_..."  # Optional, for PR creation

# Run in console mode
./run-console.sh
```

## CI/CD

The test suite runs automatically on every commit:
- ✅ Fast unit tests (< 10 seconds)
- ✅ No external dependencies required
- ✅ No API keys needed

## Coverage Goals

- **Target**: 60% line coverage (configured in pom.xml)
- **Focus**: Core business logic and data models
- **Exclude**: Integration points that require external services
