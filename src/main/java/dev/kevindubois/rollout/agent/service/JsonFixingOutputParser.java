package dev.kevindubois.rollout.agent.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility that fixes common JSON formatting errors before parsing.
 * Specifically handles issues with Qwen models that sometimes use single quotes instead of double quotes.
 */
@ApplicationScoped
public class JsonFixingOutputParser {
    
    /**
     * Fix JSON formatting issues in the given text
     */
    public String fixJson(String text) {
        if (text == null || text.trim().isEmpty()) {
            // Return a minimal valid JSON object that will trigger recovery mechanism
            return "{}";
        }
        
        Log.debug("Original text length: " + text.length());
        
        // Step 1: Extract JSON from potential XML tags or markdown
        String jsonText = extractJson(text);
        
        // Step 2: Fix common JSON formatting issues
        String fixedJson = fixJsonFormatting(jsonText);
        
        Log.debug("Fixed JSON preview: " + fixedJson.substring(0, Math.min(200, fixedJson.length())));
        
        return fixedJson;
    }
    
    /**
     * Extract JSON from text that might contain XML tags or markdown
     */
    private String extractJson(String text) {
        String trimmed = text.trim();
        
        // Remove XML tags like <tool_call>...</tool_call>
        if (trimmed.contains("<tool_call>")) {
            Pattern pattern = Pattern.compile("<tool_call>\\s*(.+?)\\s*</tool_call>", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(trimmed);
            if (matcher.find()) {
                trimmed = matcher.group(1).trim();
            }
        }
        
        // Remove markdown code blocks
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(?:json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        
        // Find JSON object boundaries
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }
        
        return trimmed;
    }
    
    /**
     * Fix common JSON formatting issues:
     * 1. Replace single quotes with double quotes for property names and string values
     * 2. Handle mixed quote scenarios
     * 3. Preserve escaped quotes
    /**
     * Fix malformed JSON operators like := or = instead of :
     * Examples:
     * - "action":="insert_after" -> "action":"insert_after"
     * - "action"="insert_after" -> "action":"insert_after"
     * - {"lineNumber"=127 -> {"lineNumber":127
     */
    private String fixMalformedOperators(String json) {
        // First, fix Unicode smart quotes to ASCII quotes
        // Left double quotation mark (U+201C) and right double quotation mark (U+201D)
        json = json.replace('\u201C', '"');  // " -> "
        json = json.replace('\u201D', '"');  // " -> "
        // Left single quotation mark (U+2018) and right single quotation mark (U+2019)
        json = json.replace('\u2018', '\''); // ' -> '
        json = json.replace('\u2019', '\''); // ' -> '
        
        // Fix escaped backslashes in property names: "action\": -> "action":
        json = json.replaceAll("\"([^\"]+)\\\\\"\\s*:", "\"$1\":");
        
        // Fix := to :
        json = json.replaceAll("\"\\s*:=\\s*\"", "\": \"");
        
        // Fix cases where = appears after a colon: "action":="value" -> "action":"value"
        json = json.replaceAll(":\\s*=\\s*\"", ": \"");
        
        // Fix cases where property names have = instead of :: {"lineNumber"=127 -> {"lineNumber":127
        json = json.replaceAll("\"([^\"]+)\"\\s*=\\s*([0-9]+)", "\"$1\": $2");
        json = json.replaceAll("\"([^\"]+)\"\\s*=\\s*\"", "\"$1\": \"");
        
        // Fix cases where = appears in arrays: ,="content" -> ,"content"
        json = json.replaceAll(",\\s*=\\s*\"", ", \"");
        
        return json;
    }
    /**
     * Remove comments and calculations from JSON
     * Examples:
     * - "errorRateDifference" : (2.4 -1.5) = .9 -> "errorRateDifference": 0.9
     * - // Calculations -> (removed)
     * - confidence:7s -> confidence: 7
     */
    private String removeCommentsAndCalculations(String json) {
        // Remove single-line comments (// ...)
        json = json.replaceAll("//[^\n]*", "");
        
        // Remove multi-line comments (/* ... */)
        json = json.replaceAll("/\\*.*?\\*/", "");
        
        // Fix calculations in values: "property" : (2.4 - 1.5) = 0.9 -> "property": 0.9
        json = json.replaceAll("\"([^\"]+)\"\\s*:\\s*\\([^)]+\\)\\s*=\\s*([0-9.]+)", "\"$1\": $2");
        
        // Fix values with trailing letters: 7s -> 7, 85o -> 85
        json = json.replaceAll(":\\s*([0-9]+)[a-zA-Z]+([,\\s}])", ": $1$2");
        
        // Fix typos in property values: canarry -> canary, p9p -> p99
        json = json.replaceAll("canarry", "canary");
        json = json.replaceAll("p9p", "p99");
        
        return json;
    }
    
    /**
     * Fix missing opening quotes on property names
     * Examples:
     * - "errorRateDifference: -16.83 -> "errorRateDifference": -16.83
     * - "comparison:"text" -> "comparison": "text"
     * - confidence$:4 -> "confidence": 4
     * - "errorRateWithinThreshold:true" -> "errorRateWithinThreshold": true
     */
    private String fixMissingPropertyQuotes(String json) {
        // Fix property names missing closing quote before colon
        // Pattern: "propertyName:value" -> "propertyName": value
        json = json.replaceAll("\"([a-zA-Z_][a-zA-Z0-9_]*):([^\"\\s])", "\"$1\": $2");
        
        // Fix property names that are missing opening quote but have closing quote and colon
        // Pattern: \n"propertyName: value -> \n"propertyName": value
        json = json.replaceAll("\\n\"([a-zA-Z_][a-zA-Z0-9_]*):\\s*", "\n\"$1\": ");
        
        // Fix property names with special characters like $ at the end
        // Pattern: propertyName$: value -> "propertyName": value
        json = json.replaceAll("([a-zA-Z_][a-zA-Z0-9_]*)\\$:\\s*", "\"$1\": ");
        
        // Fix property names that are completely missing quotes
        // Pattern: ,\npropertyName: value -> ,\n"propertyName": value
        json = json.replaceAll("([,{]\\s*\\n)([a-zA-Z_][a-zA-Z0-9_]*):\\s*", "$1\"$2\": ");
        
        // Fix missing opening quote on property names at start of line
        // Pattern: \npropertyName": value -> \n"propertyName": value
        json = json.replaceAll("\\n([a-zA-Z_][a-zA-Z0-9_]*)\":\\s*", "\n\"$1\": ");
        
        return json;
    }
    
    
    /**
     * Fix common JSON formatting issues:
     * 1. Replace single quotes with double quotes for property names and string values
     * 2. Handle mixed quote scenarios
     * 3. Preserve escaped quotes
     */
    private String fixJsonFormatting(String json) {
        // First pass: Remove comments and calculations
        json = removeCommentsAndCalculations(json);
        
        // Second pass: Fix malformed operators like := or = instead of :
        json = fixMalformedOperators(json);
        
        // Third pass: Fix missing opening quotes on property names
        json = fixMissingPropertyQuotes(json);
        
        // Fourth pass: Replace single quotes with double quotes, but be careful about:
        // - Already escaped quotes
        // - Quotes inside string values
        // - Property names vs values
        
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inSingleQuoteString = false;
        char prevChar = '\0';
        
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            char nextChar = (i + 1 < json.length()) ? json.charAt(i + 1) : '\0';
            
            // Handle escape sequences
            if (c == '\\' && (nextChar == '"' || nextChar == '\'' || nextChar == '\\')) {
                result.append(c);
                result.append(nextChar);
                i++; // Skip next character
                prevChar = nextChar;
                continue;
            }
            
            // Handle double quotes
            if (c == '"' && prevChar != '\\') {
                inString = !inString;
                result.append(c);
                prevChar = c;
                continue;
            }
            
            // Handle single quotes - convert to double quotes if they're being used as string delimiters
            if (c == '\'' && prevChar != '\\') {
                // Check if this looks like a string delimiter (not inside a double-quoted string)
                if (!inString) {
                    // This is likely a string delimiter using single quotes - convert to double quote
                    inSingleQuoteString = !inSingleQuoteString;
                    result.append('"');
                } else {
                    // Inside a double-quoted string, keep the single quote
                    result.append(c);
                }
                prevChar = c;
                continue;
            }
            
            // Regular character
            result.append(c);
            prevChar = c;
        }
        
        return result.toString();
    }
}