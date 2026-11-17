# Kubernetes Agent Tests

## Overview

The normal suite is unit tests only: fast, isolated, no external dependencies, no API keys. The labelled evaluations that call live models are opt-in via system properties.

## Always-on tests

| Test file | Purpose |
|-----------|---------|
| `api/AnalysisPipelineTest.java` | End-to-end analysis pipeline with WireMock LLM and A2A stubs |
| `api/RolloutControlResourceTest.java` | Promote/abort rollout control endpoints |
| `analysis/AnalysisGraderTest.java` | Grader verdict banding (contradicted / neutral / supported) |
| `analysis/PauseGateTest.java` | Uncertainty pause gate decisions |
| `analysis/SecondOpinionModelWiringTest.java` | The `secondopinion` named model resolves to a ChatModel bean |
| `analysis/AnalysisEvalSetTest.java` | Validates the evaluation set structure |
| `k8s/K8sToolsTest.java` | Canary diagnostics and metrics tools |
| `k8s/MetricsAggregationTest.java` | Per-pod metrics aggregation and window semantics |
| `observability/ActivityEventsTest.java` | Activity event payloads |
| `utils/TextUtilsTest.java` | Shared text utilities |

Fixtures live in `fixtures/K8sTestFixtures.java` (mock Kubernetes resources).

## Opt-in tests (live models)

These call the real chat, decision, and second-opinion models and never run in the normal suite or CI:

| Test file | Purpose | Enable with |
|-----------|---------|-------------|
| `analysis/AnalysisEvalTest.java` | Labelled analysis evaluation against `analysis-eval.json` | `-Danalysis.eval=true` |
| `analysis/GraderEvalTest.java` | Grader separation on the labelled scenarios | `-Dgrader.eval=true` |
| `analysis/EscalationSmokeTest.java` | Both tiers analyze a hard labelled case, both graded | `-Descalation.smoke=true` |

Run them before and after changing the `AnalysisAgent` or grader prompts, models, or wiring. See the main `README.md` for the full env-var commands.

## Running tests

```bash
./mvnw test                          # whole unit suite
./mvnw test -Dtest=K8sToolsTest      # one class
```

If port 8081 is already in use on your machine (another Quarkus app, dev mode), point the test HTTP server elsewhere: `./mvnw test -Dquarkus.http.test-port=18081`.

## Manual testing

For manual end-to-end testing, send a real request to the running agent with the `smoke-test.sh` script at the repository root:

```bash
export ANALYSIS_API_KEY="sk-..."
./mvnw quarkus:dev          # terminal 1
./smoke-test.sh local       # terminal 2
```

## CI/CD

GitHub Actions builds every commit: unit tests on every push, container images pushed to GHCR from `main` only.
