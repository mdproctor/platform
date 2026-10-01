package io.casehub.yaml.core.error;

public record SourceLocation(String file, int line, int column) {

    public static final SourceLocation UNKNOWN = new SourceLocation(null, -1, -1);

    public boolean isKnown() { return file != null && line >= 0; }

    @Override
    public String toString() {
        if (!isKnown()) return "<unknown>";
        return file + ":" + line + (column >= 0 ? ":" + column : "");
    }
}
