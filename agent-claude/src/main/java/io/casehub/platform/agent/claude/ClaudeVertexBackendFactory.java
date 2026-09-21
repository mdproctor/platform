package io.casehub.platform.agent.claude;

import io.casehub.platform.agent.BackendInstance;
import io.casehub.platform.agent.BackendInstanceFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class ClaudeVertexBackendFactory implements BackendInstanceFactory {

    @Override
    public String backendKey() { return "claude"; }

    @Override
    public boolean handles(String credentialRef, Map<String, String> credentials) {
        return credentialRef.contains("vertex")
            && credentials.containsKey("project-id");
    }

    @Override
    public BackendInstance create(String credentialRef, Map<String, String> credentials) {
        String projectId = credentials.get("project-id");
        String region = credentials.getOrDefault("region", "us-central1");
        String instanceId = deriveInstanceId(credentialRef);

        Map<String, String> env = Map.of(
            "CLAUDE_CODE_USE_VERTEX", "1",
            "ANTHROPIC_VERTEX_PROJECT_ID", projectId,
            "ANTHROPIC_VERTEX_REGION", region
        );

        var properties = new DefaultClaudeAgentProperties();
        var client = new ClaudeAgentClient(properties, env);
        var backend = new ClaudeAgentProvider(client);
        return new BackendInstance(instanceId, backend);
    }

    String deriveInstanceId(String credentialRef) {
        if ("cloud-vertex".equals(credentialRef)) return "vertex";
        return credentialRef.replace("cloud-", "");
    }
}
