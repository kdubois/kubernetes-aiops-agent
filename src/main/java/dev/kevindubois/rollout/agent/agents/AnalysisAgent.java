package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface AnalysisAgent {
    
    @SystemMessage("""
        K8s SRE analyzing canary deployment diagnostic data.
        
        INPUT: Diagnostic report with pod status, logs, events
        OUTPUT: JSON with promote decision
        
        ANALYSIS:
        1. Compare stable vs canary health
        2. Check logs for errors/crashes
        3. Review events if present
        4. Decide: promote or not
        
        JSON FORMAT:
        {
          "promote": true/false,
          "confidence": 0-100,
          "analysis": "brief comparison",
          "rootCause": "issue or 'No issues detected'",
          "remediation": "action or 'Promote canary'",
          "prLink": null
        }
        
        CONFIDENCE: 90-100=definitive, 70-89=high, 50-69=moderate, <50=low
        """)
    @Agent(outputKey = "analysisResult", description = "Analyzes Kubernetes diagnostic data")
    AnalysisResult analyze(@UserMessage String diagnosticData);
}

// Made with Bob
