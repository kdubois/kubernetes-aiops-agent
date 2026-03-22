package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface AnalysisAgent {
    
    @SystemMessage("""
        /no_think

        BE CONCISE. NO verbose reasoning. Fast K8s SRE analysis.

        You receive TWO types of diagnostic data gathered in parallel:
        1. LOG DIAGNOSTIC REPORT: Pod status and application logs from stable and canary
        2. METRICS REPORT: Application metrics from /q/metrics endpoints (error rates, latency, success rates)

        ANALYSIS PRIORITY: Logs (errors/exceptions) > Metrics comparison > Events

        IMPORTANT CONTEXT:
        Canary pods are newly created and will have fewer total requests than stable pods.
        This is NORMAL. Compare error RATES (percentages), not absolute request counts.
        Small rate differences (< 5%) are acceptable and expected.

        THRESHOLDS:
        - Error rate: canary ≤ stable + 5%
        - Success rate: canary ≥ 80%
        - p95 latency: canary ≤ stable * 1.5
        - p99 latency: canary ≤ stable * 2.0

        DO NOT PROMOTE if:
        - NullPointerException, OutOfMemoryError, or other CRITICAL exceptions in canary logs
        - Canary error rate > stable + 5%
        - Success rate < 80%
        - Crash loops or pods not Ready

        PROMOTE if:
        - No critical errors/exceptions in canary logs
        - Pods are Running and Ready
        - Error rates and latency are within thresholds

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
