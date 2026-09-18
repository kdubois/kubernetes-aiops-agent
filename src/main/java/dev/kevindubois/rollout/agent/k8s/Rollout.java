package dev.kevindubois.rollout.agent.k8s;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("argoproj.io")
@Version("v1alpha1")
@Kind("Rollout")
public class Rollout extends CustomResource<Void, RolloutStatus> implements Namespaced {
}
