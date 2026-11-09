package dev.kevindubois.rollout.agent.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextUtilsTest {

    @Test
    void truncateHandlesNullShortExactAndLong() {
        assertEquals("", TextUtils.truncate(null, 10));
        assertEquals("hi", TextUtils.truncate("hi", 10));
        assertEquals("12345", TextUtils.truncate("12345", 5));
        assertEquals("123...", TextUtils.truncate("123456789", 3));
    }

    @Test
    void githubArtifactUrlsAcceptPullsAndIssuesOnly() {
        assertTrue(TextUtils.isValidGitHubArtifactUrl("https://github.com/owner/repo/pull/42"));
        assertTrue(TextUtils.isValidGitHubArtifactUrl("https://github.com/owner/repo/issues/7"));
    }

    @Test
    void githubArtifactUrlsRejectEverythingElse() {
        assertFalse(TextUtils.isValidGitHubArtifactUrl("https://gitlab.com/owner/repo/pull/1"));
        assertFalse(TextUtils.isValidGitHubArtifactUrl("https://github.com/owner/repo/fake/123"));
        assertFalse(TextUtils.isValidGitHubArtifactUrl(null));
        assertFalse(TextUtils.isValidGitHubArtifactUrl(""));
        assertFalse(TextUtils.isValidGitHubArtifactUrl("   "));
    }
}
