package io.casehub.platform.simulation;

import java.util.Optional;

public interface SimulationConfig {

    Optional<String> strategyFor(String qualifiedName);

    boolean captureEnabled(String qualifiedName);

    Optional<ExhaustionPolicy> exhaustionPolicy(String qualifiedName);

    default Optional<Double> threshold(String qualifiedName) {
        return Optional.empty();
    }

    default double speed() {
        return 1.0;
    }

    default DataRealism fallthroughRealism(String qualifiedName) {
        return null;
    }


}
