package io.casehub.platform.mcp.spring;

import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.ModelEnricher;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.mcp.DomainModel;
import io.casehub.platform.mcp.DomainModelRegistry;
import io.casehub.platform.mcp.ModelScanComplete;
import io.casehub.platform.mcp.OperationDescriptor;
import io.casehub.platform.mcp.ParameterDescriptor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SpringModelScanner {

    private static final System.Logger LOG = System.getLogger(SpringModelScanner.class.getName());

    private final ApplicationContext context;
    private final DomainModelRegistry registry;
    private final ApplicationEventPublisher eventPublisher;

    public SpringModelScanner(ApplicationContext context,
                               DomainModelRegistry registry,
                               ApplicationEventPublisher eventPublisher) {
        this.context = context;
        this.registry = registry;
        this.eventPublisher = eventPublisher;
    }

    void scan() {
        Map<String, List<OperationDescriptor>> domainOps = new LinkedHashMap<>();

        // Pass 1: class-level @McpDomain (matches GraphQLModelScanner behavior)
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> beanType;
            try {
                beanType = context.getType(beanName);
            } catch (Exception e) {
                continue;
            }
            if (beanType == null) {continue;}

            McpDomain mcpDomain = findMcpDomain(beanType);
            if (mcpDomain == null) {continue;}
            if (ModelEnricher.class.isAssignableFrom(beanType)) {continue;}

            String domain = mcpDomain.value();
            domainOps.computeIfAbsent(domain, k -> new ArrayList<>());
            for (Method method : beanType.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {continue;}
                if (method.isAnnotationPresent(PlatformQuery.class)) {
                    String desc = method.getAnnotation(PlatformQuery.class).value();
                    domainOps.get(domain).add(
                            buildOperation(method, beanType,
                                           OperationDescriptor.OperationType.QUERY, desc));
                } else if (method.isAnnotationPresent(PlatformMutation.class)) {
                    String desc = method.getAnnotation(PlatformMutation.class).value();
                    domainOps.get(domain).add(
                            buildOperation(method, beanType,
                                           OperationDescriptor.OperationType.MUTATION, desc));
                }
            }
        }

        // Pass 2: interface-level @McpDomain (skip already-registered domains)
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> beanType;
            try {
                beanType = context.getType(beanName);
            } catch (Exception e) {
                continue;
            }
            if (beanType == null) {continue;}

            for (Class<?> iface : beanType.getInterfaces()) {
                McpDomain mcpDomain = iface.getAnnotation(McpDomain.class);
                if (mcpDomain == null) {continue;}

                String domain = mcpDomain.value();
                if (domainOps.containsKey(domain)) {continue;}

                domainOps.computeIfAbsent(domain, k -> new ArrayList<>());
                for (Method method : iface.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers())) {continue;}
                    if (method.isAnnotationPresent(PlatformQuery.class)) {
                        String desc = method.getAnnotation(PlatformQuery.class).value();
                        domainOps.get(domain).add(
                                buildOperation(method, beanType,
                                               OperationDescriptor.OperationType.QUERY, desc));
                    } else if (method.isAnnotationPresent(PlatformMutation.class)) {
                        String desc = method.getAnnotation(PlatformMutation.class).value();
                        domainOps.get(domain).add(
                                buildOperation(method, beanType,
                                               OperationDescriptor.OperationType.MUTATION, desc));
                    }
                }
            }
        }

        Map<String, ModelEnricher> enricherMap = resolveEnrichers();

        for (var entry : domainOps.entrySet()) {
            String              domain   = entry.getKey();
            ModelEnricher       enricher = enricherMap.get(domain);
            String              summary  = enricher != null ? enricher.summary() : "";
            Map<String, Object> state    = enricher != null ? enricher.state() : Map.of();

            DomainModel model = new DomainModel(domain, summary,
                                                List.copyOf(entry.getValue()), List.of(), state);
            registry.register(model);
            LOG.log(System.Logger.Level.INFO, "MCP domain ''{0}'': {1} operations", domain, model.operations().size());
        }

        eventPublisher.publishEvent(new ModelScanComplete());
        LOG.log(System.Logger.Level.INFO, "MCP scan complete: {0} domains", domainOps.size());
    }

    private Map<String, ModelEnricher> resolveEnrichers() {
        Map<String, ModelEnricher> map = new HashMap<>();
        for (ModelEnricher enricher : context.getBeansOfType(ModelEnricher.class).values()) {
            McpDomain domainAnn = findMcpDomain(enricher.getClass());
            if (domainAnn != null) {
                map.put(domainAnn.value(), enricher);
            }
        }
        return map;
    }

    private McpDomain findMcpDomain(Class<?> cls) {
        while (cls != null && cls != Object.class) {
            McpDomain ann = cls.getAnnotation(McpDomain.class);
            if (ann != null) return ann;
            cls = cls.getSuperclass();
        }
        return null;
    }

    private OperationDescriptor buildOperation(Method method, Class<?> beanType,
                                                OperationDescriptor.OperationType type,
                                                String description) {
        List<ParameterDescriptor> params = buildParams(method);
        return new OperationDescriptor(method.getName(), type, description, params,
                method.getReturnType().getSimpleName(), method, beanType);
    }

    private List<ParameterDescriptor> buildParams(Method method) {
        List<ParameterDescriptor> params = new ArrayList<>();
        for (Parameter param : method.getParameters()) {
            if (param.isAnnotationPresent(ContextParam.class)) continue;

            String name = param.getName();
            boolean required = !Optional.class.isAssignableFrom(param.getType());
            Map<String, String> fields = expandFields(param.getType());
            params.add(new ParameterDescriptor(name, mapTypeName(param.getType()),
                    required, "", fields));
        }
        return params;
    }

    private Map<String, String> expandFields(Class<?> type) {
        if (type.isPrimitive() || type == String.class || type == UUID.class
                || type == Instant.class || type.isEnum()
                || Map.class.isAssignableFrom(type)
                || List.class.isAssignableFrom(type)) {
            return Map.of();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (type.isRecord()) {
            for (var component : type.getRecordComponents()) {
                fields.put(component.getName(), mapTypeName(component.getType()));
            }
        } else {
            for (var field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                fields.put(field.getName(), mapTypeName(field.getType()));
            }
        }
        return fields;
    }

    private String mapTypeName(Class<?> type) {
        if (type == String.class) return "String";
        if (type == UUID.class) return "UUID";
        if (type == Instant.class) return "Instant";
        if (type == int.class || type == Integer.class) return "Integer";
        if (type == long.class || type == Long.class) return "Long";
        if (type == boolean.class || type == Boolean.class) return "Boolean";
        if (type == double.class || type == Double.class) return "Double";
        if (Map.class.isAssignableFrom(type)) return "JSON";
        if (List.class.isAssignableFrom(type)) return "List";
        return type.getSimpleName();
    }
}
