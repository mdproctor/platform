package io.casehub.yaml.step.eval;

import io.casehub.yaml.core.error.SourceLocation;
import io.casehub.yaml.core.resolver.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepContextTest {

    private final VariableResolver noOpResolver = new VariableResolver(Map.of(), java.util.Set.of());

    @Test
    void defaultLocation_isUnknown() {
        var ctx = new StepContext(noOpResolver);
        assertThat(ctx.location()).isEqualTo(SourceLocation.UNKNOWN);
    }

    @Test
    void withLocation_returnsNewContextWithLocation() {
        var ctx = new StepContext(noOpResolver);
        var loc = new SourceLocation("flow.yaml", 42, 5);
        var updated = ctx.withLocation(loc);
        assertThat(updated.location()).isEqualTo(loc);
        assertThat(updated.resolver()).isSameAs(ctx.resolver());
        assertThat(updated.deadline()).isSameAs(ctx.deadline());
    }
}
