// package org.csanchez.adk.agents.k8sagent;

// import java.util.NoSuchElementException;

// import com.google.adk.agents.BaseAgent;
// import com.google.common.collect.ImmutableList;

// public class AgentLoader implements com.google.adk.web.AgentLoader {

//     public static final AgentLoader INSTANCE = new AgentLoader();

//     @Override
//     public ImmutableList<String> listAgents() {
//         return ImmutableList.of("kubernetes");
//     }

//     @Override
//     public BaseAgent loadAgent(String name) {
//         switch (name) {
//             case "kubernetes": return KubernetesAgent.initAgent();
//             default: throw new NoSuchElementException("Agent not found: " + name);
//         }
//     }

// }