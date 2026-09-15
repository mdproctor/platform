package io.casehub.platform.llm.config;

public record CloudSourceStatus(
    String sourceId,
    String vendor,
    State state,
    String message,
    int modelCount
) {

    public enum State { ACTIVE, INACTIVE, ERROR }

    public static CloudSourceStatus active(String sourceId, String vendor, int modelCount) {
        return new CloudSourceStatus(sourceId, vendor, State.ACTIVE,
            modelCount + " models discovered", modelCount);
    }

    public static CloudSourceStatus inactive(String sourceId, String vendor, String guidance) {
        return new CloudSourceStatus(sourceId, vendor, State.INACTIVE, guidance, 0);
    }

    public static CloudSourceStatus error(String sourceId, String vendor, String errorMessage) {
        return new CloudSourceStatus(sourceId, vendor, State.ERROR, errorMessage, 0);
    }
}
