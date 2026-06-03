package dev.kevindubois.rollout.agent.a2a;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

@Path("/a2a/rollout")
public class RolloutSummaryResource {

    @Inject
    KubernetesClient kubernetesClient;

    private static final ResourceDefinitionContext ROLLOUT_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("argoproj.io")
            .withVersion("v1alpha1")
            .withKind("Rollout")
            .withPlural("rollouts")
            .withNamespaced(true)
            .build();

    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public RolloutSummary getSummary(@QueryParam("namespace") String namespace,
                                     @QueryParam("name") String rolloutName) {
        String effectiveNamespace = (namespace == null || namespace.isBlank()) ? "quarkus-demo" : namespace;
        String effectiveRolloutName = (rolloutName == null || rolloutName.isBlank()) ? "quarkus-demo" : rolloutName;

        try {
            GenericKubernetesResource rollout = kubernetesClient
                    .genericKubernetesResources(ROLLOUT_CONTEXT)
                    .inNamespace(effectiveNamespace)
                    .withName(effectiveRolloutName)
                    .get();

            if (rollout == null) {
                return RolloutSummary.notFound(effectiveNamespace, effectiveRolloutName);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) rollout.getAdditionalProperties().getOrDefault("status", Map.of());

            int canaryWeight = getCanaryWeight(status);
            int stableWeight = 100 - canaryWeight;
            long stablePodCount = countPods(effectiveNamespace, effectiveRolloutName, "stable");
            long canaryPodCount = countPods(effectiveNamespace, effectiveRolloutName, "canary");
            String phase = status.get("phase") != null ? status.get("phase").toString() : "Unknown";

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

    @SuppressWarnings("unchecked")
    private int getCanaryWeight(Map<String, Object> status) {
        try {
            String phase = status.get("phase") != null ? status.get("phase").toString() : "Unknown";
            if ("Degraded".equals(phase) || "Aborted".equals(phase)) {
                return 0;
            }

            Object stableRS = status.get("stableRS");
            Object canaryRS = status.get("canaryRS");
            if ("Healthy".equals(phase) && stableRS != null && canaryRS != null && stableRS.equals(canaryRS)) {
                return 0;
            }

            Object canaryStatus = status.get("canary");
            if (canaryStatus instanceof Map<?, ?> canaryMapObj) {
                Map<String, Object> canaryMap = (Map<String, Object>) canaryMapObj;
                Object weights = canaryMap.get("weights");
                if (weights instanceof Map<?, ?> weightsObj) {
                    Map<String, Object> weightsMap = (Map<String, Object>) weightsObj;
                    Object canary = weightsMap.get("canary");
                    if (canary instanceof Map<?, ?> canaryWeightObj) {
                        Map<String, Object> canaryWeightMap = (Map<String, Object>) canaryWeightObj;
                        Object weight = canaryWeightMap.get("weight");
                        if (weight instanceof Number) {
                            return ((Number) weight).intValue();
                        }
                    } else if (canary instanceof Number) {
                        return ((Number) canary).intValue();
                    }
                }
            }

            Object currentStepIndex = status.get("currentStepIndex");
            if (currentStepIndex instanceof Number) {
                int stepIndex = ((Number) currentStepIndex).intValue();
                int[] weights = {10, 10, 30, 30, 60, 60, 100};
                if (stepIndex >= 0 && stepIndex < weights.length) {
                    return weights[stepIndex];
                }
            }
        } catch (Exception e) {
            Log.warn("Could not extract canary weight from rollout status: " + e.getMessage());
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
