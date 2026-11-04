package dev.kevindubois.rollout.agent.api;

import dev.kevindubois.rollout.agent.fixtures.K8sTestFixtures;
import dev.kevindubois.rollout.agent.k8s.Rollout;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.EditReplacePatchable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the manual rollout control endpoints. The Kubernetes client
 * is mocked down to the resource DSL, so the tests verify the exact patches
 * the endpoints send: the promote unpause patch and the abort status signal.
 */
@QuarkusTest
class RolloutControlResourceTest {

    private static final String NAMESPACE = "quarkus-demo";
    private static final String ROLLOUT = "quarkus-demo";

    @InjectMock
    KubernetesClient k8sClient;

    private Resource<Rollout> namedResource;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MixedOperation<Rollout, KubernetesResourceList<Rollout>, Resource<Rollout>> rolloutOp = mock(MixedOperation.class);
        NonNamespaceOperation<Rollout, KubernetesResourceList<Rollout>, Resource<Rollout>> nsOp =
                mock(NonNamespaceOperation.class);
        when(k8sClient.resources(Rollout.class)).thenReturn(rolloutOp);
        when(rolloutOp.inNamespace(NAMESPACE)).thenReturn(nsOp);
        namedResource = mock(Resource.class);
        when(nsOp.withName(ROLLOUT)).thenReturn(namedResource);
        when(namedResource.get()).thenReturn(
                K8sTestFixtures.createTestRollout(ROLLOUT, NAMESPACE, "Progressing", 2, 30));
        when(namedResource.patch(any(PatchContext.class), anyString())).thenAnswer(i -> namedResource.get());
    }

    @Test
    void promoteUnpausesTheRollout() {
        given()
                .when().post("/rollout/promote?namespace=" + NAMESPACE + "&name=" + ROLLOUT)
                .then().statusCode(200);

        verify(namedResource).patch(jsonMergePatch(), eq("{\"spec\":{\"paused\":false}}"));
    }

    @Test
    void promoteDefaultsToTheDemoRollout() {
        given()
                .when().post("/rollout/promote")
                .then().statusCode(200);

        verify(namedResource).patch(jsonMergePatch(), eq("{\"spec\":{\"paused\":false}}"));
    }

    @Test
    void promoteReturns404WhenTheRolloutIsMissing() {
        when(namedResource.get()).thenReturn(null);

        given()
                .when().post("/rollout/promote?namespace=" + NAMESPACE + "&name=" + ROLLOUT)
                .then().statusCode(404);

        verify(namedResource, never()).patch(any(PatchContext.class), anyString());
    }

    @Test
    void promoteReturns500WhenThePatchFails() {
        when(namedResource.patch(any(PatchContext.class), anyString()))
                .thenThrow(new KubernetesClientException("403 Forbidden"));

        given()
                .when().post("/rollout/promote?namespace=" + NAMESPACE + "&name=" + ROLLOUT)
                .then().statusCode(500);
    }

    @Test
    void abortSignalsTheControllerThroughTheStatusSubresource() {
        EditReplacePatchable<Rollout> statusResource = statusResource();

        given()
                .when().post("/rollout/abort?namespace=" + NAMESPACE + "&name=" + ROLLOUT)
                .then().statusCode(200);

        // unpause first so the controller acts on the abort signal,
        // then the same status.abort patch `kubectl argo rollouts abort` sends
        verify(namedResource).patch(jsonMergePatch(), eq("{\"spec\":{\"paused\":false}}"));
        verify(statusResource).patch(jsonMergePatch(), eq("{\"status\":{\"abort\":true}}"));
    }

    @Test
    void abortReturns404WhenTheRolloutIsMissing() {
        when(namedResource.get()).thenReturn(null);

        given()
                .when().post("/rollout/abort?namespace=" + NAMESPACE + "&name=" + ROLLOUT)
                .then().statusCode(404);

        verify(namedResource, never()).patch(any(PatchContext.class), anyString());
    }

    @Test
    void abortReturns500WhenTheStatusPatchFails() {
        EditReplacePatchable<Rollout> statusResource = statusResource();
        when(statusResource.patch(any(PatchContext.class), anyString()))
                .thenThrow(new KubernetesClientException("403 Forbidden"));

        given()
                .when().post("/rollout/abort?namespace=" + NAMESPACE + "&name=" + ROLLOUT)
                .then().statusCode(500);
    }

    /** The status subresource operation the abort signal is patched through. */
    @SuppressWarnings("unchecked")
    private EditReplacePatchable<Rollout> statusResource() {
        EditReplacePatchable<Rollout> statusResource = mock(EditReplacePatchable.class);
        when(namedResource.status()).thenReturn(statusResource);
        when(statusResource.patch(any(PatchContext.class), anyString())).thenAnswer(i -> namedResource.get());
        return statusResource;
    }

    /** Matches the JSON merge patch context both endpoints patch with. */
    private static PatchContext jsonMergePatch() {
        return argThat(context -> PatchType.JSON_MERGE.equals(context.getPatchType()));
    }
}
