package dev.kevindubois.rollout.agent.console;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.kevindubois.rollout.agent.agents.KubernetesAgent;

/**
 * Handles console mode operation for the Kubernetes Agent
 */
@ApplicationScoped
public class ConsoleRunner {
    
    private final KubernetesAgent agentService;
    private final ExecutorService executorService;
    private boolean consoleMode = false;
    
    @Inject
    public ConsoleRunner(KubernetesAgent agentService) {
        this.agentService = agentService;
        this.executorService = Executors.newSingleThreadExecutor();
    }
    
    /**
     * Start console mode if requested via command line arguments
     */
    void onStart(@Observes StartupEvent ev) {
        // Check if console mode is enabled via system property
        String runMode = System.getProperty("run.mode");
        if ("console".equals(runMode)) {
            consoleMode = true;
            Log.info("Starting Kubernetes AI Agent in console mode");
            executorService.submit(this::runConsoleMode);
        }
    }
    
    /**
     * Cleanup on shutdown
     */
    void onStop(@Observes ShutdownEvent ev) {
        if (consoleMode) {
            Log.info("Shutting down console mode");
            executorService.shutdown();
        }
    }
    
    /**
     * Run the agent in console mode.
     * Uses "console-session" as the memory ID to maintain conversation history
     * throughout the console session.
     */
    private void runConsoleMode() {
        Log.info("Kubernetes AI Agent started in console mode. Type 'quit' to exit.");
        
        // Use a fixed memory ID for the console session to maintain conversation history
        final String memoryId = "console-session";
        Log.info("Console session memory ID: " + memoryId);
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("Exiting Kubernetes Agent. Goodbye!");
        }));
        
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nYou > ");
                String userInput = scanner.nextLine();
                if ("quit".equalsIgnoreCase(userInput)) {
                    break;
                }
                
                System.out.print("\nAgent > ");
                String response = agentService.chat(memoryId, userInput);
                System.out.println(response);
            }
        } catch (Exception e) {
            Log.error("Error in console mode", e);
        }
    }
}

