# Maven Plugin Fork Mode - Complete Solution ✅

## Overview

The ADK Maven plugin now supports a **fork mode** that runs the Spring Boot application in a separate JVM process, similar to Maven Surefire plugin. This solves the logging configuration issue and provides complete classloader isolation.

## The Problem (Solved)

When running `mvn google-adk:web` without fork mode:
- Spring Boot initializes in the Maven plugin's classloader
- Logging configuration cannot be applied
- DEBUG logs don't appear

## The Solution: Fork Mode

With fork mode enabled (`-Dfork=true`):
- ✅ Spring Boot runs in a separate JVM process
- ✅ Complete classloader isolation
- ✅ Logging configuration works perfectly
- ✅ DEBUG logs appear as expected
- ✅ JVM options can be customized

---

## Usage

### Basic Usage with Fork Mode

```bash
cd kubernetes-agent

# Enable DEBUG logging with fork mode
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -Dfork=true \
  -DloggingLevels=org.csanchez.adk.agents.k8sagent=DEBUG,com.google.adk=DEBUG
```

**Output**:
```
20:37:01,735 |-INFO in ch.qos.logback.classic.model.processor.LoggerModelHandler - Setting level of logger [org.csanchez.adk.agents.k8sagent] to DEBUG
2025-10-04T20:37:01.926 DEBUG --- [main] c.google.adk.maven.WebMojo$ForkLauncher : Running with Spring Boot v3.2.0
2025-10-04T20:37:02.423  INFO --- [main] c.g.adk.web.controller.AgentController  : AgentController initialized with 1 dynamic agents: [kubernetes]
```

### With Custom JVM Arguments

```bash
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -Dfork=true \
  -DjvmArgs="-Xmx2g -Xms512m" \
  -DloggingLevels=org.csanchez.adk.agents.k8sagent=DEBUG,com.google.adk=DEBUG
```

### Configure in pom.xml

```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.google.adk</groupId>
            <artifactId>google-adk-maven-plugin</artifactId>
            <version>0.3.1-SNAPSHOT</version>
            <configuration>
                <agents>org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE</agents>
                <fork>true</fork>
                <jvmArgs>-Xmx2g -Xms512m</jvmArgs>
                <loggingLevels>org.csanchez.adk.agents.k8sagent=DEBUG,com.google.adk=DEBUG</loggingLevels>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Then run simply:
```bash
mvn google-adk:web
```

---

## New Parameters

### `fork`
- **Property**: `-Dfork`
- **Type**: `boolean`
- **Default**: `false`
- **Description**: Fork the Spring Boot application in a separate JVM process
- **Benefits**:
  - Logging configuration works correctly
  - Complete classloader isolation
  - JVM options can be customized
  - Better memory management

### `jvmArgs`
- **Property**: `-DjvmArgs`
- **Type**: `String`
- **Default**: `null`
- **Description**: JVM arguments to pass to the forked process (only used when `fork=true`)
- **Example**: `-DjvmArgs="-Xmx2g -Xms512m -XX:+UseG1GC"`

---

## How It Works

### Architecture

**Without Fork Mode** (Original):
```
Maven JVM
└── Maven Plugin Classloader
    └── Spring Boot (in same JVM)
        └── Your Agent
```
**Problem**: Logging initializes in Maven's context

**With Fork Mode** (New):
```
Maven JVM                    Forked JVM
└── Maven Plugin       →     └── Spring Boot
                                 └── Your Agent
```
**Solution**: Spring Boot runs in isolated JVM

### Implementation Details

1. **WebMojo.java**:
   - New `runInForkedProcess()` method
   - Builds Java command with full classpath
   - Passes logging configuration as system properties
   - Uses `ForkLauncher` as main class

2. **ForkLauncher** (Inner Class):
   - Static inner class in `WebMojo`
   - Loads agent provider from system properties
   - Initializes Spring Boot with agent
   - Runs in forked JVM process

3. **Process Management**:
   - Shutdown hook for graceful termination
   - Output redirected to Maven console
   - Exit code propagation

---

## Comparison: Fork vs Non-Fork

| Feature | Without Fork | With Fork (`-Dfork=true`) |
|---------|-------------|---------------------------|
| **DEBUG Logs** | ❌ Don't work | ✅ Work perfectly |
| **Classloader Isolation** | ❌ Shared with Maven | ✅ Complete isolation |
| **JVM Options** | ❌ Can't customize | ✅ Fully customizable |
| **Startup Time** | ⚡ Slightly faster | 🐢 Slightly slower (~1-2s) |
| **Memory Usage** | 💾 Shared with Maven | 💾 Separate process |
| **Debugging** | 🔍 More complex | 🔍 Easier (separate JVM) |
| **Recommended For** | Production (no debug logs) | **Development** (with DEBUG logs) |

---

## Complete Examples

### Example 1: Development with DEBUG Logs (Recommended)

```bash
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -Dfork=true \
  -DloggingLevels=org.csanchez.adk.agents.k8sagent=DEBUG,com.google.adk=DEBUG
