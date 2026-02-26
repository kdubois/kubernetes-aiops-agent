package dev.kevindubois.rollout.agent.utils;

import io.quarkus.logging.Log;
import java.text.MessageFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limits tool calls per session to prevent rate limiting issues.
 * Tracks tool calls by memory ID and enforces a maximum limit.
 */
public class ToolCallLimiter {
    
    private static final int MAX_TOOL_CALLS = 4;
    private static final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Integer>> toolCallHistory = new ConcurrentHashMap<>();
    
    /**
     * Check if a tool call should be allowed
     * @param memoryId The session/memory ID
     * @param toolName The name of the tool being called
     * @param params String representation of parameters for duplicate detection
     * @return true if the call should be allowed, false otherwise
     */
    public static boolean allowToolCall(String memoryId, String toolName, String params) {
        // Get or create call count for this session
        AtomicInteger count = callCounts.computeIfAbsent(memoryId, k -> new AtomicInteger(0));
        
        // Check if we've exceeded the limit
        if (count.get() >= MAX_TOOL_CALLS) {
            Log.warn(MessageFormat.format(
                "Tool call limit ({0}) reached for session {1}. Rejecting call to {2}",
                MAX_TOOL_CALLS, memoryId, toolName
            ));
            return false;
        }
        
        // Check for duplicate calls
        Map<String, Integer> history = toolCallHistory.computeIfAbsent(memoryId, k -> new ConcurrentHashMap<>());
        String callKey = toolName + ":" + params;
        
        if (history.containsKey(callKey)) {
            Log.warn(MessageFormat.format(
                "Duplicate tool call detected for session {0}: {1} with params {2}",
                memoryId, toolName, params
            ));
            return false;
        }
        
        // Record this call
        history.put(callKey, count.incrementAndGet());
        Log.info(MessageFormat.format(
            "Tool call {0}/{1} for session {2}: {3}",
            count.get(), MAX_TOOL_CALLS, memoryId, toolName
        ));
        
        return true;
    }
    
    /**
     * Reset the call count for a session (call this when starting a new analysis)
     */
    public static void resetSession(String memoryId) {
        callCounts.remove(memoryId);
        toolCallHistory.remove(memoryId);
        Log.debug(MessageFormat.format("Reset tool call limiter for session {0}", memoryId));
    }
    
    /**
     * Get the current call count for a session
     */
    public static int getCallCount(String memoryId) {
        AtomicInteger count = callCounts.get(memoryId);
        return count != null ? count.get() : 0;
    }
    
    /**
     * Clean up old sessions (call periodically to prevent memory leaks)
     */
    public static void cleanup() {
        // In a production system, you'd want to track timestamps and remove old entries
        // For now, we'll keep it simple
        if (callCounts.size() > 100) {
            Log.warn("Tool call limiter has many sessions, consider implementing cleanup");
        }
    }
}

