package io.casehub.yaml.step.scenario;

import io.casehub.yaml.core.playbook.PlaybookDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaybookParserTest {

    @Test
    void parsePlaybookFrontMatter_extractsVersionAndSchema() {
        String yaml = """
                playbook: "1.0"
                schema: server
                ---
                scenario: incident-response
                states:
                  start:
                    - next: end
                  end: terminal
                """;

        PlaybookDocument doc = PlaybookParser.parse(yaml);

        assertThat(doc.hasPlaybookHeader()).isTrue();
        assertThat(doc.version()).isEqualTo("1.0");
        assertThat(doc.schema()).isEqualTo("server");
        assertThat(doc.frontMatter().name()).isNull();
        assertThat(doc.content()).containsKey("scenario");
        assertThat(doc.content()).containsKey("states");
    }

    @Test
    void parsePlaybookFrontMatter_withName() {
        String yaml = """
                playbook: "1.0"
                schema: client
                name: helpdesk-intake
                ---
                scenario: helpdesk-intake
                steps:
                  - navigate: "#intake"
                """;

        PlaybookDocument doc = PlaybookParser.parse(yaml);

        assertThat(doc.frontMatter().name()).isEqualTo("helpdesk-intake");
        assertThat(doc.schema()).isEqualTo("client");
    }

    @Test
    void parsePlaybookFrontMatter_withMetadata() {
        String yaml = """
                playbook: "1.0"
                schema: clinical-server
                author: test-user
                ---
                scenario: trial
                states:
                  start:
                    - next: end
                  end: terminal
                """;

        PlaybookDocument doc = PlaybookParser.parse(yaml);

        assertThat(doc.schema()).isEqualTo("clinical-server");
        assertThat(doc.frontMatter().metadata()).containsEntry("author", "test-user");
    }

    @Test
    void parseLegacySingleDocument_backwardCompatible() {
        String yaml = """
                scenario: legacy
                states:
                  start:
                    - next: end
                  end: terminal
                """;

        PlaybookDocument doc = PlaybookParser.parse(yaml);

        assertThat(doc.hasPlaybookHeader()).isFalse();
        assertThat(doc.schema()).isNull();
        assertThat(doc.content()).containsKey("scenario");
        assertThat(doc.content()).containsKey("states");
    }

    @Test
    void parseLegacyMultiDoc_withoutPlaybookKey_backwardCompatible() {
        String yaml = """
                scenario: test-scenario
                ---
                states:
                  idle:
                    - next: done
                  done: terminal
                """;

        PlaybookDocument doc = PlaybookParser.parse(yaml);

        assertThat(doc.hasPlaybookHeader()).isFalse();
        assertThat(doc.content()).containsKey("states");
    }

    @Test
    void parsePlaybookFrontMatter_versionAsNumber() {
        String yaml = """
                playbook: 1.0
                schema: server
                ---
                scenario: test
                states:
                  start:
                    - next: end
                  end: terminal
                """;

        PlaybookDocument doc = PlaybookParser.parse(yaml);

        assertThat(doc.version()).isEqualTo("1.0");
    }

    @Test
    void parseEmptyYaml_throws() {
        assertThatThrownBy(() -> PlaybookParser.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsePlaybook_contentMustBeMap() {
        String yaml = """
                playbook: "1.0"
                schema: server
                ---
                - item1
                - item2
                """;

        assertThatThrownBy(() -> PlaybookParser.parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapping");
    }
}
