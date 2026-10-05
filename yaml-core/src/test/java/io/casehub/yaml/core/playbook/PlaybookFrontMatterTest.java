package io.casehub.yaml.core.playbook;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaybookFrontMatterTest {

    @Test
    void fullConstructor_preservesAllFields() {
        var fm = new PlaybookFrontMatter("1.0", "server", "my-playbook",
                Map.of("author", "test"));

        assertThat(fm.version()).isEqualTo("1.0");
        assertThat(fm.schema()).isEqualTo("server");
        assertThat(fm.name()).isEqualTo("my-playbook");
        assertThat(fm.metadata()).containsEntry("author", "test");
    }

    @Test
    void twoArgConstructor_defaultsNameAndMetadata() {
        var fm = new PlaybookFrontMatter("1.0", "client");

        assertThat(fm.name()).isNull();
        assertThat(fm.metadata()).isEmpty();
    }

    @Test
    void threeArgConstructor_defaultsMetadata() {
        var fm = new PlaybookFrontMatter("1.0", "client", "demo");

        assertThat(fm.name()).isEqualTo("demo");
        assertThat(fm.metadata()).isEmpty();
    }

    @Test
    void nullVersion_throws() {
        assertThatThrownBy(() -> new PlaybookFrontMatter(null, "client"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullSchema_throws() {
        assertThatThrownBy(() -> new PlaybookFrontMatter("1.0", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void metadataIsImmutable() {
        var fm = new PlaybookFrontMatter("1.0", "client", null, Map.of("k", "v"));
        assertThatThrownBy(() -> fm.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
