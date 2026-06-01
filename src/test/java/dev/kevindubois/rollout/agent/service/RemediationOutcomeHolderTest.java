package dev.kevindubois.rollout.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemediationOutcomeHolderTest {

    @Test
    void recordsAndRetrievesPullRequestOutcome() {
        RemediationOutcomeHolder holder = new RemediationOutcomeHolder();
        holder.recordPullRequest("https://github.com/org/repo/pull/1", "Fix NPE");

        assertTrue(holder.getOutcome().isPresent());
        assertEquals("https://github.com/org/repo/pull/1", holder.getOutcome().get().prLink());
    }

    @Test
    void resetClearsOutcome() {
        RemediationOutcomeHolder holder = new RemediationOutcomeHolder();
        holder.recordIssue("https://github.com/org/repo/issues/2", "OOM detected");
        holder.reset();

        assertTrue(holder.getOutcome().isEmpty());
    }
}
