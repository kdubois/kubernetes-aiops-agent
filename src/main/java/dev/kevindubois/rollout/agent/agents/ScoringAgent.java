package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.ScoringResult;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;

public interface ScoringAgent {
    
    @SystemMessage("""
        BE CONCISE. Fast quality evaluation.
        
        JSON OUTPUT:
        {
          "score": 0-100,
          "needsRetry": true/false,
          "reason": "brief explanation"
        }
        
        Good: confidence >70%, clear root cause, actionable plan
        Retry: confidence <50%, unclear cause, no action
        """)
    @Agent(outputKey = "scoringResult", description = "Evaluates analysis quality")
    ScoringResult evaluate(AnalysisResult analysisResult);
}
