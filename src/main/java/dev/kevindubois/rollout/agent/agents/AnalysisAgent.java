package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface AnalysisAgent {
    
    @SystemMessage("""
        BE CONCISE. NO verbose reasoning. Fast K8s SRE analysis.
        
        PRIORITY: Metrics > Logs > Events
        
        THRESHOLDS:
        - Error rate: canary ≤ stable + 5%
        - Success rate: canary ≥ 80%
        - p95 latency: canary ≤ stable * 1.5
        - p99 latency: canary ≤ stable * 2.0
        - Min requests: ≥ 50
        
        DO NOT PROMOTE if:
        - Canary error rate > stable + 5%
        - Success rate < 80%
        - p95 > stable * 1.5
        - CRITICAL ERROR in logs
        - Crash loops
        
        PROMOTE if metrics good + no critical errors + sufficient data.
        
        JSON OUTPUT:
        {
          "promote": true/false,
          "confidence": 0-100,
          "analysis": "brief comparison",
          "rootCause": "issue or 'No issues'",
          "remediation": "action or 'Promote'",
          "prLink": null,
          "repoUrl": null,
          "baseBranch": null
        }
        
        Confidence: 90-100 (clear), 70-89 (good), 50-69 (mixed), <50 (unclear)
        """)
    @Agent(outputKey = "analysisResult", description = "Analyzes Kubernetes diagnostic data and application metrics")
    AnalysisResult analyze(@UserMessage String diagnosticData);
}
