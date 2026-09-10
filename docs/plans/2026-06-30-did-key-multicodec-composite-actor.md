# KeyDIDResolver Multicodec + Composite ActorDIDProvider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix KeyDIDResolver to properly decode multicodec prefixes and support P-256 keys (#130), and unify ActorDIDProvider with the composite pattern matching DIDResolver (#128).

**Architecture:** Three layers of change — SPI additions in platform-api (constants, qualifier, default method), shared utility extraction in identity module (CdiPriorityUtils), and implementation changes (KeyDIDResolver multicodec dispatch, CompositeActorDIDProvider, provider annotation changes). TDD throughout — tests drive every behavioral change.

**Tech Stack:** Java 21, Quarkus CDI (Arc), JCA (KeyFactory, ECPublicKeySpec), JUnit 5

## Global Constraints

- `platform-api/` must remain zero-dependency — no Quarkus, no JPA, no casehubio imports. Pure Java only.
- Every SPI in platform-api/ gets a @DefaultBean implementation in platform/.
- SPKI bytes must be canonical (uncompressed) — `Arrays.equals` is used for identity verification.
- secp256k1 is out of scope (JDK 15+ removed from SunEC). Tracked: #133.
- `VerificationMethod.type` stays `String` — extensible for unknown external types.

---

### Task 1: platform-api SPI changes

