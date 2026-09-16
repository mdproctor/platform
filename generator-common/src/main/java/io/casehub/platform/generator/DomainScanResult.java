package io.casehub.platform.generator;

import java.util.ArrayList;
import java.util.List;

public record DomainScanResult(
        String domainName,
        String declaringTypeFqcn,
        String declaringTypeSimple,
        String basePath,
        List<ResolvedOperation> operations
) {
    public static DomainScanResult of(String domainName, String spiInterfaceFqcn, String spiInterfaceSimple) {
        return new DomainScanResult(domainName, spiInterfaceFqcn, spiInterfaceSimple, null, new ArrayList<>());
    }

    public static DomainScanResult of(String domainName, String spiInterfaceFqcn, String spiInterfaceSimple, String basePath) {
        return new DomainScanResult(domainName, spiInterfaceFqcn, spiInterfaceSimple, basePath, new ArrayList<>());
    }

    public String resolvedBasePath() {
        return basePath != null && !basePath.isEmpty() ? basePath : "/api/" + domainName;
    }
}
