package dev.kevindubois.rollout.agent.remediation;

import dev.kevindubois.rollout.agent.model.SourceReadResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.MockitoConfig;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class SourceCodeToolTest {

    @Inject
    SourceCodeTool sourceCodeTool;

    @InjectMock
    @RestClient
    @MockitoConfig(convertScopes = true)
    GitHubRestClient githubClient;

    private static final String TEST_REPO_URL = "https://github.com/test/repo";

    @BeforeEach
    void setUp() {
        reset(githubClient);
    }

    @Test
    void testReadSingleFile() {
        String content = "test content";
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
        GitHubRestClient.GitHubFileContent fileContent = new GitHubRestClient.GitHubFileContent(
            "test.txt", "test.txt", "abc123", 100, "url", "html_url", "git_url",
            "download_url", "file", encodedContent, "base64"
        );

        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString()))
            .thenReturn(fileContent);

        SourceReadResult result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("test.txt"), "main");

        assertTrue(result.success());
        assertEquals(1, result.filesRead());
        assertEquals("test content", result.files().get("test.txt"));

        verify(githubClient).getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString());
    }

    @Test
    void testReadMultipleFiles() {
        String encodedContent1 = Base64.getEncoder().encodeToString("content 1".getBytes());
        String encodedContent2 = Base64.getEncoder().encodeToString("content 2".getBytes());

        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("file1.txt"), eq("main"), anyString()))
            .thenReturn(new GitHubRestClient.GitHubFileContent(
                "file1.txt", "file1.txt", "abc123", 100, "url", "html_url", "git_url", "download_url", "file", encodedContent1, "base64"));
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("file2.txt"), eq("main"), anyString()))
            .thenReturn(new GitHubRestClient.GitHubFileContent(
                "file2.txt", "file2.txt", "def456", 100, "url", "html_url", "git_url", "download_url", "file", encodedContent2, "base64"));

        SourceReadResult result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("file1.txt", "file2.txt"), "main");

        assertTrue(result.success());
        assertEquals(2, result.filesRead());
        assertEquals("content 1", result.files().get("file1.txt"));
        assertEquals("content 2", result.files().get("file2.txt"));
    }

    @Test
    void testFileNotFound() {
        when(githubClient.getFileContent(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Not found"));

        SourceReadResult result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("nonexistent.txt"), "main");

        assertTrue(result.success());
        assertEquals(0, result.filesRead());
        assertEquals(1, result.notFound().size());
        assertEquals("nonexistent.txt", result.notFound().get(0));
    }

    @Test
    void testPartialSuccess() {
        String encodedContent = Base64.getEncoder().encodeToString("I exist".getBytes());
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("exists.txt"), eq("main"), anyString()))
            .thenReturn(new GitHubRestClient.GitHubFileContent(
                "exists.txt", "exists.txt", "abc123", 100, "url", "html_url", "git_url", "download_url", "file", encodedContent, "base64"));
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("missing.txt"), eq("main"), anyString()))
            .thenThrow(new RuntimeException("Not found"));

        SourceReadResult result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("exists.txt", "missing.txt"), "main");

        assertTrue(result.success());
        assertEquals(1, result.filesRead());
        assertEquals("I exist", result.files().get("exists.txt"));
        assertEquals(1, result.notFound().size());
        assertEquals("missing.txt", result.notFound().get(0));
    }

    @Test
    void testInvalidRepoUrl() {
        SourceReadResult result = sourceCodeTool.readSourceFiles(null, List.of("test.txt"), "main");

        assertFalse(result.success());
        assertTrue(result.error().contains("repoUrl is required"));
        verifyNoInteractions(githubClient);
    }

    @Test
    void testEmptyFilePaths() {
        SourceReadResult result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of(), "main");

        assertFalse(result.success());
        assertTrue(result.error().contains("filePaths"));
        verifyNoInteractions(githubClient);
    }

    @Test
    void testNullFilePaths() {
        SourceReadResult result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, null, "main");

        assertFalse(result.success());
        assertTrue(result.error().contains("filePaths"));
        verifyNoInteractions(githubClient);
    }

    @Test
    void testDefaultBranch() {
        String encodedContent = Base64.getEncoder().encodeToString("content".getBytes());
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString()))
            .thenReturn(new GitHubRestClient.GitHubFileContent(
                "test.txt", "test.txt", "abc123", 100, "url", "html_url", "git_url", "download_url", "file", encodedContent, "base64"));

        SourceReadResult result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("test.txt"), null);

        assertTrue(result.success());
        assertEquals("main", result.branch());
        verify(githubClient).getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString());
    }

    @Test
    void testFileContentWithLineNumbers() {
        String content = "line 1\nline 2\nline 3";
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString()))
            .thenReturn(new GitHubRestClient.GitHubFileContent(
                "test.txt", "test.txt", "abc123", 100, "url", "html_url", "git_url", "download_url", "file", encodedContent, "base64"));

        SourceReadResult result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("test.txt"), "main");

        assertTrue(result.success());
        assertEquals("line 1\nline 2\nline 3", result.files().get("test.txt"));

        String numberedContent = result.filesWithLineNumbers().get("test.txt");
        assertNotNull(numberedContent);
        assertTrue(numberedContent.contains("   1 | line 1"));
        assertTrue(numberedContent.contains("   2 | line 2"));
        assertTrue(numberedContent.contains("   3 | line 3"));
    }
}