**Files:**
- Create: `platform-api/src/main/java/io/casehub/platform/api/identity/VerificationMethodType.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/identity/ActorDIDSource.java`
- Modify: `platform-api/src/main/java/io/casehub/platform/api/identity/ActorDIDProvider.java`
- Test: `platform-api/src/test/java/io/casehub/platform/api/identity/VerificationMethodTypeTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `VerificationMethodType.ED25519` → `"Ed25519VerificationKey2020"` (String constant)
  - `VerificationMethodType.P256` → `"EcdsaSecp256r1VerificationKey2019"` (String constant)
  - `@ActorDIDSource` — CDI `@Qualifier` annotation
  - `ActorDIDProvider.invalidate(String actorId)` — default no-op method

- [ ] **Step 1: Write VerificationMethodType test**

```java
package io.casehub.platform.api.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificationMethodTypeTest {

    @Test
    void ed25519_constant_matches_w3c_type() {
        assertEquals("Ed25519VerificationKey2020", VerificationMethodType.ED25519);
    }

    @Test
    void p256_constant_matches_w3c_type() {
        assertEquals("EcdsaSecp256r1VerificationKey2019", VerificationMethodType.P256);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl platform-api -Dtest=VerificationMethodTypeTest`
Expected: FAIL — `VerificationMethodType` does not exist.

- [ ] **Step 3: Create VerificationMethodType**

```java
package io.casehub.platform.api.identity;

public final class VerificationMethodType {

    public static final String ED25519 = "Ed25519VerificationKey2020";
    public static final String P256 = "EcdsaSecp256r1VerificationKey2019";

    private VerificationMethodType() {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode test -pl platform-api -Dtest=VerificationMethodTypeTest`
Expected: PASS

- [ ] **Step 5: Create @ActorDIDSource qualifier**

```java
package io.casehub.platform.api.identity;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.*;

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({TYPE, METHOD, FIELD, PARAMETER})
public @interface ActorDIDSource {}
```

- [ ] **Step 6: Add invalidate() default method to ActorDIDProvider**

Add to `ActorDIDProvider.java` after `didFor()`:

```java
/**
 * Invalidates any cached state for the given actor.
 * Called by the composite to propagate invalidation to all children.
 * No-op by default — override in caching implementations.
 *
 * @param actorId the actor whose cached state should be cleared
 */
default void invalidate(String actorId) {}
```

- [ ] **Step 7: Verify platform-api compiles and all tests pass**

Run: `mvn --batch-mode test -pl platform-api`
Expected: all tests PASS (including existing tests — default method is binary-compatible).

- [ ] **Step 8: Commit**

```
feat(platform#130,#128): VerificationMethodType constants, @ActorDIDSource qualifier, ActorDIDProvider.invalidate()
```

---

### Task 2: CdiPriorityUtils extraction

**Files:**
- Create: `identity/src/main/java/io/casehub/platform/identity/CdiPriorityUtils.java`
- Modify: `identity/src/main/java/io/casehub/platform/identity/CompositeDIDResolver.java`
- Test: `../../identity-core/src/test/java/io/casehub/platform/identity/CompositeDIDResolverTest.java` (existing — verify no regression)

**Interfaces:**
- Consumes: nothing
- Produces: `CdiPriorityUtils.toSortedList(Instance<T> instance)` → `List<T>` (package-private static method)

- [ ] **Step 1: Extract CdiPriorityUtils from CompositeDIDResolver**

Create `CdiPriorityUtils.java`:

```java
package io.casehub.platform.identity;

import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CdiPriorityUtils {

    private CdiPriorityUtils() {}

    static <T> List<T> toSortedList(Instance<T> instance) {
        var list = new ArrayList<Instance.Handle<T>>();
        instance.handles().forEach(list::add);
        list.sort(Comparator.comparingInt(CdiPriorityUtils::priorityOf));
        return list.stream()
                .map(Instance.Handle::get)
                .collect(java.util.stream.Collectors.toUnmodifiableList());
    }

    private static int priorityOf(Instance.Handle<?> handle) {
        var bean = handle.getBean();
        if (bean instanceof io.quarkus.arc.InjectableBean<?> injectable) {
            Integer p = injectable.getPriority();
            if (p != null) return p;
        }
        return Integer.MAX_VALUE;
    }
}
```

- [ ] **Step 2: Update CompositeDIDResolver to delegate to CdiPriorityUtils**

Replace the private `toSortedList` and `priorityOf` methods in `CompositeDIDResolver` with a call to `CdiPriorityUtils.toSortedList()`:

In the `@Inject` constructor, change:
```java
this(toSortedList(methodResolvers));
```
to:
```java
this(CdiPriorityUtils.toSortedList(methodResolvers));
```

Remove the private `toSortedList(Instance<DIDResolver>)` and `priorityOf(Instance.Handle<?>)` methods from `CompositeDIDResolver`.

- [ ] **Step 3: Run existing CompositeDIDResolver tests to verify no regression**

Run: `mvn --batch-mode test -pl identity -Dtest=CompositeDIDResolverTest`
Expected: all existing tests PASS.

- [ ] **Step 4: Commit**

```
refactor(platform#128): extract CdiPriorityUtils from CompositeDIDResolver
```

---

### Task 3: KeyDIDResolver multicodec dispatch (#130)

**Files:**
- Create: `identity/src/main/java/io/casehub/platform/identity/MulticodecKeyType.java`
- Modify: `identity/src/main/java/io/casehub/platform/identity/KeyDIDResolver.java`
- Modify: `../../identity-core/src/test/java/io/casehub/platform/identity/KeyDIDResolverTest.java`

**Interfaces:**
- Consumes: `VerificationMethodType.ED25519`, `VerificationMethodType.P256` (from Task 1)
- Produces: `KeyDIDResolver.resolve(String actorId, String did)` — now supports Ed25519 and P-256, populates alsoKnownAs

- [ ] **Step 1: Write P-256 roundtrip test (0x02 prefix)**

Add to `KeyDIDResolverTest.java`:

```java
@Test
void resolved_p256_publicKeyBytes_are_canonical_spki() throws Exception {
    var keyPair = KeyPairGenerator.getInstance("EC")
            .apply(kg -> { kg.initialize(new ECGenParameterSpec("secp256r1")); return kg; })
            ... // see step 3 for full test
}
```

Actually — write the full test. Generate a P-256 key pair, compress the public point to SEC1 format, construct a did:key with multicodec prefix `0x80 0x24` (P-256 = 0x1200 as varint), and verify the resolved SPKI matches the original key's `getEncoded()` byte-for-byte.

```java
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

    // P-256 multicodec varint: 0x1200 → [0x80, 0x24]
    byte[] multicodec = new byte[2 + compressed.length];
    multicodec[0] = (byte) 0x80;
    multicodec[1] = 0x24;
    System.arraycopy(compressed, 0, multicodec, 2, compressed.length);

    String didKey = "did:key:z" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(multicodec);

    DIDDocument doc = resolver.resolve("actor", didKey).orElseThrow();
    assertEquals(1, doc.verificationMethods().size());

    VerificationMethod vm = doc.verificationMethods().get(0);
    assertArrayEquals(expectedSpki, vm.publicKeyBytes(),
            "P-256 SPKI must be canonical (uncompressed, matching PublicKey.getEncoded())");
    assertEquals(VerificationMethodType.P256, vm.type());
}

private static byte[] toUnsignedByteArray(java.math.BigInteger value, int length) {
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
```

- [ ] **Step 2: Write P-256 test with 0x03 prefix (odd Y)**

```java
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

    String didKey = "did:key:z" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(multicodec);

    DIDDocument doc = resolver.resolve("actor", didKey).orElseThrow();
    assertArrayEquals(expectedSpki, doc.verificationMethods().get(0).publicKeyBytes(),
            "0x03 prefix must select the odd-Y root");
}
```

- [ ] **Step 3: Write alsoKnownAs tests**

```java
@Test
void resolved_document_includes_actorId_in_alsoKnownAs() {
    // Use the existing Ed25519 test setup
    var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    byte[] spki = keyPair.getPublic().getEncoded();
    byte[] raw = new byte[32];
    System.arraycopy(spki, spki.length - 32, raw, 0, 32);
    byte[] multicodec = new byte[2 + raw.length];
    multicodec[0] = (byte) 0xed;
    multicodec[1] = 0x01;
    System.arraycopy(raw, 0, multicodec, 2, raw.length);
    String didKey = "did:key:z" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(multicodec);

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
    String didKey = "did:key:z" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(multicodec);

    DIDDocument doc = resolver.resolve(null, didKey).orElseThrow();
    assertTrue(doc.alsoKnownAs().isEmpty());
}
```

- [ ] **Step 4: Write edge case tests**

```java
@Test
void returns_empty_for_unknown_multicodec_code() {
    // Use multicodec 0xFF (unknown)
    byte[] multicodec = new byte[]{(byte) 0xFF, 0x01, 0x00, 0x00};
    String didKey = "did:key:z" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(multicodec);
    assertTrue(resolver.resolve("actor", didKey).isEmpty());
}

@Test
void returns_empty_for_wrong_key_length() {
    // Ed25519 expects 32 bytes, give it 16
    byte[] multicodec = new byte[2 + 16];
    multicodec[0] = (byte) 0xed;
    multicodec[1] = 0x01;
    String didKey = "did:key:z" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(multicodec);
    assertTrue(resolver.resolve("actor", didKey).isEmpty());
}

@Test
void returns_empty_for_malformed_varint() {
    // Single byte with continuation bit set — truncated varint
    byte[] data = new byte[]{(byte) 0x80};
    String didKey = "did:key:z" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(data);
    assertTrue(resolver.resolve("actor", didKey).isEmpty());
}
```

- [ ] **Step 5: Run all new tests to verify they fail**

Run: `mvn --batch-mode test -pl identity -Dtest=KeyDIDResolverTest`
Expected: new tests FAIL, existing tests may still PASS.

- [ ] **Step 6: Create MulticodecKeyType enum**

Create `identity/src/main/java/io/casehub/platform/identity/MulticodecKeyType.java`:

```java
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

enum MulticodecKeyType {

    ED25519(0xed, VerificationMethodType.ED25519, 32) {
        private static final byte[] SPKI_PREFIX = {
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        };

        @Override
        byte[] toSpki(byte[] rawKey) {
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
        byte[] toSpki(byte[] rawKey) {
            if (rawKey[0] != 0x02 && rawKey[0] != 0x03) {
                throw new IllegalArgumentException("Invalid SEC1 compression prefix: " + rawKey[0]);
            }
            boolean yOdd = rawKey[0] == 0x03;
            byte[] xBytes = new byte[32];
            System.arraycopy(rawKey, 1, xBytes, 0, 32);
            BigInteger x = new BigInteger(1, xBytes);

            BigInteger p = ((java.security.spec.ECFieldFp) EC_PARAMS.getCurve().getField()).getP();
            BigInteger a = EC_PARAMS.getCurve().getA();
            BigInteger b = EC_PARAMS.getCurve().getB();

            // Y² = X³ + aX + b (mod p)
            BigInteger ySquared = x.modPow(BigInteger.valueOf(3), p)
                    .add(a.multiply(x).mod(p))
                    .add(b)
                    .mod(p);

            // P-256 has p ≡ 3 (mod 4), so Y = (Y²)^((p+1)/4) mod p
            BigInteger y = ySquared.modPow(p.add(BigInteger.ONE).shiftRight(2), p);

            // Sign selection: match the SEC1 prefix parity
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
    };

    final int code;
    final String vmType;
    final int rawKeyLength;

    MulticodecKeyType(int code, String vmType, int rawKeyLength) {
        this.code = code;
        this.vmType = vmType;
        this.rawKeyLength = rawKeyLength;
    }

    abstract byte[] toSpki(byte[] rawKey);

    static Optional<MulticodecKeyType> fromCode(int code) {
        for (MulticodecKeyType type : values()) {
            if (type.code == code) return Optional.of(type);
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 7: Rewrite KeyDIDResolver.resolve()**

Replace the entire `resolve()` method and remove `wrapEd25519Spki()` and `ED25519_SPKI_PREFIX`:

```java
@Override
public Optional<DIDDocument> resolve(final String actorId, final String did) {
    if (did == null || !did.startsWith(DID_KEY_PREFIX)) return Optional.empty();
    try {
        final String keyPart = did.substring(DID_KEY_PREFIX.length());
        if (!keyPart.startsWith("z")) return Optional.empty();
        final byte[] multicodec = Base64.getUrlDecoder().decode(keyPart.substring(1));

        final int[] varint = decodeVarint(multicodec);
        if (varint == null) return Optional.empty();

        final Optional<MulticodecKeyType> keyType = MulticodecKeyType.fromCode(varint[0]);
        if (keyType.isEmpty()) return Optional.empty();

        final MulticodecKeyType type = keyType.get();
        final byte[] rawKey = Arrays.copyOfRange(multicodec, varint[1], multicodec.length);
        if (rawKey.length != type.rawKeyLength) return Optional.empty();

        final byte[] spki = type.toSpki(rawKey);
        final String vmId = did + "#" + keyPart;
        final var vm = new VerificationMethod(vmId, type.vmType, spki);
        final var aka = actorId != null ? List.of(actorId) : List.<String>of();
        return Optional.of(new DIDDocument(did, List.of(vm), aka));
    } catch (final Exception e) {
        LOG.debugf("KeyDIDResolver: failed to decode %s: %s", did, e.getMessage());
        return Optional.empty();
    }
}

private static int[] decodeVarint(final byte[] data) {
    if (data == null || data.length == 0) return null;
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
```

Remove the `ED25519_SPKI_PREFIX` field and `wrapEd25519Spki()` method.

Add `import java.util.Arrays;` if not already present.

- [ ] **Step 8: Update existing Ed25519 test to also check VM type**

In the existing `resolved_publicKeyBytes_are_spki_format` test, add after the key assertions:

```java
assertEquals(VerificationMethodType.ED25519, vm.type());
```

- [ ] **Step 9: Run all KeyDIDResolver tests**

Run: `mvn --batch-mode test -pl identity -Dtest=KeyDIDResolverTest`
Expected: all tests PASS.

- [ ] **Step 10: Commit**

```
feat(platform#130): KeyDIDResolver multicodec dispatch — varint decoding, P-256 support, alsoKnownAs
```

---

### Task 4: CompositeActorDIDProvider (#128)

**Files:**
- Create: `identity/src/main/java/io/casehub/platform/identity/CompositeActorDIDProvider.java`
- Create: `../../identity-core/src/test/java/io/casehub/platform/identity/CompositeActorDIDProviderTest.java`

**Interfaces:**
- Consumes: `ActorDIDProvider` (SPI with `invalidate()` from Task 1), `CdiPriorityUtils.toSortedList()` (from Task 2), `@ActorDIDSource` (from Task 1)
- Produces: `CompositeActorDIDProvider` — `@ApplicationScoped`, iterates `@ActorDIDSource` providers by `@Priority`

- [ ] **Step 1: Write CompositeActorDIDProvider tests**

Create `../../identity-core/src/test/java/io/casehub/platform/identity/CompositeActorDIDProviderTest.java`:

```java
package io.casehub.platform.identity;

import io.casehub.platform.api.identity.ActorDIDProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CompositeActorDIDProviderTest {

    private static final String ACTOR = "claude:reviewer@v1";

    @Test
    void empty_providers_returns_empty() {
        var composite = new CompositeActorDIDProvider(List.of());
        assertTrue(composite.didFor(ACTOR).isEmpty());
    }

    @Test
    void delegates_to_single_provider() {
        ActorDIDProvider p = actorId -> Optional.of("did:web:example.com");
        var composite = new CompositeActorDIDProvider(List.of(p));
        assertEquals("did:web:example.com", composite.didFor(ACTOR).orElseThrow());
    }

    @Test
    void returns_first_non_empty_result() {
        ActorDIDProvider empty = actorId -> Optional.empty();
        ActorDIDProvider found = actorId -> Optional.of("did:web:found");
        ActorDIDProvider never = actorId -> { fail("Should not be called"); return Optional.empty(); };

        var composite = new CompositeActorDIDProvider(List.of(empty, found, never));
        assertEquals("did:web:found", composite.didFor(ACTOR).orElseThrow());
    }

    @Test
    void catches_exception_and_continues() {
        ActorDIDProvider throwing = actorId -> { throw new RuntimeException("SCIM down"); };
        ActorDIDProvider fallback = actorId -> Optional.of("did:web:fallback");

        var composite = new CompositeActorDIDProvider(List.of(throwing, fallback));
        assertEquals("did:web:fallback", composite.didFor(ACTOR).orElseThrow());
    }

    @Test
    void all_throw_returns_empty() {
        ActorDIDProvider t1 = actorId -> { throw new RuntimeException("fail 1"); };
        ActorDIDProvider t2 = actorId -> { throw new RuntimeException("fail 2"); };

        var composite = new CompositeActorDIDProvider(List.of(t1, t2));
        assertTrue(composite.didFor(ACTOR).isEmpty());
    }

    @Test
    void invalidate_propagates_to_all_children() {
        AtomicBoolean p1Called = new AtomicBoolean(false);
        AtomicBoolean p2Called = new AtomicBoolean(false);

        ActorDIDProvider p1 = new ActorDIDProvider() {
            @Override public Optional<String> didFor(String actorId) { return Optional.empty(); }
            @Override public void invalidate(String actorId) { p1Called.set(true); }
        };
        ActorDIDProvider p2 = new ActorDIDProvider() {
            @Override public Optional<String> didFor(String actorId) { return Optional.empty(); }
            @Override public void invalidate(String actorId) { p2Called.set(true); }
        };

        var composite = new CompositeActorDIDProvider(List.of(p1, p2));
        composite.invalidate(ACTOR);

        assertTrue(p1Called.get());
        assertTrue(p2Called.get());
    }

    @Test
    void invalidate_catches_exception_and_continues() {
        AtomicBoolean p2Called = new AtomicBoolean(false);

        ActorDIDProvider throwing = new ActorDIDProvider() {
            @Override public Optional<String> didFor(String actorId) { return Optional.empty(); }
            @Override public void invalidate(String actorId) { throw new RuntimeException("boom"); }
        };
        ActorDIDProvider p2 = new ActorDIDProvider() {
            @Override public Optional<String> didFor(String actorId) { return Optional.empty(); }
            @Override public void invalidate(String actorId) { p2Called.set(true); }
        };

        var composite = new CompositeActorDIDProvider(List.of(throwing, p2));
        composite.invalidate(ACTOR);

        assertTrue(p2Called.get(), "Second provider's invalidate must be called despite first throwing");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl identity -Dtest=CompositeActorDIDProviderTest`
Expected: FAIL — `CompositeActorDIDProvider` does not exist.

- [ ] **Step 3: Implement CompositeActorDIDProvider**

Create `identity/src/main/java/io/casehub/platform/identity/CompositeActorDIDProvider.java`:

```java
package io.casehub.platform.identity;

import io.casehub.platform.api.identity.ActorDIDProvider;
import io.casehub.platform.api.identity.ActorDIDSource;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CompositeActorDIDProvider implements ActorDIDProvider {

    private static final Logger LOG = Logger.getLogger(CompositeActorDIDProvider.class);

    private final List<ActorDIDProvider> providers;

    @Inject
    public CompositeActorDIDProvider(@ActorDIDSource Instance<ActorDIDProvider> sources) {
        this(CdiPriorityUtils.toSortedList(sources));
    }

    CompositeActorDIDProvider(List<ActorDIDProvider> providers) {
        this.providers = providers;
    }

    @Override
    public Optional<String> didFor(final String actorId) {
        for (final ActorDIDProvider p : providers) {
            try {
                final Optional<String> result = p.didFor(actorId);
                if (result.isPresent()) return result;
            } catch (final Exception e) {
                LOG.warnf("Provider %s failed for actorId %s: %s",
                        p.getClass().getSimpleName(), actorId, e.getMessage());
            }
        }
        return Optional.empty();
    }

    @Override
    public void invalidate(final String actorId) {
        for (final ActorDIDProvider p : providers) {
            try {
                p.invalidate(actorId);
            } catch (final Exception e) {
                LOG.warnf("Provider %s invalidate failed for actorId %s: %s",
                        p.getClass().getSimpleName(), actorId, e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl identity -Dtest=CompositeActorDIDProviderTest`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```
feat(platform#128): CompositeActorDIDProvider — iterates @ActorDIDSource providers by @Priority
```

---

### Task 5: Provider annotations, activation fix, NoOp move

**Files:**
- Modify: `identity/src/main/java/io/casehub/platform/identity/ConfiguredActorDIDProvider.java`
- Modify: `identity/src/main/java/io/casehub/platform/identity/ScimActorDIDProvider.java`
- Move: `identity/src/main/java/io/casehub/platform/identity/NoOpActorDIDProvider.java` → `platform/src/main/java/io/casehub/platform/identity/NoOpActorDIDProvider.java`
- Create: `../../identity-core/src/test/java/io/casehub/platform/identity/ScimActorDIDProviderUnconfiguredTest.java`

**Interfaces:**
- Consumes: `@ActorDIDSource` (Task 1), `CompositeActorDIDProvider` (Task 4)
- Produces: annotated providers discovered by composite

- [ ] **Step 1: Write ScimActorDIDProvider unconfigured test**

```java
package io.casehub.platform.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScimActorDIDProviderUnconfiguredTest {

    @Test
    void didFor_returns_empty_when_scim_unconfigured() {
        ScimAgentLookup unconfigured = ScimAgentLookup.unconfigured();
        ScimActorDIDProvider provider = new ScimActorDIDProvider(unconfigured);
        assertTrue(provider.didFor("claude:reviewer@v1").isEmpty());
    }

    @Test
    void invalidate_does_not_throw_when_scim_unconfigured() {
        ScimAgentLookup unconfigured = ScimAgentLookup.unconfigured();
        ScimActorDIDProvider provider = new ScimActorDIDProvider(unconfigured);
        assertDoesNotThrow(() -> provider.invalidate("claude:reviewer@v1"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl identity -Dtest=ScimActorDIDProviderUnconfiguredTest`
Expected: FAIL — `invalidate()` not yet overridden on `ScimActorDIDProvider`.

- [ ] **Step 3: Update ConfiguredActorDIDProvider annotations**

Add `@ActorDIDSource` and `@Priority(100)`:

```java
import io.casehub.platform.api.identity.ActorDIDSource;
import jakarta.annotation.Priority;

@ApplicationScoped
@ActorDIDSource
@Priority(100)
public class ConfiguredActorDIDProvider implements ActorDIDProvider {
```

- [ ] **Step 4: Update ScimActorDIDProvider**

Remove `@Alternative`. Add `@ActorDIDSource @Priority(200)`. Remove `@PostConstruct validateEndpoint()`. Add `invalidate()` override:

```java
import io.casehub.platform.api.identity.ActorDIDSource;
import jakarta.annotation.Priority;

@ApplicationScoped
@ActorDIDSource
@Priority(200)
public class ScimActorDIDProvider implements ActorDIDProvider {

    // Remove @PostConstruct validateEndpoint() entirely

    @Override
    public Optional<String> didFor(final String actorId) {
        if (lookup == null || !lookup.isConfigured()) return Optional.empty();
        return lookup.get(actorId).map(ScimAgentResource::did);
    }

    @Override
    public void invalidate(final String actorId) {
        if (lookup != null) {
            lookup.invalidate(actorId);
        }
    }

    // Remove the public invalidate(String) method that was ScimActorDIDProvider-specific
    // — the SPI method now serves this purpose
}
```

Note: add a guard for `lookup.isConfigured()` in `didFor()` so unconfigured SCIM returns empty without calling `lookup.get()` (which would cache the empty result with a full TTL).

- [ ] **Step 5: Move NoOpActorDIDProvider from identity/ to platform/**

Use IntelliJ MCP `ide_move_file` to move the file — this updates imports across the project:

Move `identity/src/main/java/io/casehub/platform/identity/NoOpActorDIDProvider.java` → `platform/src/main/java/io/casehub/platform/identity/`

The package stays `io.casehub.platform.identity` — same package, different module. No import changes needed.

- [ ] **Step 6: Run tests**

Run: `mvn --batch-mode test -pl identity -Dtest=ScimActorDIDProviderUnconfiguredTest`
Expected: PASS.

Run: `mvn --batch-mode test -pl identity,platform`
Expected: all tests PASS.

- [ ] **Step 7: Commit**

```
feat(platform#128): provider annotations — @ActorDIDSource on Config/SCIM, NoOp moved to platform/
```

---

### Task 6: ScimDIDResolver constants + downstream issue + final verification

**Files:**
- Modify: `identity/src/main/java/io/casehub/platform/identity/ScimDIDResolver.java`

**Interfaces:**
- Consumes: `VerificationMethodType.ED25519`, `VerificationMethodType.P256` (Task 1)
- Produces: no new interfaces — behavioral no-op

- [ ] **Step 1: Update ScimDIDResolver type strings**

In `ScimDIDResolver.extractVerificationMethod()`, replace hardcoded strings:

```java
final String vmType = switch (cert.getPublicKey().getAlgorithm()) {
    case "Ed25519", "EdDSA" -> VerificationMethodType.ED25519;
    case "EC" -> VerificationMethodType.P256;
    default -> null;
};
```

Add import: `import io.casehub.platform.api.identity.VerificationMethodType;`

- [ ] **Step 2: Run ScimDIDResolver tests to verify no regression**

Run: `mvn --batch-mode test -pl identity -Dtest=ScimDIDResolverTest`
Expected: all tests PASS (same string values, just from constants now).

- [ ] **Step 3: File downstream issue for casehubio/ledger**

```bash
gh issue create --repo casehubio/ledger \
  --title "chore: IdentityCacheInvalidator — use ActorDIDProvider.invalidate() instead of instanceof" \
  --body "## Context

platform#128 added \`default void invalidate(String actorId)\` to the \`ActorDIDProvider\` SPI and introduced \`CompositeActorDIDProvider\`. The composite propagates \`invalidate()\` to all children.

\`IdentityCacheInvalidator\` currently uses \`instanceof ScimActorDIDProvider\` to conditionally call \`invalidate()\`. With the composite, this \`instanceof\` check always fails — the injected bean is the composite, not the SCIM provider.

## What needs doing

Replace:
\`\`\`java
if (actorDIDProvider instanceof ScimActorDIDProvider scim) {
    scim.invalidate(event.actorId());
}
\`\`\`

With:
\`\`\`java
actorDIDProvider.invalidate(event.actorId());
\`\`\`

This supersedes the SCIM DID resolver spec's §6 approach (\`Instance<ScimAgentLookup>\` direct injection).

Scale: XS | Complexity: Low
Depends on: casehubio/platform#128"
```

- [ ] **Step 4: Run full test suite**

Run: `mvn --batch-mode test`
Expected: all tests PASS across all modules.

- [ ] **Step 5: Commit**

```
feat(platform#130,#128): ScimDIDResolver uses VerificationMethodType constants
```
