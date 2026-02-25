package dev.kevindubois.rollout.agent.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for RetryHelper utility class.
 * Tests core retry logic and error detection without time-consuming delays.
 */
class RetryHelperTest {

    @Test
    void testExecuteWithRetry_successOnFirstAttempt() throws Exception {
        // Given: An operation that succeeds immediately
        Callable<String> operation = () -> "success";

        // When: Executing with retry
        String result = RetryHelper.executeWithRetry(operation, "test-operation");

        // Then: Should return result without retries
        assertThat(result).isEqualTo("success");
    }

    @Test
    void testExecuteWithRetry_nonRetryableError() {
        // Given: An operation that fails with a non-429 error
        Callable<String> operation = () -> {
            throw new IllegalArgumentException("Invalid input");
        };

        // When/Then: Should fail immediately without retries
        assertThatThrownBy(() -> RetryHelper.executeWithRetry(operation, "test-operation"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid input");
    }

    @Test
    void testExecuteWithRetryOnTransientErrors_nonTransientError() {
        // Given: An operation that fails with a non-transient error
        Callable<String> operation = () -> {
            throw new IllegalStateException("Invalid state");
        };

        // When/Then: Should fail immediately without retries
        assertThatThrownBy(() -> RetryHelper.executeWithRetryOnTransientErrors(operation, "test-operation"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Invalid state");
    }
}

