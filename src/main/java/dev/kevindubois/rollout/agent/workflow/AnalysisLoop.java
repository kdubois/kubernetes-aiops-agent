package dev.kevindubois.rollout.agent.workflow;

import dev.kevindubois.rollout.agent.agents.UnifiedDecisionAgent;
import dev.kevindubois.rollout.agent.agents.ScoringAgent;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.ScoringResult;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.declarative.ExitCondition;

/**
 * Loop that retries the unified decision until confidence threshold is met.
 * Now works with parallel analysis results instead of raw diagnostic data.
 */
public interface AnalysisLoop {
    
    @LoopAgent(
        description = "Make promote/rollback decision with retry until confidence threshold is met",
        outputKey = "analysisResult",
        maxIterations = 3,
        subAgents = {UnifiedDecisionAgent.class, ScoringAgent.class}
    )
    AnalysisResult analyzeWithRetry(String parallelAnalyses);
    
    @ExitCondition
    static boolean shouldExit(AgenticScope scope) {
        ScoringResult scoring = (ScoringResult) scope.readState("scoringResult");
        return scoring != null && !scoring.needsRetry();
    }
}

