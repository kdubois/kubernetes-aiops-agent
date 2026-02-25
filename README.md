# Kubernetes AI Agent

An autonomous AI agent for Kubernetes debugging and remediation, powered by Quarkus LangChain4j and Gemini AI (or alternatively OpenAI).

## Overview

The Kubernetes Agent is an intelligent system that:

- **Debugs** Kubernetes pods automatically
- **Analyzes** logs, events, and metrics
- **Identifies** root causes of issues
- **Creates** GitHub pull requests with fixes
- **Integrates** with Argo Rollouts for canary analysis

## Features

### Kubernetes Debugging Tools

- **Pod Debugging**: Analyze pod status, conditions, and container states
- **Events**: Retrieve and correlate cluster events
- **Logs**: Fetch and analyze container logs (including previous crashes)
- **Metrics**: Check resource usage and limits
- **Resources**: Inspect related deployments, services, and configmaps

### Remediation Capabilities

- **Git Operations**: Clone, branch, commit, push (using JGit library)
- **GitHub PRs**: Automatically create pull requests with:
    - Root cause analysis
    - Code fixes
    - Testing recommendations
    - Links to Kubernetes resources

### A2A Communication

- **REST API**: Expose analysis capabilities via HTTP
- **Integration**: Works with `rollouts-plugin-metric-ai` for canary analysis

## Architecture

```
Argo Rollouts Analysis
	↓
rollouts-plugin-metric-ai
	↓ (A2A HTTP)
Kubernetes Agent (Quarkus LangChain4j)
	├── K8s Tools (Quarkus Kubernetes)
	├── Git Operations (JGit)
	├── GitHub PR (Quarkus Rest Client)
	└── AI Analysis (Gemini or OpenAI)
```

## Prerequisites

- Java 17+
- Maven 3.8+
- Kubernetes cluster
- Google API Key (Gemini) or OpenAI API Key
- GitHub Personal Access Token

## Local Development

### 1. Set environment variables

```bash
# to use Gemini:
export GOOGLE_API_KEY="your-google-api-key"
# to use OpenAI:
export OPENAI_API_KEY="your-openai-key"
export GITHUB_TOKEN="your-github-token"
```

### 2. Run locally

```bash
mvn quarkus:dev [-Dquarkus.profile=dev,[openai][gemini]]
# Server starts on port 8080
# Health check: http://localhost:8080/a2a/health
```

### 3. Run locally in console mode

```bash
mvn quarkus:dev [-Dquarkus.profile=dev,[openai][gemini]] -Drun.mode=console
```

## Deployment to Kubernetes

### 1. Build Docker image

```bash
mvn package -Dquarkus.profile=prod,[openai][gemini]
docker build -t quay.io/kevindubois/kubernetes-agent:latest .
docker push quay.io/kevindubois/kubernetes-agent:latest
```

Or directly with Quarkus:

```bash
quarkus image push --also-build
or
mvn quarkus:image-push -Dquarkus.container-image.build=true -DquarkusRegistryClient=true
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
# Update image in deployment/deployment.yaml if needed
kubectl apply -k deployment/
```

or directly with quarkus:

```bash
quarkus deploy
or
mvn quarkus:deploy
```

### 4. Verify deployment

```bash
# Check pods
kubectl get pods -n argo-rollouts | grep kubernetes-agent

# Check logs
kubectl logs -f deployment/kubernetes-agent -n argo-rollouts

# Test health endpoint
kubectl port-forward -n argo-rollouts svc/kubernetes-agent 8080:8080
curl http://localhost:8080/a2a/health
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
$ java -jar kubernetes-agent.jar console

You > Debug pod my-app-canary in namespace production

Agent > Analyzing pod my-app-canary in namespace production...
[Agent gathers debug info, logs, events...]

Root Cause: Container crashloop due to OOMKilled - memory limit too low

Recommendation:
1. Increase memory limit from 256Mi to 512Mi
2. Add resource requests to prevent overcommitment
3. Review memory usage patterns in logs
```

### A2A Integration

The agent exposes a REST API for other systems to use:

**Endpoint**: `POST /a2a/analyze`

**Request**:
```json
{
	"userId": "argo-rollouts",
	"prompt": "Analyze canary deployment issue. Namespace: rollouts-test-system, Pod: canary-demo-xyz",
	"context": {
		"namespace": "rollouts-test-system",
		"podName": "canary-demo-xyz",
		"stableLogs": "...",
		"canaryLogs": "..."
	}
}
```

**Response**:
```json
{
	"analysis": "Detailed analysis text...",
	"rootCause": "Identified root cause",
	"remediation": "Suggested fixes",
	"prLink": "https://github.com/owner/repo/pull/123",
	"promote": false,
	"confidence": 85
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
						model: gemini-2.0-flash-exp
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
| `GOOGLE_API_KEY` | Yes | Google Gemini API key |
| `GITHUB_TOKEN` | Yes | GitHub personal access token |
| `GIT_USERNAME` | No | Git commit username (default: "kubernetes-agent") |
| `GIT_EMAIL` | No | Git commit email (default: "agent@example.com") |
| `K8S_AGENT_URL` | No | Agent URL for plugin (default: http://kubernetes-agent.argo-rollouts.svc.cluster.local:8080) |

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
kubectl logs deployment/kubernetes-agent -n argo-rollouts

# Common issues:
# 1. Missing API keys - check secrets
# 2. Invalid service account - check RBAC
# 3. Out of memory - increase limits
```

### Health check failing

```bash
# Test endpoint directly
kubectl port-forward -n argo-rollouts svc/kubernetes-agent 8080:8080
curl http://localhost:8080/a2a/health

# Should return:
# {"status":"healthy","agent":"KubernetesAgent","version":"1.0.0"}
```

### PR creation failing

```bash
# Check GitHub token permissions:
# - repo (full control)
# - workflow (if modifying GitHub Actions)

# Check logs for git errors:
kubectl logs deployment/kubernetes-agent -n argo-rollouts | grep -i "git\|github"
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
├── src/main/java/com/google/adk/samples/agents/k8sagent/
│   ├── KubernetesAgent.java          # Main agent
│   ├── tools/                        # K8s debugging tools
│   ├── remediation/                  # Git and GitHub operations
│   └── a2a/                          # A2A REST controllers
├── deployment/                       # Kubernetes manifests
├── pom.xml                           # Maven config
└── Dockerfile                        # Container image
```

### Running Tests

```bash
mvn test
```

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

## Support

For issues or questions:
- **GitHub Issues**: [https://github.com/carlossg/kubernetes-agent/issues](https://github.com/carlossg/kubernetes-agent/issues)
- **Documentation**: See the `docs/` directory and inline README files

## Development Documentation

Additional development documentation is available in the `docs/development/` directory:
- Maven plugin integration and logging
- Debug mode configuration
- Testing strategies
- Rate limiting and fork modes


