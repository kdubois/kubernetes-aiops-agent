# Maven Plugin Logging Configuration - Final Analysis

## Summary

After implementing logging configuration support in the ADK Maven plugin (`WebMojo.java`), we discovered that **Spring Boot's logging initialization happens in the Maven plugin's classloader context**, which prevents our logging configuration from taking effect.

## What We Implemented

✅ **Three new Maven parameters**:
- `-DloggingLevel` - Root logger level
- `-DloggingLevels` - Package-specific levels (comma-separated)
- `-Dlogging Config` - Custom logback configuration file

✅ **Three configuration methods**:
1. System properties (`System.setProperty()`)
2. Spring Boot default properties (`app.setDefaultProperties()`)  
3. Spring Boot command-line arguments (`app.run(args)`)

## Why It Doesn't Work

### The Root Cause
When `mvn google-adk:web` runs, Spring Boot initializes in the **Maven plugin's classloader**, not in the target application's classloader. Spring Boot's logging system:

1. Initializes before reading `application.properties`
2. Detects the main class as "MavenCli" instead of "AdkWebServer"
3. Uses Maven's logging infrastructure (SLF4J → Maven's logging)
4. Ignores logging configuration set via properties or arguments

###Evidence
```
[INFO] Starting MavenCli v3.9.5 using Java 25...
```
Should be:
```
[INFO] Starting AdkWebServer v...
```

## Why Standalone JAR Works

When running `java -jar kubernetes-agent-1.0.0-SNAPSHOT.jar`:

✅ Spring Boot is the **primary application**
✅ Logging initializes in the correct classloader context
✅ `application.properties` and `logback-spring.xml` are loaded correctly
✅ DEBUG logs appear as expected

```
2025-10-04T20:04:41.102 DEBUG --- [main] o.c.adk.agents.k8sagent.KubernetesAgent : Tools: [...]
=== Executing Tool: debug_kubernetes_pod ===
```

## Potential Solutions (Not Implemented)

###1. Modify `AdkWebServer.java` to Force Logging Reconfiguration

Add to `AdkWebServer.main()`:
```java
public static void main(String[] args) {
    // Force logging reconfiguration if properties are set
    configureLoggingFromSystemProperties();
    
    System.setProperty(
        "org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE", String.valueOf(10 * 1024 * 1024));
    SpringApplication.run(AdkWebServer.class, args);
    log.info("AdkWebServer application started successfully.");
}

private static void configureLoggingFromSystemProperties() {
    // Check for logging.level.* system properties and apply them
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    // ... reconfigure logback programmatically
}
```

**Pros**: Would work for both Maven plugin and standalone JAR
**Cons**: Requires modifying ADK framework code

### 2. Fork the Process

Instead of calling `SpringApplication.run()` directly, fork a new Java process:

```java
ProcessBuilder pb = new ProcessBuilder(
    "java",
    "-jar", projectJar,
    "--logging.level.org.csanchez.adk.agents.k8sagent=DEBUG",
    "--logging.level.com.google.adk=DEBUG"
);
pb.inheritIO();
Process process = pb.start();
```

**Pros**: Complete classloader isolation
**Cons**: More complex, harder to debug, requires packaging the agent as a JAR first

### 3. Use Logback's JoranConfigurator Programmatically

In `WebMojo.java`, after Spring Boot starts, programmatically reconfigure logback:

```java
LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
loggerContext.reset();
// Apply custom configuration
Logger logger = loggerContext.getLogger("org.csanchez.adk.agents.k8sagent");
logger.setLevel(Level.DEBUG);
```

**Pros**: Can be done in the Maven plugin
**Cons**: Fragile, Spring Boot might reconfigure again, race conditions

## Recommended Solution

### For Development: Use Standalone JAR ✅

```bash
cd kubernetes-agent
mvn clean package -DskipTests
java -jar target/kubernetes-agent-1.0.0-SNAPSHOT.jar
```

**Benefits**:
- ✅ DEBUG logs work perfectly
- ✅ No Maven plugin complexity
- ✅ Faster iteration (no plugin rebuild needed)
- ✅ Same A2A REST API as Maven plugin
- ✅ Runs on `localhost:8080`

### For Web UI: Use Standalone JAR + Port Forward

If you need the ADK Web UI for debugging:

1. Deploy the agent to Kubernetes (already has Web UI)
2. Port-forward to local machine:
   ```bash
   kubectl port-forward -n argo-rollouts deployment/kubernetes-agent 8080:8080
   ```
3. Access UI at `http://localhost:8080`

### For Testing: Use `test-agent.sh`

The test script already supports both modes:

```bash
#Test with Kubernetes agent
./test-agent.sh k8s

# Test with local agent (standalone JAR)
./test-agent.sh local
```

## Files Modified in Maven Plugin

**`adk-java/maven_plugin/src/main/java/com/google/adk/maven/WebMojo.java`**:

1. ✅ Added `loggingLevel`, `loggingLevels`, `loggingConfig` parameters
2. ✅ Added `setupLoggingConfiguration()` method
3. ✅ Added `buildSpringBootArgs()` method
4. ✅ Added `configureSpringBootEnvironment()` method
5. ✅ Updated `logConfiguration()` to display logging settings

**Status**: Implementation complete, but doesn't work due to classloader/initialization order issues.

## Conclusion

The Maven plugin logging configuration **cannot be made to work** without either:
1. Modifying the ADK framework (`AdkWebServer.java`)
2. Using process forking instead of direct `SpringApplication.run()`

Since modifying the ADK framework is out of scope, the **recommended solution is to use the standalone JAR** for development, which already has perfect DEBUG logging support.

## Action Items

### Immediate
- ✅ Use standalone JAR for development with DEBUG logging
- ✅ Keep Maven plugin changes for future reference
- ✅ Document this limitation

### Future (Optional)
- [ ] Propose ADK framework change to support programmatic logging reconfiguration
- [ ] Implement process-forking approach in Maven plugin as alternative
- [ ] Add `--fork` flag to Maven plugin to enable subprocess mode

---

## Quick Reference

### Standalone JAR (Works Now) ✅
```bash
cd kubernetes-agent
mvn clean package -DskipTests
java -jar target/kubernetes-agent-1.0.0-SNAPSHOT.jar
# Logs appear at DEBUG level for configured packages
# Access API at http://localhost:8080
```

### Maven Plugin (Doesn't Work for DEBUG Logs) ❌
```bash
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -DloggingLevels=org.csanchez.adk.agents.k8sagent=DEBUG,com.google.adk=DEBUG
# Configuration is read but DEBUG logs don't appear
# Access Web UI at http://localhost:8000
```

### Recommended Workflow
1. Develop/debug with standalone JAR (DEBUG logs work)
2. Test integration with `./test-agent.sh local`
3. Deploy to Kubernetes when ready
4. Use Web UI via port-forward if needed


