package io.casehub.platform.governance;

import io.casehub.platform.api.governance.ExecutionPolicy;
import java.util.function.Supplier;

public interface PolicyEnforcer {
    <T> T execute(ExecutionPolicy policy, Supplier<T> action);
}
