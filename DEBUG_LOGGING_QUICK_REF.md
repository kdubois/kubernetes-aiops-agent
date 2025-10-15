# Debug Logging Quick Reference

## TL;DR - What to Do Now

### Option 1: Use Modified Maven Plugin ✅ (After Building)

```bash
cd kubernetes-agent

mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -DloggingLevels=org.csanchez.adk.agents.k8sagent=DEBUG,com.google.adk=DEBUG
```

### Option 2: Use Standalone JAR ✅ (Works Now)

```bash
cd kubernetes-agent
mvn clean package -DskipTests
java -jar target/kubernetes-agent-1.0.0-SNAPSHOT.jar
```

---

## What You'll See

### Tool Execution Logs (INFO level)
```
=== Executing Tool: debug_kubernetes_pod ===
=== Executing Tool: get_pod_logs ===
=== Executing Tool: get_kubernetes_events ===
=== Executing Tool: get_pod_metrics ===
=== Executing Tool: inspect_kubernetes_resources ===
=== Executing Tool: create_github_pr ===
```

### Agent Debug Logs (DEBUG level)
```
2025-10-04T20:04:41.102 DEBUG --- [main] o.c.adk.agents.k8sagent.KubernetesAgent : Tools: [...]
2025-10-04T20:04:41.448 DEBUG --- [main] o.c.adk.agents.k8sagent.KubernetesAgent : Running with Spring Boot v3.2.0
```

---

## Configuration Files

### `application.properties`
```properties
logging.level.root=INFO
logging.level.com.google.adk=DEBUG
logging.level.org.csanchez.adk.agents.k8sagent=DEBUG
logging.config=classpath:logback-spring.xml
```

### `logback-spring.xml`
- DEBUG level for `org.csanchez.adk.agents.k8sagent`
- DEBUG level for `com.google.adk`
- Colored console output
- Reduced noise from Spring and Kubernetes client

---

## Maven Plugin Changes Applied

**File**: `adk-java/maven_plugin/src/main/java/com/google/adk/maven/WebMojo.java`

**New Parameters**:
- `-DloggingLevel=DEBUG` - Set root logger level
- `-DloggingLevels=pkg1=DEBUG,pkg2=INFO` - Package-specific levels
- `-DloggingConfig=path/to/logback.xml` - Custom logback config

**Status**: ✅ Code changes complete, needs plugin rebuild

---

## Build Maven Plugin (One-Time Setup)

```bash
cd adk-java/maven_plugin
mvn clean install -DskipTests
```

**Note**: May require ADK parent project dependencies to be available.

---

## Which Option Should I Use?

| Scenario | Recommended Option |
|----------|-------------------|
| Quick local testing | **Standalone JAR** (Option 2) |
| Need Web UI | **Modified Maven Plugin** (Option 1) |
| CI/CD pipeline | **Standalone JAR** with A2A API |
| Production | **Standalone JAR** in Docker/K8s |

---

## Troubleshooting

### No DEBUG logs with Maven plugin?
- Check that you've rebuilt the plugin: `cd adk-java/maven_plugin && mvn clean install -DskipTests`
- Verify the configuration is logged at startup: Look for "Custom Logging Levels: ..." in output
- Try the standalone JAR to confirm logging config is correct

### Maven plugin won't build?
- Check ADK dependencies are available
- Use standalone JAR as fallback (Option 2)

### Tool execution logs not appearing?
- These are INFO level, should always appear
- Check for exceptions in the logs
- Verify the agent is actually calling the tools

---

## Files Reference

- **Configuration**: `kubernetes-agent/src/main/resources/application.properties`
- **Logback Config**: `kubernetes-agent/src/main/resources/logback-spring.xml`
- **Maven Plugin**: `adk-java/maven_plugin/src/main/java/com/google/adk/maven/WebMojo.java`
- **Tool Logging**: Each tool in `kubernetes-agent/src/main/java/org/csanchez/adk/agents/k8sagent/tools/*.java`

---

## Summary

✅ Logging configuration is ready in your agent project
✅ Maven plugin has been updated to support logging configuration
✅ Two working options available (standalone JAR works immediately)
✅ All tool execution logs are in place
✅ DEBUG logs verified working with standalone JAR

**Next Step**: Use standalone JAR for immediate debugging, build Maven plugin when needed for Web UI.


