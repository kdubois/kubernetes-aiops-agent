package dev.kevindubois.rollout.agent.utils;

import java.util.regex.Pattern;

public final class TextUtils {

    private static final Pattern GITHUB_PR_URL = Pattern.compile(
            "^https://github\\.com/[\\w.-]+/[\\w.-]+/pull/\\d+$");
    private static final Pattern GITHUB_ISSUE_URL = Pattern.compile(
            "^https://github\\.com/[\\w.-]+/[\\w.-]+/issues/\\d+$");

    private TextUtils() {}

    /**
     * Validates that a URL looks like a real GitHub PR or issue URL.
     * Rejects obviously hallucinated URLs (wrong host, impossible paths).
     */
    public static boolean isValidGitHubArtifactUrl(String url) {
        if (url == null || url.isBlank()) return false;
        return GITHUB_PR_URL.matcher(url).matches() || GITHUB_ISSUE_URL.matcher(url).matches();
    }

    public static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
