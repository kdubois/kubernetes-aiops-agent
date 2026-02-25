# Agent Instructions for Kubernetes Agent

This document provides comprehensive instructions for AI assistants working on the Kubernetes Agent project. It covers development workflows, testing procedures, deployment strategies, and coding standards.

## Project Overview

The Kubernetes Agent is a Quarkus-based AI agent that uses LangChain4j's declarative agentic framework to analyze Kubernetes rollouts, diagnose issues, and provide automated remediation through GitHub PRs.

**Key Technologies:**
- Quarkus 3.x with LangChain4j
- Declarative agent orchestration (`@Agent`, `@LoopAgent`, `@SequenceAgent`)
- Kubernetes client for cluster interaction
- JGit for Git operations
- GitHub REST API for PR creation
- A2A (Agent-to-Agent) communication protocol

## Architecture Principles

When working on this project, always follow these principles:

1. **Declarative Over Programmatic**: Use annotations (`@Agent`, `@LoopAgent`, `@SequenceAgent`) instead of programmatic API
2. **Responsibility-Based Design**: Each agent has a single, clear responsibility
3. **Native Quarkus Integration**: Leverage Quarkus CDI and LangChain4j features
4. **Simple and Clean**: Avoid unnecessary complexity
5. **Type Safety**: Use records for data contracts between agents

## Prerequisites

