package dev.kevindubois.rollout.agent.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitHubUtilsTest {

    @Test
    void extractOwnerAndRepo_httpsUrl() {
        String[] result = GitHubUtils.extractOwnerAndRepo("https://github.com/kdubois/demo-app");
        assertArrayEquals(new String[]{"kdubois", "demo-app"}, result);
    }

    @Test
    void extractOwnerAndRepo_httpsUrlWithDotGit() {
        String[] result = GitHubUtils.extractOwnerAndRepo("https://github.com/kdubois/demo-app.git");
        assertArrayEquals(new String[]{"kdubois", "demo-app"}, result);
    }

    @Test
    void extractOwnerAndRepo_sshUrl() {
        String[] result = GitHubUtils.extractOwnerAndRepo("git@github.com:kdubois/demo-app.git");
        assertArrayEquals(new String[]{"kdubois", "demo-app"}, result);
    }

    @Test
    void extractOwnerAndRepo_blank_throws() {
        assertThrows(IllegalArgumentException.class, () -> GitHubUtils.extractOwnerAndRepo(""));
    }

    @Test
    void extractOwnerAndRepo_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> GitHubUtils.extractOwnerAndRepo(null));
    }

    @Test
    void authHeader_formatsBearerToken() {
        assertEquals("Bearer my-token", GitHubUtils.authHeader("my-token"));
    }
}
