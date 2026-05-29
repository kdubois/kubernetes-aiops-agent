package dev.kevindubois.rollout.agent.utils;

public final class TextUtils {

    private TextUtils() {}

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
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
