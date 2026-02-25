package dev.kevindubois.rollout.agent.workflow;

import dev.kevindubois.rollout.agent.agents.AnalysisAgent;
import dev.kevindubois.rollout.agent.agents.ScoringAgent;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.ScoringResult;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.declarative.ExitCondition;

public interface AnalysisLoop {
    
    @LoopAgent(
        description = "Analyze Kubernetes diagnostics with retry until confidence threshold is met",
        outputKey = "analysisResult",
        maxIterations = 3,
        subAgents = {AnalysisAgent.class, ScoringAgent.class}
    )
    AnalysisResult analyzeWithRetry(String diagnosticData);
    
    @ExitCondition
    static boolean shouldExit(AgenticScope scope) {
        ScoringResult scoring = (ScoringResult) scope.readState("scoringResult");
        return scoring != null && !scoring.needsRetry();
    }
}

// Made with Bob
