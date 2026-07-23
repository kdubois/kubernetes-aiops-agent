package dev.kevindubois.rollout.agent.remediation;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.MockitoConfig;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SourceCodeTool using the Quarkus test framework.
 * CDI wires the real bean; the REST client is replaced with a Mockito mock via @InjectMock.
 * The github.token config property is supplied by src/test/resources/application.properties.
 */
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
    void testReadSingleFile() throws Exception {
        String content = "test content";
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
        GitHubRestClient.GitHubFileContent fileContent = new GitHubRestClient.GitHubFileContent(
            "test.txt", "test.txt", "abc123", 100, "url", "html_url", "git_url",
            "download_url", "file", encodedContent, "base64"
        );

        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString()))
            .thenReturn(fileContent);

        Map<String, Object> result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("test.txt"), "main");

        assertTrue((Boolean) result.get("success"));
        assertEquals(1, result.get("filesRead"));

        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) result.get("files");
        assertEquals("test content", files.get("test.txt"));

        verify(githubClient).getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString());
    }

    @Test
    void testReadMultipleFiles() throws Exception {
        String encodedContent1 = Base64.getEncoder().encodeToString("content 1".getBytes());
        String encodedContent2 = Base64.getEncoder().encodeToString("content 2".getBytes());

        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("file1.txt"), eq("main"), anyString()))
            .thenReturn(new GitHubRestClient.GitHubFileContent(
                "file1.txt", "file1.txt", "abc123", 100, "url", "html_url", "git_url", "download_url", "file", encodedContent1, "base64"));
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("file2.txt"), eq("main"), anyString()))
            .thenReturn(new GitHubRestClient.GitHubFileContent(
                "file2.txt", "file2.txt", "def456", 100, "url", "html_url", "git_url", "download_url", "file", encodedContent2, "base64"));

        Map<String, Object> result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("file1.txt", "file2.txt"), "main");

        assertTrue((Boolean) result.get("success"));
        assertEquals(2, result.get("filesRead"));

        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) result.get("files");
        assertEquals("content 1", files.get("file1.txt"));
        assertEquals("content 2", files.get("file2.txt"));
    }

    @Test
    void testFileNotFound() throws Exception {
        when(githubClient.getFileContent(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Not found"));

        Map<String, Object> result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("nonexistent.txt"), "main");

        assertTrue((Boolean) result.get("success"));
        assertEquals(0, result.get("filesRead"));

        @SuppressWarnings("unchecked")
        List<String> notFound = (List<String>) result.get("notFound");
        assertNotNull(notFound);
        assertEquals(1, notFound.size());
        assertEquals("nonexistent.txt", notFound.get(0));
    }

    @Test
    void testPartialSuccess() throws Exception {
        String encodedContent = Base64.getEncoder().encodeToString("I exist".getBytes());
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("exists.txt"), eq("main"), anyString()))
            .thenReturn(new GitHubRestClient.GitHubFileContent(
                "exists.txt", "exists.txt", "abc123", 100, "url", "html_url", "git_url", "download_url", "file", encodedContent, "base64"));
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("missing.txt"), eq("main"), anyString()))
            .thenThrow(new RuntimeException("Not found"));

        Map<String, Object> result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("exists.txt", "missing.txt"), "main");

        assertTrue((Boolean) result.get("success"));
        assertEquals(1, result.get("filesRead"));

        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) result.get("files");
        assertEquals("I exist", files.get("exists.txt"));

        @SuppressWarnings("unchecked")
        List<String> notFound = (List<String>) result.get("notFound");
        assertEquals(1, notFound.size());
        assertEquals("missing.txt", notFound.get(0));
    }

    @Test
    void testInvalidRepoUrl() {
        Map<String, Object> result = sourceCodeTool.readSourceFiles(null, List.of("test.txt"), "main");

        assertFalse((Boolean) result.get("success"));
        assertTrue(result.get("error").toString().contains("repoUrl is required"));
        verifyNoInteractions(githubClient);
    }

    @Test
    void testEmptyFilePaths() {
        Map<String, Object> result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of(), "main");

        assertFalse((Boolean) result.get("success"));
        assertTrue(result.get("error").toString().contains("filePaths"));
        verifyNoInteractions(githubClient);
    }

    @Test
    void testNullFilePaths() {
        Map<String, Object> result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, null, "main");

        assertFalse((Boolean) result.get("success"));
        assertTrue(result.get("error").toString().contains("filePaths"));
        verifyNoInteractions(githubClient);
    }

    @Test
    void testDefaultBranch() throws Exception {
        String encodedContent = Base64.getEncoder().encodeToString("content".getBytes());
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString()))
            .thenReturn(new GitHubRestClient.GitHubFileContent(
                "test.txt", "test.txt", "abc123", 100, "url", "html_url", "git_url", "download_url", "file", encodedContent, "base64"));

        Map<String, Object> result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("test.txt"), null);

        assertTrue((Boolean) result.get("success"));
        assertEquals("main", result.get("branch"));
        verify(githubClient).getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString());
    }

    @Test
    void testFileContentWithLineNumbers() throws Exception {
        String content = "line 1\nline 2\nline 3";
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString()))
            .thenReturn(new GitHubRestClient.GitHubFileContent(
                "test.txt", "test.txt", "abc123", 100, "url", "html_url", "git_url", "download_url", "file", encodedContent, "base64"));

        Map<String, Object> result = sourceCodeTool.readSourceFiles(TEST_REPO_URL, List.of("test.txt"), "main");

        assertTrue((Boolean) result.get("success"));

        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) result.get("files");
        assertEquals("line 1\nline 2\nline 3", files.get("test.txt"));

        @SuppressWarnings("unchecked")
        Map<String, String> numberedFiles = (Map<String, String>) result.get("filesWithLineNumbers");
        assertNotNull(numberedFiles);
        String numberedContent = numberedFiles.get("test.txt");
        assertNotNull(numberedContent);
        assertTrue(numberedContent.contains("   1 | line 1"));
        assertTrue(numberedContent.contains("   2 | line 2"));
        assertTrue(numberedContent.contains("   3 | line 3"));
    }
}
