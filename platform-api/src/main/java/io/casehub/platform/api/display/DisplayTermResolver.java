package io.casehub.platform.api.display;

import java.util.Optional;

public interface DisplayTermResolver {

    String resolveLabel(String value, String vocabUri);

    Optional<String> mapTerm(String value, String sourceVocabUri,
                             String targetVocabUri);

    Optional<String> mapTerm(String value, String sourceVocabUri,
                             String targetVocabUri, String mappingContext);
}
