package dev.kevindubois.rollout.agent.remediation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceCodeToolTest {
    
    @Mock
    private GitHubRestClient githubClient;
    
    private SourceCodeTool sourceCodeTool;
    private static final String TEST_REPO_URL = "https://github.com/test/repo";
    private static final String TEST_TOKEN = "test-token";
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        sourceCodeTool = new SourceCodeTool(TEST_TOKEN);
        // Inject mock client via reflection for testing
        try {
            java.lang.reflect.Field field = SourceCodeTool.class.getDeclaredField("githubClient");
            field.setAccessible(true);
            field.set(sourceCodeTool, githubClient);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Test
    void testReadSingleFile() throws Exception {
        // Setup
        String content = "test content";
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
        GitHubRestClient.GitHubFileContent fileContent = new GitHubRestClient.GitHubFileContent(
            "test.txt", "test.txt", "abc123", 100, "url", "html_url", "git_url", 
            "download_url", "file", encodedContent, "base64"
        );
        
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString()))
            .thenReturn(fileContent);
        
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of("test.txt"),
            "main"
        );
        
        // Verify
        assertTrue((Boolean) result.get("success"));
        assertEquals(1, result.get("filesRead"));
        
        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) result.get("files");
        assertEquals("test content", files.get("test.txt"));
        
        verify(githubClient).getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString());
    }
    
    @Test
    void testReadMultipleFiles() throws Exception {
        // Setup
        String content1 = "content 1";
        String content2 = "content 2";
        String encodedContent1 = Base64.getEncoder().encodeToString(content1.getBytes());
        String encodedContent2 = Base64.getEncoder().encodeToString(content2.getBytes());
        
        GitHubRestClient.GitHubFileContent fileContent1 = new GitHubRestClient.GitHubFileContent(
            "file1.txt", "file1.txt", "abc123", 100, "url", "html_url", "git_url", 
            "download_url", "file", encodedContent1, "base64"
        );
        GitHubRestClient.GitHubFileContent fileContent2 = new GitHubRestClient.GitHubFileContent(
            "file2.txt", "file2.txt", "def456", 100, "url", "html_url", "git_url", 
            "download_url", "file", encodedContent2, "base64"
        );
        
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("file1.txt"), eq("main"), anyString()))
            .thenReturn(fileContent1);
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("file2.txt"), eq("main"), anyString()))
            .thenReturn(fileContent2);
        
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of("file1.txt", "file2.txt"),
            "main"
        );
        
        // Verify
        assertTrue((Boolean) result.get("success"));
        assertEquals(2, result.get("filesRead"));
        
        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) result.get("files");
        assertEquals("content 1", files.get("file1.txt"));
        assertEquals("content 2", files.get("file2.txt"));
    }
    
    @Test
    void testFileNotFound() throws Exception {
        // Setup
        when(githubClient.getFileContent(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Not found"));
        
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of("nonexistent.txt"),
            "main"
        );
        
        // Verify
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
        // Setup - one file exists, one doesn't
        String content = "I exist";
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
        GitHubRestClient.GitHubFileContent fileContent = new GitHubRestClient.GitHubFileContent(
            "exists.txt", "exists.txt", "abc123", 100, "url", "html_url", "git_url", 
            "download_url", "file", encodedContent, "base64"
        );
        
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("exists.txt"), eq("main"), anyString()))
            .thenReturn(fileContent);
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("missing.txt"), eq("main"), anyString()))
            .thenThrow(new RuntimeException("Not found"));
        
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of("exists.txt", "missing.txt"),
            "main"
        );
        
        // Verify
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
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            null,
            List.of("test.txt"),
            "main"
        );
        
        // Verify
        assertFalse((Boolean) result.get("success"));
        assertTrue(result.get("error").toString().contains("repoUrl is required"));
        
        verifyNoInteractions(githubClient);
    }
    
    @Test
    void testEmptyFilePaths() {
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of(),
            "main"
        );
        
        // Verify
        assertFalse((Boolean) result.get("success"));
        assertTrue(result.get("error").toString().contains("filePaths"));
        
        verifyNoInteractions(githubClient);
    }
    
    @Test
    void testNullFilePaths() {
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            null,
            "main"
        );
        
        // Verify
        assertFalse((Boolean) result.get("success"));
        assertTrue(result.get("error").toString().contains("filePaths"));
        
        verifyNoInteractions(githubClient);
    }
    
    @Test
    void testDefaultBranch() throws Exception {
        // Setup
        String content = "content";
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
        GitHubRestClient.GitHubFileContent fileContent = new GitHubRestClient.GitHubFileContent(
            "test.txt", "test.txt", "abc123", 100, "url", "html_url", "git_url", 
            "download_url", "file", encodedContent, "base64"
        );
        
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString()))
            .thenReturn(fileContent);
        
        // Execute - pass null for branch
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of("test.txt"),
            null
        );
        
        // Verify - should default to "main"
        assertTrue((Boolean) result.get("success"));
        assertEquals("main", result.get("branch"));
        
        verify(githubClient).getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString());
    }
    
    @Test
    void testNestedFilePath() throws Exception {
        // Setup
        String content = "server.port=8080";
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
        GitHubRestClient.GitHubFileContent fileContent = new GitHubRestClient.GitHubFileContent(
            "application.properties", "src/main/resources/application.properties", "abc123", 100, 
            "url", "html_url", "git_url", "download_url", "file", encodedContent, "base64"
        );
        
        when(githubClient.getFileContent(eq("test"), eq("repo"), 
            eq("src/main/resources/application.properties"), eq("main"), anyString()))
            .thenReturn(fileContent);
        
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of("src/main/resources/application.properties"),
            "main"
        );
        
        // Verify
        assertTrue((Boolean) result.get("success"));
        assertEquals(1, result.get("filesRead"));
        
        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) result.get("files");
        assertEquals("server.port=8080", files.get("src/main/resources/application.properties"));
    }
    
    @Test
    void testFileContentWithLineNumbers() throws Exception {
        // Setup - multi-line content
        String content = "line 1\nline 2\nline 3";
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
        GitHubRestClient.GitHubFileContent fileContent = new GitHubRestClient.GitHubFileContent(
            "test.txt", "test.txt", "abc123", 100, "url", "html_url", "git_url",
            "download_url", "file", encodedContent, "base64"
        );
        
        when(githubClient.getFileContent(eq("test"), eq("repo"), eq("test.txt"), eq("main"), anyString()))
            .thenReturn(fileContent);
        
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of("test.txt"),
            "main"
        );
        
        // Verify
        assertTrue((Boolean) result.get("success"));
        
        // Check regular files
        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) result.get("files");
        assertEquals("line 1\nline 2\nline 3", files.get("test.txt"));
        
        // Check files with line numbers
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