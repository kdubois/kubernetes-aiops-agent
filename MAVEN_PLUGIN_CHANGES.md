# Required Changes to ADK Maven Plugin for Logging Configuration

## Problem
The ADK Maven plugin currently doesn't support customizing logging levels for user agents. The Spring Boot application it launches uses default INFO-level logging, making it difficult to debug agents during development.

## Solution
Add new Maven parameters to configure logging levels that get passed as Spring Boot system properties.

---

## Changes to `WebMojo.java`

### 1. Add New Parameters (after line 181)

```java
  /**
   * Logging level for the root logger.
   *
   * <p>Sets the root logging level for the Spring Boot application.
   * Valid values: TRACE, DEBUG, INFO, WARN, ERROR, OFF
   * Default is INFO.
   *
   * <p>Example:
   *
   * <pre>{@code
   * mvn google-adk:web -Dagents=... -DloggingLevel=DEBUG
   * }</pre>
   */
  @Parameter(property = "loggingLevel", defaultValue = "INFO")
  private String loggingLevel;

  /**
   * Additional logging level overrides in format: package1=LEVEL1,package2=LEVEL2
   *
   * <p>Allows fine-grained control over logging levels for specific packages.
   * Multiple entries can be separated by commas.
   *
   * <p>Example:
   *
   * <pre>{@code
   * mvn google-adk:web -Dagents=... \
   *   -DloggingLevels=com.google.adk=DEBUG,org.mycompany.agents=TRACE
   * }</pre>
   */
  @Parameter(property = "loggingLevels")
  private String loggingLevels;

  /**
   * Path to a custom logback configuration file.
   *
   * <p>If specified, this logback configuration file will be used instead of the default.
   * The path can be absolute or relative to the project root.
   *
   * <p>Example:
   *
   * <pre>{@code
   * mvn google-adk:web -Dagents=... \
   *   -DloggingConfig=src/main/resources/logback-dev.xml
   * }</pre>
   */
  @Parameter(property = "loggingConfig")
  private String loggingConfig;
```

### 2. Update `setupSystemProperties()` Method (replace lines 273-280)

```java
  private void setupSystemProperties() {
    System.setProperty("server.address", host);
    System.setProperty("server.port", String.valueOf(port));

    // Use custom loader instead of compiled loader
    System.setProperty("adk.agents.loader", "custom");
    getLog().debug("Set adk.agents.loader=custom");

    // Configure logging levels
    setupLoggingConfiguration();
  }

  /**
   * Configures logging levels for the Spring Boot application.
   * 
   * <p>This method sets system properties that Spring Boot reads during startup
   * to configure the logging framework (typically Logback).
   */
  private void setupLoggingConfiguration() {
    // Set root logging level
    System.setProperty("logging.level.root", loggingLevel);
    getLog().debug("Set logging.level.root=" + loggingLevel);

    // Set custom logback config if provided
    if (loggingConfig != null && !loggingConfig.trim().isEmpty()) {
      Path configPath = Paths.get(loggingConfig);
      if (!configPath.isAbsolute()) {
        configPath = Paths.get(project.getBasedir().getAbsolutePath(), loggingConfig);
      }
      
      if (Files.exists(configPath)) {
        System.setProperty("logging.config", configPath.toString());
        getLog().info("Using custom logging config: " + configPath);
      } else {
        getLog().warn("Logging config file not found: " + configPath);
      }
    }

    // Parse and set custom logging levels
    if (loggingLevels != null && !loggingLevels.trim().isEmpty()) {
      String[] levels = loggingLevels.split(",");
      for (String levelEntry : levels) {
        String[] parts = levelEntry.trim().split("=");
        if (parts.length == 2) {
          String packageName = parts[0].trim();
          String level = parts[1].trim().toUpperCase();
          System.setProperty("logging.level." + packageName, level);
          getLog().debug("Set logging.level." + packageName + "=" + level);
        } else {
          getLog().warn("Invalid logging level entry: " + levelEntry);
        }
      }
    }
  }
```

### 3. Add Import Statements (at the top of the file)

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
```

### 4. Update `logConfiguration()` Method (add after line 270)

Add logging configuration to the debug output:

```java
  private void logConfiguration() {
    getLog().info("Configuration:");
    getLog().info("  Agent Provider: " + agents);
    getLog().info("  Server Host: " + host);
    getLog().info("  Server Port: " + port);
    getLog().info("  Registry: " + (registry != null ? registry : "default"));
    getLog().info("  Logging Level: " + loggingLevel);
    if (loggingLevels != null) {
      getLog().info("  Custom Logging Levels: " + loggingLevels);
    }
    if (loggingConfig != null) {
      getLog().info("  Logging Config: " + loggingConfig);
    }
  }
```

---

## Usage Examples

### Example 1: Enable DEBUG for all loggers
```bash
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -DloggingLevel=DEBUG
```

### Example 2: Enable DEBUG for specific packages
```bash
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -DloggingLevels=org.csanchez.adk.agents.k8sagent=DEBUG,com.google.adk=DEBUG
```

### Example 3: Use custom logback configuration
```bash
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -DloggingConfig=src/main/resources/logback-dev.xml
```

### Example 4: Combine multiple options
```bash
mvn google-adk:web \
  -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
  -DloggingLevel=INFO \
  -DloggingLevels=org.csanchez.adk.agents.k8sagent=DEBUG,com.google.adk=DEBUG \
  -DloggingConfig=src/main/resources/logback-spring.xml
```

### Example 5: Configuration in pom.xml

```xml
<plugin>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk-maven-plugin</artifactId>
    <version>${google-adk.version}</version>
    <configuration>
        <agents>org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE</agents>
        <port>8080</port>
        <loggingLevel>INFO</loggingLevel>
        <loggingLevels>org.csanchez.adk.agents.k8sagent=DEBUG,com.google.adk=DEBUG</loggingLevels>
        <loggingConfig>src/main/resources/logback-spring.xml</loggingConfig>
    </configuration>
</plugin>
```

Then run simply:
```bash
mvn google-adk:web
```

---

## Benefits

1. **Backward Compatible**: Default behavior unchanged (INFO level)
2. **Flexible**: Support for root level, package-specific levels, and custom config files
3. **Developer-Friendly**: Easy to enable DEBUG mode during development
4. **Production-Ready**: Can be locked down in pom.xml for consistent builds
5. **Spring Boot Native**: Uses Spring Boot's standard logging configuration mechanism

---

## Testing the Changes

After implementing these changes:

1. Build the Maven plugin:
   ```bash
   cd adk-java/maven_plugin
   mvn clean install
   ```

2. Test with the Kubernetes agent:
   ```bash
   cd kubernetes-agent
   mvn google-adk:web \
     -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE \
     -DloggingLevels=org.csanchez.adk.agents.k8sagent=DEBUG,com.google.adk=DEBUG
   ```

3. Verify DEBUG logs appear in the console output

---

## Alternative: Quick Workaround (Without Modifying Plugin)

If you can't modify the ADK Maven plugin immediately, you can use `MAVEN_OPTS`:

```bash
export MAVEN_OPTS="-Dlogging.level.org.csanchez.adk.agents.k8sagent=DEBUG -Dlogging.level.com.google.adk=DEBUG"
mvn google-adk:web -Dagents=org.csanchez.adk.agents.k8sagent.AgentLoader.INSTANCE
```

However, this approach is less reliable because:
- System properties set via `MAVEN_OPTS` may not propagate to the Spring Boot child process
- It depends on how the plugin's classloader is configured
- Based on testing, this workaround **does not work** with the current plugin implementation

Therefore, **modifying the plugin is the recommended solution**.