- Java 17+
- Maven 3.8+
- kubectl configured
- Google API Key (Gemini) or OpenAI API Key
- GitHub Personal Access Token
- for end to end usage/testing, a Kubernetes cluster with Argo Rollouts with the rollouts-plugin-metric-ai plugin installed (https://github.com/kdubois/rollouts-plugin-metric-ai)

## Development Workflow

### 1. Local Development Setup

```bash
# Set environment variables
export GOOGLE_API_KEY="your-google-api-key"
# OR for OpenAI:
export OPENAI_API_KEY="your-openai-key"
export GITHUB_TOKEN="your-github-token"

# Run in dev mode (hot reload enabled)
mvn quarkus:dev -Dquarkus.profile=dev,gemini

# Run in console mode for interactive testing
mvn quarkus:dev -Dquarkus.profile=dev,gemini -Drun.mode=console
```

**Available Profiles:**
- `dev` - Development mode with debug logging
- `prod` - Production mode
- `gemini` - Use Google Gemini AI
- `openai` - Use OpenAI GPT models

### 2. Making Code Changes

#### Agent Development

When creating or modifying agents:

```java
@Agent
@RegisterAiService
public interface MyAgent {
    
    @SystemMessage("""
        You are a specialized agent responsible for [specific task].
        
        Guidelines:
        - [Guideline 1]
        - [Guideline 2]
        """)
    String execute(String input);
}
```

**Key Points:**
- Use `@Agent` for simple agents
- Use `@LoopAgent` for retry logic with exit conditions
- Use `@SequenceAgent` for orchestrating multiple agents
- Always provide clear system messages
- Return structured data (records) when possible

#### Tool Development

When adding Kubernetes tools:

```java
@ToolBox
public class MyK8sTool {
    
    @Tool("Description of what this tool does")
    public String myTool(
        @P("namespace") String namespace,
        @P("resourceName") String resourceName
    ) {
        // Implementation
        return result;
    }
}
```

**Tool Guidelines:**
- Use `@Tool` annotation with clear descriptions
- Use `@P` for parameter documentation
- Return string results (LLM-friendly)
- Handle errors gracefully with try-catch
- Log tool invocations for debugging

### 3. Testing Changes

#### Unit Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=AnalysisAgentTest

# Run with coverage
mvn verify
```

#### Integration Testing

```bash
# Test with local agent
./test-agent.sh local

# Test with Kubernetes deployment
./test-agent.sh k8s

# Custom local URL
LOCAL_URL=http://localhost:9090 ./test-agent.sh local
```

#### Console Testing

```bash
# Interactive testing
./run-console.sh

# Then interact with the agent:
You > Debug pod my-app-canary in namespace production
```

### 4. Building and Packaging

```bash
# Build JAR
mvn clean package -Dquarkus.profile=prod,gemini

# Build Docker image
docker build -t quay.io/kevindubois/kubernetes-agent:latest .

# Build with Quarkus
mvn quarkus:image-build -Dquarkus.profile=prod,gemini

# Build and push
mvn quarkus:image-push -Dquarkus.container-image.build=true
```

## Deployment Workflow

### 1. Local Kubernetes (Kind)

```bash
# Create Kind cluster
kind create cluster --name k8s-agent-test

# Build and load image
docker build -t quay.io/kevindubois/kubernetes-agent:latest .
kind load docker-image quay.io/kevindubois/kubernetes-agent:latest --name k8s-agent-test

# Create secrets
cp deployment/secret.yaml.template deployment/secret.yaml
# Edit secret.yaml with your keys
kubectl apply -f deployment/secret.yaml

# Deploy agent
kubectl apply -k deployment/

# Verify deployment
kubectl get pods -n argo-rollouts | grep kubernetes-agent
kubectl logs -f deployment/kubernetes-agent -n argo-rollouts
```

### 2. Production Deployment

```bash
# Build multi-arch image
docker buildx build --platform linux/amd64,linux/arm64 \
  -t quay.io/kevindubois/kubernetes-agent:v1.0.0 \
  --push .

# Update deployment with new version
kubectl set image deployment/kubernetes-agent \
  kubernetes-agent=quay.io/kevindubois/kubernetes-agent:v1.0.0 \
  -n argo-rollouts

# Monitor rollout
kubectl rollout status deployment/kubernetes-agent -n argo-rollouts
```

### 3. Quick Development Cycle

```bash
#!/bin/bash
# build-and-deploy.sh

# Build image
mvn clean package -Dquarkus.profile=prod,gemini
docker build -t quay.io/kevindubois/kubernetes-agent:latest .

# Load into Kind
kind load docker-image quay.io/kevindubois/kubernetes-agent:latest --name k8s-agent-test

# Restart deployment
kubectl rollout restart deployment/kubernetes-agent -n argo-rollouts
kubectl rollout status deployment/kubernetes-agent -n argo-rollouts

# Watch logs
kubectl logs -f deployment/kubernetes-agent -n argo-rollouts
```

## Code Style and Standards

### Java Code Style

```java
// Use records for data transfer objects
public record AnalysisResult(
    boolean promote,
    int confidence,
    String analysis,
    String rootCause,
    String remediation,
    String prLink
) {}

// Use declarative agents
@Agent
public interface AnalysisAgent {
    
    @SystemMessage("""
        You are an agent that is able to analyze results from Kubernetes logs and metrics
        """)
    @UserMessage("""
        Analyze the diagnostic data and determine if the canary should be promoted.
        
        Return a JSON object with:
        - promote: boolean
        - confidence: 0-100
        - analysis: detailed analysis
        - rootCause: identified root cause
        - remediation: suggested fix
        """)
    AnalysisResult analyze(String diagnosticData);
}

// Use dependency injection
@ApplicationScoped
public class MyService {
    
    @Inject
    KubernetesWorkflow workflow;
    
    public AnalysisResult analyze(String message) {
        return workflow.execute(UUID.randomUUID().toString(), message);
    }
}
```

### System Message Guidelines

When writing user and system messages for agents:

1. **Be Specific**: Clearly define the agent's role and responsibilities
2. **Provide Structure**: Specify expected output format (JSON, text, etc.)
3. **Set Constraints**: Define what the agent should and shouldn't do
4. **Give Examples**: Include examples when helpful
5. **Use Markdown**: Format for readability

Example:
```java
@SystemMessage("""
    You are a Kubernetes diagnostic agent responsible for gathering cluster information.
    
    Your role:
    - Use available tools to gather pod status, logs, events, and metrics
    - Focus on the specified namespace and pod
    - Collect comprehensive diagnostic data
    - Do NOT analyze or make decisions - only gather data
    
    Available tools:
    - debugPod: Get pod status and conditions
    - getLogs: Retrieve container logs
    - getEvents: Fetch cluster events
    - getMetrics: Check resource usage
    - inspectResources: View related resources
    
    Return all gathered information as a structured report.
    """)
```

### Error Handling

```java
// Use try-catch with proper logging
@Tool("Get pod logs")
public String getLogs(
    String namespace,
    String podName
) {
    try {
        return kubernetesClient
            .pods()
            .inNamespace(namespace)
            .withName(podName)
            .getLog();
    } catch (Exception e) {
        logger.error("Failed to get logs for pod {}/{}", namespace, podName, e);
        return "Error retrieving logs: " + e.getMessage();
    }
}
```

### Logging Standards

```java
// Use appropriate log levels
logger.debug("Starting analysis for pod {}/{}", namespace, podName);
logger.info("Analysis completed: promote={}, confidence={}", result.promote(), result.confidence());
logger.warn("Low confidence score: {}", result.confidence());
logger.error("Analysis failed", exception);

// Include context in log messages
logger.info("Tool invocation: {} with params: {}", toolName, params);
```

## Debugging and Troubleshooting

### View Agent Logs

```bash
# Follow logs in real-time
kubectl logs -f deployment/kubernetes-agent -n argo-rollouts

# Filter for specific patterns
kubectl logs deployment/kubernetes-agent -n argo-rollouts | grep -i "analysis\|error\|tool"

# View logs with timestamps
kubectl logs deployment/kubernetes-agent -n argo-rollouts --timestamps=true

# Get recent logs
kubectl logs deployment/kubernetes-agent -n argo-rollouts --tail=100
```

### Common Issues

#### Agent Not Starting

```bash
# Check pod status
kubectl get pods -n argo-rollouts | grep kubernetes-agent

# Describe pod for events
kubectl describe pod -n argo-rollouts -l app=kubernetes-agent

# Check for missing secrets
kubectl get secret kubernetes-agent-secret -n argo-rollouts

# Verify RBAC permissions
kubectl auth can-i get pods --as=system:serviceaccount:argo-rollouts:kubernetes-agent
```

#### API Key Issues

```bash
# Verify secret exists and has correct keys
kubectl get secret kubernetes-agent-secret -n argo-rollouts -o yaml

# Check if environment variables are set in pod
kubectl exec -n argo-rollouts deployment/kubernetes-agent -- env | grep -E "GOOGLE_API_KEY|OPENAI_API_KEY|GITHUB_TOKEN"
```

#### Tool Execution Failures

```bash
# Check RBAC permissions for Kubernetes operations
kubectl auth can-i list pods --as=system:serviceaccount:argo-rollouts:kubernetes-agent -n default

# Test tool manually
kubectl exec -n argo-rollouts deployment/kubernetes-agent -- \
  curl -X POST http://localhost:8080/a2a/analyze \
  -H "Content-Type: application/json" \
  -d '{"userId":"test","prompt":"Debug pod test in namespace default"}'
```

#### GitHub PR Creation Failures

```bash
# Check GitHub token permissions (needs repo scope)
# View logs for git/GitHub errors
kubectl logs deployment/kubernetes-agent -n argo-rollouts | grep -i "git\|github\|pr"

# Verify git configuration
kubectl exec -n argo-rollouts deployment/kubernetes-agent -- \
  git config --list
```

### Health Check

```bash
# Port forward to agent service
kubectl port-forward -n argo-rollouts svc/kubernetes-agent 8080:8080

# Test health endpoint
curl http://localhost:8080/a2a/health

# Expected response:
# {"status":"healthy","agent":"KubernetesAgent","version":"1.0.0"}
```

## Testing Strategies

### 1. Unit Testing Agents

```java
@QuarkusTest
class AnalysisAgentTest {
    
    @Inject
    AnalysisAgent agent;
    
    @Test
    void testAnalysis() {
        String diagnosticData = """
            Pod Status: Running
            Logs: Application started successfully
            Events: No warnings
            """;
        
        AnalysisResult result = agent.analyze(diagnosticData);
        
        assertThat(result.promote()).isTrue();
        assertThat(result.confidence()).isGreaterThan(70);
    }
}
```

### 2. Integration Testing Workflow

```java
@QuarkusTest
class KubernetesWorkflowTest {
    
    @Inject
    KubernetesWorkflow workflow;
    
    @Test
    void testCompleteWorkflow() {
        String memoryId = UUID.randomUUID().toString();
        String message = "Analyze pod test-pod in namespace default";
        
        AnalysisResult result = workflow.execute(memoryId, message);
        
        assertThat(result).isNotNull();
        assertThat(result.analysis()).isNotEmpty();
    }
}
```

### 3. E2E Testing with Real Cluster

```bash
# Deploy test application
kubectl apply -f test/fixtures/test-app.yaml

# Trigger analysis
./test-agent.sh k8s

# Verify results
kubectl get analysisrun -n default
```

## Performance Considerations

### Memory Management

```yaml
# Recommended resource limits
resources:
  requests:
    memory: "512Mi"
    cpu: "250m"
  limits:
    memory: "2Gi"
    cpu: "1000m"
```

### Rate Limiting

The agent includes built-in rate limiting for API calls:

```java
// Configured in application.properties
quarkus.langchain4j.openai.timeout=60s
quarkus.langchain4j.openai.max-retries=3
```

### Tool Call Optimization

```java
// Use ToolCallLimiter to prevent excessive tool calls
@ApplicationScoped
public class ToolCallLimiter {
    private static final int MAX_TOOL_CALLS = 20;
    
    public void checkLimit(int currentCalls) {
        if (currentCalls >= MAX_TOOL_CALLS) {
            throw new IllegalStateException("Tool call limit exceeded");
        }
    }
}
```

## Integration with Argo Rollouts

### Analysis Template Configuration

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
            analysisMode: agent
            namespace: "{{args.namespace}}"
            podName: "{{args.canary-pod}}"
            agentUrl: "http://kubernetes-agent.argo-rollouts.svc.cluster.local:8080"
```

### Testing Integration

```bash
# Deploy test rollout
kubectl apply -f test/fixtures/canary-rollout.yaml

# Trigger canary deployment
kubectl argo rollouts set image canary-demo \
  canary-demo=argoproj/rollouts-demo:yellow \
  -n default

# Monitor analysis
kubectl argo rollouts get rollout canary-demo -n default --watch

# View analysis run
kubectl get analysisrun -n default
kubectl describe analysisrun <name> -n default
```

## Documentation Standards

When adding new features or modifying existing code:

1. **Update ARCHITECTURE.md**: Document architectural changes
2. **Update README.md**: Update user-facing documentation
3. **Add Javadoc**: Document public APIs and complex logic
4. **Update this file**: Add new workflows or procedures
5. **Add Examples**: Include code examples for new features

## Checklist for New Features

- [ ] Code is as simple, clean and concise as possible
- [ ] Code follows declarative agent pattern from Quarkus LangChain4j (https://github.com/quarkiverse/quarkus-langchain4j)
- [ ] System and user messages are clear and specific
- [ ] Error handling is implemented
- [ ] Logging is appropriate
- [ ] Unit tests are added
- [ ] Integration tests pass
- [ ] Documentation is updated
- [ ] ARCHITECTURE.md reflects changes
- [ ] README.md is updated if needed
- [ ] Code is formatted (mvn fmt:format)
- [ ] No compiler warnings
- [ ] Unit tests are fast

## Resources

- [Quarkus Documentation](https://quarkus.io/guides/)
- [LangChain4j Documentation](https://docs.langchain4j.dev/)
- [Quarkus LangChain4j Extension Documentation](https://docs.quarkiverse.io/quarkus-langchain4j/dev/)
- [Quarkus LangChain4j Source Code](https://github.com/quarkiverse/quarkus-langchain4j)
- [Argo Rollouts Documentation](https://argo-rollouts.readthedocs.io/)
- [Kubernetes Client Documentation](https://quarkus.io/guides/kubernetes-client)
- [JGit Documentation](https://www.eclipse.org/jgit/)

## Support and Contribution

For questions or issues:
- Check existing GitHub issues
- Review ARCHITECTURE.md for design decisions
- Consult README.md for usage examples
- Ask in project discussions

When contributing:
- Follow the code style guidelines
- Add tests for new features
- Update documentation
- Keep commits focused, short and well-described
- Reference issues in commit messages