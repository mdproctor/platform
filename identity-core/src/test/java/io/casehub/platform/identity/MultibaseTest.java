package io.casehub.platform.identity;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class MultibaseTest {

    @Test
    void decode_z_prefix_uses_base58btc() {
        byte[] data = {1, 2, 3, 4, 5};
        String base58 = Base58.encode(data);
        assertArrayEquals(data, Multibase.decode("z" + base58));
    }

    @Test
    void decode_u_prefix_uses_base64url() {
        byte[] data = {1, 2, 3, 4, 5};
        String base64url = Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        assertArrayEquals(data, Multibase.decode("u" + base64url));
    }

    @Test
    void decode_unknown_prefix_throws() {
        assertThrows(IllegalArgumentException.class, () -> Multibase.decode("x12345"));
    }

    @Test
    void decode_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> Multibase.decode(null));
    }

    @Test
    void decode_empty_string_throws() {
        assertThrows(IllegalArgumentException.class, () -> Multibase.decode(""));
    }

    @Test
    void decode_prefix_only_returns_empty_bytes() {
        assertArrayEquals(new byte[0], Multibase.decode("z"));
        assertArrayEquals(new byte[0], Multibase.decode("u"));
    }

    @Test
    void decode_f_prefix_uses_hex() {
        // f + lowercase hex for bytes [0xDE, 0xAD]
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD}, Multibase.decode("fdead"));
    }

    @Test
    void round_trip_base58btc() {
        byte[] data = {0, 0, (byte) 0xed, 0x01, 10, 20, 30};
        String encoded = Multibase.encode(data, 'z');
        assertTrue(encoded.startsWith("z"));
        assertArrayEquals(data, Multibase.decode(encoded));
    }

    @Test
    void round_trip_base64url() {
        byte[] data = {0, 0, (byte) 0xed, 0x01, 10, 20, 30};
        String encoded = Multibase.encode(data, 'u');
        assertTrue(encoded.startsWith("u"));
        assertArrayEquals(data, Multibase.decode(encoded));
    }

    @Test
    void encode_unknown_prefix_throws() {
        assertThrows(IllegalArgumentException.class, () -> Multibase.encode(new byte[]{1}, 'x'));
    }
}
