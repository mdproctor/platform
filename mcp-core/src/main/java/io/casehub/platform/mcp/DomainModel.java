package io.casehub.platform.mcp;

import java.util.List;
import java.util.Map;

public record DomainModel(
        String name,
        String summary,
        List<OperationDescriptor> operations,
        List<EventDescriptor> events,
        Map<String, Object> state) {

    public long queryCount() {
        return operations.stream()
                .filter(op -> op.type() == OperationDescriptor.OperationType.QUERY)
                .count();
    }

    public long mutationCount() {
        return operations.stream()
                .filter(op -> op.type() == OperationDescriptor.OperationType.MUTATION)
                .count();
    }
}
