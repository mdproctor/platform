## D1: Structured SourceDeclaration extension with auth and integrity blocks

**Choice:** Add security fields to SourceDeclaration via two structured sub-records: `auth` (typed authentication config) and `integrity` (digest + optional signature with signer DID). Both are optional and nullable. SourceDeclaration grows from `(uri, priority)` to `(uri, priority, auth, integrity)`.
**Alternatives:**
- Flat fields — all security fields directly on SourceDeclaration. At 7+ fields (credential, credential-header, digest, signature, signer, tls...) this crosses a complexity threshold for a record that currently has 2 fields. Cross-field validation becomes implicit.
- Monolithic SecurityPolicy — groups all security fields under a single `security:` nesting. Doesn't separate orthogonal concerns (auth vs integrity). Makes the common case of auth-without-integrity or integrity-without-auth clunky.
- Manifest-level SecurityConfig — applies security to all sources from a manifest. Can't handle per-source credentials, which is the primary use case.
**Rationale:** Auth and integrity are orthogonal concerns — a source can have auth without integrity, integrity without auth, or both. The structured blocks keep SourceDeclaration's core compact (uri + priority + two optional blocks), group related fields naturally, and make cross-field validation explicit (e.g., signer requires signature within the integrity block; header name requires type=header within the auth block). The common case (no security) has zero nesting — both blocks are absent.
**Trade-offs:** One level of YAML nesting for security config. Two new record types (SourceAuth, SourceIntegrity). Both justified by the conceptual grouping.
**Sources:** SourceDeclaration.java, ProviderDeclaration.java, #336 issue body
**Exploration:** quick
**Status:** revised (R1-02: reviewer correctly identified flat approach doesn't scale to 7+ fields; structured blocks separate orthogonal concerns cleanly)

## D2: mTLS deferred to follow-up issue

**Choice:** Defer mTLS support to a separate follow-up issue. This issue focuses on auth tokens and content integrity.
**Alternatives:**
- Include mTLS (original choice) — per-source TLS config via SourceTlsConfig record with CredentialRef strings for keystore/truststore paths and passwords. Forces per-source HttpClient instances (JDK HttpClient binds SSLContext at construction; each instance owns a thread pool and connection pool). CredentialRef is a poor fit for keystore paths (repurposing a credential resolver as a generic config resolver). Significant cryptographic plumbing orthogonal to auth/integrity.
- Global TLS config — single config block, no per-source flexibility. Still scope creep for this issue.
**Rationale:** mTLS is orthogonal to auth tokens and content integrity. Including it forces per-source HttpClient lifecycle management, doesn't fit CredentialRef cleanly (keystore paths vs credential values), and adds significant implementation complexity unrelated to the primary use case. Corporate PKI environments can be supported in a follow-up without impacting the auth/integrity design — the SourceDeclaration record can be extended with an optional `tls` block later.
**Trade-offs:** Corporate PKI environments unsupported until follow-up. Acceptable — this issue's primary scope is auth tokens and content integrity.
**Sources:** CredentialRef.java, ManifestLoader.java, java.net.http.HttpClient (SSLContext binding at construction)
**Exploration:** quick
**Status:** revised (R1-05: reviewer correctly identified mTLS as scope creep that complicates the primary design without architectural benefit to the auth/integrity concern)

## D3: Content integrity — algorithm-prefixed SHA-256 digest + optional detached signature with signer DID

**Choice:** SHA-256 digest in algorithm-prefixed format (`sha256:<hex>`) as primary integrity mechanism. Optional signature field (base64-encoded raw signature bytes) with a corresponding `signer` field (DID URI string) for cryptographic non-repudiation. All three fields reside in the `integrity:` block on SourceDeclaration. Both digest and signature cover the raw HTTP response body bytes (after decompression, before parsing) — the publisher runs `sha256sum catalog.yaml` and the consumer hashes the response body. Signature covers the raw content bytes (not the digest string), keeping digest and signature independent. Verification order: digest first (cheap O(1) hash computation), then signature (may require network call for DID resolution). Digest without signature is a valid configuration. Bidirectional validation at load time: signer without signature is an error, AND signature without signer is an error.
**Alternatives:**
- Bare hex/base64 digest without algorithm prefix — locks the platform to SHA-256 forever or requires a breaking change for algorithm evolution. The algorithm-prefixed format (`sha256:...`) follows the industry standard used by Docker image digests, OCI content-addressable storage, and npm Subresource Integrity.
- SHA-256 only, no signature — simpler but no non-repudiation for high-security environments.
- Signature only — requires every source publisher to have a signing key. Too heavy for most deployments.
- JWS-wrapped signature (embeds signer in `kid` header) — avoids separate signer field but more complex to implement and parse. The platform's existing identity module uses raw SPKI-encoded public keys and raw signature bytes (VerificationMethod record), not JWS.
**Rationale:** Algorithm-prefixed format enables non-breaking algorithm evolution (sha384:, sha512: in future). Separate `signer` field (DID URI) is required for DIDResolver.resolve(null, did) — actorId is null since manifest loading is a startup/config operation with no actor context (DIDResolver javadoc explicitly allows null actorId for credential issuer DID resolution). Verification order (digest first) is an optimization: digest check is O(1) hash; signature verification requires DID resolution (potentially a network call via WebDIDResolver), then SPKI key parsing and signature verification. Running digest first short-circuits the expensive path in the common failure case (stale hash after content update).
**Trade-offs:** Digest and signature partially overlap for integrity (a valid signature implies content hasn't been tampered with), but they serve different operational roles. Digest-only requires no key infrastructure — appropriate for most internal deployments. Signature adds non-repudiation — appropriate for high-security supply chain scenarios. Both together provide defense-in-depth with cheap pre-check. When both are present and digest matches but signature fails: the content is unchanged (digest proves integrity) but the authorship claim is invalid — this is still fail-closed per D5, with an ERROR-level log distinguishing the semantic difference.
**Sources:** identity/ module (DIDResolver, DIDDocument, VerificationMethod, VerificationMethodType), Docker digest format, OCI content-addressable storage, #336 issue body
**Exploration:** quick
**Status:** revised (R1-07: format specifications added; R1-08: removed stale fail-open note, D5 decides fail-closed; R1-22: algorithm-prefixed digest format; R1-24: signer field for DID resolution; R2-02: hash/sign scope specified as raw response body bytes; R2-03: bidirectional signer/signature validation)

## D4: Address-scoped HTTPS enforcement with explicit redirect handling

**Choice:** Auto-allow HTTP for loopback addresses (127.0.0.1, [::1], localhost) and RFC 1918 private addresses (10.x, 172.16-31.x, 192.168.x). Require HTTPS for all other addresses by default. Config override `casehub.agent.manifest.allow-insecure=true` available only as an escape hatch for public HTTP endpoints (with warning log naming the specific URI). Explicit 3xx handling: check for redirect status codes, log a warning with the redirect target (Location header), and skip the source — do not follow redirects.
**Alternatives:**
- Binary allow-insecure flag (original D4) — too broad. A developer who sets this for local dev and deploys with it enabled silently disables transport security for all production sources.
- Strict enforce everywhere — impractical for localhost dev.
- Follow redirects with credential stripping on cross-origin — adds complexity. The redirect case is unusual for manifest source URIs.
- Rely on implicit NEVER redirect policy without explicit 3xx handling — current code checks `statusCode() >= 400`, so 3xx responses pass through and the redirect response body is parsed as YAML (silent corruption).
**Rationale:** Matches Docker's approach to insecure registries — auto-allow loopback/private, require explicit opt-in for public insecure endpoints. Eliminates the risk of a dev convenience flag leaking to production. Explicit 3xx rejection with informative logging (including the Location header) gives operators clear feedback to update their source URIs to the final target. Following redirects with auth headers would leak credentials on cross-origin redirects.
**Trade-offs:** Slightly more implementation complexity for address classification (loopback/private detection). Worth the safety improvement. Sources behind CDNs with permanent redirects need their URIs updated to the final target — correct behavior since the operator should know the actual endpoint.
**Sources:** ManifestLoader.fetchRemote(), Docker insecure registry design, java.net.http.HttpClient redirect policy, RFC 1918
**Exploration:** quick
**Status:** revised (R1-11: address-scoped enforcement; R1-25: explicit 3xx handling folded in)

## D5: Fail-closed verification with differentiated error logging

**Choice:** Both digest mismatch and signature verification failure reject the source (fail-closed). Missing digest/signature fields are fine (no check requested). Declared but failing checks are hard errors — the source is skipped. Differentiated logging by failure category:
- Unreachable source → WARN (transient infrastructure failure, retry likely fixes)
- Digest mismatch → ERROR with expected vs actual digest values (content modified since digest computed — operator error or supply chain attack)
- Signature failure → ERROR with specific failure reason: DID unresolvable, key type mismatch, wrong algorithm, verification failed (potential integrity compromise)
**Alternatives:**
- Fail-open — log warning, use content anyway. Defeats the purpose of declaring integrity checks.
- Undifferentiated logging (original D5) — treats infrastructure failures same as security failures. Cannot alert on security-relevant events.
- Fail-closed for signature only, warn for digest — inconsistent posture.
**Rationale:** Declaring a digest or signature is an explicit security intent. Failure means the content is suspect. Differentiated logging helps operators distinguish transient infrastructure issues from potential supply chain attacks. ERROR-level for integrity failures enables alerting on security-relevant events. The error message includes diagnostic detail (expected/actual values, specific failure reason) to guide remediation.
**Trade-offs:** A source with a stale digest (content updated but digest not) will be rejected until the digest is updated. This is the correct behavior — it forces the operator to verify the new content before updating the hash.
**Sources:** ManifestLoader.fetchRemote(), ManifestCredentialResolver.resolve()
**Exploration:** quick
**Status:** revised (R1-13: differentiated error logging by failure category)

## D6: Typed auth block with type discriminator

**Choice:** Authentication configured via an `auth:` block on SourceDeclaration with a `type` discriminator. Supported initial types: `bearer` (credential sent as `Authorization: Bearer <value>`), `header` (credential sent as `<header-name>: <value>` with explicit header name), `basic` (credential sent as `Authorization: Basic <value>`). Each type specifies only the fields relevant to it. The `credential` field within each type is a String parsed via CredentialRef.parse().
**Alternatives:**
- Flat credential + credential-header (original D6) — ambiguous semantics when credential-header is `Authorization` (two ways to achieve nearly the same thing, with a subtle Bearer prefix difference). Not extensible to new auth types without adding more fields.
- Serverless Workflow AuthenticationPolicy reuse — adds coupling to `serverlessworkflow-types` dependency. AuthenticationPolicy is a generated POJO with just an `additionalProperties` map, not designed for reuse outside Serverless Workflow's type system. agent-config-core is "Pure Java + Jackson" with no Serverless Workflow dependency, and manifest authentication is a different concern (declarative config-file authentication) from workflow execution authentication.
- Always require a map — verbose for the 90% Bearer case.
**Rationale:** Typed discriminator is self-documenting and extensible. Each auth type has a clear YAML shape with no ambiguity. auth.md (`~/claude/casehub/parent/docs/platform/auth.md`, line 148) declares AuthenticationPolicy as the canonical model for outbound auth — but its scope is workers, connectors, and quarkus-flow workflow steps (runtime HTTP calls to external services). Agent-config manifest source fetching is a different concern: declarative config-file authentication at startup, not runtime outbound service calls. D6 aligns with AuthenticationPolicy's typed discriminator principles without coupling to the Serverless Workflow dependency. New auth types (e.g., OAuth2 client-credentials) can be added as new type values without structural changes.
**Trade-offs:** Three types (bearer, header, basic) cover realistic use cases for manifest source authentication. OAuth2 client-credentials can be added as a type in a follow-up if needed, without structural changes.
**Sources:** SourceDeclaration.java, CredentialRef.java, io.serverlessworkflow.api.types.AuthenticationPolicy (examined for principles — generated POJO with additionalProperties bag, not reusable)
**Exploration:** quick
**Status:** revised (R1-16: typed discriminator adopted, AuthenticationPolicy coupling rejected — auth.md declares it canonical for workers/connectors/quarkus-flow, but agent-config manifest fetching is a different concern; R1-17: ambiguity eliminated by typed approach; R2-01: corrected factual error about auth.md existence)

## D7: Content-Type validation — hardcoded allow-list with media type parsing

**Choice:** Validate response Content-Type against a hardcoded allow-list: application/yaml, application/x-yaml, text/yaml, application/json. Parse the media type before matching — extract type/subtype, ignore parameters (charset, boundary, etc.). Reject other content types with warning log. Missing Content-Type header is allowed (many internal endpoints don't set it).
**Alternatives:**
- String-equality matching — rejects valid responses with parameters (e.g., `application/yaml; charset=utf-8` from any properly-configured web server). Broken in production.
- Configurable content types per source — overengineering for this use case.
- No validation — risks parsing HTML login redirect pages or error pages as YAML.
**Rationale:** HTTP Content-Type headers include parameters per RFC 6838. A string-equality check against `application/yaml` would reject `application/yaml; charset=utf-8`. Media type parsing (split on `;`, trim the first segment) is trivial and prevents false rejections. JSON is included in the allow-list because it's valid YAML and some endpoints serve JSON model catalogs.
**Trade-offs:** Unusual content types (e.g., text/plain serving valid YAML) would be rejected. Operator must fix their server's Content-Type header — correct behavior.
**Sources:** ManifestLoader.fetchRemote(), RFC 6838
**Exploration:** quick
**Status:** revised (R1-19: media type parsing requirement made explicit)

## D8: Response body size limit — 1 MB

**Choice:** Wrap response InputStream in a bounded reader that rejects responses exceeding 1 MB. Bounded reader terminates the HTTP connection when the limit is exceeded rather than reading and discarding the remainder. Manifest files are small YAML/JSON documents — 1 MB is generous.
**Alternatives:**
- No limit — risk of OOM from malicious or misconfigured sources.
- Configurable limit — unnecessary complexity for a safety guard.
**Rationale:** Defense-in-depth. A real model catalog manifest is typically under 100 KB. 1 MB leaves ample headroom while preventing resource exhaustion. Terminating the connection on exceeding (rather than reading+discarding) prevents slow-read attacks even below the request timeout.
**Trade-offs:** None meaningful — a manifest larger than 1 MB is a misconfiguration.
**Sources:** ManifestLoader.fetchRemote()
**Exploration:** quick
**Status:** revised (R1-20: connection termination on limit exceeded)

## D9: ManifestLoader dependency injection model

**Choice:** ManifestLoader accepts dependencies via constructor parameters: ManifestCredentialResolver (for auth header construction), DIDResolver (for signature verification, nullable — null when no signature verification is configured), and a configuration record for HTTPS enforcement policy. AgentConfigLoader passes these through from its own constructor parameters. ManifestLoader remains a plain Java class (no CDI) with constructor injection. When DIDResolver is null, any source with a declared signature in its `integrity:` block is rejected (fail-closed per D5) — the source declared a security intent (signature verification) that the deployment cannot fulfill. This means adding signatures to a published catalog is a compatibility-affecting change for consumers without DID resolution infrastructure.
**Alternatives:**
- Keep zero-arg constructor with static/ambient resolution — can't test, can't configure per-deployment.
- Convert to CDI bean — contradicts agent-config-core's "Pure Java + Jackson, no CDI" constraint.
- Extract SecureSourceFetcher as a separate class — premature decomposition. ManifestLoader's responsibility is manifest loading, which includes secure fetching. The fetch pipeline (auth, integrity, content-type, size limit) is a single logical operation. If the class grows too large during implementation, refactoring to extract a helper is a mechanical step, not a design decision.
**Rationale:** ManifestLoader's zero-arg constructor cannot survive these changes — it needs at minimum a credential resolver (for auth) and optionally a DID resolver (for signature verification). Constructor injection is the natural pattern for a "Pure Java + Jackson" module. The API surface change from zero-arg to multi-param constructor is architecturally significant: AgentConfigLoader.load() changes from `new ManifestLoader()` to `new ManifestLoader(resolver, didResolver, config)`, and test code constructs ManifestLoader with test doubles.
**Trade-offs:** AgentConfigBeans (the Quarkus wiring layer) needs to provide DIDResolver and HTTPS enforcement config to AgentConfigLoader. This is straightforward — DIDResolver is already a CDI bean, and the enforcement config is a simple record.
**Sources:** ManifestLoader.java (zero-arg constructor), AgentConfigLoader.java (constructs ManifestLoader), AgentConfigBeans.java (CDI wiring)
**Exploration:** quick (surfaced by reviewer as implicit decision I2)
**Status:** revised (R2-04: null DIDResolver behavior and operational consequence made explicit)

## D10: HTTP redirect policy — explicit NEVER with 3xx detection

**Choice:** ManifestLoader's HttpClient explicitly sets redirect policy to NEVER (do not rely on default). The fetch pipeline checks for 3xx status codes explicitly (status >= 300 and < 400) and logs a warning that includes the redirect target (from Location header), directing the operator to update the source URI. The source is skipped (same as any other fetch failure).
**Alternatives:**
- Follow redirects with credential stripping on cross-origin — adds complexity; credential stripping logic must correctly classify same-origin vs cross-origin, which is error-prone. The redirect case is unusual for manifest source URIs.
- Rely on implicit NEVER without 3xx handling — the current code checks `statusCode() >= 400`, so 3xx responses pass through and the redirect response body (typically HTML) is parsed as YAML (silent corruption or confusing parse errors).
**Rationale:** Following redirects with auth headers leaks credentials to redirect targets on cross-origin redirects. But silently parsing redirect response bodies as YAML is equally bad — it produces confusing errors or silently corrupts the manifest. Explicit 3xx detection with informative logging gives operators clear, actionable feedback: update the source URI to the final target.
**Trade-offs:** Sources behind CDNs with permanent redirects need their URIs updated to the final endpoint. This is correct behavior — operators should know and control the actual endpoint their agent fetches from.
**Sources:** ManifestLoader.fetchRemote() (current `>= 400` check misses 3xx), java.net.http.HttpClient.Redirect.NEVER
**Exploration:** quick (surfaced by reviewer as implicit decision I4)
**Status:** captured

## D11: Credential type on SourceAuth — String via CredentialRef

**Choice:** The credential field in SourceAuth types is typed `String`, parsed via `CredentialRef.parse()`. Not `Object` like ProviderDeclaration's credential field.
**Alternatives:**
- Object (matching ProviderDeclaration) — ProviderDeclaration's Object credential supports both scalar (String) and map (Map<String,String>) credentials because some vendor APIs need multi-field credentials (e.g., API key + API secret). SourceAuth has no such requirement — source authentication always uses a single resolved credential value. Object would be misleading type-widening.
- CredentialRef directly as the Java type — would require a custom Jackson deserializer for the sealed interface. String with CredentialRef.parse() at resolution time is simpler and consistent with how credential references appear in YAML (as plain strings like `env:API_KEY`, `file:/path/to/token`, `ref:vault/secret`).
**Rationale:** SourceAuth credentials are always scalar strings (CredentialRef-parseable). ProviderDeclaration's Object type is not inconsistency — it's a different requirement (vendors may need multi-field credentials). SourceAuth has no map case. String is the correct type.
**Trade-offs:** ProviderDeclaration (Object) and SourceAuth (String) use different types for credential fields. This is intentional — they solve different problems with different credential shapes.
**Sources:** CredentialRef.java (sealed interface: EnvRef, FileRef, ExternalRef), ProviderDeclaration.java (Object credential with hasScalarCredential/mapCredential dispatch), ManifestCredentialResolver.java
**Exploration:** quick (surfaced by reviewer as implicit decision I5)
**Status:** captured
