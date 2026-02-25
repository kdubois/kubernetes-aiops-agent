package dev.kevindubois.rollout.agent.service;

import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for AgentResponseParser.
 * Tests parsing of agent responses including root cause extraction, promotion decisions,
 * PR link extraction, and edge cases.
 */
class AgentResponseParserTest {

    private AgentResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new AgentResponseParser();
    }

    // ========== Root Cause Extraction Tests ==========

    @Test
    void testParse_extractsRootCause() {
        // Given: Response with clear root cause section
        String response = """
            ## Analysis
            The canary deployment is experiencing issues.
            
            ## Root Cause
            The database connection string is incorrect, causing connection failures.
            
            ## Remediation
            Update the connection string in the ConfigMap.
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Root cause is extracted
        assertThat(result.rootCause()).contains("Root Cause");
        assertThat(result.rootCause()).contains("database connection string");
    }

    @Test
    void testParse_rootCauseWithVariousFormats() {
        // Given: Response with different markdown formats
        String response1 = """
            # Root Cause
            Memory leak in the application
            """;
        
        String response2 = """
            ### root cause
            CPU throttling detected
            """;
        
        String response3 = """
            **Root Cause**: Network timeout
            """;

        // When: Parsing responses with different formats
        KubernetesAgentResponse result1 = parser.parse(response1);
        KubernetesAgentResponse result2 = parser.parse(response2);
        KubernetesAgentResponse result3 = parser.parse(response3);

        // Then: Root cause is extracted from all formats
        assertThat(result1.rootCause()).containsIgnoringCase("root cause");
        assertThat(result2.rootCause()).containsIgnoringCase("root cause");
        assertThat(result3.rootCause()).containsIgnoringCase("root cause");
    }

    @Test
    void testParse_missingRootCause() {
        // Given: Response without root cause section
        String response = """
            ## Analysis
            The deployment is failing.
            
            ## Remediation
            Check the logs for more details.
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Fallback to default message
        assertThat(result.rootCause()).isEqualTo("See analysis");
    }

    // ========== Promotion Decision Tests (Parameterized) ==========

    @ParameterizedTest
    @CsvSource({
        "'promote: false', false",
        "'promote**: false', false",
        "'**promote**: false', false",
        "'- **promote**: false', false",
        "'promote: `false`', false",
        "'promote**: `false`', false"
    })
    void testParse_promoteFalsePatterns(String pattern, boolean expectedPromote) {
        // Given: Response with promote: false pattern
        String response = String.format("""
            ## Analysis
            Issues detected.
            
            ## Decision
            %s
            """, pattern);

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Promotion decision is correctly extracted
        assertThat(result.promote()).isEqualTo(expectedPromote);
    }

    @ParameterizedTest
    @CsvSource({
        "'promote: true', true",
        "'promote**: true', true",
        "'**promote**: true', true",
        "'- **promote**: true', true",
        "'promote: `true`', true",
        "'promote**: `true`', true"
    })
    void testParse_promoteTruePatterns(String pattern, boolean expectedPromote) {
        // Given: Response with promote: true pattern
        String response = String.format("""
            ## Analysis
            All checks passed.
            
            ## Decision
            %s
            """, pattern);

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Promotion decision is correctly extracted
        assertThat(result.promote()).isEqualTo(expectedPromote);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "do not promote",
        "should not promote",
        "should not be promoted",
        "abort",
        "rollback",
        "should be halted",
        "canary should not be promoted"
    })
    void testParse_negativePromotionKeywords(String keyword) {
        // Given: Response with negative promotion keywords
        String response = String.format("""
            ## Analysis
            Critical issues found.
            
            ## Recommendation
            The deployment %s due to errors.
            """, keyword);

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Promotion is set to false
        assertThat(result.promote()).isFalse();
    }

    @Test
    void testParse_defaultPromotionDecision() {
        // Given: Response without explicit promotion decision
        String response = """
            ## Analysis
            The deployment looks good.
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Default to true (safe default)
        assertThat(result.promote()).isTrue();
    }

    @Test
    void testParse_promoteFalseOverridesDefault() {
        // Given: Response with explicit promote: false
        String response = """
            ## Analysis
            Everything looks fine, but...
            
            ## Decision
            promote: false
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Explicit false overrides default
        assertThat(result.promote()).isFalse();
    }

    // ========== PR Link Extraction Tests ==========

    @ParameterizedTest
    @ValueSource(strings = {
        "https://github.com/org/repo/pull/123",
        "https://github.com/test-org/test-repo/pull/456",
        "https://github.com/my-company/my-project/pull/789",
        "PR: https://github.com/org/repo/pull/999"
    })
    void testParse_extractsPRLinks(String prLink) {
        // Given: Response with PR link
        String response = String.format("""
            ## Analysis
            Issue fixed.
            
            ## Remediation
            A PR has been created: %s
            """, prLink);

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: PR link is extracted
        assertThat(result.prLink()).isNotNull();
        assertThat(result.prLink()).contains("github.com");
        assertThat(result.prLink()).contains("/pull/");
    }

    @Test
    void testParse_noPRLink() {
        // Given: Response without PR link
        String response = """
            ## Analysis
            Manual fix required.
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: PR link is null
        assertThat(result.prLink()).isNull();
    }

    @Test
    void testParse_multiplePRLinks() {
        // Given: Response with multiple PR links (should extract first)
        String response = """
            ## Remediation
            Created PR: https://github.com/org/repo/pull/123
            Also see: https://github.com/org/repo/pull/456
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: First PR link is extracted
        assertThat(result.prLink()).isNotNull();
        assertThat(result.prLink()).contains("/pull/");
    }

    // ========== Edge Cases Tests ==========

    @Test
    void testParse_emptyResponse() {
        // Given: Empty response
        String response = "";

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Safe defaults are returned
        assertThat(result.analysis()).isEmpty();
        assertThat(result.rootCause()).isEqualTo("See analysis");
        assertThat(result.remediation()).isEqualTo("See analysis");
        assertThat(result.prLink()).isNull();
        assertThat(result.promote()).isTrue();
    }

    @Test
    void testParse_nullResponse() {
        // Given: Null response
        String response = null;

        // When/Then: Should handle gracefully (may throw NPE or return defaults)
        // This tests the robustness of the parser
        assertThatThrownBy(() -> parser.parse(response))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testParse_malformedMarkdown() {
        // Given: Response with malformed markdown
        String response = """
            ## Analysis
            Some text
            ### Nested header without proper structure
            ## Root Cause
            Missing closing tags
            ## Remediation
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Parser handles it gracefully
        assertThat(result).isNotNull();
        assertThat(result.analysis()).isNotEmpty();
    }

    @Test
    void testParse_confidenceLevels() {
        // Given: Response with promote: false
        String responseFalse = """
            ## Decision
            promote: false
            """;
        
        // Given: Response with promote: true
        String responseTrue = """
            ## Decision
            promote: true
            """;

        // When: Parsing responses
        KubernetesAgentResponse resultFalse = parser.parse(responseFalse);
        KubernetesAgentResponse resultTrue = parser.parse(responseTrue);

        // Then: Confidence levels are set appropriately
        assertThat(resultFalse.confidence()).isLessThan(resultTrue.confidence());
        assertThat(resultTrue.confidence()).isEqualTo(80);
        assertThat(resultFalse.confidence()).isEqualTo(50);
    }

    // ========== Complex Scenarios ==========

    @Test
    void testParse_fullResponseWithAllSections() {
        // Given: Complete response with all sections
        String response = """
            ## Analysis
            The canary deployment is experiencing high error rates.
            
            ## Root Cause
            Database connection pool exhaustion due to missing connection timeout configuration.
            
            ## Remediation
            1. Update the database connection configuration
            2. Add connection timeout settings
            3. Increase pool size
            
            A PR has been created: https://github.com/org/repo/pull/123
            
            ## Decision
            promote: false
            
            The deployment should be halted until the fix is applied.
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: All sections are correctly extracted
        assertThat(result.analysis()).contains("canary deployment");
        assertThat(result.rootCause()).contains("Root Cause");
        assertThat(result.rootCause()).contains("Database connection pool");
        assertThat(result.remediation()).contains("Remediation");
        assertThat(result.prLink()).contains("github.com/org/repo/pull/123");
        assertThat(result.promote()).isFalse();
        assertThat(result.confidence()).isEqualTo(50);
    }

    @Test
    void testParse_caseInsensitivePromoteDetection() {
        // Given: Response with mixed case
        String response = """
            ## Decision
            PROMOTE: FALSE
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Case-insensitive detection works
        assertThat(result.promote()).isFalse();
    }

    @Test
    void testParse_promoteInDifferentContexts() {
        // Given: Response where "promote" appears in different contexts
        String response = """
            ## Analysis
            We should promote best practices.
            
            ## Decision
            However, promote: false for this deployment.
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Only the decision context is considered
        assertThat(result.promote()).isFalse();
    }

    @Test
    void testParse_remediationExtraction() {
        // Given: Response with remediation section
        String response = """
            ## Root Cause
            Memory leak
            
            ## Remediation
            Restart the pods and apply the memory fix patch.
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Remediation is extracted
        assertThat(result.remediation()).contains("Remediation");
        assertThat(result.remediation()).contains("Restart the pods");
    }

    @Test
    void testParse_multipleMarkdownSections() {
        // Given: Response with multiple markdown sections
        String response = """
            # Main Analysis
            
            ## Root Cause
            Configuration error
            
            ## Remediation
            Fix the config
            
            ## Additional Notes
            This is extra information
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Sections are properly delimited
        assertThat(result.rootCause()).contains("Root Cause");
        assertThat(result.rootCause()).doesNotContain("Additional Notes");
    }

    @Test
    void testParse_specialCharactersInResponse() {
        // Given: Response with special characters
        String response = """
            ## Root Cause
            Error: Connection refused @ localhost:5432
            Stack trace: java.net.ConnectException
            
            ## Decision
            promote: false
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Special characters are handled
        assertThat(result.rootCause()).contains("@");
        assertThat(result.rootCause()).contains(":");
        assertThat(result.promote()).isFalse();
    }

    @Test
    void testParse_unicodeCharacters() {
        // Given: Response with unicode characters
        String response = """
            ## Root Cause
            ❌ Deployment failed
            ✅ Rollback successful
            
            ## Decision
            promote: false
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Unicode is preserved
        assertThat(result.rootCause()).contains("❌");
        assertThat(result.rootCause()).contains("✅");
    }

    @Test
    void testParse_veryLongResponse() {
        // Given: Very long response
        StringBuilder longResponse = new StringBuilder();
        longResponse.append("## Root Cause\n");
        for (int i = 0; i < 1000; i++) {
            longResponse.append("Line ").append(i).append("\n");
        }
        longResponse.append("\n## Decision\npromote: false");

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(longResponse.toString());

        // Then: Long response is handled
        assertThat(result.rootCause()).contains("Root Cause");
        assertThat(result.promote()).isFalse();
    }

    @Test
    void testParse_whitespaceHandling() {
        // Given: Response with extra whitespace
        String response = """
            
            
            ## Root Cause   
            
            Database error   
            
            
            ## Decision
               promote: false   
            
            """;

        // When: Parsing the response
        KubernetesAgentResponse result = parser.parse(response);

        // Then: Whitespace is handled appropriately
        assertThat(result.rootCause()).isNotEmpty();
        assertThat(result.promote()).isFalse();
    }
}

