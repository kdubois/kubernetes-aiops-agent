package dev.kevindubois.rollout.agent.k8s;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TrafficWeight {

    private int weight;

    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
}
