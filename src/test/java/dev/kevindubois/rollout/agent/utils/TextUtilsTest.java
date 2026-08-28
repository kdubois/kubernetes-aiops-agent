package dev.kevindubois.rollout.agent.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextUtilsTest {

    @Test
    void truncate_nullInput() {
        assertEquals("", TextUtils.truncate(null, 10));
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
