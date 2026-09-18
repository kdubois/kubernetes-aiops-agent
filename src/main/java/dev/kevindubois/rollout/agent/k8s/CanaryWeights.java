package dev.kevindubois.rollout.agent.k8s;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CanaryWeights {

    private TrafficWeight canary;
    private TrafficWeight stable;

    public TrafficWeight getCanary() { return canary; }
    public void setCanary(TrafficWeight canary) { this.canary = canary; }

    public TrafficWeight getStable() { return stable; }
    public void setStable(TrafficWeight stable) { this.stable = stable; }
}
