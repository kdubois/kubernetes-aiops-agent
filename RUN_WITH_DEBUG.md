# Running Kubernetes Agent with DEBUG Logging

## Problem
The ADK Maven plugin (`mvn google-adk:web`) runs Spring Boot in an isolated classloader that doesn't honor your logging configuration files (`logback.xml`, `logback-spring.xml`, or `application.properties`).

## ✅ Solution: Run the Standalone JAR

Debug logging **WORKS** when you run the Spring Boot JAR directly:

```bash
# Build the project
mvn clean package -DskipTests

# Run with DEBUG logging enabled
java -jar target/kubernetes-agent-1.0.0-SNAPSHOT.jar
```

Your `application.properties` and `logback-spring.xml` will be properly loaded, and you'll see:

```
2025-10-04T19:58:37.989 DEBUG --- [main] o.c.adk.agents.k8sagent.KubernetesAgent : Tools: [...]
=== Executing Tool: debug_kubernetes_pod ===
=== Executing Tool: get_pod_logs ===
```

## Configuration

The debug logging is configured in:

1. **`src/main/resources/application.properties`**:
   ```properties
   logging.level.com.google.adk=DEBUG
   logging.level.org.csanchez.adk.agents.k8sagent=DEBUG
   logging.config=classpath:logback-spring.xml
   ```

2. **`src/main/resources/logback-spring.xml`**:
   - DEBUG level for `org.csanchez.adk.agents.k8sagent`
   - DEBUG level for `com.google.adk`
   - Colored console output
   - Reduced noise from Spring and Kubernetes client

## Tool Execution Logging

Each tool logs when it's executed (INFO level):
- `=== Executing Tool: debug_kubernetes_pod ===`
- `=== Executing Tool: get_pod_logs ===`
- `=== Executing Tool: get_kubernetes_events ===`
- `=== Executing Tool: get_pod_metrics ===`
- `=== Executing Tool: inspect_kubernetes_resources ===`
- `=== Executing Tool: create_github_pr ===`

## Why Doesn't `mvn google-adk:web` Work?

The ADK Maven plugin:
1. Runs in Maven's classloader
2. Creates a new Spring Boot application in a child classloader
3. The Spring Boot logging system initializes BEFORE reading `application.properties`
4. System properties passed via `-D` don't propagate to the child classloader
5. The `AdkWebServer` class (from the ADK framework) has its own logging configuration

This is a limitation of the ADK Maven plugin's architecture.

## Alternative: Web UI with Standalone JAR

If you need the web UI AND debug logging, you could potentially:
1. Extract the `AdkWebServer` class from the ADK library
2. Customize it with your logging configuration
3. Run it as a standalone Spring Boot application

However, the simplest approach is to use the A2A REST API (which the standalone JAR provides) for testing.


