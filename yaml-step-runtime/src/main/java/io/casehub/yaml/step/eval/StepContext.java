package io.casehub.yaml.step.eval;

import io.casehub.yaml.core.error.SourceLocation;
import io.casehub.yaml.core.resolver.VariableResolver;

import java.time.Duration;
import java.util.Optional;

public final class StepContext {

    private final VariableResolver resolver;
    private final DeadlineContext deadline;
    private final SourceLocation location;

    public StepContext(VariableResolver resolver) {
        this(resolver, DeadlineContext.NONE, SourceLocation.UNKNOWN);
    }

    public StepContext(VariableResolver resolver, DeadlineContext deadline) {
        this(resolver, deadline, SourceLocation.UNKNOWN);
    }

    public StepContext(VariableResolver resolver, DeadlineContext deadline, SourceLocation location) {
        this.resolver = resolver;
        this.deadline = deadline;
        this.location = location;
    }

    public VariableResolver resolver() { return resolver; }

    public DeadlineContext deadline() { return deadline; }

    public SourceLocation location() { return location; }

    public StepContext withResolver(VariableResolver resolver) {
        return new StepContext(resolver, this.deadline, this.location);
    }

    public StepContext withDeadline(Duration timeout) {
        return new StepContext(resolver, deadline.withTimeout(timeout), this.location);
    }

    public StepContext withLocation(SourceLocation location) {
        return new StepContext(resolver, deadline, location);
    }
}
