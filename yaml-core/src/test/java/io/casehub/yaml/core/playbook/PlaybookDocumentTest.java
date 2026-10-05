package io.casehub.yaml.core.playbook;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaybookDocumentTest {

    @Test
    void withFrontMatter_exposesSchemaAndVersion() {
        var fm = new PlaybookFrontMatter("1.0", "server");
        var doc = new PlaybookDocument(fm, Map.of("states", Map.of()));

        assertThat(doc.hasPlaybookHeader()).isTrue();
        assertThat(doc.schema()).isEqualTo("server");
        assertThat(doc.version()).isEqualTo("1.0");
    }

    @Test
    void withoutFrontMatter_backwardCompatible() {
        var doc = new PlaybookDocument(null, Map.of("scenario", "legacy"));

        assertThat(doc.hasPlaybookHeader()).isFalse();
        assertThat(doc.schema()).isNull();
        assertThat(doc.version()).isNull();
    }

    @Test
    void nullContent_throws() {
        assertThatThrownBy(() -> new PlaybookDocument(null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
