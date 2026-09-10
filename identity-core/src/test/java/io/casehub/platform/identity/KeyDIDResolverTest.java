package io.casehub.platform.identity;

import io.casehub.platform.api.identity.DIDDocument;
import io.casehub.platform.api.identity.VerificationMethod;
import io.casehub.platform.api.identity.VerificationMethodType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyDIDResolverTest {

    private final KeyDIDResolver resolver = new KeyDIDResolver();

    @Test
    void returns_empty_for_null_did() {
        assertTrue(resolver.resolve("actor", null).isEmpty());
    }

    @Test
    void returns_empty_for_non_key_did() {
        assertTrue(resolver.resolve("actor", "did:web:example.com").isEmpty());
    }

    @Test
    void resolved_publicKeyBytes_are_spki_format() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = keyPair.getPublic().getEncoded();

        // Build a did:key: z + base58btc(multicodec_prefix + raw_key)
        byte[] raw = new byte[32];
        System.arraycopy(spki, spki.length - 32, raw, 0, 32);
        byte[] multicodec = new byte[2 + raw.length];
        multicodec[0] = (byte) 0xed;
        multicodec[1] = 0x01;
        System.arraycopy(raw, 0, multicodec, 2, raw.length);
        String didKey = "did:key:z" + Base58.encode(multicodec);

        DIDDocument doc = resolver.resolve("actor", didKey).orElseThrow();
        assertEquals(1, doc.verificationMethods().size());

        VerificationMethod vm = doc.verificationMethods().get(0);
        byte[] vmBytes = vm.publicKeyBytes();

        // Must be SPKI format (44 bytes for Ed25519), not raw (32 bytes)
        assertEquals(44, vmBytes.length);

        // Must be loadable as a public key via X509EncodedKeySpec
        var loadedKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(vmBytes));
        assertNotNull(loadedKey);

        // Must report correct VM type
        assertEquals(VerificationMethodType.ED25519, vm.type());
    }

    // --- P-256 tests ---

    @Test
    void resolved_p256_publicKeyBytes_are_canonical_spki() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        var keyPair = kpg.generateKeyPair();
        ECPublicKey ecPub = (ECPublicKey) keyPair.getPublic();
        byte[] expectedSpki = ecPub.getEncoded();

        // Compress the public point to SEC1 format
        byte[] x = toUnsignedByteArray(ecPub.getW().getAffineX(), 32);
        byte[] compressed = new byte[33];
        compressed[0] = (byte) (ecPub.getW().getAffineY().testBit(0) ? 0x03 : 0x02);
        System.arraycopy(x, 0, compressed, 1, 32);

        // P-256 multicodec varint: 0x1200 -> [0x80, 0x24]
        byte[] multicodec = new byte[2 + compressed.length];
        multicodec[0] = (byte) 0x80;
        multicodec[1] = 0x24;
        System.arraycopy(compressed, 0, multicodec, 2, compressed.length);

        String didKey = "did:key:z" + Base58.encode(multicodec);

        DIDDocument doc = resolver.resolve("actor", didKey).orElseThrow();
        assertEquals(1, doc.verificationMethods().size());

        VerificationMethod vm = doc.verificationMethods().get(0);
        assertArrayEquals(expectedSpki, vm.publicKeyBytes(),
                "P-256 SPKI must be canonical (uncompressed, matching PublicKey.getEncoded())");
        assertEquals(VerificationMethodType.P256, vm.type());
    }

    @Test
    void resolved_p256_with_odd_y_selects_correct_root() throws Exception {
        // Generate keys until we get one with odd Y
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey ecPub;
        do {
            ecPub = (ECPublicKey) kpg.generateKeyPair().getPublic();
        } while (!ecPub.getW().getAffineY().testBit(0));

        byte[] expectedSpki = ecPub.getEncoded();
        byte[] x = toUnsignedByteArray(ecPub.getW().getAffineX(), 32);
        byte[] compressed = new byte[33];
        compressed[0] = 0x03; // odd Y
        System.arraycopy(x, 0, compressed, 1, 32);

        byte[] multicodec = new byte[2 + compressed.length];
        multicodec[0] = (byte) 0x80;
        multicodec[1] = 0x24;
        System.arraycopy(compressed, 0, multicodec, 2, compressed.length);

        String didKey = "did:key:z" + Base58.encode(multicodec);

        DIDDocument doc = resolver.resolve("actor", didKey).orElseThrow();
        assertArrayEquals(expectedSpki, doc.verificationMethods().get(0).publicKeyBytes(),
                "0x03 prefix must select the odd-Y root");
    }
