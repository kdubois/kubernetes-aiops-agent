package dev.kevindubois.rollout.agent.a2a;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.kevindubois.rollout.agent.workflow.KubernetesWorkflow;
import dev.kevindubois.rollout.agent.agents.RemediationAgent;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.KubernetesAgentRequest;
import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;
import dev.kevindubois.rollout.agent.remediation.GitHubRestClient;
import dev.kevindubois.rollout.agent.remediation.SourceCodeTool;
import dev.kevindubois.rollout.agent.utils.RetryHelper;
import dev.kevindubois.rollout.agent.utils.ToolCallLimiter;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * REST API controller for Kubernetes Agent.
 * Provides HTTP endpoints for health checks and canary analysis.
 */
@Path("/a2a")
public class KubernetesAgentResource {

    @Inject
    KubernetesWorkflow kubernetesWorkflow;
    
    @Inject
    RemediationAgent remediationAgent;

    @Inject
    SourceCodeTool sourceCodeTool;

    @Inject
    @RestClient
    GitHubRestClient githubClient;
     
    /**
     * Main analyze endpoint
     */
    @POST
    @Path("/analyze")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyze(KubernetesAgentRequest request) {
        Log.info(MessageFormat.format("Received analysis request from user: {0}", request.userId()));
        
        try {
            // Extract context values for later use
            Map<String, Object> context = request.context();
            String repoUrl = context != null ? (String) context.get("repoUrl") : null;
            String baseBranch = context != null ? (String) context.get("baseBranch") : "main";
            
            Log.info(MessageFormat.format("Context - repoUrl: {0}, baseBranch: {1}", repoUrl, baseBranch));
            
            // Build prompt with context
            String prompt = buildPrompt(request);
            Log.debug(MessageFormat.format("Built prompt: {0}", prompt));
            
            // Get effective memory ID (uses memoryId if provided, otherwise falls back to userId)
            String memoryId = request.getEffectiveMemoryId();
            Log.debug(MessageFormat.format("Using memory ID: {0}", memoryId));
            
            // Reset tool call limiter for this new analysis session
            ToolCallLimiter.resetSession(memoryId);
            Log.info(MessageFormat.format("Reset tool call limiter for session: {0}", memoryId));
            
            // Execute multi-agent workflow with retry logic for transient errors
            AnalysisResult analysisResult = RetryHelper.executeWithRetryOnTransientErrors(
                () -> kubernetesWorkflow.execute(memoryId, prompt, repoUrl, baseBranch),
                "Multi-agent workflow analysis"
            );
            
            // Add context to result if not already present (fallback mechanism)
            if (analysisResult.repoUrl() == null && repoUrl != null) {
                analysisResult = new AnalysisResult(
                    analysisResult.promote(),
                    analysisResult.confidence(),
                    analysisResult.analysis(),
                    analysisResult.rootCause(),
                    analysisResult.remediation(),
                    analysisResult.prLink(),
                    repoUrl,
                    baseBranch
                );
            }
            
            // Validate PR link is not hallucinated
            if (analysisResult.prLink() != null && isHallucinatedUrl(analysisResult.prLink())) {
                Log.warn(MessageFormat.format("Detected hallucinated PR link: {0}, setting to null", analysisResult.prLink()));
                analysisResult = new AnalysisResult(
                    analysisResult.promote(),
                    analysisResult.confidence(),
                    analysisResult.analysis(),
                    analysisResult.rootCause(),
                    analysisResult.remediation(),
                    null,  // Clear hallucinated link
                    analysisResult.repoUrl(),
                    analysisResult.baseBranch()
                );
            }
            
            // Convert AnalysisResult to KubernetesAgentResponse
            KubernetesAgentResponse response = new KubernetesAgentResponse(
                analysisResult.analysis(),
                analysisResult.rootCause(),
                analysisResult.remediation(),
                analysisResult.prLink(),
                analysisResult.promote(),
                analysisResult.confidence()
            );
            
            Log.info("Multi-agent workflow completed successfully");
            
            // Trigger async remediation if needed (fire-and-forget)
            final AnalysisResult finalResult = analysisResult;
            final String finalPrompt = buildPrompt(request);
            if (!finalResult.promote() && repoUrl != null && !repoUrl.isEmpty()) {
                Log.info("Triggering async remediation for rollback decision");

                // Only pre-fetch source code for code bugs, not operational issues
                final String enrichedPrompt;
                if (!isOperationalIssue(finalResult.rootCause())) {
                    String analysisText = finalPrompt + "\n" + finalResult.toString();
                    String sourceContext = prefetchSourceCode(analysisText, repoUrl, baseBranch);
                    enrichedPrompt = finalPrompt + sourceContext;
                } else {
                    Log.info("Operational issue detected (e.g. memory leak), skipping source code pre-fetch — will create issue instead of PR");
                    enrichedPrompt = finalPrompt;
                }

                CompletableFuture.runAsync(() -> {
                    try {
                        Log.info("Starting async remediation");
                        AnalysisResult remediationResult = remediationAgent.implementRemediation(
                            enrichedPrompt, finalResult, repoUrl, baseBranch
                        );
                        if (remediationResult.prLink() != null && !remediationResult.prLink().isEmpty()) {
                            Log.info(MessageFormat.format(
                                "Async remediation completed - GitHub artifact created: {0}",
                                remediationResult.prLink()
                            ));
                        } else {
                            Log.info("Async remediation completed - no GitHub artifact created");
                        }
                    } catch (Exception e) {
                        Log.error("Async remediation failed (non-critical)", e);
                    }
                });
            } else {
                Log.debug("Skipping remediation - promote=true or no repoUrl configured");
            }
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            Log.error(MessageFormat.format("Error processing request from user: {0}", request.userId()), e);
            Log.error(MessageFormat.format("Request details - Prompt: {0}", request.prompt()));
            Log.error(MessageFormat.format("Request details - Context: {0}", request.context()));
            
            // Log additional details for debugging
            if (e instanceof NullPointerException) {
                Log.error("NullPointerException detected - this may be a Gemini API response issue");
                Log.error(MessageFormat.format("Stack trace: {0}", getStackTraceAsString(e)));
            }
            
            KubernetesAgentResponse errorResponse = KubernetesAgentResponse.empty()
                .withAnalysis(MessageFormat.format("Error: {0}", e.getMessage()))
                .withRootCause("Analysis failed: " + e.getClass().getSimpleName())
                .withRemediation("Unable to provide remediation due to API error. Please try again.")
                .withPromote(true) // Default to promote on error
                .withConfidence(0);
            
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                .entity(errorResponse)
                .build();
        }
    }
    
