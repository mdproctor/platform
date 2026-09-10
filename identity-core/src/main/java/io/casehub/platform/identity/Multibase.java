package io.casehub.platform.identity;

import java.util.Base64;

public final class Multibase {

    private Multibase() {}

    public static byte[] decode(final String multibase) {
        if (multibase == null || multibase.isEmpty()) {
            throw new IllegalArgumentException("Multibase string must not be null or empty");
        }
        final String payload = multibase.substring(1);
        return switch (multibase.charAt(0)) {
            case 'z' -> Base58.decode(payload);
            case 'u' -> payload.isEmpty() ? new byte[0] : Base64.getUrlDecoder().decode(payload);
            case 'f' -> decodeHex(payload);
            default -> throw new IllegalArgumentException(
                    "Unsupported multibase prefix: " + multibase.charAt(0));
        };
    }

    public static String encode(final byte[] data, final char prefix) {
        return switch (prefix) {
            case 'z' -> "z" + Base58.encode(data);
            case 'u' -> "u" + Base64.getUrlEncoder().withoutPadding().encodeToString(data);
            default -> throw new IllegalArgumentException("Unsupported multibase prefix: " + prefix);
        };
    }

    private static byte[] decodeHex(final String hex) {
        if (hex.isEmpty()) return new byte[0];
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        final byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
