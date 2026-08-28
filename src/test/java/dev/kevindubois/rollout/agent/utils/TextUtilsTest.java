package dev.kevindubois.rollout.agent.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextUtilsTest {

    @Test
    void extractNamespace_withNamespaceEquals() {
        assertEquals("quarkus-demo", TextUtils.extractNamespace("namespace=quarkus-demo and pod=test"));
    }

    @Test
    void extractNamespace_withNamespaceColon() {
        assertEquals("kube-system", TextUtils.extractNamespace("namespace: kube-system"));
    }

    @Test
    void extractNamespace_noNamespace() {
        assertEquals("default", TextUtils.extractNamespace("analyze pods in production"));
    }

    @Test
    void extractNamespace_nullInput() {
        assertEquals("default", TextUtils.extractNamespace(null));
    }

    @Test
    void extractSummary_blankInput() {
        assertNull(TextUtils.extractSummary(null));
        assertNull(TextUtils.extractSummary(""));
        assertNull(TextUtils.extractSummary("   "));
    }

    @Test
    void extractSummary_shortSentence() {
        assertEquals("Hello world.", TextUtils.extractSummary("Hello world. More text here."));
    }

    @Test
    void extractSummary_longText() {
        String longText = "A".repeat(200) + ". Rest of the text.";
        String result = TextUtils.extractSummary(longText);
        assertTrue(result.length() <= 151);
        assertTrue(result.endsWith("..."));
    }

    @Test
    void extractSummary_missingPunctuation() {
        assertEquals("No period at end.", TextUtils.extractSummary("No period at end"));
    }

    @Test
    void truncate_nullInput() {
        assertNull(TextUtils.truncate(null, 10));
    }

    @Test
    void truncate_shortText() {
        assertEquals("hi", TextUtils.truncate("hi", 10));
    }

    @Test
    void truncate_exactBoundary() {
        assertEquals("12345", TextUtils.truncate("12345", 5));
    }

    @Test
    void truncate_longText() {
        assertEquals("123...", TextUtils.truncate("123456789", 3));
    }

    @Test
    void isValidGitHubArtifactUrl_validPR() {
        assertTrue(TextUtils.isValidGitHubArtifactUrl("https://github.com/owner/repo/pull/42"));
    }

    @Test
    void isValidGitHubArtifactUrl_validIssue() {
        assertTrue(TextUtils.isValidGitHubArtifactUrl("https://github.com/owner/repo/issues/7"));
    }

    @Test
    void isValidGitHubArtifactUrl_invalidHost() {
        assertFalse(TextUtils.isValidGitHubArtifactUrl("https://gitlab.com/owner/repo/pull/1"));
    }

    @Test
    void isValidGitHubArtifactUrl_nullOrBlank() {
        assertFalse(TextUtils.isValidGitHubArtifactUrl(null));
        assertFalse(TextUtils.isValidGitHubArtifactUrl(""));
        assertFalse(TextUtils.isValidGitHubArtifactUrl("   "));
    }

    @Test
    void isValidGitHubArtifactUrl_hallucinatedPath() {
        assertFalse(TextUtils.isValidGitHubArtifactUrl("https://github.com/owner/repo/fake/123"));
    }
}
