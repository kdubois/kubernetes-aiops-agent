package dev.kevindubois.rollout.agent.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.kevindubois.rollout.agent.remediation.GitHubRestClient;
import dev.kevindubois.rollout.agent.remediation.SourceCodeTool;

/**
 * Service for pre-fetching source code files from GitHub repositories.
 * Extracts file paths from diagnostic data and fetches relevant source files.
 */
@ApplicationScoped
public class SourceCodePrefetcher {

    private static final int MAX_SOURCE_FILES = 5;
    
    @Inject
    SourceCodeTool sourceCodeTool;

    @Inject
    @RestClient
    GitHubRestClient githubClient;

    /**
     * Pre-fetch source code files by extracting file paths from diagnostic/analysis data.
     */
    public String prefetchSourceCode(String diagnosticData, String repoUrl, String baseBranch) {
        List<String> filePaths = extractFilePathsFromStackTraces(diagnosticData);

        if (filePaths.isEmpty()) {
            List<String> classNames = extractClassNames(diagnosticData);
            if (!classNames.isEmpty()) {
                Log.info(MessageFormat.format("No file paths from stack traces, searching repo for classes: {0}", classNames));
                filePaths = searchRepoForClasses(repoUrl, baseBranch, classNames);
            }
        }

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

    private List<String> extractClassNames(String text) {
        List<String> classNames = new ArrayList<>();

        Pattern quarkusLogger = Pattern.compile("\\[(?:[a-z]{2}\\.)+([A-Z][a-zA-Z0-9]+)\\]");
        Matcher m1 = quarkusLogger.matcher(text);
        while (m1.find()) {
            addClassName(classNames, m1.group(1));
        }

        Pattern suffixed = Pattern.compile("\\b([A-Z][a-zA-Z0-9]*(?:Resource|Service|Controller|Repository|Handler|Manager|Factory|Bean|Endpoint|Client|Provider|Impl))\\b");
        Matcher m2 = suffixed.matcher(text);
        while (m2.find()) {
            addClassName(classNames, m2.group(1));
        }

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

    private List<String> extractFilePathsFromStackTraces(String text) {
        List<String> paths = new ArrayList<>();

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
}

