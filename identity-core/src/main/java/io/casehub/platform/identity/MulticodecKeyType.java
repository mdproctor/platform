package io.casehub.platform.identity;

import io.casehub.platform.api.identity.VerificationMethodType;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Optional;

public enum MulticodecKeyType {

    ED25519(0xed, VerificationMethodType.ED25519, 32) {
        private static final byte[] SPKI_PREFIX = {
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        };

        @Override
        public byte[] toSpki(byte[] rawKey) {
            byte[] spki = new byte[SPKI_PREFIX.length + rawKey.length];
            System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
            System.arraycopy(rawKey, 0, spki, SPKI_PREFIX.length, rawKey.length);
            return spki;
        }
    },

    P256(0x1200, VerificationMethodType.P256, 33) {
        private static final ECParameterSpec EC_PARAMS;
        static {
            try {
                AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                params.init(new ECGenParameterSpec("secp256r1"));
                EC_PARAMS = params.getParameterSpec(ECParameterSpec.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @Override
        public byte[] toSpki(byte[] rawKey) {
            if (rawKey[0] != 0x02 && rawKey[0] != 0x03) {
                throw new IllegalArgumentException("Invalid SEC1 compression prefix: " + rawKey[0]);
            }
            boolean yOdd   = rawKey[0] == 0x03;
            byte[]  xBytes = new byte[32];
            System.arraycopy(rawKey, 1, xBytes, 0, 32);
            BigInteger x = new BigInteger(1, xBytes);

            BigInteger p = ((java.security.spec.ECFieldFp) EC_PARAMS.getCurve().getField()).getP();
            BigInteger a = EC_PARAMS.getCurve().getA();
            BigInteger b = EC_PARAMS.getCurve().getB();

            BigInteger ySquared = x.modPow(BigInteger.valueOf(3), p)
                                   .add(a.multiply(x).mod(p))
                                   .add(b)
                                   .mod(p);

            BigInteger y = ySquared.modPow(p.add(BigInteger.ONE).shiftRight(2), p);

            if (y.testBit(0) != yOdd) {
                y = p.subtract(y);
            }

            try {
                ECPublicKeySpec spec = new ECPublicKeySpec(new ECPoint(x, y), EC_PARAMS);
                return KeyFactory.getInstance("EC").generatePublic(spec).getEncoded();
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to construct P-256 public key", e);
            }
        }
    },

    SECP256K1(0xe7, VerificationMethodType.SECP256K1, 33) {
        private static final BigInteger P = new BigInteger(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
        private static final BigInteger B = BigInteger.valueOf(7);
        private static final byte[] SPKI_HEADER = {
                0x30, 0x56, 0x30, 0x10,
                0x06, 0x07, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x02, 0x01,
                0x06, 0x05, 0x2B, (byte) 0x81, 0x04, 0x00, 0x0A,
                0x03, 0x42, 0x00, 0x04
        };

        @Override
        public byte[] toSpki(byte[] rawKey) {
            if (rawKey[0] != 0x02 && rawKey[0] != 0x03) {
                throw new IllegalArgumentException("Invalid SEC1 compression prefix: " + rawKey[0]);
            }
            boolean yOdd   = rawKey[0] == 0x03;
            byte[]  xBytes = new byte[32];
            System.arraycopy(rawKey, 1, xBytes, 0, 32);
            BigInteger x = new BigInteger(1, xBytes);

            BigInteger ySquared = x.modPow(BigInteger.valueOf(3), P).add(B).mod(P);
            BigInteger y = ySquared.modPow(P.add(BigInteger.ONE).shiftRight(2), P);

            if (y.testBit(0) != yOdd) {
                y = P.subtract(y);
            }

            byte[] xOut = toFixedLength(x, 32);
            byte[] yOut = toFixedLength(y, 32);
            byte[] spki = new byte[SPKI_HEADER.length + 64];
            System.arraycopy(SPKI_HEADER, 0, spki, 0, SPKI_HEADER.length);
            System.arraycopy(xOut, 0, spki, SPKI_HEADER.length, 32);
            System.arraycopy(yOut, 0, spki, SPKI_HEADER.length + 32, 32);
            return spki;
        }

        private static byte[] toFixedLength(BigInteger value, int length) {
            byte[] bytes = value.toByteArray();
            if (bytes.length == length) {return bytes;}
            byte[] result = new byte[length];
            if (bytes.length > length) {
                System.arraycopy(bytes, bytes.length - length, result, 0, length);
            } else {
                System.arraycopy(bytes, 0, result, length - bytes.length, bytes.length);
            }
            return result;
        }
    };

    public final int    code;
    public final String vmType;
    public final int    rawKeyLength;

    MulticodecKeyType(int code, String vmType, int rawKeyLength) {
        this.code         = code;
        this.vmType       = vmType;
        this.rawKeyLength = rawKeyLength;
    }

    public abstract byte[] toSpki(byte[] rawKey);

    public static Optional<MulticodecKeyType> fromCode(int code) {
        for (MulticodecKeyType type : values()) {
            if (type.code == code) {return Optional.of(type);}
        }
        return Optional.empty();
    }
}
