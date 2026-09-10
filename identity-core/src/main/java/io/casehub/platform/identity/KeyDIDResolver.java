package io.casehub.platform.identity;

import io.casehub.platform.api.identity.DIDDocument;
import io.casehub.platform.api.identity.DIDResolver;
import io.casehub.platform.api.identity.VerificationMethod;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class KeyDIDResolver implements DIDResolver {

    private static final Logger LOG            = Logger.getLogger(KeyDIDResolver.class);
    private static final String DID_KEY_PREFIX = "did:key:";

    @Override
    public Optional<DIDDocument> resolve(final String actorId, final String did) {
        if (did == null || !did.startsWith(DID_KEY_PREFIX)) {return Optional.empty();}
        try {
            final String keyPart    = did.substring(DID_KEY_PREFIX.length());
            final byte[] multicodec = Multibase.decode(keyPart);

            final int[] varint = decodeVarint(multicodec);
            if (varint == null) {return Optional.empty();}

            final Optional<MulticodecKeyType> keyType = MulticodecKeyType.fromCode(varint[0]);
            if (keyType.isEmpty()) {return Optional.empty();}

            final MulticodecKeyType type   = keyType.get();
            final byte[]            rawKey = Arrays.copyOfRange(multicodec, varint[1], multicodec.length);
            if (rawKey.length != type.rawKeyLength) {return Optional.empty();}

            final byte[] spki = type.toSpki(rawKey);
            final String vmId = did + "#" + keyPart;
            final var    vm   = new VerificationMethod(vmId, type.vmType, spki);
            final var    aka  = actorId != null ? List.of(actorId) : List.<String>of();
            return Optional.of(new DIDDocument(did, List.of(vm), aka));
        } catch (final IllegalArgumentException e) {
            LOG.debugf("KeyDIDResolver: unsupported multibase encoding in %s: %s", did, e.getMessage());
            return Optional.empty();
        } catch (final Exception e) {
            LOG.debugf("KeyDIDResolver: failed to decode %s: %s", did, e.getMessage());
            return Optional.empty();
        }
    }

    private static int[] decodeVarint(final byte[] data) {
        if (data == null || data.length == 0) {return null;}
        int value = 0;
        int shift = 0;
        for (int i = 0; i < Math.min(data.length, 4); i++) {
            int b = data[i] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new int[]{value, i + 1};
            }
            shift += 7;
        }
        return null;
    }
}
