package dev.kevindubois.rollout.agent.analysis;

import dev.kevindubois.rollout.agent.observability.ActivityEventListener;
import dev.langchain4j.agentic.declarative.AgentListenerSupplier;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.MonitoredAgent;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import io.quarkus.arc.Arc;

/**
 * Main workflow orchestrating the complete Kubernetes rollout analysis.
 * 1. AnalysisDataAgent: Non-AI agents fetch diagnostics and metrics in parallel
 * 2. DataCombinerAgent: Non-AI agent combines the parallel reports into a single string
 * 3. AnalysisAgent: AI agent analyzes the data and makes the promote/rollback decision
 *
 * Remediation is deliberately NOT part of this sequence: {@code AnalysisService}
 * triggers it after the decision event is published, so the remediation activity
 * appears after the analysis narrative in the feed and uses the final
 * (post-escalation) result.
 *
 * The result carries the agentic scope so the caller can read intermediate
 * workflow state — notably the combined "diagnosticData" the analysis grader
 * grades the conclusion against.
 */
public interface AnalysisOrchestratorAgent extends MonitoredAgent {

    @AgentListenerSupplier
    static AgentListener listener() {
        return Arc.container().instance(ActivityEventListener.class).get();
    }

    @SequenceAgent(
        description = "Complete Kubernetes rollout analysis workflow",
        outputKey = "analysisResult",
        subAgents = {
            AnalysisDataAgent.class,
            DataCombinerAgent.class,
            AnalysisAgent.class
        }
    )
    ResultWithAgenticScope<AnalysisResult> execute(
        String message,
        String namespace,
        String stableSelector,
        String canarySelector
    );
}