```

**You'll see**:
- ✅ Tool execution logs: `=== Executing Tool: debug_kubernetes_pod ===`
- ✅ Agent DEBUG logs: `DEBUG --- [main] o.c.adk.agents.k8sagent.KubernetesAgent`
- ✅ ADK framework DEBUG logs

### Example 2: With Custom Logback Configuration

```bash
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -Dfork=true \
  -DloggingConfig=src/main/resources/logback-dev.xml
```

### Example 3: Production-Like (INFO logs)

```bash
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -Dfork=false
  # Or omit -Dfork (defaults to false)
```

### Example 4: With JVM Tuning

```bash
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -Dfork=true \
  -DjvmArgs="-Xmx4g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200" \
  -DloggingLevels=org.csanchez.adk.agents.k8sagent=DEBUG
```

---

## Troubleshooting

### "Could not find or load main class ForkLauncher"
**Cause**: Maven plugin JAR not in classpath
**Fix**: Rebuild plugin: `cd adk-java/maven_plugin && mvn clean install -DskipTests`

### Process exits immediately
**Check**:
1. Agent provider configuration: `-Dagents=...`
2. Classpath issues (compile project first: `mvn compile`)
3. Port conflicts (change port: `-Dport=8080`)

### DEBUG logs still not appearing
**Verify**:
1. Fork mode is enabled: Look for `Fork: true` in configuration output
2. Logging levels are set: Look for `Custom Logging Levels: ...`
3. Check output for: `Setting level of logger [org.csanchez.adk.agents.k8sagent] to DEBUG`

### Forked process won't stop
**Solution**: Press `Ctrl+C` - shutdown hook will kill the forked process automatically

---

## Performance Considerations

### Fork Mode Overhead
- **Initial startup**: +1-2 seconds (JVM initialization)
- **Runtime**: No difference (separate process)
- **Memory**: Additional JVM heap (configure with `-DjvmArgs`)

### When to Use Fork Mode
- ✅ **Development**: Always use fork mode for DEBUG logging
- ✅ **Debugging**: Easier to attach debugger to forked process
- ✅ **Integration tests**: Isolate test execution
- ❌ **Quick checks**: Use non-fork if you don't need DEBUG logs

---

## Files Modified

### `WebMojo.java`
1. ✅ Added `fork` parameter (boolean, default: false)
2. ✅ Added `jvmArgs` parameter (String, optional)
3. ✅ Added `forkedProcess` field (Process)
4. ✅ Added `runInForkedProcess()` method
5. ✅ Updated `cleanupResources()` to kill forked process
6. ✅ Updated `logConfiguration()` to show fork settings
7. ✅ Added `ForkLauncher` static inner class

### New Files
- None (all changes in existing `WebMojo.java`)

---

## Summary

✅ **Fork mode is the complete solution for DEBUG logging**
✅ **Simple to use**: Just add `-Dfork=true`
✅ **Flexible**: Customize JVM, logging, and more
✅ **Production-ready**: Non-fork mode still available
✅ **Backward compatible**: Default behavior unchanged

### Quick Reference

```bash
# Development (DEBUG logs) - RECOMMENDED
mvn google-adk:web -Dagents=... -Dfork=true -DloggingLevels=...=DEBUG

# Production (INFO logs)
mvn google-adk:web -Dagents=...

# With Web UI
mvn google-adk:web -Dagents=... -Dfork=true -DloggingLevels=...=DEBUG
# Then open http://localhost:8000
```

**Fork mode solves all logging configuration issues!** 🎉


