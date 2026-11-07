# Software Analysis and Remediation AI Agent

[![Build and Push](https://github.com/kdubois/kubernetes-aiops-agent/actions/workflows/build.yml/badge.svg)](https://github.com/kdubois/kubernetes-aiops-agent/actions/workflows/build.yml)

An autonomous AI agent for Kubernetes software delivery debugging and remediation, powered by Quarkus LangChain4j with support for Google Gemini AI and OpenAI.

## Overview

The Kubernetes Agent is an intelligent system that:

- **Debugs** Kubernetes pods automatically
- **Analyzes** logs, metrics, events, and rollout status
- **Aggregates** metrics across every stable and canary pod — latency quantiles, window latency, and heap take the worst pod, so a regression on any single pod is visible even when a canary scale-up adds healthy pods to the average
- **Identifies** root causes of issues
- **Delegates** fixes to the sandboxed [remediation-agent](../remediation-agent) service, which creates GitHub pull requests or issues
- **Integrates** with Argo Rollouts for canary analysis

## Testing

The agent can automatically analyze and diagnose production issues. Test with realistic scenarios using the demo application in the `argo-rollouts-quarkus-demo` directory.

See the [Demo Script](../argo-rollouts-quarkus-demo/DEMO_SCRIPT.md) for detailed testing scenarios including:
- **Happy Path**: Successful deployment with AI analysis
- **NullPointerException Bug**: Bug detection with automatic rollback and PR creation
- **Memory Leak**: Performance issue detection with automatic rollback
- **Latency Drift**: Metrics-only regression that passes the early analysis and fails the later one

### Analysis evaluation

The labelled evaluation set in [`src/test/resources/analysis-eval.json`](src/test/resources/analysis-eval.json) measures how often the analysis agrees with the expected decision for known scenarios: the four demo outcomes plus edge cases (bug in stable only, same errors in both, early canary with thin metrics, crash-looping canary). It calls the configured chat model once per case and writes a report to `target/analysis-eval-report.md`:

```bash
ANALYSIS_API_KEY (cheap analysis tier), ANALYSIS_STRONG_MODEL/ANALYSIS_STRONG_BASE_URL/ANALYSIS_STRONG_API_KEY (escalation tier, optional)=... ANALYSIS_BASE_URL=... ANALYSIS_MODEL=... \
  mvn test -Dtest=AnalysisEvalTest -Danalysis.eval=true
```

The normal suite never runs the evaluation because it calls the live model; `AnalysisEvalSetTest` (always on) only validates the evaluation set's structure. Run the evaluation before and after changing the `AnalysisAgent` prompt or model to see whether agreement moved.

### Quick Test

```bash
# Test the agent with the demo application
cd ../argo-rollouts-quarkus-demo
./test-scenarios.sh
```

## Features

### Kubernetes Debugging Tools

- **Pod Debugging**: Analyze pod status, conditions, and container states
- **Events**: Retrieve and correlate cluster events
- **Logs**: Fetch and analyze container logs (including previous crashes)
- **Metrics**: Check resource usage and limits
- **Resources**: Inspect related deployments, services, and configmaps

### Remediation Capabilities

After a rollback decision with a repository URL configured, the agent delegates the fix to the
[remediation-agent](../remediation-agent) service over A2A. Remediation is triggered after the
decision event is published — on the final, post-escalation result — and hands off to a
background A2A call, so the analysis response returns to Argo Rollouts immediately.

The remote remediation agent:

- **Swappable coding agent**: runs opencode (or pi, Bob) in an isolated sandbox pod — `restricted-v2`, no
  service-account token, read-only root filesystem, egress limited to GitHub, Maven Central and the LLM endpoint
- **Verified fixes**: the repository's own test suite runs inside the sandbox after the coding agent finishes;
  a branch is pushed only when the suite is green
- **Evidence-bearing PRs**: pull requests carry the test results, files changed, model, sandbox name and duration
- **Issues for operational problems**: OOM, memory leaks and other operational signals open a GitHub issue instead

The sandboxed flow is defined in the [remediation-agent repository](../remediation-agent); this service only
composes the request from the analysis result and reports the outcome through the activity events.

**Example**: When detecting a NullPointerException in logs, the agent can:
1. Read the source file mentioned in the stack trace
2. Analyze the actual code structure
3. Propose a surgical fix targeting only the buggy line
4. Create a PR with precise line-based changes
5. Validate the patch to prevent accidental code deletion

**Line-Based Patching**: The agent uses surgical precision to fix bugs:
- **Replace**: Fix the exact buggy line (e.g., `nullString.length()` → `versionUpper.length()`)
- **Insert**: Add new lines (e.g., null checks, validation)
- **Delete**: Remove problematic lines (used sparingly)
- **Validation**: Warns about potentially destructive changes (deleting return statements, closing braces, etc.)

### REST API

- **REST API**: Expose analysis capabilities via HTTP
- **Integration**: Works with `rollouts-plugin-metric-ai` for canary analysis

### Uncertainty pause gate

When the caller sends `pauseOnUncertainty: true` in the request context, the agent pauses the rollout instead of letting a low-confidence promote decision proceed: if the analysis promotes with confidence below `uncertaintyThreshold` (default 50), the Rollout's `spec.paused` is patched and a human decides with `oc argo rollouts unpause` or the promote endpoint below. The measurement itself stays successful — the pause, not an abort, is what holds the rollout. A rollback decision never pauses, since the abort already stops the rollout. The paused rollout name is returned in the response's `pausedRollout` field and published as a `PAUSE` activity event, and the gate publishes a `PAUSE_GATE` event with the full causal chain: self-reported confidence, grader probability, effective confidence, and threshold (a negative grader probability means no second opinion was available).

The rollout named in the request context (`rolloutName`, which the plugin sends) is the one paused; without a name, the first rollout in the namespace is used. An already-paused rollout is not re-patched and does not re-publish an event, but a rollout that a human has unpaused is paused again by a later analysis that is still below the threshold — each measurement applies the gate policy. A pause that fails (for example, missing RBAC) is surfaced as a `PAUSE_FAILED` activity event.

Pausing requires patch permission on rollouts in the target namespace, granted by a namespaced Role (see the `progressive-delivery` repo); the agent is read-only everywhere else.

### Manual rollout control

Two endpoints serve the human decision on a gate-held rollout, both defaulting to the `quarkus-demo` rollout in the `quarkus-demo` namespace (the same defaults as `GET /rollout/summary`):

- `POST /rollout/promote?namespace=...&name=...` — resumes the rollout by patching `spec.paused` to `false`, the exact reverse of the gate's pause patch. Returns 200 on success, 404 when the rollout does not exist, 500 on error.
- `POST /rollout/abort?namespace=...&name=...` — aborts the rollout to the stable version with the same signal `kubectl argo rollouts abort` sends: a merge patch of `status.abort=true` on the rollout's status subresource, after unpausing so the controller acts on it. The controller scales the canary down and reverts traffic to the stable ReplicaSet. `spec.template` is left alone because Git owns it — the rollout stays in the aborted state until the image is reverted in Git.

Both publish `PROMOTE` / `ABORT` activity events and return a `ControlResult` JSON body (`action`, `namespace`, `rollout`, `error`). Promote needs patch permission on rollouts, abort additionally on `rollouts/status` (extend the namespaced pause Role from the `progressive-delivery` repo).

### Analysis grader (decision-model second opinion)

The confidence the analysis LLM reports about itself is not always calibrated. After the analysis pass, the Jev decision model (TypeSafe) grades the conclusion with two yes/no questions over a condensed form of the diagnostic data plus the conclusion: does any evidence contradict the conclusion, and is there enough data to verify it? The answers map onto a three-way verdict on the supported-probability scale the gate consumes — contradicted stays below the 0.30 veto bar (escalation, confidence drop), evidence too thin to verify lands in the neutral band (the self-reported confidence stands; the next measurement is the remedy, not a human), and a verified conclusion reaches 0.5 and above. The grader state carries the decision thresholds the analysis judged against and the action semantics (promote continues to the next measurement, rollback aborts), so "supported" means checkable against the stated policy rather than statistically proven — without that framing, a correct conclusion built on an early-canary sample read as disputed and the gate held healthy rollouts. A confident analysis the grader contradicts pauses the rollout; a doubted-but-actually-fine analysis promotes as before when the grader agrees.

The grader degrades gracefully: without `TYPESAFE_API_KEY`, on a call failure, or on a timeout it disables itself and the gate falls back to the self-reported confidence — the grader never fails the measurement. The response contract is unchanged: `confidence` stays the self-reported value; the pause gate and the `DECISION` event reflect the effective confidence, with both values logged next to each other.

Every grade is published as a `QUALITY_CHECK` activity event with the score and the finding — `passed` (≥ 50/100, the evidence backs the conclusion), `neutral` (30–50, the evidence is too thin to decide), or `failed` (below 30/100, the evidence contradicts the conclusion). The evidence itself is gathered as a window: metrics are scraped twice with `analysis.sample.window-seconds` (default 10) between them, and the analysis judges the delta — the requests, errors and latency observed during the measurement, unaffected by pod warmup. The window reports the fleet mean and the worst pod's mean latency; the worst pod is the signal because freshly scaled-up canary pods would otherwise dilute a single slow pod back to baseline. The window extends while the canary sample is below `analysis.sample.min-requests` (default 500 — an error rate over a handful of requests is noise), bounded by `analysis.sample.max-waits`; each extension shows in the feed as a "Gathering more canary evidence" event. The event order tells the story: the `CONFIDENCE_SCORE` event publishes the model's self-reported confidence right after its conclusion, the quality check grades it next, and the `DECISION` event announces the effective confidence with both inputs. When the escalation tier re-analyzes a failed conclusion, the `MODEL_ESCALATION` event names both models and their decisions, e.g. `glm-5-3 said continue (95/100), quality check failed at 29/100 — DeepSeek-V4.1 re-analyzed: continue (85/100)`. The model names come from the configuration (`quarkus.langchain4j.openai.chat-model.model-name` and `analysis.escalation.model`), so the events always name the models actually in use.

The grader is a single decision call with a threshold (grade → combine → gate), shaped like langchain4j-agentic's Confidence Cascade pattern, so a future tiered-model design (cheap analysis first, a stronger model when uncertain) can adopt the cascade planner directly.

## Architecture

```mermaid
%%{init: {'theme': 'base', 'look': 'handDrawn'}}%%
flowchart LR
    A([Plugin]):::plugin --> B

    subgraph B[Diagnostic Agents]
        direction TB
        B1[DiagnosticsDataAgent]:::diagnostic
        B2[MetricsDataAgent]:::diagnostic
        B3[RolloutStatusDataAgent]:::diagnostic
        
        subgraph K8S[K8s Data Sources]
            direction LR
            K1[Canary Pods]:::canary
            K2[Stable Pods]:::stable
            K3[Rollout CR]:::data
        end
        
        B1 --> K1
        B1 --> K2
        B2 --> K1
        B2 --> K2
        B3 --> K3
    end

    B --> C[DataCombinerAgent<br/>merge logs, rollout status, and metrics]:::combiner
    C --> D[AnalysisAgent<br/>AI evaluation]:::analysis
    D -->|return decision| F([Return]):::plugin

    D -->|rollback with repoUrl, async A2A| G

    subgraph G[RemediationAgent (A2A)]
        direction TB
        G1[Sandbox: coding agent]:::remediation
        G2[Repository test suite]:::remediation
        G3[PR with evidence / Issue]:::remediation
        G1 --> G2
        G2 -->|green| G3
        G2 -->|red| G3
    end

    classDef plugin fill:#90caf9,stroke:#1565c0,stroke-width:3px,color:#000
    classDef data fill:#c5e1a5,stroke:#558b2f,stroke-width:3px,color:#000
    classDef diagnostic fill:#81d4fa,stroke:#0277bd,stroke-width:3px,color:#000
    classDef canary fill:#ffab91,stroke:#d84315,stroke-width:3px,color:#000
    classDef stable fill:#a5d6a7,stroke:#388e3c,stroke-width:3px,color:#000
    classDef combiner fill:#f8bbd0,stroke:#512da8,stroke-width:3px,color:#000
    classDef analysis fill:#80deea,stroke:#00838f,stroke-width:3px,color:#000
    classDef decision fill:#f8bbd0,stroke:#6a1b9a,stroke-width:3px,color:#000
    classDef remediation fill:#e1bee7,stroke:#3949ab,stroke-width:3px,color:#000
```

## CI/CD

The project uses GitHub Actions for automated builds and deployments:

- **Automated Builds**: Every push to `main` triggers a build, runs tests, and pushes container images to GitHub Container Registry (GHCR)
- **Test Coverage**: Code coverage reports are automatically uploaded to Codecov
- **Pull Request Validation**: PRs are built and tested automatically without pushing images
- **Container Images**: Available at `ghcr.io/kdubois/kubernetes-agent`
- **Dependency Management**: Dependabot automatically creates PRs for Maven dependencies, GitHub Actions, and Docker base images

Container images are tagged with:
- `latest` for the main branch
- Version tags for releases (e.g., `v1.0.0`)
- SHA-based tags for all builds

## Prerequisites

- Java 21+
- Maven 3.8+
- Kubernetes cluster
- Google API Key (Gemini) or OpenAI API Key

## Local Development

### 1. Set environment variables

```bash
export ANALYSIS_API_KEY="your-api-key"   # Any OpenAI-compatible API key
```

### 2. Run locally

```bash
mvn quarkus:dev

# Server starts on port 8080
# Health check: http://localhost:8080/q/health
```

### 3. Run locally in console mode

```bash
# Interactive console mode for testing
mvn quarkus:dev -Drun.mode=console

# Or use the convenience script
./run-console.sh
```

## Deployment to Kubernetes

### 1. Build Docker image

```bash
# Build with Maven
mvn clean package -Dquarkus.profile=prod,gemini

# Build Docker image
docker build -f src/main/docker/Dockerfile.jvm -t quay.io/kevindubois/kubernetes-agent:latest .

# Push to registry
docker push quay.io/kevindubois/kubernetes-agent:latest
```

Or directly with Quarkus:

```bash
# Build and push in one command
mvn quarkus:image-push -Dquarkus.container-image.build=true -Dquarkus.profile=prod,gemini
```


### 2. Create secrets

```bash
# Copy template
cp deployment/secret.yaml.template deployment/secret.yaml

# Edit secret.yaml and add your keys
# Then apply:
kubectl apply -f deployment/secret.yaml
```

### 3. Deploy agent

```bash
# Deploy using Kustomize
kubectl apply -k deployment/

# Verify deployment
kubectl get pods -n openshift-gitops | grep kubernetes-agent
```

**Note**: The default namespace is `openshift-gitops`. Update `deployment/kustomization.yaml` if deploying to a different namespace.

### 4. Verify deployment

```bash
# Check pods
kubectl get pods -n openshift-gitops | grep kubernetes-agent

# Check logs
kubectl logs -f deployment/kubernetes-agent -n openshift-gitops

# Test health endpoint
kubectl port-forward -n openshift-gitops svc/kubernetes-agent 8080:8080
curl http://localhost:8080/q/health
```

### 5. Run tests

The `test-agent.sh` script supports both Kubernetes and local modes:

```bash
# Test agent running in Kubernetes (default)
./test-agent.sh k8s

# Test agent running locally on localhost:8080
./test-agent.sh local

# Use custom local URL
LOCAL_URL=http://localhost:9090 ./test-agent.sh local

# Use custom Kubernetes context
CONTEXT=my-k8s-context ./test-agent.sh k8s
```

The test script will:
1. ✅ Check health endpoint
2. ✅ Send a sample analysis request
3. ✅ Verify no errors in logs (K8s mode only)

## Usage

### Direct Console Mode

```bash
# Run console mode
./run-console.sh

# Or manually:
mvn quarkus:dev -Dquarkus.profile=dev,gemini -Drun.mode=console

# Example interaction:
You > Debug pod my-app-canary in namespace production

Agent > Analyzing pod my-app-canary in namespace production...
[Agent gathers debug info, logs, events...]

Root Cause: Container crashloop due to OOMKilled - memory limit too low

Recommendation:
1. Increase memory limit from 256Mi to 512Mi
2. Add resource requests to prevent overcommitment
3. Review memory usage patterns in logs
```

### REST API

The agent exposes a REST API for other systems to use:

**Endpoint**: `POST /analyze`

**Request**:
```json
{
	"userId": "argo-rollouts",
	"prompt": "Analyze canary deployment health",
	"context": {
		"namespace": "quarkus-demo",
		"rolloutName": "quarkus-demo",
		"repoUrl": "https://github.com/example/quarkus-demo",
		"stableSelector": "role=stable",
		"canarySelector": "role=canary"
	}
}
```

**Response**:
```json
{
	"analysis": "Detailed analysis text...",
	"rootCause": "Identified root cause",
	"remediation": "Suggested fixes",
	"promote": false,
	"confidence": 85,
	"pausedRollout": null,
	"error": null
}
```

On failure the endpoint returns HTTP 500 (or 504 on timeout) with `promote: false`,
`confidence: 0`, and a short `error` message.

Manual rollout control (see [Manual rollout control](#manual-rollout-control)):

```bash
POST /rollout/promote?namespace=quarkus-demo&name=quarkus-demo   # resume a gate-held rollout
POST /rollout/abort?namespace=quarkus-demo&name=quarkus-demo     # abort to the stable version
```

Both return a `ControlResult`:

```json
{
	"action": "promote",
	"namespace": "quarkus-demo",
	"rollout": "quarkus-demo",
	"error": null
}
```

## Integration with Argo Rollouts

### 1. Configure Analysis Template

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
	name: canary-analysis-with-agent
spec:
	metrics:
		- name: ai-analysis
			provider:
				plugin:
					ai-metric:
						# Use agent mode
						analysisMode: agent
						namespace: "{{args.namespace}}"
						podName: "{{args.canary-pod}}"
						# Fallback to default mode
						stablePodLabel: app=rollouts-demo,revision=stable
						canaryPodLabel: app=rollouts-demo,role=stable
						model: gemini-3.5-flash
```

### 2. The plugin will automatically:
1. Check if agent is healthy
2. Send analysis request with logs
3. Receive intelligent analysis
4. Get PR link if fix was created
5. Decide to promote or abort canary

## Configuration

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `ANALYSIS_API_KEY` | Yes | API key for the analysis model (any OpenAI-compatible endpoint) |
| `ANALYSIS_BASE_URL` | No | Base URL for analysis model API (default: "https://api.openai.com/v1") |
| `ANALYSIS_MODEL` | No | Analysis model name (default: "gpt-4o") |
| `TYPESAFE_API_KEY` | No | TypeSafe API key for the Jev analysis grader; without it the grader disables itself and the pause gate uses the self-reported confidence |
| `REMEDIATION_AGENT_URL` | No | URL of the remediation-agent A2A service (defaults to `http://remediation-agent:8080`) |

### Resource Limits

Recommended settings for production:

```yaml
resources:
	requests:
		memory: "512Mi"
		cpu: "250m"
	limits:
		memory: "2Gi"
		cpu: "1000m"
```

## Troubleshooting

### Agent not starting

```bash
# Check logs
kubectl logs deployment/kubernetes-agent -n openshift-gitops

# Common issues:
# 1. Missing API keys - check secrets
# 2. Invalid service account - check RBAC
# 3. Out of memory - increase limits
# 4. Wrong namespace - check deployment namespace
```

### Health check failing

```bash
# Test endpoint directly
kubectl port-forward -n openshift-gitops svc/kubernetes-agent 8080:8080
curl http://localhost:8080/q/health

# Should return Quarkus health check response
```

### API Key Issues

```bash
# Verify secret exists
kubectl get secret kubernetes-agent -n openshift-gitops

# Check environment variables in pod
kubectl exec -n openshift-gitops deployment/kubernetes-agent -- env | grep -E "ANALYSIS_API_KEY|GITHUB_TOKEN|REMEDIATION_AGENT_URL"
```

### PR creation failing

```bash
# Check GitHub token permissions:
# - repo (full control)
# - workflow (if modifying GitHub Actions)

# Check logs for git errors:
kubectl logs deployment/kubernetes-agent -n openshift-gitops | grep -i "git\|github"
```

## Security Considerations

1. **RBAC**: Agent only has read access to K8s resources (no write)
2. **Secrets**: Store API keys in Kubernetes secrets
3. **Network**: Use NetworkPolicies to restrict egress
4. **Git**: Use fine-grained personal access tokens
5. **Review**: Always review PRs before merging

## Development

### Project Structure

```
kubernetes-agent/
├── src/main/java/dev/kevindubois/rollout/agent/
│   ├── a2a/                          # A2A protocol (agent card, JSON-RPC executor)
│   │   └── A2AAgentExecutor.java
│   ├── analysis/                     # Analysis pipeline
│   │   ├── AnalysisOrchestratorAgent.java # Main workflow (@SequenceAgent)
│   │   ├── AnalysisService.java      # Shared analysis entry point (REST + A2A)
│   │   ├── AnalysisDataAgent.java    # Parallel data gathering (@ParallelAgent)
│   │   ├── DiagnosticsDataAgent.java # Pod diagnostics and logs gathering
│   │   ├── RolloutStatusDataAgent.java # Rollout phase, step, and traffic weights
│   │   ├── MetricsDataAgent.java     # Metrics gathering
│   │   ├── DataCombinerAgent.java    # Combine data sources
│   │   └── AnalysisAgent.java        # AI analysis and promote/rollback decision
│   ├── api/                          # REST API
│   │   ├── KubernetesAgentResource.java # Analysis endpoint (POST /analyze)
│   │   └── RolloutSummaryResource.java # Rollout summary REST endpoint
│   ├── k8s/                          # Kubernetes tools and Rollout CR models
│   │   └── K8sTools.java             # K8s data gathering tools
│   ├── observability/                # Activity events for the demo UI
│   ├── remediation/                  # PR/issue creation, Git and GitHub clients
│   └── utils/                        # Utilities
├── deployment/                       # Kubernetes manifests
│   ├── deployment.yaml
│   ├── rbac.yaml
│   ├── service.yaml
│   └── secret.yaml.template
├── pom.xml                           # Maven config
├── ARCHITECTURE.md                   # Architecture documentation
├── agents.md                         # Agent development guide
└── src/main/docker/                  # Dockerfiles
    ├── Dockerfile.jvm
    ├── Dockerfile.native
    └── Dockerfile.native-micro
```

### Running Tests

```bash
# Run unit tests
mvn test

# Run with coverage
mvn verify

# Run integration tests
mvn verify -DskipITs=false

# Run E2E tests (requires cluster)
./run-e2e-test.sh
```

See [src/test/README.md](src/test/README.md) for detailed testing documentation.

### Building Multi-arch Images

```bash
docker buildx build --platform linux/amd64,linux/arm64 \
	-t quay.io/kevindubois/kubernetes-agent:latest \
	--push .
```

## Roadmap

- [ ] Multi-cluster support
- [ ] Historical analysis (learn from past incidents)
- [ ] Cost optimization recommendations
- [ ] Security vulnerability detection
- [ ] Self-healing capabilities
- [ ] Slack/PagerDuty notifications
- [ ] Advanced code analysis before fixes

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Additional Documentation

- **[ARCHITECTURE.md](ARCHITECTURE.md)**: Detailed architecture and design decisions
- **[agents.md](agents.md)**: Comprehensive agent development guide
- **[src/test/README.md](src/test/README.md)**: Testing documentation and strategies

## Model Support

The agent supports any OpenAI-compatible endpoint. Configure via environment variables:

### OpenAI
```bash
export ANALYSIS_API_KEY="sk-..."
export ANALYSIS_MODEL="gpt-4o"  # Optional, this is the default
mvn quarkus:dev
```

### Google Gemini (via OpenAI-compatible endpoint)
```bash
export ANALYSIS_API_KEY="AIza..."
export ANALYSIS_BASE_URL="https://generativelanguage.googleapis.com/v1beta/openai/"
export ANALYSIS_MODEL="gemini-3.5-flash"
mvn quarkus:dev
```

### vLLM / LiteLLM (OpenAI-compatible)
```bash
export ANALYSIS_API_KEY="dummy"
export ANALYSIS_BASE_URL="http://vllm-service:8000/v1"
export ANALYSIS_MODEL="gemma-2-9b-it"
mvn quarkus:dev
```
## Support

For issues or questions:
- **GitHub Issues**: [Create an issue](https://github.com/kdubois/kubernetes-aiops-agent/issues)
- **Documentation**: See [ARCHITECTURE.md](ARCHITECTURE.md) and [agents.md](agents.md) for detailed information



