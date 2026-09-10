package io.casehub.platform.identity;

import java.math.BigInteger;
import java.util.Arrays;

public final class Base58 {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);
    private static final int[] DECODE_TABLE = new int[128];

    static {
        Arrays.fill(DECODE_TABLE, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            DECODE_TABLE[ALPHABET.charAt(i)] = i;
        }
    }

    private Base58() {}

    public static String encode(final byte[] data) {
        if (data.length == 0) return "";

        int leadingZeros = 0;
        while (leadingZeros < data.length && data[leadingZeros] == 0) leadingZeros++;

        BigInteger value = new BigInteger(1, data);
        final StringBuilder sb = new StringBuilder();
        while (value.compareTo(BigInteger.ZERO) > 0) {
            final BigInteger[] divRem = value.divideAndRemainder(BASE);
            sb.append(ALPHABET.charAt(divRem[1].intValue()));
            value = divRem[0];
        }
        sb.append("1".repeat(leadingZeros));
        return sb.reverse().toString();
    }

    public static byte[] decode(final String base58) {
        if (base58.isEmpty()) return new byte[0];

        int leadingOnes = 0;
        while (leadingOnes < base58.length() && base58.charAt(leadingOnes) == '1') leadingOnes++;

        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < base58.length(); i++) {
            final char c = base58.charAt(i);
            if (c >= 128 || DECODE_TABLE[c] < 0) {
                throw new IllegalArgumentException("Invalid Base58 character: " + c);
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(DECODE_TABLE[c]));
        }

        byte[] decoded = value.toByteArray();
        if (decoded.length > 1 && decoded[0] == 0) {
            decoded = Arrays.copyOfRange(decoded, 1, decoded.length);
        }
        if (decoded.length == 1 && decoded[0] == 0 && leadingOnes == base58.length()) {
            decoded = new byte[0];
        }

        final byte[] result = new byte[leadingOnes + decoded.length];
        System.arraycopy(decoded, 0, result, leadingOnes, decoded.length);
        return result;
    }
}
