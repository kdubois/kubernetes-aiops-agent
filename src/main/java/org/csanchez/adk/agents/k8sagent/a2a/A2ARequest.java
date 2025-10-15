package org.csanchez.adk.agents.k8sagent.a2a;

import java.util.Map;

/**
 * A2A request from rollouts-plugin-metric-ai
 */
public class A2ARequest {
	private String userId;
	private String prompt;
	private Map<String, Object> context;
	
	public String getUserId() {
		return userId;
	}
	
	public void setUserId(String userId) {
		this.userId = userId;
	}
	
	public String getPrompt() {
		return prompt;
	}
	
	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}
	
	public Map<String, Object> getContext() {
		return context;
	}
	
	public void setContext(Map<String, Object> context) {
		this.context = context;
	}
}


