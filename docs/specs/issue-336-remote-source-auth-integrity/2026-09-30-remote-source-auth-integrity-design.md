# Remote Source Authentication and Integrity Verification — Design Spec

**Issue:** casehubio/platform#336
**Date:** 2026-09-30
**Status:** Draft
**Depends on:** casehubio/platform#335 (agent-config manifest)

## Summary

Hardens ManifestLoader's remote source fetching with authentication headers, content integrity verification (SHA-256 digest + optional cryptographic signature), Content-Type validation, response size limits, HTTPS enforcement, and explicit redirect handling. All changes are in `agent-config-core` (framework-neutral, pure Java + Jackson).

## Problem

ManifestLoader fetches remote manifest sources via bare HTTP GET — no authentication, no integrity verification, no HTTPS enforcement. A compromised or misconfigured remote source can inject arbitrary model definitions or credential references into the merged manifest. The #335 design explicitly deferred these as follow-on concerns.

Current gaps:
- No `Authorization` header on remote requests — can't access protected corporate model catalogs
- No content hash verification — tampered catalogs are silently accepted
- No Content-Type validation — HTML login redirect pages parsed as YAML
- No response size bound — unbounded body reads risk OOM
- No HTTPS enforcement — `http://` URIs accepted without warning
- No redirect handling — 3xx responses bypass the `>= 400` check and their bodies are parsed as YAML

## Scope

**In scope:** Authentication headers (bearer, API key, basic), content integrity (SHA-256 digest + optional DID-based signature), Content-Type validation, response size limits, HTTPS enforcement with address-scoped auto-allow, explicit redirect rejection.

