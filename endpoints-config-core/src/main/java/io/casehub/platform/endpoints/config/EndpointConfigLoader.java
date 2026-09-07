package io.casehub.platform.endpoints.config;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.path.PathParser;
import org.jboss.logging.Logger;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EndpointConfigLoader {

    private static final Logger LOG = Logger.getLogger(EndpointConfigLoader.class);
    private static final Pattern VAR_PATTERN    = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern UNRESOLVED_VAR = Pattern.compile("\\$\\{[^}]+}");

    public EndpointConfigLoader(EndpointRegistry registry, List<String> endpointFiles, String pathSeparator) {
        if (endpointFiles != null && !endpointFiles.isEmpty()) {
            PathParser parser = PathParser.of(pathSeparator);
            int count = 0;
            for (String fileSpec : endpointFiles) {
                try (InputStream is = openStream(fileSpec)) {
                    List<Map<String, Object>> raw = YamlEndpointLoader.load(is);
                    for (Map<String, Object> entry : raw) {
                        registry.register(parseDescriptor(entry, parser));
                        count++;
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load endpoints from: " + fileSpec, e);
                }
            }
            LOG.infof("Loaded %d endpoints into %s",
                count, registry.getClass().getSuperclass().getSimpleName());
        }
    }

    public static EndpointDescriptor parseDescriptor(Map<String, Object> entry, PathParser parser) {
        String rawPath      = required(entry, "path");
        String rawTenancyId = required(entry, "tenancyId");
        String rawType      = required(entry, "type");
        String rawProtocol  = required(entry, "protocol");

        String path      = validateNoUnresolved(interpolate(rawPath),      "path");
        String tenancyId = validateNoUnresolved(interpolate(rawTenancyId), "tenancyId");

        String rawCredRef    = optionalStr(entry, "credentialRef");
        String credentialRef = rawCredRef != null
            ? validateNoUnresolved(interpolate(rawCredRef), "credentialRef")
            : null;

        Map<String, String> properties = Map.of();
        if (entry.containsKey("properties")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawProps = (Map<String, Object>) entry.get("properties");
            if (rawProps != null) {
                Map<String, String> interpolated = new LinkedHashMap<>();
                for (Map.Entry<String, Object> prop : rawProps.entrySet()) {
                    String v = validateNoUnresolved(
                        interpolate(String.valueOf(prop.getValue())),
                        "properties." + prop.getKey());
                    interpolated.put(prop.getKey(), v);
                }
                properties = Map.copyOf(interpolated);
            }
        }

        if (!entry.containsKey("capabilities"))
            throw new RuntimeException("missing field: capabilities");
        @SuppressWarnings("unchecked")
        List<String> capStrings = (List<String>) entry.get("capabilities");
        Set<EndpointCapability> capabilities = capStrings == null ? Set.of() : capStrings.stream()
            .map(EndpointCapability::valueOf)
            .collect(Collectors.toUnmodifiableSet());

        return new EndpointDescriptor(
            Path.parse(path, parser),
            tenancyId,
            EndpointType.valueOf(rawType),
            EndpointProtocol.valueOf(rawProtocol),
            properties,
            credentialRef,
            capabilities);
    }

    public static String interpolate(String value) {
        if (value == null || !value.contains("${")) return value;
        Matcher m = VAR_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = System.getProperty(key);
            if (replacement == null) replacement = System.getenv(key);
            if (replacement == null) replacement = m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static InputStream openStream(String fileSpec) throws Exception {
        if (fileSpec.startsWith("classpath:")) {
            String resource = fileSpec.substring("classpath:".length());
            InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource);
            if (is == null)
                throw new IllegalArgumentException("Classpath resource not found: " + resource);
            return is;
        }
        return new FileInputStream(fileSpec);
    }

    private static String required(Map<String, Object> entry, String field) {
        Object val = entry.get(field);
        if (val == null) throw new RuntimeException("missing field: " + field);
        return String.valueOf(val);
    }

    private static String optionalStr(Map<String, Object> entry, String field) {
        Object val = entry.get(field);
        return val != null ? String.valueOf(val) : null;
    }

    public static String validateNoUnresolved(String value, String field) {
        if (value != null && UNRESOLVED_VAR.matcher(value).find())
            throw new RuntimeException(
                "Unresolved variable in field '" + field + "': " + value);
        return value;
    }
}
