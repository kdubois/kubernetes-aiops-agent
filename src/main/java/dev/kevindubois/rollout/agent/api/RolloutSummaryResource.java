package dev.kevindubois.rollout.agent.api;

import dev.kevindubois.rollout.agent.k8s.CanaryWeights;
import dev.kevindubois.rollout.agent.k8s.Rollout;
import dev.kevindubois.rollout.agent.k8s.RolloutStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import java.util.Optional;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/a2a/rollout")
public class RolloutSummaryResource {

    // Canary weight per step index fallback setting
    private static final int[] STEP_WEIGHTS = {10, 10, 30, 30, 60, 60, 100};

    @Inject
    KubernetesClient kubernetesClient;

    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public RolloutSummary getSummary(@QueryParam("namespace") String namespace,
                                     @QueryParam("name") String rolloutName) {
        String effectiveNamespace = (namespace == null || namespace.isBlank()) ? "quarkus-demo" : namespace;
        String effectiveRolloutName = (rolloutName == null || rolloutName.isBlank()) ? "quarkus-demo" : rolloutName;

        try {
            RolloutStatus status = Optional.ofNullable(kubernetesClient.resources(Rollout.class)
                    .inNamespace(effectiveNamespace)
                    .withName(effectiveRolloutName)
                    .get())
                    .map(Rollout::getStatus)
                    .orElse(null);

            if (status == null) {
                return RolloutSummary.notFound(effectiveNamespace, effectiveRolloutName);
            }

            int canaryWeight = getCanaryWeight(status);
            int stableWeight = 100 - canaryWeight;
            long stablePodCount = countPods(effectiveNamespace, effectiveRolloutName, "stable");
            long canaryPodCount = countPods(effectiveNamespace, effectiveRolloutName, "canary");
            String phase = status.getPhase() != null ? status.getPhase() : "Unknown";

            return new RolloutSummary(
                    effectiveNamespace,
                    effectiveRolloutName,
                    phase,
                    canaryWeight,
                    stableWeight,
                    stablePodCount,
                    canaryPodCount,
                    true,
                    null
            );
        } catch (Exception e) {
            Log.error("Failed to fetch rollout summary", e);
            return RolloutSummary.error(effectiveNamespace, effectiveRolloutName, e.getMessage());
        }
    }

    private long countPods(String namespace, String rolloutName, String role) {
        return kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("app", rolloutName)
                .withLabel("role", role)
                .list()
                .getItems()
                .stream()
                .filter(p -> p.getMetadata() != null && p.getMetadata().getDeletionTimestamp() == null)
                .count();
    }

    private int getCanaryWeight(RolloutStatus status) {
        String phase = status.getPhase() != null ? status.getPhase() : "Unknown";

        if ("Degraded".equals(phase) || "Aborted".equals(phase)) {
            return 0;
        }

        // When a rollout finishes, stableRS and canaryRS converge to the same hash.
        if ("Healthy".equals(phase)
                && status.getStableRS() != null
                && status.getStableRS().equals(status.getCanaryRS())) {
            return 0;
        }

        // Prefer the live traffic-weight reported by the controller.
        if (status.getCanary() != null) {
            CanaryWeights weights = status.getCanary().getWeights();
            if (weights != null && weights.getCanary() != null) {
                return weights.getCanary().getWeight();
            }
        }

        // Fall back to the step-index table when live weights are unavailable.
        Integer stepIndex = status.getCurrentStepIndex();
        if (stepIndex != null && stepIndex >= 0 && stepIndex < STEP_WEIGHTS.length) {
            return STEP_WEIGHTS[stepIndex];
        }

        return 0;
    }

    public record RolloutSummary(
            String namespace,
            String name,
            String phase,
            int canaryWeight,
            int stableWeight,
            long stablePodCount,
            long canaryPodCount,
            boolean available,
            String error
    ) {
        public static RolloutSummary notFound(String namespace, String name) {
            return new RolloutSummary(namespace, name, "NotFound", 0, 100, 0, 0, false, "Rollout not found");
        }

        public static RolloutSummary error(String namespace, String name, String error) {
            return new RolloutSummary(namespace, name, "Error", 0, 100, 0, 0, false, error);
        }
    }
}
