# Kubernetes Agent Development Guide

Quarkus-based autonomous AI agent that analyzes Kubernetes rollouts, decides promote/rollback, and creates GitHub PRs or issues for detected problems. This is an experimental project — prioritize simplicity over backwards compatibility.

Do not create summary or migration documents. If a change is made, update the relevant code and documentation in place.

## Overview

**Stack:** Quarkus 3.x, LangChain4j declarative agents, Fabric8 Kubernetes client, JGit, GitHub REST API, A2A protocol

**Principles:**
1. Use declarative annotations (`@Agent`, `@LoopAgent`, `@SequenceAgent`, `@ParallelAgent`)
2. Single responsibility per agent
3. Records for type-safe data contracts between agents
4. Keep it simple

## Project Layout

```
src/main/java/dev/kevindubois/rollout/agent/
  a2a/
    KubernetesAgentResource.java    # REST endpoints: POST /a2a/analyze, GET /a2a/health
  workflow/
    KubernetesWorkflow.java         # Top-level orchestrator (@SequenceAgent)
    ParallelDataWorkflow.java       # DiagnosticsDataAgent ∥ MetricsDataAgent (@ParallelAgent)
    AnalysisLoop.java               # AnalysisAgent → ScoringAgent, up to 3 iterations (@LoopAgent)
    RemediationLoop.java            # Async remediation orchestration
  agents/
    DiagnosticsDataAgent.java       # Non-AI: pod logs, events, resource state
    MetricsDataAgent.java           # Non-AI: /q/metrics scraping
    DataCombinerAgent.java          # Non-AI: merges diagnostics + metrics into single report
    AnalysisAgent.java              # AI: decide promote/rollback, write analysis + rootCause
    ScoringAgent.java               # AI: quality-check AnalysisResult; request retry if needed
    RemediationAgent.java           # AI: open GitHub PR (code bug) or GitHub issue (ops issue)
  k8s/
    K8sTools.java                   # Tools: debugPod, getPodLogs, getEvents, getMetrics, getResources
  remediation/
    SourceCodeTool.java             # Tool: read source files from GitHub API
    GitHubPatchPRTool.java          # Tool: create PR with line-based patches
    GitHubPRTool.java               # Tool: create PR with full file content
    GitHubIssueTool.java            # Tool: create GitHub issue
    GitOperations.java              # JGit: clone, branch, commit, push
  model/                            # Records: AnalysisResult, RemediationResult, KubernetesAgentRequest/Response
  utils/
    ToolCallLimiter.java            # Hard cap: max 20 tool calls per workflow
deployment/
  deployment.yaml                   # K8s Deployment (512Mi–2Gi memory)
  rbac.yaml                         # ClusterRole: read pods/logs/events/rollouts; exec pods
  service.yaml                      # ClusterIP :8080
  secret.yaml.template              # API key template — copy and fill before deploying
```

## Prerequisites

Java 21+, Maven 3.8+, kubectl/oc, Google or OpenAI API key, GitHub token with `repo` scope, Kubernetes cluster with Argo Rollouts + `rollouts-plugin-metric-ai`.

## Development

### Local Setup

In local dev, credentials are read from environment variables via `application.properties` fallback (`github.token=${GITHUB_TOKEN:}`):

```bash
export GOOGLE_API_KEY="..."   # or OPENAI_API_KEY
export GITHUB_TOKEN="..."

mvn quarkus:dev -Dquarkus.profile=dev,gemini
mvn quarkus:dev -Dquarkus.profile=dev,gemini -Drun.mode=console   # Interactive console mode
```

In production, credentials come from the `kubernetes-agent` Kubernetes Secret (K8s Secret path) or from a Vault KV path (Vault path — activated by adding `vault` to `QUARKUS_PROFILE`). See the `progressive-delivery` README for Vault bootstrap steps.

**Profiles:** `dev`, `prod`, `gemini`, `openai`

### Agent Pattern

```java
@Agent
@RegisterAiService
public interface MyAgent {
    @SystemMessage("You are a specialized agent for [task]. Guidelines: ...")
    String execute(String input);
}
```

