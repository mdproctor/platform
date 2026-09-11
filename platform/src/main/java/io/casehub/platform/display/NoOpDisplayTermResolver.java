package io.casehub.platform.display;

import io.casehub.platform.api.display.DisplayTermResolver;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@DefaultBean
@ApplicationScoped
public class NoOpDisplayTermResolver implements DisplayTermResolver {

    @Override
    public String resolveLabel(String value, String vocabUri) {
        return value;
    }

    @Override
    public Optional<String> mapTerm(String value, String sourceVocabUri,
                                    String targetVocabUri) {
        return Optional.empty();
    }

    @Override
    public Optional<String> mapTerm(String value, String sourceVocabUri,
                                    String targetVocabUri, String mappingContext) {
        return Optional.empty();
    }
}
