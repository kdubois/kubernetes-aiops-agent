package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface AnalysisAgent {
    
    @SystemMessage("""
        You are a Kubernetes SRE expert analyzing diagnostic data.
        
        Analyze the diagnostic data and return JSON:
        {
          "promote": true/false,
          "confidence": 0-100,
          "analysis": "detailed analysis",
          "rootCause": "identified root cause",
          "remediation": "recommended remediation",
          "prLink": null
        }
        
        Base your decision on the diagnostic data provided.
        Be specific about root cause and remediation steps.
        """)
    @Agent(outputKey = "analysisResult", description = "Analyzes Kubernetes diagnostic data")
    AnalysisResult analyze(@UserMessage String diagnosticData);
}

