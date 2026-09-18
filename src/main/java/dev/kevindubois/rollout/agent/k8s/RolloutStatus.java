package dev.kevindubois.rollout.agent.k8s;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RolloutStatus {

    private String phase;
    private String stableRS;
    private String canaryRS;
    private Integer currentStepIndex;
    private CanaryStatus canary;

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getStableRS() { return stableRS; }
    public void setStableRS(String stableRS) { this.stableRS = stableRS; }

    public String getCanaryRS() { return canaryRS; }
    public void setCanaryRS(String canaryRS) { this.canaryRS = canaryRS; }

    public Integer getCurrentStepIndex() { return currentStepIndex; }
    public void setCurrentStepIndex(Integer currentStepIndex) { this.currentStepIndex = currentStepIndex; }

    public CanaryStatus getCanary() { return canary; }
    public void setCanary(CanaryStatus canary) { this.canary = canary; }
}
