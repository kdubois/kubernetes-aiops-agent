package dev.kevindubois.rollout.agent.a2a;

import java.util.Map;

/**
 * Request for Kubernetes Agent analysis.
 *
 * @param userId  User identifier for the request
 * @param prompt  The analysis prompt/question
 * @param context Additional context information for the analysis
 */
public record KubernetesAgentRequest(
    String userId,
    String prompt,
    Map<String, Object> context
) {
}
