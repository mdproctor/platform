package io.casehub.platform.agent;

import java.util.Map;

public interface BackendInstanceFactory {
    String backendKey();
    boolean handles(String credentialRef, Map<String, String> credentials);
    BackendInstance create(String credentialRef, Map<String, String> credentials);
}