**Out of scope:** mTLS (deferred to #498 — orthogonal concern requiring per-source HttpClient lifecycle, poorly fits CredentialRef pattern). URI allowlisting. FileRef path restrictions. Response caching.

## Data Model

### SourceDeclaration

Grows from `(uri, priority)` to `(uri, priority, auth, integrity)`:

```java
public record SourceDeclaration(
    String uri,
    int priority,
    SourceAuth auth,           // nullable — no auth when absent
    SourceIntegrity integrity  // nullable — no integrity checks when absent
) {}
```

Auth and integrity are orthogonal concerns — a source can have auth without integrity, integrity without auth, or both. The common case (no security) has zero nesting.

### SourceAuth — Typed Authentication

```java
public record SourceAuth(
    String type,        // "bearer" | "header" | "basic"
    String credential,  // CredentialRef string: "env:TOKEN", "ref:vault/key"
    @JsonProperty("header-name")
    String headerName   // required when type="header", ignored otherwise
) {}
```

| Type | HTTP Header |
|------|------------|
| `bearer` | `Authorization: Bearer <resolved-value>` |
| `header` | `<header-name>: <resolved-value>` |
| `basic` | `Authorization: Basic base64(<resolved-value>)` |

The resolved credential value for `basic` auth is the raw `user-id:password` string. ManifestLoader base64-encodes it per RFC 7617 before setting the header. This matches the `bearer` pattern where the operator provides the raw value and ManifestLoader handles the wire format.

The `credential` field is a `String` parsed via `CredentialRef.parse()` at resolution time (D11). Unlike `ProviderDeclaration`'s `Object` credential (which supports both scalar and map forms for multi-field vendor credentials), source authentication always uses a single resolved credential value.

### SourceIntegrity — Digest + Optional Signature

```java
public record SourceIntegrity(
    String digest,    // "sha256:<hex>" — algorithm-prefixed
    String signature, // base64url-encoded raw signature bytes (RFC 4648 §5), optional
    String signer     // DID URI for signature verification, required iff signature present
) {}
```

- **Digest format:** Algorithm-prefixed (`sha256:<hex>`), following Docker/OCI/npm convention. Enables non-breaking algorithm evolution (`sha384:`, `sha512:` in future).
- **Digest and signature scope:** Both cover the raw HTTP response body bytes (after decompression, before parsing). The publisher runs `sha256sum catalog.yaml`; the consumer hashes the response body.
- **Signature covers content bytes, not the digest string** — keeping digest and signature independent.
- **Signature byte format:** Signature bytes must be in the format expected by the JDK's `java.security.Signature` class: 64 raw bytes for Ed25519, DER-encoded ASN.1 for ECDSA (P-256/P-384/P-521). This is the format produced by `Signature.sign()` and consumed by `SignatureVerifier.verify()`. Publishers using JWS/VC tooling that produces raw r||s concatenation for ECDSA must convert to DER before base64url-encoding.
- **Bidirectional validation:** `signer` without `signature` is an error. `signature` without `signer` is an error.

### YAML Examples

```yaml
sources:
  # Auth only — bearer token for corporate catalog
  - uri: https://corp.example/approved-models.yaml
    priority: 40
    auth:
      type: bearer
      credential: ref:vault/catalog-token

  # Auth + integrity — API key with pinned content
  - uri: https://partner.example/models.yaml
    priority: 45
    auth:
      type: header
      credential: env:PARTNER_API_KEY
      header-name: X-Api-Key
    integrity:
      digest: sha256:a1b2c3d4e5f6...
      signature: dGhpcyBpcyBhIHNpZ25hdHVyZQ==
      signer: did:web:partner.example

  # Integrity only — public catalog, digest-pinned
  - uri: https://registry.example/models.yaml
    priority: 50
    integrity:
      digest: sha256:f6e5d4c3b2a1...

  # No security — seed catalog or trusted internal
  - uri: https://internal.corp/models.yaml
    priority: 35
```

## ManifestLoader Changes

### Constructor & Dependencies

ManifestLoader changes from zero-arg to constructor injection (D9):

```java
public ManifestLoader(
    ManifestCredentialResolver credentialResolver,
    DIDResolver didResolver,           // nullable — for signature verification
    ManifestSecurityConfig securityConfig
) {}

public record ManifestSecurityConfig(
    boolean allowInsecure  // casehub.agent.manifest.allow-insecure, default false
) {}
```

`AgentConfigLoader` passes these through from its own constructor parameters. `ManifestLoader` remains a plain Java class (no CDI) with constructor injection.

When `didResolver` is null, any source with a declared signature is rejected (fail-closed per D5) — the source declared a security intent that the deployment cannot fulfil. This means adding signatures to a published catalog is a compatibility-affecting change for consumers without DID resolution infrastructure.

### Fetch Pipeline

`fetchRemote(String uri)` becomes `fetchRemote(SourceDeclaration source)` with this ordered pipeline:

```
source
  │
  ├─ 1. HTTPS enforcement (D4)
  │     Auto-allow HTTP for loopback/private addresses.
  │     Reject public HTTP unless allowInsecure=true.
  │
  ├─ 2. Load-time validation
  │     Cross-field constraint checks on auth and integrity blocks.
  │     Invalid sources skipped with ERROR log.
  │
  ├─ 3. Build request
  │     Resolve auth credential via ManifestCredentialResolver.
  │     Attach appropriate header based on auth type.
  │
  ├─ 4. Send request
  │     HttpClient.Redirect.NEVER (explicit).
  │     FETCH_TIMEOUT (10s) on request.
  │
  ├─ 5. Redirect detection (D10)
  │     3xx → WARN log with Location header, skip source.
  │
  ├─ 6. HTTP error check
  │     >= 400 → WARN log with status code, skip source.
  │
  ├─ 7. Content-Type validation (D7)
  │     Parse media type (ignore parameters like charset).
  │     Allow: application/yaml, application/x-yaml, text/yaml, application/json.
  │     Missing header → allowed. Wrong type → WARN, skip source.
  │
  ├─ 8. Size-limited read (D8)
  │     Bounded InputStream, 1 MB limit.
  │     Terminate connection on exceed (not read+discard).
  │     Read full body into byte[].
  │
  ├─ 9. Digest verification (D3, D5)
  │     If digest declared: compute SHA-256 of body bytes, compare.
  │     Mismatch → ERROR with expected/actual, skip source.
  │
  ├─ 10. Signature verification (D3, D5)
  │      If signature declared:
  │      Null didResolver → ERROR "no DID resolver configured", skip.
  │      Resolve signer DID via didResolver.
  │      If resolve returns empty → ERROR "DID unresolvable: <signer-did>", skip.
  │      If resolved DID document has empty verificationMethods →
  │        ERROR "no verification methods in DID document for signer: <signer-did>", skip.
  │      Iterate verificationMethods in DID document order, calling
  │      SignatureVerifier.verify(bodyBytes, signatureBytes, vm.publicKeyBytes())
  │      for each. First VALID outcome accepts the source.
  │      If no method yields VALID: ERROR with the last non-VALID outcome
  │      mapped to a specific failure reason, skip source.
  │
  │      SignatureVerifier (platform-api) is algorithm-transparent — it
  │      detects Ed25519, EC (P-256/P-384/P-521), and ML-DSA from the
  │      SPKI-encoded public key bytes. No algorithm selection or type
  │      routing is needed at this layer.
  │
  └─ 11. Parse
        Deserialize byte[] as YAML Manifest.
```

### HTTPS Enforcement — Address Classification

Private method `isPrivateAddress(URI uri)` resolves the hostname and checks against:
- **Loopback:** 127.0.0.0/8, ::1, `localhost`
- **RFC 1918 private (IPv4):** 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
- **IPv6 unique-local (RFC 4193):** fc00::/7
- **IPv6 link-local:** fe80::/10

Auto-allowed for HTTP without any config flag. Public HTTP requires `casehub.agent.manifest.allow-insecure=true` — logged at WARN level naming the specific URI.

**DNS rebinding note:** Hostname resolution for address classification is independent of the HttpClient's connection resolution. An attacker controlling the DNS server could return different addresses between the two resolutions. This is mitigated by the assumption that source URIs are operator-configured, not user-supplied — the attacker would need to compromise DNS for a domain the operator explicitly trusts.

### HttpClient Configuration

Single shared `HttpClient` instance (unchanged from current). `HttpClient.Builder.followRedirects(HttpClient.Redirect.NEVER)` set explicitly. mTLS deferred (#498) — no per-source SSLContext needed.

### DID Resolution Semantics

DID resolution (step 10) is a network call embedded within the fetch pipeline. `DIDResolver.resolve()` returns `Optional.empty()` for any failure — the contract explicitly prohibits throwing. `WebDIDResolver` has its own HTTP client, timeout (`WebDIDResolverProperties.timeoutMs()`), size limit (`maxResponseBytes`), and host blocking (SSRF protection). These are configured independently of the fetch pipeline's `FETCH_TIMEOUT`. If `didResolver.resolve()` returns empty, the source is skipped with ERROR "DID unresolvable: <signer-did>". The distinction between "transient network failure" and "DID doesn't exist" is not surfaced — both result in the same skip-with-ERROR behavior, because a source that declares a signer intends verification to succeed.

### Redirect Handling

Explicit 3xx detection after send. Log WARN with the URI and `Location` header value, directing the operator to update the source URI to the final target. Source is skipped.

## Validation Rules

### Load-time Cross-field Constraints

| Constraint | Error |
|-----------|-------|
| `auth.type` not in `{bearer, header, basic}` | "Unknown auth type '<type>' for source <uri>" |
| `auth.type == "header"` without `auth.headerName` | "header-name required when auth type is 'header' for source <uri>" |
| `auth.headerName` contains non-token characters (RFC 7230 §3.2) | "Invalid header name '<name>' for source <uri> (must be a valid HTTP token)" |
| `auth.credential` not CredentialRef-parseable | "Invalid credential reference '<value>' for source <uri>" |
| `integrity.signature` without `integrity.signer` | "signer required when signature is declared for source <uri>" |
| `integrity.signer` without `integrity.signature` | "signature required when signer is declared for source <uri>" |
| `integrity.digest` with unknown algorithm prefix | "Unknown digest algorithm '<prefix>' for source <uri> (supported: sha256)" |
| `integrity.digest` hex value has wrong length or non-hex characters | "Malformed digest value for source <uri>: expected 64 hex characters after sha256: prefix" |
| `integrity.signature` is not valid base64url (RFC 4648 §5) | "Invalid signature encoding for source <uri>: must be base64url-encoded" |

Invalid sources are skipped with an ERROR log. Other sources continue — one bad source doesn't crash the chain.

## Error Differentiation

| Failure | Level | Diagnostic Detail |
|---------|-------|--------------------|
| Source unreachable / timeout | WARN | URI, exception message |
| HTTP 3xx redirect | WARN | URI, Location header value |
| HTTP 4xx/5xx | WARN | URI, status code |
| Wrong Content-Type | WARN | URI, received type, expected types |
| Size limit exceeded | WARN | URI, limit |
| Digest mismatch | ERROR | URI, expected digest, actual digest |
| DID unresolvable | ERROR | URI, signer DID, "DID unresolvable: <signer-did>" |
| DID document has no verification methods | ERROR | URI, signer DID, "no verification methods in DID document for signer: <signer-did>" |
| Signature verification failed | ERROR | URI, signer DID, specific failure reason (from `VerificationOutcome`: `UNSUPPORTED_ALGORITHM`, `MALFORMED_KEY`, `SIGNATURE_MISMATCH`) |
| Null DIDResolver with signature | ERROR | URI, signer DID, "no DID resolver configured" |
| Credential resolution failed | ERROR | URI, credential ref, exception |
| Validation constraint violation | ERROR | URI, specific constraint |

WARN = transient infrastructure failure, likely resolves on retry. ERROR = security-relevant event or configuration error, requires operator action.

**Credential logging invariant:** Resolved credential values MUST NOT appear in any log output at any level (DEBUG, TRACE, etc.). Log the credential reference string (e.g., `env:TOKEN`, `ref:vault/key`) for diagnostics, never the resolved value. This applies to the entire fetch pipeline — auth header construction, error paths, and any debug logging.

## AgentConfigLoader Wiring

```java
public ManifestResult load() {
    var resolver = new ManifestCredentialResolver(credentialResolver);
    var securityConfig = new ManifestSecurityConfig(allowInsecure);
    var loader = new ManifestLoader(resolver, didResolver, securityConfig);
    var manifest = loader.load(projectDir, profile);
    var processor = new ManifestProcessor(
        credentialStore, modelRegistry, resolver, vendorRequirements, reconciler);
    return processor.process(manifest);
}
```

`AgentConfigLoader` accepts `DIDResolver` (nullable) and `boolean allowInsecure` in its constructor. `AgentConfigBeans` (Quarkus wiring) injects `DIDResolver` via `Instance<DIDResolver>` for optional injection and reads `casehub.agent.manifest.allow-insecure` from SmallRye Config.

## Module Impact

All changes are in **agent-config-core** — the framework-neutral module. No new module needed.

| File | Change |
|------|--------|
| `SourceDeclaration.java` | Add `auth` and `integrity` fields |
| `SourceAuth.java` | **New** — typed auth record |
| `SourceIntegrity.java` | **New** — integrity record |
| `ManifestSecurityConfig.java` | **New** — HTTPS enforcement config |
| `ManifestLoader.java` | Constructor injection, fetch pipeline, HTTPS enforcement, redirect handling, content-type validation, size limit, digest/signature verification. `loadResource(URI)` restricted to non-HTTP schemes — throws `IllegalArgumentException` for `http`/`https` URIs (all remote fetching must go through the `fetchRemote` pipeline) |
| `AgentConfigLoader.java` | Pass dependencies through to ManifestLoader |
| `ManifestLoader` tests | Auth, integrity, HTTPS, redirect, content-type, size limit tests |

No changes to: `ManifestProcessor`, `Manifest`, `ManifestCredentialResolver`, `CredentialRef`.

**AgentConfigBeans changes** (in `agent-config/` Quarkus module):
- Inject `Instance<DIDResolver>` (optional)
- Read `casehub.agent.manifest.allow-insecure` config property
- Pass both to `AgentConfigLoader` constructor

## Testing Strategy

| Test Category | Approach |
|--------------|----------|
| Auth header construction | Unit test: mock ManifestCredentialResolver, verify request headers per auth type |
| Digest verification | Unit test: known SHA-256 values, match and mismatch cases |
| Signature verification | Unit test: test DIDResolver returning known DID document with Ed25519 test key pair, sign test content via `SignatureVerifier`, verify acceptance and rejection. Verify iteration over multiple verification methods (first-match semantics). Verify `VerificationOutcome` error mapping |
| Null DIDResolver + signature | Unit test: verify fail-closed behavior |
| HTTPS enforcement | Unit test: loopback/private auto-allow, public HTTP rejection, allowInsecure override |
| Redirect handling | Unit test: mock 3xx response, verify skip + warning log |
| Content-Type validation | Unit test: allow-list acceptance, media type parameter stripping, missing header allowed, wrong type rejected |
| Size limit | Unit test: response exceeding 1 MB rejected |
| Integration | ManifestLoader with real YAML sources, auth + integrity together |
| Backward compatibility | Existing SourceDeclaration YAML without auth/integrity fields loads correctly (all fields nullable) |

## Backward Compatibility

Fully backward compatible. All new fields on `SourceDeclaration` are nullable — existing YAML manifests without `auth:` or `integrity:` blocks continue to work unchanged. The fetch pipeline skips security checks when the corresponding fields are absent.

`ManifestLoader`'s constructor change is a source-breaking change for direct callers, but the only direct caller is `AgentConfigLoader` (same module). `AgentConfigBeans` (the only CDI consumer of `AgentConfigLoader`) is updated to pass the new dependencies.

## Future Extensions

- **mTLS (#498):** Add optional `tls:` block to `SourceDeclaration` with per-source keystore/truststore refs. Requires per-source `HttpClient` instances (JDK binds SSLContext at construction).
- **Algorithm evolution:** `sha384:`, `sha512:` digest algorithms — add to the algorithm prefix parser.
- **OAuth2 client-credentials:** New `auth.type` value — structural change-free.
- **Response caching:** Cache last-known-good manifest content for degraded-mode startup.

## References

- `agent-config-core/src/main/java/io/casehub/platform/agent/config/ManifestLoader.java` — current fetch implementation
- `agent-config-core/src/main/java/io/casehub/platform/agent/config/SourceDeclaration.java` — current (uri, priority)
- `agent-config-core/src/main/java/io/casehub/platform/agent/config/CredentialRef.java` — credential reference pattern
- `agent-config-core/src/main/java/io/casehub/platform/agent/config/ManifestCredentialResolver.java` — credential resolution
- `agent-config-core/src/main/java/io/casehub/platform/agent/config/AgentConfigLoader.java` — top-level orchestrator
- `platform-api/src/main/java/io/casehub/platform/api/identity/DIDResolver.java` — DID resolution SPI
- `platform-api/src/main/java/io/casehub/platform/api/identity/DIDDocument.java` — resolved DID document
- `platform-api/src/main/java/io/casehub/platform/api/identity/VerificationMethod.java` — public key record (SPKI-encoded)
- `platform-api/src/main/java/io/casehub/platform/api/signing/SignatureVerifier.java` — algorithm-transparent signature verification utility
- `platform-api/src/main/java/io/casehub/platform/api/signing/VerificationOutcome.java` — verification result enum
- `specs/issue-335-agent-config-manifest/2026-09-16-agent-config-manifest-design.md` — parent design (§Security)
- `specs/issue-336-remote-source-auth-integrity/decisions.md` — D1-D11 design decisions
- Docker content-addressable storage — algorithm-prefixed digest convention
- RFC 1918 — private address ranges
- RFC 6838 — media type parameters
