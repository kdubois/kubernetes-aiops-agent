package org.csanchez.rollout.k8sagent.model;

import java.util.Map;

/**
 * Request class for Kubernetes Agent analysis.
 *
 * @param userId User identifier for the request
 * @param prompt The analysis prompt/question
 * @param context Additional context information for the analysis
 * @param memoryId Optional memory identifier for maintaining conversation history.
 *                 If null, a new conversation will be started using userId as the memory ID.
 */
public record KubernetesAgentRequest(
    String userId,
    String prompt,
    Map<String, Object> context,
    String memoryId
) {
    /**
     * Constructor with default memoryId (uses userId).
     * This maintains backward compatibility with existing code.
     */
    public KubernetesAgentRequest(String userId, String prompt, Map<String, Object> context) {
        this(userId, prompt, context, null);
    }
    
    /**
     * Returns the effective memory ID to use for conversation history.
     * Falls back to userId if memoryId is not provided.
     */
    public String getEffectiveMemoryId() {
        return memoryId != null ? memoryId : userId;
    }
}


