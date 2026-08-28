package dev.kevindubois.rollout.agent.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextUtils {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("namespace[=:\\s]+(\\S+)");
    private static final Pattern GITHUB_PR_URL = Pattern.compile(
            "^https://github\\.com/[\\w.-]+/[\\w.-]+/pull/\\d+$");
    private static final Pattern GITHUB_ISSUE_URL = Pattern.compile(
            "^https://github\\.com/[\\w.-]+/[\\w.-]+/issues/\\d+$");

    private TextUtils() {}

    public static String extractNamespace(String message) {
        if (message == null) return "default";
        Matcher matcher = NAMESPACE_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "default";
    }

    /**
     * Validates that a URL looks like a real GitHub PR or issue URL.
     * Rejects obviously hallucinated URLs (wrong host, impossible paths).
     */
    public static boolean isValidGitHubArtifactUrl(String url) {
        if (url == null || url.isBlank()) return false;
        return GITHUB_PR_URL.matcher(url).matches() || GITHUB_ISSUE_URL.matcher(url).matches();
    }

    public static String extractSummary(String analysis) {
        if (analysis == null || analysis.isBlank()) {
            return null;
        }
        String firstSentence = analysis.split("[.!?]\\s", 2)[0].trim();
        if (firstSentence.length() > 150) {
            firstSentence = firstSentence.substring(0, 147) + "...";
        }
        if (!firstSentence.endsWith(".") && !firstSentence.endsWith("!") && !firstSentence.endsWith("?")) {
            firstSentence += ".";
        }
        return firstSentence;
    }

    public static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
