package dev.kevindubois.rollout.agent.service;

import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for AgentResponseFormatter.
 * Tests formatting of agent responses into markdown structure.
 */
class AgentResponseFormatterTest {

    private AgentResponseFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new AgentResponseFormatter();
    }

    // ========== Basic Formatting Tests ==========

    @Test
    void testFormat_withAllFields() {
        // Given: Response with all fields populated
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Detailed analysis of the canary deployment shows high error rates.",
            "Database connection pool exhaustion",
            "Increase connection pool size and add timeout configuration",
            "https://github.com/org/repo/pull/123",
            false,
            75
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: All sections are present with proper markdown structure
        assertThat(formatted).contains("# Kubernetes Analysis");
        assertThat(formatted).contains("Detailed analysis of the canary deployment");
        assertThat(formatted).contains("## Summary");
        assertThat(formatted).contains("- **Root Cause**: Database connection pool exhaustion");
        assertThat(formatted).contains("- **Remediation**: Increase connection pool size");
        assertThat(formatted).contains("- **Recommendation**: Do not promote canary");
        assertThat(formatted).contains("- **Confidence**: 75%");
        assertThat(formatted).contains("- **PR Link**: https://github.com/org/repo/pull/123");
    }

    @Test
    void testFormat_withNullFields() {
        // Given: Response with null PR link (optional field)
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Analysis text",
            "Root cause identified",
            "Remediation steps",
            null,  // null PR link
            true,
            80
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: PR link section is not included
        assertThat(formatted).contains("# Kubernetes Analysis");
        assertThat(formatted).contains("Analysis text");
        assertThat(formatted).contains("- **Root Cause**: Root cause identified");
        assertThat(formatted).contains("- **Remediation**: Remediation steps");
        assertThat(formatted).contains("- **Recommendation**: Promote canary");
        assertThat(formatted).contains("- **Confidence**: 80%");
        assertThat(formatted).doesNotContain("PR Link");
    }

    @Test
    void testFormat_withEmptyFields() {
        // Given: Response with empty string fields (will be trimmed by record)
        KubernetesAgentResponse response = KubernetesAgentResponse.empty()
            .withAnalysis("   ")
            .withRootCause("   ")
            .withRemediation("   ")
            .withPromote(true)
            .withConfidence(50);

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: Empty fields are handled gracefully
        assertThat(formatted).contains("# Kubernetes Analysis");
        assertThat(formatted).contains("## Summary");
        assertThat(formatted).contains("- **Root Cause**:");
        assertThat(formatted).contains("- **Remediation**:");
        assertThat(formatted).contains("- **Confidence**: 50%");
    }

    // ========== Markdown Structure Tests ==========

    @Test
    void testFormat_markdownFormatting() {
        // Given: Response with standard content
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Analysis content",
            "Root cause",
            "Remediation",
            null,
            true,
            90
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: Proper markdown structure is maintained
        assertThat(formatted).startsWith("# Kubernetes Analysis\n\n");
        assertThat(formatted).contains("\n\n## Summary\n\n");
        assertThat(formatted).contains("- **Root Cause**:");
        assertThat(formatted).contains("- **Remediation**:");
        assertThat(formatted).contains("- **Recommendation**:");
        assertThat(formatted).contains("- **Confidence**:");
        
        // Verify proper spacing
        assertThat(formatted).contains("\n\n");
        assertThat(formatted).doesNotContain("\n\n\n\n");
    }

    // ========== PR Link Tests ==========

    @Test
    void testFormat_withPRLink() {
        // Given: Response with PR link
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Analysis",
            "Root cause",
            "Remediation",
            "https://github.com/test/repo/pull/456",
            true,
            85
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: PR link is included in formatted output
        assertThat(formatted).contains("- **PR Link**: https://github.com/test/repo/pull/456");
    }

    @Test
    void testFormat_withoutPRLink() {
        // Given: Response without PR link
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Analysis",
            "Root cause",
            "Remediation",
            null,
            true,
            85
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: PR link section is omitted
        assertThat(formatted).doesNotContain("PR Link");
        assertThat(formatted).doesNotContain("https://");
    }

    // ========== Promotion Decision Tests ==========

    @Test
    void testFormat_promoteTrueDecision() {
        // Given: Response with promote=true
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "All checks passed",
            "No issues found",
            "Continue with deployment",
            null,
            true,  // promote = true
            95
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: Recommendation shows promote canary
        assertThat(formatted).contains("- **Recommendation**: Promote canary");
        assertThat(formatted).doesNotContain("Do not promote");
    }

    @Test
    void testFormat_promoteFalseDecision() {
        // Given: Response with promote=false
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Critical issues detected",
            "High error rate",
            "Rollback deployment",
            null,
            false,  // promote = false
            30
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: Recommendation shows do not promote
        assertThat(formatted).contains("- **Recommendation**: Do not promote canary");
        assertThat(formatted).doesNotContain("Promote canary\n");
    }

    // ========== Edge Cases ==========

    @Test
    void testFormat_longAnalysisText() {
        // Given: Response with very long analysis text
        StringBuilder longAnalysis = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longAnalysis.append("Line ").append(i).append(" of analysis. ");
        }
        
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            longAnalysis.toString(),
            "Root cause",
            "Remediation",
            null,
            true,
            70
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: Long text is handled properly
        assertThat(formatted).contains("# Kubernetes Analysis");
        assertThat(formatted).contains("Line 0 of analysis");
        assertThat(formatted).contains("Line 99 of analysis");
        assertThat(formatted).contains("## Summary");
    }

    @Test
    void testFormat_specialCharactersInContent() {
        // Given: Response with special characters
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Analysis with special chars: @#$%^&*()",
            "Root cause: Connection @ localhost:5432",
            "Remediation: Update config.yaml & restart",
            "https://github.com/org/repo/pull/123?query=test&param=value",
            false,
            60
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: Special characters are preserved
        assertThat(formatted).contains("@#$%^&*()");
        assertThat(formatted).contains("localhost:5432");
        assertThat(formatted).contains("config.yaml &");
        assertThat(formatted).contains("?query=test&param=value");
    }

    @Test
    void testFormat_unicodeCharacters() {
        // Given: Response with unicode characters
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "❌ Deployment failed\n✅ Rollback successful",
            "🔍 Root cause: Memory leak",
            "🔧 Fix: Restart pods",
            null,
            false,
            55
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: Unicode is preserved
        assertThat(formatted).contains("❌");
        assertThat(formatted).contains("✅");
        assertThat(formatted).contains("🔍");
        assertThat(formatted).contains("🔧");
    }

    @Test
    void testFormat_multilineContent() {
        // Given: Response with multiline content
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Line 1 of analysis\nLine 2 of analysis\nLine 3 of analysis",
            "Root cause line 1\nRoot cause line 2",
            "Step 1: Do this\nStep 2: Do that\nStep 3: Complete",
            null,
            true,
            88
        );

        // When: Formatting the response
        String formatted = formatter.format(response);

        // Then: Multiline content is preserved
        assertThat(formatted).contains("Line 1 of analysis\nLine 2 of analysis");
        assertThat(formatted).contains("Root cause line 1\nRoot cause line 2");
        assertThat(formatted).contains("Step 1: Do this\nStep 2: Do that");
    }

    @Test
    void testFormat_confidenceBoundaries() {
        // Given: Responses with boundary confidence values
        KubernetesAgentResponse response0 = new KubernetesAgentResponse(
            "Analysis", "Root", "Remediation", null, true, 0
        );
        KubernetesAgentResponse response100 = new KubernetesAgentResponse(
            "Analysis", "Root", "Remediation", null, true, 100
        );

        // When: Formatting the responses
        String formatted0 = formatter.format(response0);
        String formatted100 = formatter.format(response100);

        // Then: Confidence values are displayed correctly
        assertThat(formatted0).contains("- **Confidence**: 0%");
        assertThat(formatted100).contains("- **Confidence**: 100%");
    }

    @Test
    void testFormat_consistentOutput() {
        // Given: Same response formatted multiple times
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Analysis",
            "Root cause",
            "Remediation",
            "https://github.com/org/repo/pull/789",
            true,
            80
        );

        // When: Formatting multiple times
        String formatted1 = formatter.format(response);
        String formatted2 = formatter.format(response);
        String formatted3 = formatter.format(response);

        // Then: Output is consistent
        assertThat(formatted1).isEqualTo(formatted2);
        assertThat(formatted2).isEqualTo(formatted3);
    }
}
