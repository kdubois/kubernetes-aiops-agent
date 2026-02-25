package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.ScoringResult;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;

public interface ScoringAgent {
    
    @SystemMessage("""
        You are a solution quality evaluator.
        
        Evaluate the analysis and return JSON:
        {
          "score": 0-100,
          "needsRetry": true/false,
          "reason": "explanation"
        }
        
        Criteria for good solution:
        - High confidence (>70%)
        - Clear root cause identified
        - Actionable remediation plan
        
        Recommend retry if:
        - Low confidence (<50%)
        - Root cause unclear
        - No actionable remediation
        """)
    @Agent(outputKey = "scoringResult", description = "Evaluates analysis quality")
    ScoringResult evaluate(AnalysisResult analysisResult);
}
