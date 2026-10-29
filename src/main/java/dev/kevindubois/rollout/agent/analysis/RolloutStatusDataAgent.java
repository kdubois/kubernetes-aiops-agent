package dev.kevindubois.rollout.agent.analysis;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.kevindubois.rollout.agent.k8s.RolloutStatusData;
import dev.langchain4j.agentic.Agent;
import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;

/**
 * Non-AI agent that fetches the Argo Rollouts resource status: phase, current step,
 * traffic split, and stable/canary pod counts. Calls K8sTools directly — no LLM involved.
 */
public class RolloutStatusDataAgent {

    @Agent(description = "Fetches rollout phase, step, and traffic weights", outputKey = "rolloutReport")
    public static String gatherRolloutStatus(String message, String namespace) {
        Log.info("RolloutStatusDataAgent: fetching rollout status (non-AI agent)");

        K8sTools k8sTools = Arc.container().instance(K8sTools.class).get();

        RolloutStatusData status = k8sTools.getRolloutStatus(namespace, null);

        String report = formatReport(status);
        Log.infof("RolloutStatusDataAgent: report generated (%d chars)", report.length());
        return report;
    }

    private static String formatReport(RolloutStatusData status) {
        if (status == null || status.hasError()) {
            return "=== ROLLOUT STATUS REPORT ===\nERROR: "
                    + (status != null ? status.error() : "No rollout status available") + "\n=== END ===";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== ROLLOUT STATUS REPORT ===\n");
        sb.append("ROLLOUT: ").append(status.rolloutName())
                .append(" (namespace ").append(status.namespace()).append(")\n");
        sb.append("PHASE: ").append(status.phase());
        if (status.isStepPause()) {
            sb.append(" (normal canary step pause while measurements run)");
        } else if (status.message() != null && !status.message().isBlank()) {
            sb.append(" — ").append(status.message());
        }
        sb.append("\n");
        sb.append("STEP INDEX: ").append(status.currentStepIndex() != null ? status.currentStepIndex() : "N/A").append("\n");
        sb.append("TRAFFIC: canary ").append(status.canaryWeight())
                .append("%, stable ").append(status.stableWeight()).append("%\n");
        sb.append("PODS: stable ").append(status.stablePodCount())
                .append(", canary ").append(status.canaryPodCount()).append("\n");
        sb.append("=== END ===");
        return sb.toString();
    }
}
