# Kubernetes Agent Development Guide

Quarkus-based autonomous AI agent that analyzes Kubernetes rollouts, decides promote/rollback, and delegates remediation to the sandboxed remediation-agent service over A2A. This is an experimental project — prioritize simplicity over backwards compatibility.

Do not create summary or migration documents. If a change is made, update the relevant code and documentation in place.

## Overview

**Stack:** Quarkus 3.x, LangChain4j declarative agents, Fabric8 Kubernetes client, A2A protocol

**Principles:**
1. Use declarative annotations (`@Agent`, `@LoopAgent`, `@SequenceAgent`, `@ParallelAgent`)
2. Single responsibility per agent
3. Records for type-safe data contracts between agents
4. Keep it simple

## Project Layout

```
src/main/java/dev/kevindubois/rollout/agent/
  a2a/
    A2AAgentExecutor.java           # A2A protocol executor
    KubernetesAgentCard.java        # A2A agent card (JSON-RPC discovery)
  analysis/
    AnalysisOrchestratorAgent.java  # Top-level orchestrator (@SequenceAgent)
    AnalysisService.java            # Shared analysis entry point (REST + A2A)
    AnalysisDataAgent.java          # DiagnosticsDataAgent ∥ RolloutStatusDataAgent ∥ MetricsDataAgent (@ParallelAgent)
    DiagnosticsDataAgent.java       # Non-AI: pod logs, resource state
    RolloutStatusDataAgent.java     # Non-AI: rollout phase, step, traffic weights
    MetricsDataAgent.java           # Non-AI: /q/metrics scraping
    DataCombinerAgent.java          # Non-AI: merges diagnostics, rollout status, and metrics into single report
    AnalysisAgent.java              # AI: decide promote/rollback, write analysis + rootCause
    AnalysisGrader.java             # Decision model (Jev): grades the conclusion, second opinion for the pause gate
    AnalysisEscalation.java         # Strong-model tier (DeepSeek): re-analyzes a disputed conclusion before the gate
  api/
    KubernetesAgentResource.java    # REST endpoint: POST /analyze
    KubernetesAgentRequest.java     # REST request DTO (userId, prompt, context)
    KubernetesAgentResponse.java    # REST response DTO (promote, confidence, analysis, ...)
    RolloutSummaryResource.java     # REST endpoint: GET /rollout/summary
    RolloutControlResource.java     # REST endpoints: POST /rollout/promote, /rollout/abort
  k8s/
    K8sTools.java                   # getCanaryDiagnostics, getCanaryMetrics, getRolloutStatus (structured concurrency)
    RolloutStatusData.java          # Record: rollout phase, step, weights, pod counts
  observability/
    ActivityEventListener.java      # Publishes agent activity events for the demo UI
    ActivityEventsResource.java     # REST endpoint: activity event stream
  remediation/
    RemediationAgent.java           # Triggers remediation after the decision event (gate + compose)
    RemediationDelegator.java       # Background A2A call to the remediation-agent, publishes outcome events
    a2a/
      RemediationA2AAgent.java         # Typed client interface for the remote A2A agent
      RemediationA2AAgentProducer.java # Lazy LangChain4j A2A client builder (agent card over HTTP)
      RemediationRequest.java          # A2A request contract (repoUrl, rootCause, analysis, ...)
      RemediationResponse.java         # A2A response contract (status, prLink/issueLink, evidence)
  utils/
    TextUtils.java                  # Shared text utilities (truncate, isValidGitHubArtifactUrl)
deployment/
  deployment.yaml                   # K8s Deployment (512Mi–2Gi memory), probes: /q/health
  rbac.yaml                         # ClusterRole: read pods/logs/events/rollouts; exec pods
  service.yaml                      # ClusterIP :8080
  secret.yaml.template              # API key template — copy and fill before deploying
```

## Prerequisites

Java 25+, Maven 3.8+, kubectl/oc, an API key for any OpenAI-compatible endpoint, Kubernetes cluster with Argo Rollouts + `rollouts-plugin-metric-ai`. Optionally a `TYPESAFE_API_KEY` for the Jev analysis grader (without it the pause gate uses the self-reported confidence).

## Development

### Local Setup

In local dev, credentials are read from environment variables via `application.properties` fallback:

```bash
export ANALYSIS_API_KEY="..."   # Any OpenAI-compatible API key

mvn quarkus:dev
mvn quarkus:dev -Drun.mode=console   # Interactive console mode
```

In production, credentials come from the `kubernetes-agent` Kubernetes Secret (K8s Secret path) or from a Vault KV path (Vault path — activated by adding `vault` to `QUARKUS_PROFILE`). See the `progressive-delivery` README for Vault bootstrap steps.

**Profiles:** `dev`, `prod`

### Agent Pattern

```java
@Agent(outputKey = "result", description = "...")
public interface MyAgent {
    @SystemMessage("You are a specialized agent for [task]. Guidelines: ...")
    String execute(String input);
}
```

Use `@LoopAgent` for retry loops, `@SequenceAgent` for ordered steps, `@ParallelAgent` for concurrent non-AI work.

## Building and Deploying

### Fast path (no version bump)

```bash
quarkus build --no-tests && quarkus image push --also-build
kubectl rollout restart deployment/kubernetes-agent -n openshift-gitops
kubectl rollout status deployment/kubernetes-agent -n openshift-gitops
```

### Version bump

Update the image tag in `deployment/kustomization.yaml`, commit, and push. Argo CD will sync.

### Full multi-platform build

```bash
docker buildx build --platform linux/amd64,linux/arm64 \
  -t quay.io/kevindubois/kubernetes-agent:v<version> --push .
```

## Testing

