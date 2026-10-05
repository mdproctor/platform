package io.casehub.yaml.core.playbook;

public final class PlaybookSchemas {

    public static final String CLIENT = "client";
    public static final String SERVER = "server";

    private PlaybookSchemas() {}

    public static boolean isBuiltIn(String schema) {
        return CLIENT.equals(schema) || SERVER.equals(schema);
    }

    public static boolean isDomainSchema(String schema) {
        return schema != null && schema.contains("-") && !isBuiltIn(schema);
    }
}
