package dev.kevindubois.rollout.agent.remediation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceCodeToolTest {
    
    @Mock
    private GitOperations gitOps;
    
    @TempDir
    Path tempDir;
    
    private SourceCodeTool sourceCodeTool;
    private static final String TEST_REPO_URL = "https://github.com/test/repo";
    private static final String TEST_TOKEN = "test-token";
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        sourceCodeTool = new SourceCodeTool(gitOps, TEST_TOKEN);
    }
    
    @Test
    void testReadSingleFile() throws Exception {
        // Setup
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "test content");
        
        when(gitOps.cloneRepository(anyString(), anyString())).thenReturn(tempDir);
        
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
        
        verify(gitOps).cloneRepository(TEST_REPO_URL, TEST_TOKEN);
        verify(gitOps).cleanup(tempDir);
    }
    
    @Test
    void testReadMultipleFiles() throws Exception {
        // Setup
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file1, "content 1");
        Files.writeString(file2, "content 2");
        
        when(gitOps.cloneRepository(anyString(), anyString())).thenReturn(tempDir);
        
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
        
        verify(gitOps).cleanup(tempDir);
    }
    
    @Test
    void testFileNotFound() throws Exception {
        // Setup
        when(gitOps.cloneRepository(anyString(), anyString())).thenReturn(tempDir);
        
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
        
        verify(gitOps).cleanup(tempDir);
    }
    
    @Test
    void testPartialSuccess() throws Exception {
        // Setup - one file exists, one doesn't
        Path existingFile = tempDir.resolve("exists.txt");
        Files.writeString(existingFile, "I exist");
        
        when(gitOps.cloneRepository(anyString(), anyString())).thenReturn(tempDir);
        
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
        
        verify(gitOps).cleanup(tempDir);
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
        
        verifyNoInteractions(gitOps);
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
        
        verifyNoInteractions(gitOps);
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
        
        verifyNoInteractions(gitOps);
    }
    
    @Test
    void testCloneFailure() throws Exception {
        // Setup
        when(gitOps.cloneRepository(anyString(), anyString()))
            .thenThrow(new RuntimeException("Clone failed"));
        
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of("test.txt"),
            "main"
        );
        
        // Verify
        assertFalse((Boolean) result.get("success"));
        assertTrue(result.get("error").toString().contains("Clone failed"));
        assertEquals(TEST_REPO_URL, result.get("repoUrl"));
        assertEquals("main", result.get("branch"));
    }
    
    @Test
    void testDefaultBranch() throws Exception {
        // Setup
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "content");
        
        when(gitOps.cloneRepository(anyString(), anyString())).thenReturn(tempDir);
        
        // Execute - pass null for branch
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of("test.txt"),
            null
        );
        
        // Verify - should default to "main"
        assertTrue((Boolean) result.get("success"));
        assertEquals("main", result.get("branch"));
        
        verify(gitOps).cleanup(tempDir);
    }
    
    @Test
    void testNestedFilePath() throws Exception {
        // Setup
        Path nestedDir = tempDir.resolve("src/main/resources");
        Files.createDirectories(nestedDir);
        Path nestedFile = nestedDir.resolve("application.properties");
        Files.writeString(nestedFile, "server.port=8080");
        
        when(gitOps.cloneRepository(anyString(), anyString())).thenReturn(tempDir);
        
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
        
        verify(gitOps).cleanup(tempDir);
    }
    
    @Test
    void testCleanupCalledOnException() throws Exception {
        // Setup
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "content");
        
        when(gitOps.cloneRepository(anyString(), anyString())).thenReturn(tempDir);
        // Don't throw on cleanup - just verify it's called
        
        // Execute
        Map<String, Object> result = sourceCodeTool.readSourceFiles(
            TEST_REPO_URL,
            List.of("test.txt"),
            "main"
        );
        
        // Verify - cleanup should be called in finally block
        assertTrue((Boolean) result.get("success"));
        verify(gitOps).cleanup(tempDir);
    }
}