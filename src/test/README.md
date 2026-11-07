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
| `KubernetesAgentResponseTest.java` | Tests request/response data models | Fast |
| `AnalysisPipelineTest.java` | End-to-end analysis pipeline with WireMock LLM and A2A stubs | Fast |
| `K8sToolsTest.java` | Tests canary diagnostics and metrics tools | Fast |
| `MetricsAggregationTest.java` | Tests per-pod metrics aggregation | Fast |
| `PauseGateTest.java` | Tests the uncertainty pause gate | Fast |
| `TextUtilsTest.java` | Tests shared text utilities | Fast |
| `AnalysisEvalSetTest.java` | Validates the evaluation set structure | Fast |

### Test Fixtures

- `K8sTestFixtures.java` - Mock Kubernetes resources for testing

## Running Tests

### Run All Unit Tests
```bash
./mvnw test
```

### Run Specific Test
```bash
./mvnw test -Dtest=K8sToolsTest
```

### Run with Coverage
```bash
./mvnw test jacoco:report
```

## Manual Testing

For manual end-to-end testing, send a real request to the running agent with the `smoke-test.sh` script at the repository root:

```bash
export ANALYSIS_API_KEY="sk-..."
./mvnw quarkus:dev          # terminal 1
./smoke-test.sh local    # terminal 2
```

## CI/CD

The test suite runs automatically on every commit:
- Fast unit tests (< 10 seconds)
- No external dependencies required
- No API keys needed

## Coverage Goals

- **Target**: 60% line coverage (configured in pom.xml)
- **Focus**: Core business logic and data models
- **Exclude**: Integration points that require external services
