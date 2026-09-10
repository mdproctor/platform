package io.casehub.platform.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Base58Test {

    @Test
    void encode_empty_array_returns_empty_string() {
        assertEquals("", Base58.encode(new byte[0]));
    }

    @Test
    void decode_empty_string_returns_empty_array() {
        assertArrayEquals(new byte[0], Base58.decode(""));
    }

    @Test
    void encode_single_zero_byte_returns_one() {
        assertEquals("1", Base58.encode(new byte[]{0}));
    }

    @Test
    void decode_single_one_returns_zero_byte() {
        assertArrayEquals(new byte[]{0}, Base58.decode("1"));
    }

    @Test
    void encode_preserves_leading_zero_bytes() {
        assertEquals("11", Base58.encode(new byte[]{0, 0}));
        assertEquals("111", Base58.encode(new byte[]{0, 0, 0}));
    }

    @Test
    void decode_preserves_leading_ones_as_zero_bytes() {
        assertArrayEquals(new byte[]{0, 0}, Base58.decode("11"));
        assertArrayEquals(new byte[]{0, 0, 0}, Base58.decode("111"));
    }

    @Test
    void encode_decode_round_trip() {
        byte[] data = {1, 2, 3, 4, 5};
        assertArrayEquals(data, Base58.decode(Base58.encode(data)));
    }

    @Test
    void encode_decode_round_trip_with_leading_zeros() {
        byte[] data = {0, 0, 1, 2, 3};
        assertArrayEquals(data, Base58.decode(Base58.encode(data)));
    }

    @Test
    void encode_known_vector_hello_world() {
        byte[] input = "Hello World!".getBytes();
        assertEquals("2NEpo7TZRRrLZSi2U", Base58.encode(input));
    }

    @Test
    void decode_known_vector_hello_world() {
        assertArrayEquals("Hello World!".getBytes(), Base58.decode("2NEpo7TZRRrLZSi2U"));
    }

    @Test
    void encode_known_vector_with_leading_zeros() {
        // 0x0000287fb4cd → "11233QC4"
        byte[] input = {0x00, 0x00, 0x28, 0x7f, (byte) 0xb4, (byte) 0xcd};
        assertEquals("11233QC4", Base58.encode(input));
    }

    @Test
    void decode_known_vector_with_leading_zeros() {
        byte[] expected = {0x00, 0x00, 0x28, 0x7f, (byte) 0xb4, (byte) 0xcd};
        assertArrayEquals(expected, Base58.decode("11233QC4"));
    }

    @Test
    void decode_rejects_invalid_characters() {
        assertThrows(IllegalArgumentException.class, () -> Base58.decode("0OIl"));
    }

    @Test
    void encode_decode_round_trip_large_value() {
        byte[] data = new byte[34];
        data[0] = (byte) 0xed;
        data[1] = 0x01;
        for (int i = 2; i < 34; i++) data[i] = (byte) (i * 7);
        assertArrayEquals(data, Base58.decode(Base58.encode(data)));
    }
}
