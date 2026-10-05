package io.casehub.yaml.core.playbook;

import java.util.Map;
import java.util.Objects;

public record PlaybookDocument(PlaybookFrontMatter frontMatter, Map<String, Object> content) {

    public PlaybookDocument {
        Objects.requireNonNull(content, "content");
    }

    public boolean hasPlaybookHeader() {
        return frontMatter != null;
    }

    public String schema() {
        return frontMatter != null ? frontMatter.schema() : null;
    }

    public String version() {
        return frontMatter != null ? frontMatter.version() : null;
    }
}
