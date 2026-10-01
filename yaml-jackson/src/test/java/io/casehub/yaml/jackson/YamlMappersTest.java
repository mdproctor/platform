package io.casehub.yaml.jackson;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlMappersTest {

    private final ObjectMapper mapper = YamlMappers.create();

    @Test
    void yesValue_remainsString_notCoercedToBoolean() throws Exception {
        String yaml = "flag: yes\n";
        Map<String, Object> result = mapper.readValue(yaml, new TypeReference<>() {});
        assertThat(result.get("flag")).isEqualTo("yes");
    }

    @Test
    void noValue_remainsString_notCoercedToBoolean() throws Exception {
        String yaml = "flag: no\n";
        Map<String, Object> result = mapper.readValue(yaml, new TypeReference<>() {});
        assertThat(result.get("flag")).isEqualTo("no");
    }

    @Test
    void onOffValues_remainStrings() throws Exception {
        String yaml = "a: on\nb: off\n";
        Map<String, Object> result = mapper.readValue(yaml, new TypeReference<>() {});
        assertThat(result.get("a")).isEqualTo("on");
        assertThat(result.get("b")).isEqualTo("off");
    }

    @Test
    void trueFalse_areBoolean() throws Exception {
        String yaml = "a: true\nb: false\n";
        Map<String, Object> result = mapper.readValue(yaml, new TypeReference<>() {});
        assertThat(result.get("a")).isEqualTo(true);
        assertThat(result.get("b")).isEqualTo(false);
    }

    @Test
    void integerValues_preservedAsInteger() throws Exception {
        String yaml = "count: 500\n";
        Map<String, Object> result = mapper.readValue(yaml, new TypeReference<>() {});
        assertThat(result.get("count")).isInstanceOf(Integer.class).isEqualTo(500);
    }

    @Test
    void doubleValues_preservedAsDouble() throws Exception {
        String yaml = "ratio: 3.14\n";
        Map<String, Object> result = mapper.readValue(yaml, new TypeReference<>() {});
        assertThat(result.get("ratio")).isEqualTo(3.14);
    }

    @Test
    void quotedNumericString_remainsString() throws Exception {
        String yaml = "port: \"500\"\n";
        Map<String, Object> result = mapper.readValue(yaml, new TypeReference<>() {});
        assertThat(result.get("port")).isEqualTo("500");
    }

    @Test
    void plainString_remainsString() throws Exception {
        String yaml = "env: prod\n";
        Map<String, Object> result = mapper.readValue(yaml, new TypeReference<>() {});
        assertThat(result.get("env")).isEqualTo("prod");
    }

    @Test
    void createWithCoreModule_registersMixins() throws Exception {
        ObjectMapper withModule = YamlMappers.createWithCoreModule();
        assertThat(withModule.getFactory())
                .isInstanceOf(com.fasterxml.jackson.dataformat.yaml.YAMLFactory.class);
        String yaml = "flag: yes\n";
        Map<String, Object> result = withModule.readValue(yaml, new TypeReference<>() {});
        assertThat(result.get("flag")).isEqualTo("yes");
    }
}
