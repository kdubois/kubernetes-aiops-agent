package dev.kevindubois.rollout.agent.api;

import dev.kevindubois.rollout.agent.k8s.Rollout;
import dev.kevindubois.rollout.agent.observability.ActivityEvents;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Manual rollout control for the demo UI: the human counterpart to the
 * uncertainty gate. Promote resumes a rollout the gate paused; abort rolls
 * it back to the stable version. Both default to the demo rollout in the
 * demo namespace, like {@link RolloutSummaryResource}.
 */
@Path("/rollout")
public class RolloutControlResource {

    private static final String DEFAULT_NAMESPACE = "quarkus-demo";
    private static final String DEFAULT_ROLLOUT = "quarkus-demo";

    @Inject
    KubernetesClient k8sClient;

    @Inject
    ActivityEvents activityEvents;

    /**
     * Resumes a rollout the uncertainty gate paused: a JSON merge patch of
     * spec.paused=false, the exact reverse of the gate's pause patch. The
     * controller continues the canary steps from where the gate held them.
     */
    @POST
    @Path("/promote")
    @Produces(MediaType.APPLICATION_JSON)
    public Response promote(@QueryParam("namespace") String namespace, @QueryParam("name") String name) {
        String ns = orDefault(namespace, DEFAULT_NAMESPACE);
        String rolloutName = orDefault(name, DEFAULT_ROLLOUT);
        try {
            Resource<Rollout> rollout = rollout(ns, rolloutName);
            if (rollout.get() == null) {
                return notFound("promote", ns, rolloutName);
            }
            rollout.patch(PatchContext.of(PatchType.JSON_MERGE), "{\"spec\":{\"paused\":false}}");
            activityEvents.rolloutResumed(ns, rolloutName);
            Log.infof("Promoted rollout %s/%s", ns, rolloutName);
            return Response.ok(new ControlResult("promote", ns, rolloutName, null)).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to promote rollout %s/%s", ns, rolloutName);
            return Response.serverError().entity(new ControlResult("promote", ns, rolloutName, e.getMessage())).build();
        }
    }

    /**
     * Aborts the rollout back to the stable version with the same signal
     * {@code kubectl argo rollouts abort} sends: a merge patch of
     * {@code status.abort=true} on the status subresource. The controller
     * scales the canary down, reverts traffic to the stable ReplicaSet, and
     * clears the flag. The rollout is also unpaused first, so an abort works
     * on a rollout the pause gate is holding.
     *
     * <p>{@code spec.template} is deliberately left alone — it is managed by
     * Git, and Argo CD would revert a template patch on the next sync. Note
     * the rollout stays in the aborted (Degraded) state until the template is
     * reverted in Git.
     */
    @POST
    @Path("/abort")
    @Produces(MediaType.APPLICATION_JSON)
    public Response abort(@QueryParam("namespace") String namespace, @QueryParam("name") String name) {
        String ns = orDefault(namespace, DEFAULT_NAMESPACE);
        String rolloutName = orDefault(name, DEFAULT_ROLLOUT);
        try {
            Resource<Rollout> rollout = rollout(ns, rolloutName);
            if (rollout.get() == null) {
                return notFound("abort", ns, rolloutName);
            }
            rollout.patch(PatchContext.of(PatchType.JSON_MERGE), "{\"spec\":{\"paused\":false}}");
            rollout.status().patch(PatchContext.of(PatchType.JSON_MERGE), "{\"status\":{\"abort\":true}}");
            activityEvents.rolloutAborted(ns, rolloutName);
            Log.infof("Aborted rollout %s/%s", ns, rolloutName);
            return Response.ok(new ControlResult("abort", ns, rolloutName, null)).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to abort rollout %s/%s", ns, rolloutName);
            return Response.serverError().entity(new ControlResult("abort", ns, rolloutName, e.getMessage())).build();
        }
    }

    private Resource<Rollout> rollout(String namespace, String name) {
        return k8sClient.resources(Rollout.class).inNamespace(namespace).withName(name);
    }

    private static String orDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static Response notFound(String action, String namespace, String rollout) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(new ControlResult(action, namespace, rollout, "rollout not found")).build();
    }

    /**
     * Outcome of a manual control action: which action ran, on which rollout,
     * and the error message when it failed (null on success).
     */
    public record ControlResult(String action, String namespace, String rollout, String error) {
    }
}
