package io.casehub.platform.mock;

import io.casehub.platform.api.governance.SessionIsolator;

import java.util.function.Supplier;

public class NoOpSessionIsolator implements SessionIsolator {
    @Override public <T> T runIsolated(Supplier<T> work) { return work.get(); }
}
