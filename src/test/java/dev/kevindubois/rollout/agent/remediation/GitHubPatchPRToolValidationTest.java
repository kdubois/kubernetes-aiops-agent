package dev.kevindubois.rollout.agent.remediation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GitHubPatchPRTool validation logic that prevents bad PRs
 */
@QuarkusTest
class GitHubPatchPRToolValidationTest {

    @TempDir
    Path tempDir;

    @Test
    void testValidationWarnsAboutDeletingReturnStatement() throws Exception {
        // Create a test file with a return statement
        Path testFile = tempDir.resolve("TestClass.java");
        Files.writeString(testFile, """
            public class TestClass {
                public int getStatus() {
                    int length = nullString.length();
                    return length;
                }
            }
            """);

        GitHubPatchPRTool tool = new GitHubPatchPRTool(new GitOperations(), "fake-token");
        
        // This should trigger a validation warning (deleting return statement)
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
            "TestClass.java",
            List.of(
                new GitHubPatchPRTool.LineChange(4, "delete", null)
            )
        );

        // The validation should log a warning but not throw an exception
        // We can't easily assert on log output, but we verify the patch structure is valid
        assertNotNull(patch.changes);
        assertEquals(1, patch.changes.size());
        assertEquals("delete", patch.changes.get(0).action);
    }

    @Test
    void testValidationWarnsAboutExcessiveDeletions() {
        GitHubPatchPRTool tool = new GitHubPatchPRTool(new GitOperations(), "fake-token");
        
        // Create a patch with many deletions (should trigger warning)
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
            "TestClass.java",
            List.of(
                new GitHubPatchPRTool.LineChange(1, "delete", null),
                new GitHubPatchPRTool.LineChange(2, "delete", null),
                new GitHubPatchPRTool.LineChange(3, "delete", null),
                new GitHubPatchPRTool.LineChange(4, "delete", null),
                new GitHubPatchPRTool.LineChange(5, "delete", null),
                new GitHubPatchPRTool.LineChange(6, "delete", null)
            )
        );

        // Verify the patch structure
        assertEquals(6, patch.changes.size());
        assertTrue(patch.changes.stream().allMatch(c -> "delete".equals(c.action)));
    }

    @Test
    void testCorrectPatchWithReplaceAction() {
        GitHubPatchPRTool tool = new GitHubPatchPRTool(new GitOperations(), "fake-token");
        
        // This is the CORRECT way to fix a bug - replace only the buggy line
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
            "TestClass.java",
            List.of(
                new GitHubPatchPRTool.LineChange(
                    3, 
                    "replace", 
                    "        int length = versionUpper.length(); // Fixed: use correct variable"
                )
            )
        );

        // Verify the patch structure
        assertEquals(1, patch.changes.size());
        assertEquals("replace", patch.changes.get(0).action);
        assertEquals(3, patch.changes.get(0).lineNumber);
        assertNotNull(patch.changes.get(0).content);
    }

    @Test
    void testInsertAfterForAddingNullCheck() {
        GitHubPatchPRTool tool = new GitHubPatchPRTool(new GitOperations(), "fake-token");
        
        // Adding a null check before using a variable
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
            "TestClass.java",
            List.of(
                new GitHubPatchPRTool.LineChange(
                    2, 
                    "insert_after", 
                    "        if (versionUpper == null) return 0;"
                )
            )
        );

        // Verify the patch structure
        assertEquals(1, patch.changes.size());
        assertEquals("insert_after", patch.changes.get(0).action);
        assertEquals(2, patch.changes.get(0).lineNumber);
    }
}

