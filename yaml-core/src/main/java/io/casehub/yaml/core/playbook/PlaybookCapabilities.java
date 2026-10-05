package io.casehub.yaml.core.playbook;

public final class PlaybookCapabilities {

    // Shared capabilities
    public static final String STEPS = "steps";
    public static final String VARIABLE_RESOLUTION = "variable-resolution";
    public static final String DECORATORS = "decorators";
    public static final String CONTROL_FLOW = "control-flow";
    public static final String MODULES = "modules";
    public static final String CONCURRENCY = "concurrency";
    public static final String STATE_MACHINE = "state-machine";

    // Client-only capabilities
    public static final String CHAPTERS = "chapters";
    public static final String SECTIONS = "sections";
    public static final String ARIA = "aria";
    public static final String SPOTLIGHT = "spotlight";
    public static final String TUTORIALS = "tutorials";

    // Server-only capabilities
    public static final String ORCHESTRATION = "orchestration";
    public static final String CORRELATION = "correlation";
    public static final String MCP_INVOKE = "mcp-invoke";
    public static final String GRAPHQL_INVOKE = "graphql-invoke";
    public static final String CODEGEN = "codegen";

    private PlaybookCapabilities() {}
}
