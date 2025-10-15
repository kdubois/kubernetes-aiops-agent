package org.csanchez.adk.agents.k8sagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import org.csanchez.adk.agents.k8sagent.remediation.GitHubPRTool;
import org.csanchez.adk.agents.k8sagent.remediation.GitOperations;
import org.csanchez.adk.agents.k8sagent.tools.*;
import com.google.adk.sessions.Session;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.GoogleSearchTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import com.google.adk.events.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@SpringBootApplication
public class KubernetesAgent {
	
	private static final Logger logger = LoggerFactory.getLogger(KubernetesAgent.class);
	private static final String MODEL_NAME = "gemini-2.0-flash-exp";
	private static final String AGENT_NAME = "KubernetesAgent";
	private static final String USER_ID = "argo-rollouts";
	
	// ROOT_AGENT needed for ADK Web UI and A2A communication
	public static final BaseAgent ROOT_AGENT = initAgent();
	
	public static void main(String[] args) {
		// Start Spring Boot application (includes REST API for A2A)
		SpringApplication.run(KubernetesAgent.class, args);
		
		// Optionally run in console mode if not in server mode
		if (args.length > 0 && "console".equals(args[0])) {
			runConsoleMode();
		}
	}
	
	@Bean
	public InMemoryRunner getRunner() {
		return new InMemoryRunner(ROOT_AGENT);
	}
	
	private static void runConsoleMode() {
		InMemoryRunner runner = new InMemoryRunner(ROOT_AGENT);
		Session session = runner.sessionService()
			.createSession(AGENT_NAME, USER_ID)
			.blockingGet();
		
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			System.out.println("\nExiting Kubernetes Agent. Goodbye!");
		}));
		
		try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
			System.out.println("Kubernetes AI Agent started. Type 'quit' to exit.");
			while (true) {
				System.out.print("\nYou > ");
				String userInput = scanner.nextLine();
				if ("quit".equalsIgnoreCase(userInput)) {
					break;
				}
				Content userMsg = Content.fromParts(Part.fromText(userInput));
				Flowable<Event> events = runner.runAsync(USER_ID, session.id(), userMsg);
				System.out.print("\nAgent > ");
				events.blockingForEach(event -> System.out.println(event.stringifyContent()));
			}
		}
	}
	
	public static BaseAgent initAgent() {
		try {
			logger.info("Initializing Kubernetes AI Agent");
			
			// Kubernetes Tools - use FunctionTool.create() like cloud-run
			List<BaseTool> allTools = new ArrayList<>();
			allTools.add(FunctionTool.create(K8sTools.class, "debugPod"));
			allTools.add(FunctionTool.create(K8sTools.class, "getEvents"));
			allTools.add(FunctionTool.create(K8sTools.class, "getLogs"));
			allTools.add(FunctionTool.create(K8sTools.class, "getMetrics"));
			allTools.add(FunctionTool.create(K8sTools.class, "inspectResources"));
			
			logger.info("Loaded {} Kubernetes tools", allTools.size());
			
			// GitHub remediation tool
			GitHubPRTool prTool = null;
			try {
				prTool = new GitHubPRTool(new GitOperations());
				allTools.add(prTool);
				logger.info("GitHub PR tool initialized");
			} catch (Exception e) {
				logger.warn("GitHub PR tool not available (GITHUB_TOKEN not set): {}", e.getMessage());
			}
			
			// Google Search for known issues
			LlmAgent searchAgent = LlmAgent.builder()
				.model("gemini-2.0-flash-exp")
				.name("search_agent")
				.description("Search Google for known issues")
				.instruction("You're a specialist in searching for known Kubernetes and software issues")
				.tools(new GoogleSearchTool())
				.outputKey("search_result")
				.build();
			
			allTools.add(AgentTool.create(searchAgent, false));
			
			logger.info("Total tools available: {}", allTools.size());
			logger.debug("Tools: {}", allTools);
			
			// Build the main agent
			BaseAgent agent = LlmAgent.builder()
				.model(MODEL_NAME)
				.name(AGENT_NAME)
				.description("Autonomous Kubernetes debugging and remediation agent")
				.instruction("""
					You are an expert Kubernetes SRE and developer with deep knowledge of:
					- Container orchestration and Kubernetes internals
					- Common application failure patterns
					- Log analysis and root cause identification
					- Code remediation and bug fixing
					
					Your workflow:
					1. Analyze the problem description and identify the failing pod/service
					2. Gather comprehensive diagnostic data:
						 - Pod status and conditions (use debug_kubernetes_pod)
						 - Recent events (use get_kubernetes_events)
						 - Container logs (use get_pod_logs, include previous=true if crashed)
						 - Resource metrics (use get_pod_metrics)
						 - Related resources like services, deployments (use inspect_kubernetes_resources)
					3. Identify root cause using AI analysis and pattern matching
					4. Search for known issues if applicable (use search_agent)
					5. If a code fix is needed, determine:
						 - Which repository to clone
						 - Which files need changes
						 - Specific code modifications (diffs)
						 - Then call create_github_pr with this information
						 
						 IMPORTANT: You provide the WHAT (files to change, code diffs), 
						 the tool handles the HOW (git clone, branch, commit, push, PR creation)
						 using standard libraries. Do NOT generate git commands.
					6. Return a comprehensive report with:
						 - Root cause
						 - Remediation steps taken
						 - PR link (if created)
						 - Recommendations for prevention
					
					Always gather data systematically before making conclusions.
					Be thorough but concise in your analysis.
				""")
				.tools(allTools.toArray(new BaseTool[0]))
				.outputKey("k8s_agent_result")
				.build();
			
			logger.info("Kubernetes AI Agent initialized successfully");
			return agent;
			
		} catch (Exception e) {
			logger.error("Failed to initialize agent", e);
			throw new RuntimeException("Agent initialization failed", e);
		}
	}
}


