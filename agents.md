# Kubernetes Agent Development Guide

AI assistant instructions for the Kubernetes Agent project. This is an experimental project - prioritize simplicity over backwards compatibility.

## Overview

Quarkus-based AI agent using LangChain4j's declarative framework to analyze Kubernetes rollouts, diagnose issues, and create automated remediation PRs.

**Stack:** Quarkus 3.x, LangChain4j, Kubernetes client, JGit, GitHub REST API, A2A protocol

**Principles:**
1. Use declarative annotations (`@Agent`, `@LoopAgent`, `@SequenceAgent`)
2. Single responsibility per agent
3. Leverage Quarkus CDI and LangChain4j
4. Keep it simple
5. Use records for type-safe data contracts

## Prerequisites

Java 17+, Maven 3.8+, kubectl, Google/OpenAI API key, GitHub token, Kubernetes cluster with Argo Rollouts + rollouts-plugin-metric-ai

## Development

### Local Setup

```bash
export GOOGLE_API_KEY="key"  # or OPENAI_API_KEY
export GITHUB_TOKEN="token"

mvn quarkus:dev -Dquarkus.profile=dev,gemini
mvn quarkus:dev -Dquarkus.profile=dev,gemini -Drun.mode=console  # Interactive
```

**Profiles:** `dev`, `prod`, `gemini`, `openai`

### Agent Pattern

@Agent
@RegisterAiService
public interface MyAgent {
    @SystemMessage("You are a specialized agent for [task]. Guidelines: ...")
    String execute(String input);
}
```

Use `@LoopAgent` for retries, `@SequenceAgent` for orchestration. Return records when possible.

### Tool Pattern

@ToolBox
public class MyK8sTool {
    @Tool("Tool description")
    public String myTool(@P("namespace") String ns, @P("name") String name) {
        try {
            // Implementation
            return result;
        } catch (Exception e) {
            logger.error("Tool failed", e);
            return "Error: " + e.getMessage();
        }
    }
}
```

### Testing

mvn test
mvn test -Dtest=AnalysisAgentTest
./test-agent.sh local  # or k8s
./run-console.sh       # Interactive
```

### Building

mvn clean package -Dquarkus.profile=prod,gemini
docker build -t quay.io/kevindubois/kubernetes-agent:latest .
mvn quarkus:image-push -Dquarkus.container-image.build=true
```

## Deployment

### Kind (Local)

kind create cluster --name k8s-agent-test
docker build -t quay.io/kevindubois/kubernetes-agent:latest .
kind load docker-image quay.io/kevindubois/kubernetes-agent:latest --name k8s-agent-test
cp deployment/secret.yaml.template deployment/secret.yaml  # Edit with keys
kubectl apply -f deployment/secret.yaml
kubectl apply -k deployment/
kubectl logs -f deployment/kubernetes-agent -n argo-rollouts
```

### Production
```bash
docker buildx build --platform linux/amd64,linux/arm64 -t quay.io/kevindubois/kubernetes-agent:v1.0.0 --push .
kubectl set image deployment/kubernetes-agent kubernetes-agent=quay.io/kevindubois/kubernetes-agent:v1.0.0 -n argo-rollouts
kubectl rollout status deployment/kubernetes-agent -n argo-rollouts
```

## Code Standards

### Patterns

// Records for DTOs
public record AnalysisResult(boolean promote, int confidence, String analysis, String rootCause, String remediation, String prLink) {}

// Declarative agents with clear system messages
@Agent
public interface AnalysisAgent {
    @SystemMessage("You analyze Kubernetes logs and metrics. Return JSON: {promote, confidence, analysis, rootCause, remediation}")
    @UserMessage("Analyze: {{diagnosticData}}")
    AnalysisResult analyze(String diagnosticData);
}

// Dependency injection
@ApplicationScoped
public class MyService {
    @Inject KubernetesWorkflow workflow;
    public AnalysisResult analyze(String msg) { return workflow.execute(UUID.randomUUID().toString(), msg); }
}
```

### System Messages
Be specific, define structure, set constraints, use examples. Focus on role, tools, and output format.

### Logging
```java
logger.debug("Starting analysis for {}/{}", namespace, podName);
logger.info("Analysis: promote={}, confidence={}", result.promote(), result.confidence());
logger.error("Failed", exception);
```

## Debugging

### Logs
```bash
kubectl logs -f deployment/kubernetes-agent -n argo-rollouts
kubectl logs deployment/kubernetes-agent -n argo-rollouts | grep -i "error\|tool"
```

### Common Issues
**Not starting:** Check pod status, secrets, RBAC
**API keys:** Verify secret and env vars
**Tools failing:** Check RBAC permissions
**GitHub PRs:** Verify token has repo scope

### Health Check
```bash
kubectl port-forward -n argo-rollouts svc/kubernetes-agent 8080:8080
curl http://localhost:8080/a2a/health
```

## Performance

**Resources:** 512Mi-2Gi memory, 250m-1000m CPU
**Rate limiting:** Configured in application.properties
**Tool calls:** Max 20 per workflow (ToolCallLimiter)

## Argo Rollouts Integration

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
            analysisMode: agent
            namespace: "{{args.namespace}}"
            podName: "{{args.canary-pod}}"
            agentUrl: "http://kubernetes-agent.argo-rollouts.svc.cluster.local:8080"
```

Test: Deploy rollout, trigger canary, monitor with `kubectl argo rollouts get rollout <name> --watch`

## Feature Checklist

- [ ] Simple, clean, concise code
- [ ] Follows declarative agent pattern
- [ ] Clear system/user messages
- [ ] Error handling + logging
- [ ] Tests added and passing
- [ ] Documentation updated (ARCHITECTURE.md, README.md)
- [ ] Code formatted (`mvn fmt:format`)
- [ ] No warnings

## Resources

- [Quarkus](https://quarkus.io/guides/) | [LangChain4j](https://docs.langchain4j.dev/) | [Quarkus LangChain4j](https://docs.quarkiverse.io/quarkus-langchain4j/dev/)
- [Argo Rollouts](https://argo-rollouts.readthedocs.io/) | [Kubernetes Client](https://quarkus.io/guides/kubernetes-client) | [JGit](https://www.eclipse.org/jgit/)

## Contributing

Check GitHub issues, review ARCHITECTURE.md and README.md. Follow code style, add tests, update docs, keep commits focused.