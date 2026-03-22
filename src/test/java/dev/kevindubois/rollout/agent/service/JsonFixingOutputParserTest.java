package dev.kevindubois.rollout.agent.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonFixingOutputParserTest {
    
    private final JsonFixingOutputParser parser = new JsonFixingOutputParser();
    
    @Test
    void testFixSingleQuotesToDoubleQuotes() {
        String input = "{'lineNumber': 60, 'action': 'insert_after', 'content': 'line'}";
        String expected = "{\"lineNumber\": 60, \"action\": \"insert_after\", \"content\": \"line\"}";
        
        String result = parser.fixJson(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testMixedQuotes() {
        String input = "{\"lineNumber\": 60, 'action': 'insert_after', \"content\": \"line\"}";
        String expected = "{\"lineNumber\": 60, \"action\": \"insert_after\", \"content\": \"line\"}";
        
        String result = parser.fixJson(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testNestedObject() {
        String input = "{'outer': {'inner': 'value', 'number': 123}}";
        String expected = "{\"outer\": {\"inner\": \"value\", \"number\": 123}}";
        
        String result = parser.fixJson(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testArrayWithSingleQuotes() {
        String input = "{'items': ['one', 'two', 'three']}";
        String expected = "{\"items\": [\"one\", \"two\", \"three\"]}";
        
        String result = parser.fixJson(input);
        
        assertEquals(expected, result);
    }
    
    @Test
    void testExtractFromXmlTags() {
        String input = "<tool_call>\n{'action': 'test'}\n</tool_call>";
        String result = parser.fixJson(input);
        
        assertTrue(result.contains("\"action\""));
        assertTrue(result.contains("\"test\""));
        assertFalse(result.contains("<tool_call>"));
    }
    
    @Test
    void testExtractFromMarkdown() {
        String input = "```json\n{'action': 'test'}\n```";
        String result = parser.fixJson(input);
        
        assertTrue(result.contains("\"action\""));
        assertTrue(result.contains("\"test\""));
        assertFalse(result.contains("```"));
    }
    
    @Test
    void testAlreadyValidJson() {
        String input = "{\"lineNumber\": 60, \"action\": \"insert_after\"}";
        String result = parser.fixJson(input);
        
        assertEquals(input, result);
    }
    
    @Test
    void testComplexRealWorldExample() {
        // This is the actual problematic output from the error log
        String input = """
            {"lineNumber": 60, "action": 'insert_after', 'content': '        }'}
            """;
        
        String result = parser.fixJson(input);
        
        // Should convert all single quotes to double quotes
        assertFalse(result.contains("'"));
        assertTrue(result.contains("\"action\""));
        assertTrue(result.contains("\"insert_after\""));
        assertTrue(result.contains("\"content\""));
    }
    
    @Test
    void testPreserveEscapedQuotes() {
        String input = "{\"message\": \"He said \\\"hello\\\"\"}";
        String result = parser.fixJson(input);
        
        assertEquals(input, result);
    }
    
    @Test
    void testSingleQuoteInDoubleQuotedString() {
        String input = "{\"message\": \"It's working\"}";
        String result = parser.fixJson(input);
        
        // Single quote inside double-quoted string should be preserved
        assertEquals(input, result);
    }
}