// --- secp256k1 tests (JCA unavailable on JDK 21+ — uses known test vectors) ---

    private static final BigInteger SECP256K1_GX = new BigInteger(
            "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);
    private static final BigInteger SECP256K1_GY = new BigInteger(
            "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);
    private static final BigInteger SECP256K1_P  = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);

    // secp256k1 SPKI header: SEQUENCE > SEQUENCE { OID ecPublicKey, OID secp256k1 } > BIT STRING 00 04
    private static final byte[] SECP256K1_SPKI_HEADER = {
            0x30, 0x56, 0x30, 0x10,
            0x06, 0x07, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x02, 0x01,
            0x06, 0x05, 0x2B, (byte) 0x81, 0x04, 0x00, 0x0A,
            0x03, 0x42, 0x00, 0x04
    };

    private byte[] buildExpectedSecp256k1Spki(BigInteger x, BigInteger y) {
        byte[] xBytes = toUnsignedByteArray(x, 32);
        byte[] yBytes = toUnsignedByteArray(y, 32);
        byte[] spki   = new byte[SECP256K1_SPKI_HEADER.length + 64];
        System.arraycopy(SECP256K1_SPKI_HEADER, 0, spki, 0, SECP256K1_SPKI_HEADER.length);
        System.arraycopy(xBytes, 0, spki, SECP256K1_SPKI_HEADER.length, 32);
        System.arraycopy(yBytes, 0, spki, SECP256K1_SPKI_HEADER.length + 32, 32);
        return spki;
    }

    @Test
    void resolved_secp256k1_with_even_y_produces_correct_spki() {
        // Generator point G has even Y
        assertFalse(SECP256K1_GY.testBit(0), "Generator point G should have even Y");

        byte[] xBytes     = toUnsignedByteArray(SECP256K1_GX, 32);
        byte[] compressed = new byte[33];
        compressed[0] = 0x02;
        System.arraycopy(xBytes, 0, compressed, 1, 32);

        // secp256k1 multicodec: 0xe7 as LEB128 varint → [0xe7, 0x01]
        byte[] multicodec = new byte[2 + compressed.length];
        multicodec[0] = (byte) 0xe7;
        multicodec[1] = 0x01;
        System.arraycopy(compressed, 0, multicodec, 2, compressed.length);

        String      didKey = "did:key:z" + Base58.encode(multicodec);
        DIDDocument doc    = resolver.resolve("actor", didKey).orElseThrow();
        assertEquals(1, doc.verificationMethods().size());

        VerificationMethod vm = doc.verificationMethods().get(0);
        assertEquals(VerificationMethodType.SECP256K1, vm.type());
        assertEquals(88, vm.publicKeyBytes().length);

        byte[] expectedSpki = buildExpectedSecp256k1Spki(SECP256K1_GX, SECP256K1_GY);
        assertArrayEquals(expectedSpki, vm.publicKeyBytes(),
                          "secp256k1 SPKI must match independently constructed SPKI from known coordinates");
    }

    @Test
    void resolved_secp256k1_with_odd_y_selects_correct_root() {
        // Use generator point with 0x03 prefix — decompression must yield p - Gy
        byte[] xBytes     = toUnsignedByteArray(SECP256K1_GX, 32);
        byte[] compressed = new byte[33];
        compressed[0] = 0x03;
        System.arraycopy(xBytes, 0, compressed, 1, 32);

        byte[] multicodec = new byte[2 + compressed.length];
        multicodec[0] = (byte) 0xe7;
        multicodec[1] = 0x01;
        System.arraycopy(compressed, 0, multicodec, 2, compressed.length);

        String      didKey = "did:key:z" + Base58.encode(multicodec);
        DIDDocument doc    = resolver.resolve("actor", didKey).orElseThrow();

        BigInteger expectedY    = SECP256K1_P.subtract(SECP256K1_GY);
        byte[]     expectedSpki = buildExpectedSecp256k1Spki(SECP256K1_GX, expectedY);
        assertArrayEquals(expectedSpki, doc.verificationMethods().get(0).publicKeyBytes(),
                          "0x03 prefix with even-Y generator must select the odd root (p - Gy)");
    }

    @Test
    void resolved_secp256k1_decompressed_y_satisfies_curve_equation() {
        byte[] xBytes     = toUnsignedByteArray(SECP256K1_GX, 32);
        byte[] compressed = new byte[33];
        compressed[0] = 0x02;
        System.arraycopy(xBytes, 0, compressed, 1, 32);

        byte[] multicodec = new byte[2 + compressed.length];
        multicodec[0] = (byte) 0xe7;
        multicodec[1] = 0x01;
        System.arraycopy(compressed, 0, multicodec, 2, compressed.length);

        String      didKey = "did:key:z" + Base58.encode(multicodec);
        DIDDocument doc    = resolver.resolve("actor", didKey).orElseThrow();

        byte[] spki = doc.verificationMethods().get(0).publicKeyBytes();
        // Extract Y from SPKI: header(24) + X(32) + Y(32)
        byte[] yBytes = new byte[32];
        System.arraycopy(spki, 56, yBytes, 0, 32);
        BigInteger y = new BigInteger(1, yBytes);

        // Verify Y^2 = X^3 + 7 (mod p)
        BigInteger ySquared = y.modPow(BigInteger.TWO, SECP256K1_P);
        BigInteger xCubedPlus7 = SECP256K1_GX.modPow(BigInteger.valueOf(3), SECP256K1_P)
                                             .add(BigInteger.valueOf(7)).mod(SECP256K1_P);
        assertEquals(xCubedPlus7, ySquared,
                     "Decompressed Y must satisfy secp256k1 curve equation Y² = X³ + 7");
    }


    // --- alsoKnownAs tests ---

    @Test
    void resolved_document_includes_actorId_in_alsoKnownAs() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = keyPair.getPublic().getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(spki, spki.length - 32, raw, 0, 32);
        byte[] multicodec = new byte[2 + raw.length];
        multicodec[0] = (byte) 0xed;
        multicodec[1] = 0x01;
        System.arraycopy(raw, 0, multicodec, 2, raw.length);
        String didKey = "did:key:z" + Base58.encode(multicodec);

        DIDDocument doc = resolver.resolve("claude:reviewer@v1", didKey).orElseThrow();
        assertEquals(List.of("claude:reviewer@v1"), doc.alsoKnownAs());
    }

    @Test
    void resolved_document_has_empty_alsoKnownAs_when_actorId_is_null() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = keyPair.getPublic().getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(spki, spki.length - 32, raw, 0, 32);
        byte[] multicodec = new byte[2 + raw.length];
        multicodec[0] = (byte) 0xed;
        multicodec[1] = 0x01;
        System.arraycopy(raw, 0, multicodec, 2, raw.length);
        String didKey = "did:key:z" + Base58.encode(multicodec);

        DIDDocument doc = resolver.resolve(null, didKey).orElseThrow();
        assertTrue(doc.alsoKnownAs().isEmpty());
    }

    // --- Edge case tests ---

    @Test
    void returns_empty_for_unknown_multicodec_code() {
        // Use multicodec 0xFF (unknown)
        byte[] multicodec = new byte[]{(byte) 0xFF, 0x01, 0x00, 0x00};
        String didKey = "did:key:z" + Base58.encode(multicodec);
        assertTrue(resolver.resolve("actor", didKey).isEmpty());
    }

    @Test
    void returns_empty_for_wrong_key_length() {
        // Ed25519 expects 32 bytes, give it 16
        byte[] multicodec = new byte[2 + 16];
        multicodec[0] = (byte) 0xed;
        multicodec[1] = 0x01;
        String didKey = "did:key:z" + Base58.encode(multicodec);
        assertTrue(resolver.resolve("actor", didKey).isEmpty());
    }

    @Test
    void returns_empty_for_malformed_varint() {
        // Single byte with continuation bit set -- truncated varint
        byte[] data = new byte[]{(byte) 0x80};
        String didKey = "did:key:z" + Base58.encode(data);
        assertTrue(resolver.resolve("actor", didKey).isEmpty());
    }

    // --- Helper ---

    private static byte[] toUnsignedByteArray(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == length) return bytes;
        byte[] result = new byte[length];
        if (bytes.length > length) {
            System.arraycopy(bytes, bytes.length - length, result, 0, length);
        } else {
            System.arraycopy(bytes, 0, result, length - bytes.length, bytes.length);
        }
        return result;
    }
}
