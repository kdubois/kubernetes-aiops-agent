package org.csanchez.adk.agents.k8sagent.a2a;

/**
 * A2A response to rollouts-plugin-metric-ai
 */
public class A2AResponse {
	private String analysis;
	private String rootCause;
	private String remediation;
	private String prLink;
	private boolean promote;
	private int confidence;
	
	public String getAnalysis() {
		return analysis;
	}
	
	public void setAnalysis(String analysis) {
		this.analysis = analysis;
	}
	
	public String getRootCause() {
		return rootCause;
	}
	
	public void setRootCause(String rootCause) {
		this.rootCause = rootCause;
	}
	
	public String getRemediation() {
		return remediation;
	}
	
	public void setRemediation(String remediation) {
		this.remediation = remediation;
	}
	
	public String getPrLink() {
		return prLink;
	}
	
	public void setPrLink(String prLink) {
		this.prLink = prLink;
	}
	
	public boolean isPromote() {
		return promote;
	}
	
	public void setPromote(boolean promote) {
		this.promote = promote;
	}
	
	public int getConfidence() {
		return confidence;
	}
	
	public void setConfidence(int confidence) {
		this.confidence = confidence;
	}
}


