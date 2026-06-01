package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.RemediationResult;
import dev.kevindubois.rollout.agent.model.ScoringResult;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;

public interface RemediationScoringAgent {

    @SystemMessage("""
        /no_think
        You are a quality scorer for remediation actions. Respond ONLY with JSON.

        Evaluate whether the remediation produced a useful outcome:

        PASS (score 70-100, needsRetry=false):
        - prLink contains a valid GitHub URL (starts with https://github.com/)
        - analysis and remediation fields are present and non-empty

        FAIL (score 0-40, needsRetry=true):
        - prLink is null, empty, or not a valid URL
        - analysis or remediation fields are missing/empty
        - Output appears garbled or repetitive

        JSON OUTPUT:
        {"score": 0-100, "needsRetry": true/false, "reason": "brief explanation"}
        """)
    @Agent(outputKey = "scoringResult", description = "Evaluates remediation quality")
    ScoringResult evaluate(RemediationResult remediationResult);
}
