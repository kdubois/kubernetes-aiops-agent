# Testing the Kubernetes AI Agent

This guide explains how to test the kubernetes-agent after deployment.

## Prerequisites

1. Agent deployed in cluster: `kubectl apply -k deployment/`
2. `argo-rollouts` secret with credentials configured
3. Port-forward to access the agent: 
   ```bash
   kubectl port-forward -n argo-rollouts svc/kubernetes-agent 8080:8080
   ```

## Quick Test

### 1. Health Check

```bash
curl http://localhost:8080/a2a/health
```

Expected response:
```json
{
  "status": "healthy",
  "agent": "KubernetesAgent",
  "version": "1.0.0"
}
```

### 2. Simple Analysis Test

Run the basic test script:

```bash
./test-agent.sh
```

This tests the agent's ability to receive and process requests.

## Testing with GitHub PR Creation

### Setup

1. **Create a test repository** on GitHub (or use an existing one)
2. **Ensure GITHUB_TOKEN** is configured in the `argo-rollouts` secret
3. **Update the script** with your repository:

```bash
export GITHUB_REPO="your-username/your-repo"
export AGENT_URL="http://localhost:8080"
```

### Run the GitHub Test

```bash
./test-with-github.sh
```

This script:
- Simulates a realistic canary deployment failure (database connection issue)
- Sends pod logs, events, and metrics to the agent
- Asks the agent to analyze and create a GitHub PR with a fix
- Reports if a PR was successfully created

### Expected Behavior

The agent will:
1. ✅ Analyze the pod logs and identify the database connection issue
2. ✅ Recognize that `DATABASE_HOST=localhost` should be a service name
3. ✅ Create a GitHub PR with the fix to the deployment YAML
4. ✅ Include root cause analysis and testing recommendations in the PR

## Manual Testing with curl

You can also test manually with curl:

```bash
curl -X POST http://localhost:8080/a2a/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-user",
    "prompt": "Analyze this canary failure and suggest a fix",
    "context": {
      "namespace": "default",
      "podName": "my-app-canary-abc123",
      "rolloutName": "my-app",
      "logs": "Error: Cannot connect to database\nConnection refused",
      "repoUrl": "https://github.com/your-username/your-repo"
    }
  }'
```

## Viewing Agent Logs

Monitor the agent's activity:

```bash
kubectl logs -n argo-rollouts -l app=kubernetes-agent -f
```

## ADK Web UI

Access the ADK web interface:

1. Port-forward: `kubectl port-forward -n argo-rollouts svc/kubernetes-agent 8080:8080`
2. Open browser: http://localhost:8080
3. Interact with the KubernetesAgent through the web UI

## Integration Testing

For integration testing with rollouts-plugin-metric-ai:

1. Deploy a sample application with Argo Rollouts
2. Configure an Analysis with the AI plugin
3. Trigger a canary deployment
4. The plugin will automatically call the agent if issues are detected

See `../rollouts-plugin-metric-ai/config/rollouts-examples/` for example rollouts.

## Troubleshooting

### Agent not responding
- Check pod status: `kubectl get pods -n argo-rollouts -l app=kubernetes-agent`
- Check logs: `kubectl logs -n argo-rollouts -l app=kubernetes-agent`
- Verify port-forward is active

### GitHub PR not created
- Verify GITHUB_TOKEN is set in secret: `kubectl get secret argo-rollouts -n argo-rollouts -o yaml`
- Check agent logs for GitHub API errors
- Ensure the repo URL is accessible and you have write permissions

### Analysis times out
- The AI analysis can take 30-60 seconds
- Check GOOGLE_API_KEY is configured
- Review agent logs for API errors

## Sample Test Repositories

You can use these public repos for testing (fork them first):
- https://github.com/argoproj/rollouts-demo
- Or create a simple repo with a deployment YAML

## Next Steps

- Integrate with rollouts-plugin-metric-ai
- Configure webhook for automatic PR reviews
- Set up monitoring and alerting

