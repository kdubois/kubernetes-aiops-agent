package dev.kevindubois.rollout.agent.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Unicode smart quote handling in JsonFixingOutputParser
 */
class JsonFixingOutputParserUnicodeTest {
    
    private final JsonFixingOutputParser parser = new JsonFixingOutputParser();
    
    @Test
    void testUnicodeSmartQuotes() {
        // Unicode left/right double quotes (U+201C and U+201D)
        String input = "{\u201Cname\u201D: \u201CcreateGitHubPR\u201D, \u201Ccontent\u201D: \u201Ctest\u201D}";
        String result = parser.fixJson(input);
        
        // Should convert to ASCII quotes
        assertTrue(result.contains("\"name\""), "Should convert Unicode quotes to ASCII");
        assertTrue(result.contains("\"createGitHubPR\""), "Should convert Unicode quotes to ASCII");
        assertFalse(result.contains("\u201C"), "Should not contain left Unicode quote");
        assertFalse(result.contains("\u201D"), "Should not contain right Unicode quote");
    }
    
    @Test
    void testMixedUnicodeAndAsciiQuotes() {
        // Mix of Unicode and ASCII quotes
        String input = "{\u201Cname\u201D: \"value\", \u201Ckey\u201D: \u201Cdata\u201D}";
        String result = parser.fixJson(input);
        
        // All should be ASCII
        assertFalse(result.contains("\u201C"), "Should not contain left Unicode quote");
        assertFalse(result.contains("\u201D"), "Should not contain right Unicode quote");
        assertTrue(result.contains("\"name\""), "Should have ASCII quotes");
    }
    
    @Test
    void testComplexJsonWithUnicodeQuotes() {
        String input = """
            {
              "repoUrl": "https://github.com/test/repo",
              "patches": [
                {
                  "filePath": "src/main/Test.java",
                  "changes": [
                    {"lineNumber": 123, "action": "insert_after"}
                  ]
                }
              ]
            }
            """;
        
        String result = parser.fixJson(input);
        
        // Should be valid JSON with ASCII quotes only
        assertFalse(result.contains("\u201C"), "Should not contain Unicode quotes");
        assertFalse(result.contains("\u201D"), "Should not contain Unicode quotes");
        assertTrue(result.contains("\"repoUrl\""), "Should have ASCII quotes");
        assertTrue(result.contains("\"patches\""), "Should have ASCII quotes");
    }
}