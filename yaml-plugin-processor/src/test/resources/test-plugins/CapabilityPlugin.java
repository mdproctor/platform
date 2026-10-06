package test.plugins;

import io.casehub.yaml.plugin.api.*;
import java.util.Map;

@Plugin(value = "cap-action", capability = "orchestration")
public record CapabilityPlugin(@Required String target) {
    @Execute
    public Result run() {
        return Result.of(Map.of("target", target));
    }
}
