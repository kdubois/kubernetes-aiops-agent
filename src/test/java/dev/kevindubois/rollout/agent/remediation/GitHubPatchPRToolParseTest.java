package dev.kevindubois.rollout.agent.remediation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitHubPatchPRToolParseTest {

    @Test
    void parsePatchesJsonFromArrayString() throws Exception {
        String json = """
            [{"filePath":"src/main/java/Foo.java","changes":[{"lineNumber":42,"action":"replace","content":"    fixed;"}]}]
            """;

        List<Map<String, Object>> patches = GitHubPatchPRTool.parsePatchesJson(json);

        assertEquals(1, patches.size());
        assertEquals("src/main/java/Foo.java", patches.get(0).get("filePath"));
    }

    @Test
    void parsePatchesJsonFromDoubleEncodedString() throws Exception {
        String inner = "[{\"filePath\":\"src/main/java/Foo.java\",\"changes\":[{\"lineNumber\":42,\"action\":\"replace\",\"content\":\"    fixed;\"}]}]";
        String doubleEncoded = "\"" + inner.replace("\"", "\\\"") + "\"";

        List<Map<String, Object>> patches = GitHubPatchPRTool.parsePatchesJson(doubleEncoded);

        assertEquals(1, patches.size());
        assertEquals("src/main/java/Foo.java", patches.get(0).get("filePath"));
    }

    @Test
    void parsePatchesJsonRejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class,
                () -> GitHubPatchPRTool.parsePatchesJson("[{\"filePath\": \"incomplete\""));
    }
}
