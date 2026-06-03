package dev.kevindubois.rollout.agent.workflow;

import dev.kevindubois.rollout.agent.agents.RemediationAgent;
import dev.kevindubois.rollout.agent.agents.RemediationScoringAgent;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.RemediationResult;
import dev.kevindubois.rollout.agent.model.ScoringResult;
import dev.kevindubois.rollout.agent.observability.ActivityEventListener;
import dev.langchain4j.agentic.declarative.AgentListenerSupplier;
import dev.langchain4j.agentic.declarative.ExitCondition;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.MonitoredAgent;
import io.quarkus.arc.Arc;

/**
 * Loop that retries remediation until the scoring agent confirms a valid outcome.
 */
public interface RemediationLoop extends MonitoredAgent {

    @AgentListenerSupplier
    static AgentListener listener() {
        return Arc.container().instance(ActivityEventListener.class).get();
    }

    @LoopAgent(
        description = "Remediate with quality scoring and retry on failure",
        outputKey = "remediationResult",
        maxIterations = 2,
        subAgents = {RemediationAgent.class, RemediationScoringAgent.class}
    )
    RemediationResult remediateWithRetry(
        String diagnosticData,
        AnalysisResult analysisResult,
        String repoUrl,
        String baseBranch
    );

    @ExitCondition(testExitAtLoopEnd = true,
        description = "Exit when scoring indicates remediation quality is acceptable")
    static boolean shouldExit(ScoringResult scoringResult) {
        return !scoringResult.needsRetry();
    }
}