Use `@LoopAgent` for retry loops, `@SequenceAgent` for ordered steps, `@ParallelAgent` for concurrent non-AI work.

### Tool Pattern

```java
@ToolBox
public class MyK8sTool {
    @Tool("Describe what this tool does and when to call it")
    public String myTool(@P("namespace") String ns, @P("name") String name) {
        try {
            return result;
        } catch (Exception e) {
            logger.error("Tool failed", e);
            return "Error: " + e.getMessage();
        }
    }
}
```

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

### Kind (local cluster)

```bash
kind create cluster --name k8s-agent-test
docker build -t quay.io/kevindubois/kubernetes-agent:latest .
kind load docker-image quay.io/kevindubois/kubernetes-agent:latest --name k8s-agent-test
# K8s Secret path (default):
cp deployment/secret.yaml.template deployment/secret.yaml   # fill in keys
kubectl apply -f deployment/secret.yaml
kubectl apply -k deployment/
kubectl logs -f deployment/kubernetes-agent -n argo-rollouts
```

## Testing

```bash
mvn test
mvn test -Dtest=AnalysisAgentTest
./test-agent.sh local    # or k8s
./run-console.sh         # Interactive console mode
```

## Workflow

The analysis request from the plugin triggers this sequence:

1. `ParallelDataWorkflow` — `DiagnosticsDataAgent` and `MetricsDataAgent` run concurrently using only Kubernetes tools (no LLM).
2. `DataCombinerAgent` — merges the two reports into a single `diagnosticData` string.
3. `AnalysisLoop` — `AnalysisAgent` → `ScoringAgent`; repeats until score is acceptable or 3 iterations are exhausted.
4. Response returned synchronously to the plugin (`promote`, `confidence`, `analysis`, `rootCause`).
5. If `promote=false` and `repoUrl` is set, `RemediationAgent` runs asynchronously (after 3s delay):
   - Operational signals (OOM, memory leak, heap warnings) → GitHub issue via `GitHubIssueTool`
   - Code bugs (NPE, wrong value, logic error) → GitHub PR via `GitHubPatchPRTool`

## Debugging

```bash
kubectl logs -f deployment/kubernetes-agent -n openshift-gitops
kubectl logs deployment/kubernetes-agent -n openshift-gitops | grep -i "error\|tool"

# Health check
kubectl port-forward -n openshift-gitops svc/kubernetes-agent 8080:8080
curl http://localhost:8080/a2a/health
```

**Common issues:**

| Symptom | Fix |
|---|---|
| Pod not starting | Check secret exists (K8s path) or Vault is reachable (Vault path) |
| API key errors | Verify the secret keys and profile match (gemini vs openai) |
| Tool calls failing | Check RBAC — agent needs read access to pods, logs, events |
| GitHub PR not created | Verify `github.token` is set (env var or Vault KV `github_token`); token needs `repo` scope |

## Code Standards

- Records for DTOs — no mutable state in data contracts.
- `@SystemMessage` should define role, output format, and constraints; `@UserMessage` passes runtime data.
- Log at `debug` for per-operation detail, `info` for decisions, `error` for failures.
- Format: `mvn fmt:format`

## Feature Checklist

- [ ] Simple, clean, concise code
- [ ] Follows declarative agent pattern
- [ ] System and user messages are specific and well-structured
- [ ] Error handling and logging are appropriate
- [ ] Tests added and passing
- [ ] `README.md` updated if behaviour changed
- [ ] No compiler warnings
- [ ] No "Made with Bob" comments

## Resources

- [Quarkus](https://quarkus.io/guides/) | [Quarkus LangChain4j](https://docs.quarkiverse.io/quarkus-langchain4j/dev/) | [LangChain4j](https://docs.langchain4j.dev/)
- [Argo Rollouts](https://argo-rollouts.readthedocs.io/) | [Kubernetes Client](https://quarkus.io/guides/kubernetes-client)
- [JGit](https://www.eclipse.org/jgit/) | [Fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client)
