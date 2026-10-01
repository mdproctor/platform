package io.casehub.yaml.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;

public final class YamlMappers {

    private YamlMappers() {}

    public static ObjectMapper create() {
        return new ObjectMapper(
                YAMLFactory.builder()
                           .enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS)
                           .build());
    }

    public static ObjectMapper createWithCoreModule() {
        ObjectMapper mapper = create();
        mapper.registerModule(new YamlCoreJacksonModule());
        return mapper;
    }
}
