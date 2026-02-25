package dev.kevindubois.rollout.agent.model;

import java.util.Objects;

/**
 * Immutable response class for Kubernetes Agent analysis results.
 * 
 * <p>This record encapsulates the complete analysis output including:
 * <ul>
 *   <li>Full analysis text</li>
 *   <li>Root cause identification</li>
 *   <li>Remediation recommendations</li>
 *   <li>Pull request link (if remediation was automated)</li>
 *   <li>Promotion decision for canary deployments</li>
 *   <li>Confidence score (0-100)</li>
 * </ul>
 * 
 * <p>Use the builder-style {@code with*()} methods to create modified copies
 * while maintaining immutability.
 * 
 * @param analysis Full analysis text from the agent
 * @param rootCause Identified root cause of the issue
 * @param remediation Recommended remediation steps
 * @param prLink GitHub pull request link if automated fix was created (nullable)
 * @param promote Whether to promote the canary deployment
 * @param confidence Confidence score between 0 and 100 (inclusive)
 */
public record KubernetesAgentResponse(
    String analysis,
    String rootCause,
    String remediation,
    String prLink,
    boolean promote,
    int confidence
) {
    /**
     * Compact canonical constructor with validation.
     * 
     * @throws IllegalArgumentException if confidence is not in range [0, 100]
     * @throws NullPointerException if any required field (except prLink) is null
     */
    public KubernetesAgentResponse {
        // Validate required fields
        Objects.requireNonNull(analysis, "analysis cannot be null");
        Objects.requireNonNull(rootCause, "rootCause cannot be null");
        Objects.requireNonNull(remediation, "remediation cannot be null");
        
        // Validate confidence range
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException(
                "confidence must be between 0 and 100, got: " + confidence
            );
        }
        
        // Normalize empty strings to prevent inconsistencies
        analysis = analysis.trim();
        rootCause = rootCause.trim();
        remediation = remediation.trim();
        if (prLink != null) {
            prLink = prLink.trim();
            if (prLink.isEmpty()) {
                prLink = null;
            }
        }
    }
    
    /**
     * Creates a default response with empty values.
     * Useful as a starting point for builder-style construction.
     * 
     * @return A new response with empty strings and default values
     */
    public static KubernetesAgentResponse empty() {
        return new KubernetesAgentResponse("", "", "", null, false, 0);
    }
    
    /**
     * Creates a new response with updated analysis text.
     * 
     * @param analysis The new analysis text
     * @return A new response instance with the updated analysis
     * @throws NullPointerException if analysis is null
     */
    public KubernetesAgentResponse withAnalysis(String analysis) {
        return new KubernetesAgentResponse(
            analysis, 
            this.rootCause, 
            this.remediation, 
            this.prLink, 
            this.promote, 
            this.confidence
        );
    }
    
    /**
     * Creates a new response with updated root cause.
     * 
     * @param rootCause The new root cause description
     * @return A new response instance with the updated root cause
     * @throws NullPointerException if rootCause is null
     */
    public KubernetesAgentResponse withRootCause(String rootCause) {
        return new KubernetesAgentResponse(
            this.analysis, 
            rootCause, 
            this.remediation, 
            this.prLink, 
            this.promote, 
            this.confidence
        );
    }
    
    /**
     * Creates a new response with updated remediation steps.
     * 
     * @param remediation The new remediation description
     * @return A new response instance with the updated remediation
     * @throws NullPointerException if remediation is null
     */
    public KubernetesAgentResponse withRemediation(String remediation) {
        return new KubernetesAgentResponse(
            this.analysis, 
            this.rootCause, 
            remediation, 
            this.prLink, 
            this.promote, 
            this.confidence
        );
    }
    
    /**
     * Creates a new response with updated PR link.
     * 
     * @param prLink The new pull request link (nullable)
     * @return A new response instance with the updated PR link
     */
    public KubernetesAgentResponse withPrLink(String prLink) {
        return new KubernetesAgentResponse(
            this.analysis, 
            this.rootCause, 
            this.remediation, 
            prLink, 
            this.promote, 
            this.confidence
        );
    }
    
    /**
     * Creates a new response with updated promotion decision.
     * 
     * @param promote Whether to promote the canary deployment
     * @return A new response instance with the updated promotion decision
     */
    public KubernetesAgentResponse withPromote(boolean promote) {
        return new KubernetesAgentResponse(
            this.analysis, 
            this.rootCause, 
            this.remediation, 
            this.prLink, 
            promote, 
            this.confidence
        );
    }
    
    /**
     * Creates a new response with updated confidence score.
     * 
     * @param confidence The new confidence score (0-100)
     * @return A new response instance with the updated confidence
     * @throws IllegalArgumentException if confidence is not in range [0, 100]
     */
    public KubernetesAgentResponse withConfidence(int confidence) {
        return new KubernetesAgentResponse(
            this.analysis, 
            this.rootCause, 
            this.remediation, 
            this.prLink, 
            this.promote, 
            confidence
        );
    }
    
    /**
     * Checks if this response has a pull request link.
     * 
     * @return true if a PR link is present, false otherwise
     */
    public boolean hasPrLink() {
        return prLink != null && !prLink.isEmpty();
    }
    
    /**
     * Checks if the confidence score indicates high confidence.
     * High confidence is defined as >= 80.
     * 
     * @return true if confidence >= 80, false otherwise
     */
    public boolean isHighConfidence() {
        return confidence >= 80;
    }
    
    /**
     * Checks if the confidence score indicates low confidence.
     * Low confidence is defined as < 50.
     * 
     * @return true if confidence < 50, false otherwise
     */
    public boolean isLowConfidence() {
        return confidence < 50;
    }
}

