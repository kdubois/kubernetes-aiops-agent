package dev.kevindubois.rollout.agent.k8s;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RolloutSpec {

    private Boolean paused;

    public Boolean getPaused() { return paused; }
    public void setPaused(Boolean paused) { this.paused = paused; }
}
