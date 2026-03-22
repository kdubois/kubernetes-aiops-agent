package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.remediation.GitHubIssueTool;
import dev.kevindubois.rollout.agent.remediation.GitHubPatchPRTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class RemediationAgentProducer {

    @Inject
    @ModelName("remediation")
    ChatModel remediationModel;

    @Inject
    GitHubPatchPRTool gitHubPatchPRTool;

    @Inject
    GitHubIssueTool gitHubIssueTool;

    @Produces
    @ApplicationScoped
    public RemediationAgent createRemediationAgent() {
        return AiServices.builder(RemediationAgent.class)
            .chatModel(remediationModel)
            .tools(gitHubPatchPRTool, gitHubIssueTool)
            .build();
    }
}