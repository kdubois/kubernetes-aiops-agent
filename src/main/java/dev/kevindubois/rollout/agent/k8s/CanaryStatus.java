package dev.kevindubois.rollout.agent.k8s;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CanaryStatus {

    private CanaryWeights weights;

    public CanaryWeights getWeights() { return weights; }
    public void setWeights(CanaryWeights weights) { this.weights = weights; }
}