```bash
mvn test
mvn test -Dtest=K8sToolsTest
./run-console.sh         # Interactive console mode

# Labelled analysis evaluation (calls the live model, opt-in):
ANALYSIS_API_KEY=... ANALYSIS_BASE_URL=... ANALYSIS_MODEL=... \
  mvn test -Dtest=AnalysisEvalTest -Danalysis.eval=true
```

The evaluation cases live in `src/test/resources/analysis-eval.json`; the report is written to `target/analysis-eval-report.md`. Run it before and after changing the `AnalysisAgent` prompt or model, and add a case whenever a new failure mode matters.

## Workflow

The analysis request from the plugin triggers this sequence:

1. `AnalysisDataAgent` — `DiagnosticsDataAgent`, `RolloutStatusDataAgent`, and `MetricsDataAgent` run concurrently using only Kubernetes tools (no LLM).
2. `DataCombinerAgent` — merges the three reports into a single `diagnosticData` string.
3. `AnalysisAgent` — decides promote/rollback in a single pass and writes the analysis, root cause, and confidence.
4. `AnalysisGrader` — after the workflow, the Jev decision model grades whether the conclusion is supported by the diagnostic data. The grader is a veto with a margin, not a second confidence scale: only a clearly negative grade (probability below 0.30) triggers the escalation tier or lowers the effective confidence that drives the pause gate; in the neutral band the self-reported confidence stands. Without `TYPESAFE_API_KEY` the grader disables itself and the self-reported confidence stands.
5. `AnalysisEscalation` — the second-opinion tier (Confidence Cascade shape): a clearly disputed conclusion is re-analyzed once by a different model over the same diagnostic data — the analysis model first, the second-opinion model on dispute — and the escalated conclusion is re-graded before the gate decides. Configure via `ANALYSIS_STRONG_*`; an empty model disables the tier and a dispute goes straight to the gate. Evaluate the grader and escalation together with `ANALYSIS_API_KEY=... TYPESAFE_API_KEY=... ANALYSIS_STRONG_MODEL=... mvn test -Dtest=GraderEvalTest -Dgrader.eval=true`.
6. `RemediationAgent` — triggered by `AnalysisService` after the decision event is published, so the remediation activity appears after the analysis narrative and uses the final (post-escalation) result. On a rollback with `repoUrl` set it composes the request and hands it to `RemediationDelegator`, which calls the remediation-agent over A2A on a background thread. On a promote or without a repository it returns without delegating.
7. Response returned synchronously to the plugin (`promote`, `confidence`, `analysis`, `rootCause`) — the decision is not delayed by the sandbox run. The response `confidence` stays the self-reported value; the effective confidence drives only the gate and the activity events.
8. If `pauseOnUncertainty` is enabled and the result promotes with confidence below `uncertaintyThreshold`, the rollout is paused for human review (`spec.paused` patch, namespaced Role required). The human decision is served by `POST /rollout/promote` (unpause) and `POST /rollout/abort` (status subresource `abort` signal) in `RolloutControlResource`.
9. When the sandbox run finishes, the delegator publishes the outcome through the activity events:
   - Operational signals (OOM, memory leak, heap warnings) → GitHub issue
   - Code bugs (NPE, wrong value, logic error) → GitHub PR

Note: the A2A call is not a framework-level `@A2AClientAgent` subagent — in the current langchain4j-agentic version that annotation fetches the agent card eagerly at graph build time, `optional = true` is ignored on non-AI agents, and `async = true` subagents block the root call. See `upstream-issues.txt`.

## Debugging

```bash
kubectl logs -f deployment/kubernetes-agent -n openshift-gitops
kubectl logs deployment/kubernetes-agent -n openshift-gitops | grep -i "error\|tool"

# Health check
kubectl port-forward -n openshift-gitops svc/kubernetes-agent 8080:8080
curl http://localhost:8080/q/health
```

**Common issues:**

| Symptom | Fix |
|---|---|
| Pod not starting | Check secret exists (K8s path) or Vault is reachable (Vault path) |
| API key errors | Verify the secret keys match the expected env vars (ANALYSIS_API_KEY) |
| Tool calls failing | Check RBAC — agent needs read access to pods, logs, events |
| Remediation not delegated | The A2A agent card is fetched lazily from `remediation.agent.url`; check the remediation-agent service is up and the URL resolves |

## Code Standards

- Clean, idiomatic Quarkus code. No overengineering — the simplest solution that works.
- No repetition, no boilerplate. Prefer records over object mappers or manual DTO mapping; use Java 25 idioms (records, pattern matching, sealed types, text blocks) where they fit.
- Records for DTOs — no mutable state in data contracts.
- `@SystemMessage` should define role, output format, and constraints; `@UserMessage` passes runtime data.
- Log at `debug` for per-operation detail, `info` for decisions, `error` for failures.
- Use `Log.infof` (parametrized) instead of `MessageFormat.format` for logging.
- Format: `mvn fmt:format`

## Feature Checklist

- [ ] Simple, clean, concise code
- [ ] Follows declarative agent pattern
- [ ] System and user messages are specific and well-structured
- [ ] Error handling and logging are appropriate
- [ ] Tests added and passing
- [ ] `README.md` updated if behaviour changed
- [ ] No compiler warnings

## Resources

- [Quarkus](https://quarkus.io/guides/) | [Quarkus LangChain4j](https://docs.quarkiverse.io/quarkus-langchain4j/dev/) | [LangChain4j](https://docs.langchain4j.dev/)
- [Argo Rollouts](https://argo-rollouts.readthedocs.io/) | [Kubernetes Client](https://quarkus.io/guides/kubernetes-client) | [Fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client)
