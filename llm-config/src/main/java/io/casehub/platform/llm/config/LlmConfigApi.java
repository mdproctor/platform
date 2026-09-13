package io.casehub.platform.llm.config;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import java.util.List;

@McpDomain("llm-config")
public interface LlmConfigApi {

    @PlatformQuery("List available LLM vendors with their auth requirements")
    List<VendorInfo> vendors();

    @PlatformQuery("List currently configured providers for the caller's tenant")
    List<ProviderConfig> configured();

    @PlatformMutation("Validate credentials against a vendor's live API — returns discovered models on success")
    ValidationResult validate(ValidateRequest request);

    @PlatformMutation("Validate, persist, and register a provider configuration as a ModelSource")
    ConfigureResult configure(ConfigureRequest request);

    @PlatformMutation("Remove a provider configuration and deregister its ModelSource")
    void unconfigure(String providerId);

    @PlatformQuery("List cloud model source status — active, inactive, or error with guidance")
    List<CloudSourceStatus> cloudSourceStatus();

    @PlatformQuery("Ollama runtime status — reachability, version, loaded models with VRAM")
    OllamaSourceStatus ollamaStatus();

    @PlatformMutation("Pull a model into Ollama — accepts library names or hf.co/ references")
    PullOperation pullModel(PullRequest request);

    @PlatformQuery("Check pull operation progress")
    PullProgress pullStatus(String operationId);

    @PlatformMutation("Cancel an in-progress pull operation")
    void cancelPull(String operationId);

    @PlatformMutation("Delete a model from Ollama")
    void deleteModel(String modelName);

}
