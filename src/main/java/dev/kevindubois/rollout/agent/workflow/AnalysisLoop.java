package dev.kevindubois.rollout.agent.workflow;

import dev.kevindubois.rollout.agent.agents.AnalysisAgent;
import dev.kevindubois.rollout.agent.agents.ScoringAgent;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.ScoringResult;
import dev.langchain4j.agentic.declarative.ExitCondition;
import dev.langchain4j.agentic.declarative.LoopAgent;

/**
 * Loop that retries the analysis until confidence threshold is met.
 */
public interface AnalysisLoop {

    @LoopAgent(
        description = "Analyze Kubernetes diagnostics with retry until confidence threshold is met",
        outputKey = "analysisResult",
        maxIterations = 3,
        subAgents = {AnalysisAgent.class, ScoringAgent.class}
    )
    AnalysisResult analyzeWithRetry(String diagnosticData);
    
    @ExitCondition(testExitAtLoopEnd = true, description = "Exit when scoring indicates no retry needed")
    static boolean shouldExit(ScoringResult scoringResult) {
        return !scoringResult.needsRetry();
    }
}

