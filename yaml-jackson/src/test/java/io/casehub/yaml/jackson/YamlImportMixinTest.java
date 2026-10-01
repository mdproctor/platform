package io.casehub.yaml.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.yaml.core.module.YamlModuleFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YamlImportMixinTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = YamlMappers.createWithCoreModule();
    }

    @Test
    void ifKeyDeserializesToConditionField() throws Exception {
        String yaml = """
                module:
                  name: test
                imports:
                  - module: other
                    as: o
                    if: "${enabled}"
                nodes:
                  a:
                    type: sensor
                """;
        YamlModuleFile file = mapper.readValue(yaml, YamlModuleFile.class);
        assertThat(file.imports()).hasSize(1);
        assertThat(file.imports().get(0).condition()).isEqualTo("${enabled}");
    }

    @Test
    void importWithoutConditionHasNullCondition() throws Exception {
        String yaml = """
                module:
                  name: test
                imports:
                  - module: other
                    as: o
                nodes:
                  a:
                    type: sensor
                """;
        YamlModuleFile file = mapper.readValue(yaml, YamlModuleFile.class);
        assertThat(file.imports()).hasSize(1);
        assertThat(file.imports().get(0).condition()).isNull();
    }
}
