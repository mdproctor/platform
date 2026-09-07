package io.casehub.platform.identity;

import java.util.List;

public record ScimAgentResource(String did, List<byte[]> derCertificates) {
    public ScimAgentResource {
        derCertificates = derCertificates == null ? List.of() : List.copyOf(derCertificates);
    }
}
