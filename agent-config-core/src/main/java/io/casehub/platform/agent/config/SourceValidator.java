package io.casehub.platform.agent.config;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class SourceValidator {

    private static final Set<String> AUTH_TYPES = Set.of("bearer", "header", "basic");
    private static final Pattern HTTP_TOKEN = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$");
    private static final Pattern HEX_64 = Pattern.compile("^[0-9a-fA-F]{64}$");

    private SourceValidator() {}

    public static List<String> validate(SourceDeclaration source) {
        var errors = new ArrayList<String>();
        validateAuth(source, errors);
        validateIntegrity(source, errors);
        return errors;
    }

    private static void validateAuth(SourceDeclaration source, List<String> errors) {
        var auth = source.auth();
        if (auth == null) return;

        if (!AUTH_TYPES.contains(auth.type())) {
            errors.add("Unknown auth type '" + auth.type() + "' for source " + source.uri());
        }
        if ("header".equals(auth.type()) && (auth.headerName() == null || auth.headerName().isBlank())) {
            errors.add("header-name required when auth type is 'header' for source " + source.uri());
        }
        if (auth.headerName() != null && !HTTP_TOKEN.matcher(auth.headerName()).matches()) {
            errors.add("Invalid header name '" + auth.headerName() + "' for source " + source.uri()
                    + " (must be a valid HTTP token)");
        }
        if (auth.credential() != null) {
            try {
                CredentialRef.parse(auth.credential());
            } catch (IllegalArgumentException e) {
                errors.add("Invalid credential reference '" + auth.credential() + "' for source " + source.uri());
            }
        }
    }

    private static void validateIntegrity(SourceDeclaration source, List<String> errors) {
        var integrity = source.integrity();
        if (integrity == null) return;

        if (integrity.digest() != null) {
            if (!integrity.digest().startsWith("sha256:")) {
                var prefix = integrity.digest().contains(":")
                        ? integrity.digest().substring(0, integrity.digest().indexOf(':'))
                        : integrity.digest();
                errors.add("Unknown digest algorithm '" + prefix + "' for source " + source.uri()
                        + " (supported: sha256)");
            } else {
                var hex = integrity.digest().substring("sha256:".length());
                if (!HEX_64.matcher(hex).matches()) {
                    errors.add("Malformed digest value for source " + source.uri()
                            + ": expected 64 hex characters after sha256: prefix");
                }
            }
        }

        if (integrity.signature() != null && integrity.signer() == null) {
            errors.add("signer required when signature is declared for source " + source.uri());
        }
        if (integrity.signer() != null && integrity.signature() == null) {
            errors.add("signature required when signer is declared for source " + source.uri());
        }

        if (integrity.signature() != null) {
            try {
                Base64.getUrlDecoder().decode(integrity.signature());
            } catch (IllegalArgumentException e) {
                errors.add("Invalid signature encoding for source " + source.uri()
                        + ": must be base64url-encoded");
            }
        }
    }
}
