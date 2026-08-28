package dev.kevindubois.rollout.agent.remediation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GitHubPatchPRTool validation logic that prevents destructive PRs.
 */
class GitHubPatchPRToolValidationTest {

    private static final List<String> SAMPLE_FILE = List.of(
            "package com.example;",                            // 1
            "",                                                // 2
            "public class DemoResource {",                     // 3
            "",                                                // 4
            "    private boolean enableNullPointerBug = true;", // 5
            "",                                                // 6
            "    public String getVersion() {",                // 7
            "        String versionUpper = null;",             // 8
            "        if (enableNullPointerBug) {",             // 9
            "            versionUpper = null;",                // 10
            "        }",                                       // 11
            "        return versionUpper.toUpperCase();",      // 12
            "    }",                                           // 13
            "}"                                                // 14
    );

    // --- Task 2.3: Reject >2 deletes ---

    @Test
    void excessiveDeletesRejected() {
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
                "DemoResource.java",
                List.of(
                        new GitHubPatchPRTool.LineChange(9, "delete", null),
                        new GitHubPatchPRTool.LineChange(10, "delete", null),
                        new GitHubPatchPRTool.LineChange(11, "delete", null)
                )
        );

        Exception e = assertThrows(Exception.class, () ->
                GitHubPatchPRTool.validatePatch(patch, SAMPLE_FILE));
        assertTrue(e.getMessage().contains("delete operations"));
        assertTrue(e.getMessage().contains("max 2"));
    }

    // --- Task 2.3: Reject >3 total changes ---

    @Test
    void tooManyTotalChangesRejected() {
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
                "DemoResource.java",
                List.of(
                        new GitHubPatchPRTool.LineChange(5, "replace", "    private boolean enableNullPointerBug = false;"),
                        new GitHubPatchPRTool.LineChange(8, "replace", "        String versionUpper = \"\";"),
                        new GitHubPatchPRTool.LineChange(10, "replace", "            versionUpper = \"v1\";"),
                        new GitHubPatchPRTool.LineChange(12, "replace", "        return versionUpper != null ? versionUpper.toUpperCase() : \"\";")
                )
        );

        Exception e = assertThrows(Exception.class, () ->
                GitHubPatchPRTool.validatePatch(patch, SAMPLE_FILE));
        assertTrue(e.getMessage().contains("total changes"));
        assertTrue(e.getMessage().contains("max 3"));
    }

    // --- Task 2.3: Reject return/}/catch deletes ---

    @Test
    void deletingReturnStatementRejected() {
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
                "DemoResource.java",
                List.of(new GitHubPatchPRTool.LineChange(12, "delete", null))
        );

        Exception e = assertThrows(Exception.class, () ->
                GitHubPatchPRTool.validatePatch(patch, SAMPLE_FILE));
        assertTrue(e.getMessage().contains("structural line"));
    }

    @Test
    void deletingClosingBraceRejected() {
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
                "DemoResource.java",
                List.of(new GitHubPatchPRTool.LineChange(11, "delete", null))
        );

        Exception e = assertThrows(Exception.class, () ->
                GitHubPatchPRTool.validatePatch(patch, SAMPLE_FILE));
        assertTrue(e.getMessage().contains("structural line"));
    }

    // --- Task 2.3: Reject incomplete control-flow edit ---

    @Test
    void deletingIfWithoutBlockBodyRejected() {
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
                "DemoResource.java",
                List.of(new GitHubPatchPRTool.LineChange(9, "delete", null))
        );

        Exception e = assertThrows(Exception.class, () ->
                GitHubPatchPRTool.validatePatch(patch, SAMPLE_FILE));
        assertTrue(e.getMessage().contains("control flow"));
    }

    // --- Task 2.4: 10-delete wipe of if(enableNullPointerBug) block rejected ---

    @Test
    void tenDeleteWipeOfNPEBlockRejected() {
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
                "DemoResource.java",
                List.of(
                        new GitHubPatchPRTool.LineChange(5, "delete", null),
                        new GitHubPatchPRTool.LineChange(6, "delete", null),
                        new GitHubPatchPRTool.LineChange(7, "delete", null),
                        new GitHubPatchPRTool.LineChange(8, "delete", null),
                        new GitHubPatchPRTool.LineChange(9, "delete", null),
                        new GitHubPatchPRTool.LineChange(10, "delete", null),
                        new GitHubPatchPRTool.LineChange(11, "delete", null),
                        new GitHubPatchPRTool.LineChange(12, "delete", null),
                        new GitHubPatchPRTool.LineChange(13, "delete", null),
                        new GitHubPatchPRTool.LineChange(14, "delete", null)
                )
        );

        Exception e = assertThrows(Exception.class, () ->
                GitHubPatchPRTool.validatePatch(patch, SAMPLE_FILE));
        assertTrue(e.getMessage().contains("replace"), "Error should guide to use replace");
    }

    // --- Task 2.5: One-line replace of the NPE line is accepted ---

    @Test
    void oneLineReplaceOfBuggyLineAccepted() throws Exception {
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
                "DemoResource.java",
                List.of(new GitHubPatchPRTool.LineChange(10, "replace",
                        "            versionUpper = \"v1.0\";"))
        );

        assertDoesNotThrow(() -> GitHubPatchPRTool.validatePatch(patch, SAMPLE_FILE));
    }

    @Test
    void twoLineReplaceAccepted() throws Exception {
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
                "DemoResource.java",
                List.of(
                        new GitHubPatchPRTool.LineChange(8, "replace", "        String versionUpper = \"v1\";"),
                        new GitHubPatchPRTool.LineChange(10, "replace", "            versionUpper = \"v2\";")
                )
        );

        assertDoesNotThrow(() -> GitHubPatchPRTool.validatePatch(patch, SAMPLE_FILE));
    }

    // --- Task 2.2: expectedLine mismatch rejected ---

    @Test
    void expectedLineMismatchRejected() {
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
                "DemoResource.java",
                List.of(new GitHubPatchPRTool.LineChange(10, "replace",
                        "            versionUpper = \"v1.0\";",
                        "WRONG EXPECTED LINE"))
        );

        Exception e = assertThrows(Exception.class, () ->
                GitHubPatchPRTool.validateExpectedLines(patch, SAMPLE_FILE));
        assertTrue(e.getMessage().contains("expectedLine mismatch"));
    }

    @Test
    void expectedLineMatchAccepted() {
        GitHubPatchPRTool.FilePatch patch = new GitHubPatchPRTool.FilePatch(
                "DemoResource.java",
                List.of(new GitHubPatchPRTool.LineChange(10, "replace",
                        "            versionUpper = \"v1.0\";",
                        "versionUpper = null;"))
        );

        assertDoesNotThrow(() -> GitHubPatchPRTool.validateExpectedLines(patch, SAMPLE_FILE));
    }
}
