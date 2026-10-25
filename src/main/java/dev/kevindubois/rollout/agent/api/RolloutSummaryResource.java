package dev.kevindubois.rollout.agent.api;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.kevindubois.rollout.agent.k8s.RolloutStatusData;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/a2a/rollout")
public class RolloutSummaryResource {

    @Inject
    K8sTools k8sTools;

    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public RolloutSummary getSummary(@QueryParam("namespace") String namespace,
                                     @QueryParam("name") String rolloutName) {
        String effectiveNamespace = (namespace == null || namespace.isBlank()) ? "quarkus-demo" : namespace;
        String effectiveRolloutName = (rolloutName == null || rolloutName.isBlank()) ? "quarkus-demo" : rolloutName;

        try {
            RolloutStatusData status = k8sTools.getRolloutStatus(effectiveNamespace, effectiveRolloutName);

            if (status.hasError()) {
                return RolloutSummary.error(effectiveNamespace, effectiveRolloutName, status.error());
            }

            return new RolloutSummary(
                    effectiveNamespace,
                    status.rolloutName(),
                    status.phase(),
                    status.canaryWeight(),
                    status.stableWeight(),
                    status.stablePodCount(),
                    status.canaryPodCount(),
                    true,
                    null
            );
        } catch (Exception e) {
            Log.error("Failed to fetch rollout summary", e);
            return RolloutSummary.error(effectiveNamespace, effectiveRolloutName, e.getMessage());
        }
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
        public static RolloutSummary error(String namespace, String name, String error) {
            return new RolloutSummary(namespace, name, "Error", 0, 100, 0, 0, false, error);
        }
    }
}