    /**
     * Pre-fetch source code files by extracting file paths from the diagnostic/analysis data.
     * Tries regex extraction first, then falls back to searching the repo tree for class names.
     */
    private String prefetchSourceCode(String diagnosticData, String repoUrl, String baseBranch) {
        // Strategy 1: Extract file paths from stack traces or FQCNs
        List<String> filePaths = extractFilePathsFromStackTraces(diagnosticData);

        // Strategy 2: If no paths found, extract class names and search the repo tree
        if (filePaths.isEmpty()) {
            List<String> classNames = extractClassNames(diagnosticData);
            if (!classNames.isEmpty()) {
                Log.info(MessageFormat.format("No file paths from stack traces, searching repo for classes: {0}", classNames));
                filePaths = searchRepoForClasses(repoUrl, baseBranch, classNames);
            }
        }

        // Strategy 3: If still nothing, fetch all main source files from the repo
        if (filePaths.isEmpty()) {
            Log.info("No specific classes identified, fetching all main source files from repo");
            filePaths = getAllMainSourceFiles(repoUrl, baseBranch);
        }

        if (filePaths.isEmpty()) {
            Log.info("No source files found in repo, skipping pre-fetch");
            return "";
        }

        Log.info(MessageFormat.format("Pre-fetching {0} source files: {1}", filePaths.size(), filePaths));
        try {
            Map<String, Object> result = sourceCodeTool.readSourceFiles(repoUrl, filePaths, baseBranch);
            if (Boolean.TRUE.equals(result.get("success"))) {
                @SuppressWarnings("unchecked")
                Map<String, String> filesWithLineNumbers = (Map<String, String>) result.get("filesWithLineNumbers");
                if (filesWithLineNumbers != null && !filesWithLineNumbers.isEmpty()) {
                    StringBuilder sb = new StringBuilder("\n\n=== SOURCE CODE (pre-fetched) ===\n");
                    sb.append("Use this source code to create a PR with createGitHubPRWithPatches.\n\n");
                    filesWithLineNumbers.forEach((path, content) -> {
                        sb.append("--- ").append(path).append(" ---\n");
                        sb.append(content).append("\n");
                    });
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            Log.warn("Failed to pre-fetch source code (non-critical)", e);
        }
        return "";
    }

    /**
     * Search the repository tree for Java files matching the given class names.
     */
    private List<String> searchRepoForClasses(String repoUrl, String branch, List<String> classNames) {
        List<String> foundPaths = new ArrayList<>();
        try {
            String[] ownerRepo = repoUrl.replace("https://github.com/", "").replace(".git", "").split("/", 2);
            String token = System.getenv("GITHUB_TOKEN");
            if (token == null || token.isEmpty()) return foundPaths;

            String authHeader = "Bearer " + token;
            var tree = githubClient.getTree(ownerRepo[0], ownerRepo[1], branch, "1", authHeader);
            if (tree != null && tree.tree() != null) {
                for (var entry : tree.tree()) {
                    if ("blob".equals(entry.type()) && entry.path().endsWith(".java")) {
                        for (String className : classNames) {
                            if (entry.path().endsWith("/" + className + ".java") || entry.path().equals(className + ".java")) {
                                if (!foundPaths.contains(entry.path())) {
                                    foundPaths.add(entry.path());
                                    Log.info(MessageFormat.format("Found source file for {0}: {1}", className, entry.path()));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.warn(MessageFormat.format("Failed to search repo tree: {0}", e.getMessage()));
        }
        return foundPaths;
    }

    private static final int MAX_SOURCE_FILES = 5;

    /**
     * Fetch main (non-test, non-model) Java source files from the repository.
     * Prioritizes service/resource classes over model/config classes.
     * Caps at MAX_SOURCE_FILES to avoid rate limiting.
     */
    private List<String> getAllMainSourceFiles(String repoUrl, String branch) {
        List<String> priorityFiles = new ArrayList<>();
        List<String> otherFiles = new ArrayList<>();
        try {
            String[] ownerRepo = repoUrl.replace("https://github.com/", "").replace(".git", "").split("/", 2);
            String token = System.getenv("GITHUB_TOKEN");
            if (token == null || token.isEmpty()) return priorityFiles;

            String authHeader = "Bearer " + token;
            var tree = githubClient.getTree(ownerRepo[0], ownerRepo[1], branch, "1", authHeader);
            if (tree != null && tree.tree() != null) {
                for (var entry : tree.tree()) {
                    if ("blob".equals(entry.type())
                            && entry.path().endsWith(".java")
                            && entry.path().startsWith("src/main/")
                            && !entry.path().contains("/model/")) {
                        // Prioritize files likely to contain business logic
                        String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
                        if (name.contains("Resource") || name.contains("Service")
                                || name.contains("Controller") || name.contains("Handler")) {
                            priorityFiles.add(entry.path());
                        } else {
                            otherFiles.add(entry.path());
                        }
                    }
                }
            }
            // Add priority files first, then fill remaining slots with others
            List<String> result = new ArrayList<>(priorityFiles);
            for (String f : otherFiles) {
                if (result.size() >= MAX_SOURCE_FILES) break;
                result.add(f);
            }
            Log.info(MessageFormat.format("Found {0} main source files, returning top {1}",
                    priorityFiles.size() + otherFiles.size(), result.size()));
            return result;
        } catch (Exception e) {
            Log.warn(MessageFormat.format("Failed to list repo source files: {0}", e.getMessage()));
        }
        return priorityFiles;
    }

    /**
     * Extract class names from text using multiple strategies:
     * 1. Quarkus abbreviated logger names like [de.ke.de.MetricsResource]
     * 2. Bare class names with common suffixes (Resource, Service, etc.)
     * 3. Any CamelCase word with 2+ parts (e.g., MetricsResource, LoadGeneratorService)
     */
    private List<String> extractClassNames(String text) {
        List<String> classNames = new ArrayList<>();

        // Strategy 1: Quarkus abbreviated logger names like [de.ke.de.MetricsResource]
        Pattern quarkusLogger = Pattern.compile("\\[(?:[a-z]{2}\\.)+([A-Z][a-zA-Z0-9]+)\\]");
        Matcher m1 = quarkusLogger.matcher(text);
        while (m1.find()) {
            addClassName(classNames, m1.group(1));
        }

        // Strategy 2: Class names with common Java suffixes
        Pattern suffixed = Pattern.compile("\\b([A-Z][a-zA-Z0-9]*(?:Resource|Service|Controller|Repository|Handler|Manager|Factory|Bean|Endpoint|Client|Provider|Impl))\\b");
        Matcher m2 = suffixed.matcher(text);
        while (m2.find()) {
            addClassName(classNames, m2.group(1));
        }

        // Strategy 3: CamelCase class names mentioned near error keywords
        Pattern nearError = Pattern.compile("(?:in|of|from|class)\\s+([A-Z][a-zA-Z0-9]{2,})");
        Matcher m3 = nearError.matcher(text);
        while (m3.find()) {
            addClassName(classNames, m3.group(1));
        }

        return classNames;
    }

    private void addClassName(List<String> classNames, String className) {
        if (!classNames.contains(className)
                && !className.endsWith("Exception")
                && !className.equals("String")
                && !className.equals("Object")
                && !className.equals("Running")
                && !className.equals("Occasional")) {
            classNames.add(className);
        }
    }

    /**
     * Extract Java file paths from stack trace lines or fully qualified class names.
     */
    private List<String> extractFilePathsFromStackTraces(String text) {
        List<String> paths = new ArrayList<>();

        // Match stack trace lines: "at package.ClassName.method(ClassName.java:line)"
        Pattern stackTrace = Pattern.compile("at\\s+([a-zA-Z0-9_.]+)\\.\\w+\\(([A-Za-z0-9_]+\\.java):\\d+\\)");
        Matcher m1 = stackTrace.matcher(text);
        while (m1.find()) {
            String fullClass = m1.group(1);
            int lastDot = fullClass.lastIndexOf('.');
            if (lastDot > 0) {
                String filePath = "src/main/java/" + fullClass.substring(0, lastDot).replace('.', '/') + "/" + m1.group(2);
                if (!paths.contains(filePath)) {
                    paths.add(filePath);
                }
            }
        }

        // Match fully qualified class names (e.g., dev.kevindubois.demo.MetricsResource)
        if (paths.isEmpty()) {
            Pattern fqcn = Pattern.compile("([a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*){2,})\\.([A-Z][a-zA-Z0-9]*)");
            Matcher m2 = fqcn.matcher(text);
            while (m2.find()) {
                String pkg = m2.group(1);
                String className = m2.group(2);
                if (pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg.startsWith("jakarta.")
                        || pkg.startsWith("io.quarkus") || pkg.startsWith("org.jboss")) {
                    continue;
                }
                String filePath = "src/main/java/" + pkg.replace('.', '/') + "/" + className + ".java";
                if (!paths.contains(filePath)) {
                    paths.add(filePath);
                }
            }
        }

        return paths;
    }

    /**
     * Convert exception stack trace to string for logging
     */
    private String getStackTraceAsString(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\n  at ").append(element.toString());
        }
        return sb.toString();
    }
    
    /**
     * Build prompt from request
     */
    private String buildPrompt(KubernetesAgentRequest request) {
        Map<String, Object> context = request.context();
        
        StringBuilder prompt = new StringBuilder();
        prompt.append(request.prompt()).append("\n\n");
        
        if (context != null) {
            prompt.append("Context:\n");
            context.forEach((key, value) -> {
                if (value != null) {
                    prompt.append("- ").append(key).append(": ").append(value).append("\n");
                }
            });
        }

        prompt.append("\n⚠️ CRITICAL: USE getCanaryDiagnostics TOOL FIRST ⚠️\n");
        prompt.append("⚠️ MAXIMUM 1-2 TOOL CALLS - BE EFFICIENT ⚠️\n\n");
        prompt.append("EFFICIENT WORKFLOW:\n");
        prompt.append("1. ALWAYS call getCanaryDiagnostics(namespace, containerName, tailLines) FIRST\n");
        prompt.append("   - This fetches BOTH stable AND canary pod info and logs in ONE call\n");
        prompt.append("   - Returns pod names, phases, ready status, and logs for both stable and canary\n");
        prompt.append("   - Container name can be null/empty for auto-detection\n");
        prompt.append("2. Analyze the results from getCanaryDiagnostics\n");
        prompt.append("3. Only call additional tools if absolutely necessary (e.g., getEvents for pod failures)\n");
        prompt.append("4. Return your analysis immediately\n\n");
        prompt.append("RULES:\n");
        prompt.append("- Call ONE tool at a time and wait for results\n");
        prompt.append("- NEVER hallucinate or guess pod/resource names\n");
        prompt.append("- Use actual names from tool results\n");
        prompt.append("- Skip getEvents if pods are Running/Ready\n");
        prompt.append("- Each tool can only be called ONCE with the same parameters\n\n");
        prompt.append("The multi-agent workflow will:\n");
        prompt.append("1. DiagnosticAgent: Gather data efficiently (1-2 tool calls using getCanaryDiagnostics)\n");
        prompt.append("2. AnalysisAgent: Analyze the gathered data\n");
        prompt.append("3. RemediationAgent: Implement fixes if needed\n");
        
        return prompt.toString();
    }
    
    /**
     * Determine if the root cause is an operational issue (not fixable with a code patch).
     * Memory leaks, OOM, resource exhaustion, config issues → create issue, not PR.
     */
    private boolean isOperationalIssue(String rootCause) {
        if (rootCause == null || rootCause.isEmpty()) return false;
        String lower = rootCause.toLowerCase();
        return lower.contains("memory leak") || lower.contains("oom") || lower.contains("out of memory")
                || lower.contains("outofmemory") || lower.contains("resource exhaustion")
                || lower.contains("cpu throttl") || lower.contains("disk space")
                || lower.contains("oomkilled") || lower.contains("heap")
                || lower.contains("gc activity") || lower.contains("garbage collect")
                || lower.contains("performance degradation");
    }

    /**
     * Detect hallucinated URLs that the LLM may have invented.
     * Common patterns include example.com, example/repo, or generic placeholder URLs.
     *
     * @param url The URL to validate
     * @return true if the URL appears to be hallucinated, false otherwise
     */
    private boolean isHallucinatedUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        // Detect common hallucination patterns
        boolean isHallucinated = url.contains("example.com") ||
                                 url.contains("example/repo") ||
                                 url.matches(".*github\\.com/[^/]+/repo/.*");
        
        if (isHallucinated) {
            Log.debug(MessageFormat.format("URL matched hallucination pattern: {0}", url));
        }
        
        return isHallucinated;
    }
}