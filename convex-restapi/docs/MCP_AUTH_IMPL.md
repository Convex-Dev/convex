# Implementation Plan: Peer Authentication and MCP Signing Service

Staged implementation of the design in `MCP_AUTH.md`. Each stage is independently testable. We build bottom-up: core primitives → service layer → API layer.

## What Already Exists

| Component | Status | Location |
|---|---|---|
| `JWT` (EdDSA sign/verify) | Exists | `convex.core.json.JWT` |
| `Multikey` (Ed25519 multibase) | Exists | `convex.core.crypto.util.Multikey` |
| `OwnerLattice` | Exists | `convex.lattice.generic.OwnerLattice` (uses `AHashMap<ACell, SignedData<V>>`) |
| `SignedLattice` | Exists | `convex.lattice.generic.SignedLattice` |
| `EtchStore` | Exists | `convex.etch.EtchStore` |
| `Symmetric` (AES-CBC) | Exists | `convex.core.crypto.Symmetric` — **not GCM, needs replacement or companion** |
| `Hashing` (SHA-256) | Exists | `convex.core.crypto.Hashing` |
| `Base58` | Exists | `convex.core.crypto.util.Base58` |
| MCP server + 16 tools | Exists | `convex.restapi.mcp.McpAPI` (JSON-RPC 2.0, full tool framework) |
| `McpTool` base class | Exists | `convex.restapi.mcp.McpTool` (tool metadata from JSON) |
| `DIDAPI` | Exists | `convex.restapi.api.DIDAPI` (did:web endpoints) |
| HKDF | **Done (Stage 1)** | `convex.core.crypto.HKDF` — RFC 5869 SHA-256, wraps BouncyCastle `HKDFBytesGenerator` |
| AES-256-GCM | **Done (Stage 1)** | `convex.core.crypto.AESGCM` — 12-byte nonce prepended, JDK `AES/GCM/NoPadding` |
| `LocalLattice` | **Done (Stage 2)** | `convex.lattice.LocalLattice` — `:local` OwnerLattice convention, registered in `Lattice.ROOT` |
| `ACursor` abstraction | Exists | `convex.lattice.cursor.ACursor` — atomic get/set/updateAndGet, path navigation |
| SigningService | **Done (Stage 3)** | `convex.peer.signing.SigningService` — takes `ACursor<ACell>`, decoupled from persistence |
| `PeerAuth` | **Done (Stage 7)** | `convex.peer.auth.PeerAuth` — two-path JWT verification + peer token issuance |
| `AuthMiddleware` | **Done (Stage 8)** | `convex.restapi.auth.AuthMiddleware` — Javalin bearer token middleware |

---

## Stage 1: Core Crypto — HKDF and AES-256-GCM ✓

**Module:** `convex-core` — **DONE** (20 tests pass)

**Files created:**
- `convex-core/src/main/java/convex/core/crypto/HKDF.java` — `derive(ikm, salt, info, length)`, `derive256()` convenience
- `convex-core/src/main/java/convex/core/crypto/AESGCM.java` — `encrypt(key, plaintext)`, `decrypt(key, data)` using JDK `AES/GCM/NoPadding`
- `convex-core/src/test/java/convex/core/crypto/HKDFTest.java` — 8 tests (3 RFC 5869 vectors, variance, edge cases)
- `convex-core/src/test/java/convex/core/crypto/AESGCMTest.java` — 12 tests (round-trips, wrong key, tamper, nonce variance, input validation)

**Verify:** `mvn test -pl convex-core -Dtest=HKDFTest,AESGCMTest`

---

## Stage 2: Local Lattice — OwnerLattice Convention ✓

**Module:** `convex-core` — **DONE** (16 tests pass)

**Files created:**
- `convex-core/src/main/java/convex/lattice/LocalLattice.java` — `KEY_LOCAL`, `LATTICE`, `createSlot()`, `setSlot()`, `getSlot()`, `getSignedSlot()`, `get()` helpers
- `convex-core/src/main/java/convex/lattice/generic/LWWLattice.java` — timestamp-based last-write-wins register with hash tiebreaker for commutativity
- `convex-core/src/test/java/convex/lattice/LocalLatticeTest.java` — 18 tests
- `convex-core/src/test/java/convex/lattice/generic/LWWLatticeTest.java` — 14 tests (lattice laws: commutativity, associativity, idempotency)

**Also modified:**
- `convex-core/.../cvm/Keywords.java` — added `LOCAL` keyword constant
- `convex-core/.../lattice/Lattice.java` — registered `:local` in `Lattice.ROOT`

**Design notes:**
- Per-peer value is `AHashMap<Keyword, ACell>` with per-keyword merge via `MapLattice(LWWLattice.INSTANCE)`
- OwnerLattice handles inter-peer isolation; intra-peer merge is per-service keyword via LWW (timestamp-based, hash tiebreaker for commutativity)
- Each service (`:signing`, etc.) bumps a `:timestamp` key on every mutation; merge picks the higher timestamp
- Different services merge independently — updating `:signing` does not clobber a sibling service
- Since LWW replaces each service's map as a unit, internal deletions (e.g., key removal) use `dissoc` — no tombstones needed

**Verify:** `mvn test -pl convex-core -Dtest=LWWLatticeTest,LocalLatticeTest`

---

## Stage 3: Signing Service — Encrypted Key Store ✓

**Module:** `convex-peer` — **DONE** (16 tests pass)

**Files created:**
- `convex-peer/src/main/java/convex/peer/signing/SigningService.java`
- `convex-peer/src/test/java/convex/peer/signing/SigningServiceTest.java`

**Also modified:**
- `convex-peer/module-info.java` — exports `convex.peer.signing`

**SigningService API:**
- Constructor takes `AKeyPair peerKeyPair, ACursor<ACell> cursor` — decoupled from persistence. The server layer controls how the cursor is backed (EtchStore, in-memory, OwnerLattice path, etc.)
- `init()` — generates encryptionSecret on first start or loads from existing cursor state
- `createKey(AString identity, AString passphrase)` → `AccountKey`
- `listKeys(AString identity)` → `List<AccountKey>`
- `loadKey(AString identity, AccountKey publicKey, AString passphrase)` → `byte[]` seed (or null if wrong lookup hash)
- Internal: `storeKey()`, `addToKeyIndex()`, `computeLookupHash()`, `computeIdentityHash()`, `deriveKeyWrappingKey()`, `encryptSecret()`, `decryptSecret()`
- Follows `convex.lattice.kv.LatticeKV` / `KVDatabase` cursor pattern

**Tests:**
- Init creates structure; init is idempotent; uninitialised throws
- createKey returns key; appears in listKeys; multiple keys per identity; compartmentalised identities
- Stored key loadable with correct credentials; wrong passphrase/identity → null (different lookup hash)
- Loaded seed matches original key pair
- Persist and reload via cursor snapshot; different peer key cannot decrypt secret
- encryptionSecret round-trip; null constructor args rejected

**Verify:** `mvn test -pl convex-peer -Dtest=SigningServiceTest`

---

## Stage 4: Signing Service — Sign and JWT ✓

**Module:** `convex-peer` — **DONE** (37 tests total, 9 new in this stage)

**Extended:** `SigningService.java`, `SigningServiceTest.java`

**New methods:**
- `ASignature sign(AString identity, AccountKey publicKey, AString passphrase, ABlob message)` — decrypt key, sign with Ed25519, zero seed, return signature (or null if key not found)
- `AString getSelfSignedJWT(AString identity, AccountKey publicKey, AString passphrase, String audience, AMap<AString,ACell> extraClaims, long lifetimeSeconds)` — decrypt key, build JWT with `sub`/`iss` = `did:key:<multikey>`, `iat`, `exp`, optional `aud`, merge extra claims, sign with `JWT.signPublic()`, zero seed, return encoded JWT (or null if key not found)

**Tests:**
- Sign bytes, verify signature with public key ✓
- Wrong passphrase → null ✓
- Wrong identity → null (different lookup hash) ✓
- getSelfSignedJWT: verify result with `JWT.verifyPublic()` ✓
- getSelfSignedJWT: verify `sub` and `iss` are correct `did:key` ✓
- getSelfSignedJWT: verify `aud` claim when audience provided ✓
- getSelfSignedJWT: verify custom claims merged into payload ✓
- getSelfSignedJWT: wrong passphrase → null ✓
- getSelfSignedJWT: verify `exp` is in the future within lifetime ✓

**Verify:** `mvn test -pl convex-peer -Dtest=SigningServiceTest`

---

## Stage 5: Signing Service — Elevated Operations ✓

**Module:** `convex-peer` — **DONE** (37 tests pass, 12 new in this stage)

**Extended:** `SigningService.java`, `SigningServiceTest.java`

**New methods:**
- `AccountKey importKey(AString identity, ABlob seed, AString passphrase)` — store existing seed, deduplicate identity index
- `ABlob exportKey(AString identity, AccountKey publicKey, AString passphrase)` — return decrypted seed as Blob (or null)
- `void deleteKey(AString identity, AccountKey publicKey, AString passphrase)` — remove from `:keys` via `dissoc`, remove from identity index
- `void changePassphrase(AString identity, AccountKey publicKey, AString oldPass, AString newPass)` — decrypt with old, remove old `:keys` entry, re-encrypt with new passphrase

**Internal helpers added:**
- `removeFromKeys()` — dissoc lookup hash from `:keys` Index, bump `:timestamp`
- `removeFromKeyIndex()` — rebuild identity's `AVector<AccountKey>` without the deleted key, bump `:timestamp`

**Identity index format:** Plaintext `Index<Hash, AVector<AccountKey>>` (`:identities` key). Identity hash → vector of public keys. No encryption — public keys are public information, identity hashes are one-way. Deletion rebuilds the vector without the deleted key. No tombstones needed because the entire `:signing` map is merged as a unit via LWW (higher `:timestamp` wins), so deleted keys cannot be resurrected by stale replicas.

**Timestamp bumping:** Every mutation to the `:signing` map (`storeKey`, `removeFromKeys`, `addToKeyIndex`, `removeFromKeyIndex`) bumps the `:timestamp` key for LWW merge correctness.

**Tests:**
- Import known seed → correct public key, appears in listKeys ✓
- Import duplicate (same identity, same seed, same passphrase) → idempotent, no duplicate in listKeys ✓
- Export key matches imported seed ✓
- Export with wrong passphrase → null ✓
- Delete key removed from listKeys ✓
- Delete key cannot be loaded ✓
- Delete key preserves other keys for same identity ✓
- Delete key persists across cursor restart ✓
- Change passphrase: new passphrase works ✓
- Change passphrase: old passphrase fails ✓
- Change passphrase: wrong old passphrase throws IllegalArgumentException ✓
- Change passphrase preserves identity index (key still in listKeys) ✓

**Verify:** `mvn test -pl convex-peer -Dtest=SigningServiceTest`

---

## Stage 6: Signing Service — Multi-Peer and Key Rotation ✓

**Module:** `convex-peer` — **DONE** (39 tests pass, 2 new in this stage)

**Extended:** `SigningServiceTest.java`

**Focus:** Verify cursor isolation and peer key rotation at the SigningService level. OwnerLattice merge is already tested in `LocalLatticeTest`.

**New tests:**
- `testIndependentServicesIndependentCursors` — two services with separate cursors, same identity string → independent key stores, cross-service load fails (different encryptionSecret) ✓
- `testPeerKeyRotation` — create keys with peer key A, re-wrap encryptionSecret to peer key B (decrypt with old, encrypt with new), verify all signing keys accessible with new peer key, loaded seeds match original keys ✓

**Already covered by existing tests:**
- Different peer key cannot decrypt encryptionSecret → `testDifferentPeerKeyCannotDecryptSecret`
- Persist and reload via cursor snapshot → `testPersistAndReloadViaCursor`

**Verify:** `mvn test -pl convex-peer -Dtest=SigningServiceTest`

---

## Stage 7: Auth — JWT Verification ✓

**Module:** `convex-peer` — **DONE** (12 tests pass)

**Files created:**
- `convex-peer/src/main/java/convex/peer/auth/PeerAuth.java`
- `convex-peer/src/test/java/convex/peer/auth/PeerAuthTest.java`

**Also modified:**
- `convex-peer/module-info.java` — added `exports convex.peer.auth`

**PeerAuth.java:**
- Constructor takes `AKeyPair peerKeyPair` — stores peer key pair and derived `AccountKey`
- `AString verifyBearerToken(AString jwt)` — returns identity (AString DID) or null
  - Parses JWT via `JWT.parse(jwt)`
  - Try self-issued: `getKeyID()` → `Multikey.decodePublicKey()` → `verifyEdDSA(signerKey)` → `validateClaims()` → verify `sub` matches `did:key:` for signing key → return `sub`
  - Try peer-signed: `verifyEdDSA(peerKey)` → `validateClaims()` → return `sub`
- `AString issuePeerToken(AString identity, long lifetimeSeconds)` — create peer-signed JWT with `sub=identity`, `iss=did:key:<peer-multikey>`, `iat`, `exp`
- `AccountKey getPeerKey()` — returns peer's public key

**Tests:**
- Self-issued JWT: create with known keypair, verify, correct `did:key:` identity returned ✓
- Self-issued JWT: expired → rejected ✓
- Self-issued JWT: tampered signature → rejected ✓
- Self-issued JWT: `kid` doesn't match signing key → rejected ✓
- Peer-signed JWT: issue and verify → correct identity ✓
- Peer-signed JWT: verify with wrong peer key → rejected ✓
- Peer-signed JWT: expired → rejected ✓
- Null token → null ✓
- Garbage token → null ✓
- Null constructor arg → IllegalArgumentException ✓
- Peer token claims: verify sub, exp in future ✓
- getPeerKey matches constructor key pair ✓

**Verify:** `mvn test -pl convex-peer -Dtest=PeerAuthTest`

---

## Stage 8: REST API — Auth Middleware ✓

**Module:** `convex-restapi` — **DONE** (11 tests pass, 131 total restapi tests pass)

**Files created:**
- `convex-restapi/src/main/java/convex/restapi/auth/AuthMiddleware.java`
- `convex-restapi/src/test/java/convex/restapi/test/AuthMiddlewareTest.java`

**Also modified:**
- `convex-restapi/src/main/java/convex/restapi/RESTServer.java` — added middleware field, getter, registration in `addAPIRoutes()`

**AuthMiddleware.java:**
- Two handler modes:
  - `handler()` — optional auth: sets identity if valid token present, does not reject unauthenticated requests
  - `requiredHandler()` — required auth: returns 401 JSON error if no valid token
- `static AString getIdentity(Context ctx)` — retrieves identity from context attribute for downstream handlers
- `PeerAuth getPeerAuth()` — access to underlying PeerAuth instance
- Extracts `Authorization: Bearer <token>` header, delegates to `PeerAuth.verifyBearerToken()`
- Identity stored as `ctx.attribute("auth.identity", AString)`

**RESTServer.java changes:**
- Added `protected AuthMiddleware authMiddleware` field and `getAuthMiddleware()` getter
- In `addAPIRoutes()`: creates `PeerAuth` from server keypair, registers `authMiddleware.handler()` via `app.before()` (optional auth on all routes)
- Null-safe: skips middleware registration if server has no keypair

**Tests (extends ARESTTest):**
- PeerAuth unit: creation, self-issued round-trip, peer-signed round-trip ✓
- AuthMiddleware unit: creation, null arg rejected ✓
- HTTP integration: public endpoint without auth → 200 ✓
- HTTP integration: MCP with self-issued JWT → 200 ✓
- HTTP integration: MCP with peer-signed JWT → 200 ✓
- HTTP integration: MCP without auth → 200 (MCP allows unauthenticated) ✓
- HTTP integration: MCP with expired JWT → 200 (identity not set, but route allows it) ✓
- HTTP integration: MCP with garbage auth → 200 (same) ✓

**Verify:** `mvn test -pl convex-restapi -Dtest=AuthMiddlewareTest`

---

## Stage 9: MCP Tools — Core Signing ✓

**Module:** `convex-restapi` — **DONE** (11 tests pass, 142 total restapi tests pass)

**Files created:**
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingServiceInfo.json`
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingCreateKey.json`
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingListKeys.json`
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingSign.json`
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingGetJWT.json`
- `convex-restapi/src/test/java/convex/restapi/test/SigningMcpTest.java`

**Also modified:**
- `convex-restapi/src/main/java/convex/restapi/RESTServer.java` — added `SigningService` field, `Root<ACell>` cursor, `init()` in constructor, `getSigningService()` getter
- `convex-restapi/src/main/java/convex/restapi/mcp/McpAPI.java` — added `ThreadLocal<Context>` for passing request context to tools, `getRequestIdentity()` helper, 5 new inner tool classes, registered in `registerTools()`

**New MCP tools:**
- `signingServiceInfo` — no auth required, returns `{available: true/false}`
- `signingCreateKey` — auth required, takes `passphrase`, returns `{publicKey: "0x..."}`
- `signingListKeys` — auth required, returns `{keys: ["0x...", ...]}`
- `signingSign` — auth required, takes `publicKey`, `passphrase`, `value` (hex), returns `{signature: "0x...", publicKey: "0x..."}`
- `signingGetJWT` — auth required, takes `publicKey`, `passphrase`, optional `audience`/`lifetime`, returns `{jwt: "ey..."}`

**Design notes:**
- `ThreadLocal<Context>` set in `handleMcpRequest`, cleared in `finally` block — enables tool inner classes to access Javalin context for `AuthMiddleware.getIdentity()` without changing the `McpTool.handle()` signature
- `RESTServer` creates a `SigningService` with `Root<ACell>` in-memory cursor — the cursor can be swapped for lattice-backed persistence later
- Tools return `toolError("Authentication required")` (isError: true) when no identity is present, not 401 — tools are protocol-valid responses with error payloads

**Tests (extends ARESTTest):**
- `signingServiceInfo` returns `available=true` without auth ✓
- Auth → `signingCreateKey` → `signingListKeys` → key appears in list ✓
- Auth → multiple `signingCreateKey` → different keys generated ✓
- Auth → `signingCreateKey` → `signingSign` → verify signature externally with `Providers.verify()` ✓
- Auth → `signingSign` with wrong passphrase → tool error ✓
- Auth → `signingCreateKey` → `signingGetJWT` → verify JWT with `JWT.verifyPublic()`, check `sub` is `did:key:` ✓
- Auth → `signingGetJWT` with audience → verify `aud` claim in JWT ✓
- `signingCreateKey` without auth → tool error ✓
- `signingListKeys` without auth → tool error ✓
- `signingSign` without auth → tool error ✓
- Identity compartmentalisation: Alice's keys invisible to Bob, Bob cannot sign with Alice's key ✓

**Verify:** `mvn test -pl convex-restapi -Dtest=SigningMcpTest`

---

## Stage 10: MCP Tools — Elevated Operations and Confirmation Flow ✓

**Module:** `convex-restapi` — **DONE** (11 tests pass, 163 total restapi tests pass)

**Files created:**
- `convex-restapi/src/main/java/convex/restapi/auth/ConfirmationService.java`
- `convex-restapi/src/main/java/convex/restapi/mcp/SigningMcpTools.java`
- `convex-restapi/src/main/java/convex/restapi/api/ConfirmAPI.java`
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingImportKey.json`
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingExportKey.json`
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingDeleteKey.json`
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingChangePassphrase.json`
- `convex-restapi/src/test/java/convex/restapi/test/ElevatedMcpTest.java`
- `convex-restapi/src/test/java/convex/restapi/test/SigningMcpClientTest.java` (MCP SDK client tests)

**Also modified:**
- `convex-restapi/pom.xml` — added MCP SDK test dependency (`io.modelcontextprotocol.sdk:mcp:0.12.1`)
- `convex-restapi/src/main/java/convex/restapi/mcp/McpAPI.java` — extracted signing tools into `SigningMcpTools`, made helpers package-private, added `getRESTServer()` accessor
- `convex-restapi/src/main/java/convex/restapi/RESTServer.java` — added `ConfirmationService` field/getter, registered `ConfirmAPI`

**ConfirmationService.java:**
- In-memory store: `Map<String, Confirmation>` where `Confirmation` record holds `(identity, toolName, paramsHash, description, expiresAt, approved)`
- Token format: `ct_` + 32 hex chars (16 random bytes), 5-minute lifetime
- `createConfirmation(identity, toolName, params, description)` → creates token, auto-cleans expired entries
- `approveConfirmation(token)` → marks as approved (returns boolean)
- `validateAndConsume(token, identity, toolName, params)` → checks approved, not expired, scope-bound (identity + tool + params hash match), then removes (single-use)
- `computeParamsHash(toolName, params)` — SHA-256 of `toolName + params.toString()` for scope binding

**SigningMcpTools.java:**
- Package-private class in `convex.restapi.mcp`, extracted from McpAPI to control class size
- Contains all 9 signing tool inner classes (5 standard + 4 elevated) and `handleElevated` helper
- `registerAll()` registers all tools with McpAPI via package-private `registerTool()`
- `handleElevated()` — two-step flow: without `confirmToken` → return `confirmation_required` with URL; with valid `confirmToken` → validate+consume → proceed

**ConfirmAPI.java:**
- Extends `ABaseAPI`, registers `GET /confirm` and `POST /confirm`
- GET renders HTML confirmation page with tool name, identity, description, and Confirm button
- POST approves the confirmation and renders success page
- Proper HTML escaping via `esc()` method

**Elevated MCP tools:**
- `signingImportKey` — import Ed25519 seed, requires confirmation
- `signingExportKey` — reveal private seed, requires confirmation
- `signingDeleteKey` — permanently destroy key, requires confirmation
- `signingChangePassphrase` — re-encrypt with new passphrase, requires confirmation
- All tool JSON descriptions reference the signing service and list related tools

**Tests (ElevatedMcpTest, extends ARESTTest):**
- Export key full confirmation round-trip (create → export without token → confirm → export with token → verify seed) ✓
- Import key full confirmation round-trip (import → confirm → verify key appears in list) ✓
- Delete key confirmation flow ✓
- Change passphrase confirmation flow (sign works with new, fails with old) ✓
- All 4 elevated tools without auth → tool error ✓
- Reused confirmToken → rejected (single-use) ✓
- confirmToken for wrong tool → rejected (scope-bound) ✓
- Unapproved confirmToken → rejected ✓
- Confirm endpoint GET missing token → 400 ✓
- Confirm endpoint GET invalid token → 404 ✓
- Confirm endpoint POST invalid token → 404 ✓

**Additional tests (SigningMcpClientTest, extends ARESTTest):**
- 10 tests exercising signing tools via official MCP SDK (`McpSyncClient` with `HttpClientStreamableHttpTransport`)
- Verifies full MCP protocol stack: ping, listTools, tool schemas, serviceInfo, createKey+listKeys, sign+verify, getJWT, auth enforcement

**Verify:** `mvn test -pl convex-restapi -Dtest=ElevatedMcpTest,SigningMcpClientTest`

---

## Stage 11: MCP Tools — Signing Convenience Layer

**Module:** `convex-restapi`

**Motivation:** The existing `transact` and `createAccount` tools require the agent to manage raw Ed25519 seeds. These new `signing*` variants use the signing service instead, so the agent only needs a passphrase. The signing service is not always enabled (disabled by default, requires config `mcp.signing: true`), so these tools are registered only when the signing service is available. The existing raw tools remain unchanged.

**Files to create/modify:**
- Add tools to `SigningMcpTools.java` (extends existing extraction)
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingTransact.json`
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingCreateAccount.json`
- `convex-restapi/src/main/resources/convex/restapi/mcp/tools/signingListAccounts.json`
- `convex-restapi/src/test/java/convex/restapi/test/SigningConvenienceTest.java`

**New MCP tools (separate from existing raw tools):**
- `signingTransact` — takes `source`, `address`, `passphrase`; resolves signing key for address, prepares tx, signs via signing service, submits
- `signingCreateAccount` — takes `passphrase`, optional `faucet` amount; creates signing key, creates on-chain account with that key, optional faucet payout
- `signingListAccounts` — takes no args (uses identity); lists signing keys, queries network for addresses registered with each key

**Design notes:**
- Tools only registered when `getSigningService() != null`
- Signing service enablement should be a config option (Stage 14: `mcp.signing`)
- `signingTransact` looks up the account's public key on-chain, finds matching signing key for the authenticated identity, signs with that key
- `signingCreateAccount` combines `signingCreateKey` + raw `createAccount` in a single tool call

**Tests:**
- `signingCreateAccount` with faucet → returns address + public key, account exists on network with correct key ✓
- `signingListAccounts` → shows the created account ✓
- `signingTransact` with created account → executes CVX code, returns result ✓
- `signingTransact` with wrong passphrase → error ✓
- `signingTransact` with address not managed by this identity → error ✓
- All tools without auth → error ✓
- Tools not registered when signing service unavailable ✓

**Verify:** `mvn test -pl convex-restapi -Dtest=SigningConvenienceTest`

---

## Stage 12: Social Login — OAuth Flow

**Module:** `convex-restapi`

**Files to create:**
- `convex-restapi/src/main/java/convex/restapi/auth/OAuthService.java`
- `convex-restapi/src/main/java/convex/restapi/web/AuthPage.java`
- `convex-restapi/src/test/java/convex/restapi/test/OAuthTest.java`

**OAuthService.java:**
- OAuth 2.1 + PKCE flow management
- Provider configuration (Google, Apple, GitHub, Discord)
- JWKS fetching and caching (Google, Apple)
- ID token validation → extract stable user ID
- Identity construction: `did:web:<hostname>:oauth:<provider>:<sub>`
- Peer-signed JWT issuance via `PeerAuth.issuePeerToken()`

**Auth endpoint:**
- `GET /auth` — renders login page with provider buttons
- `GET /auth/callback` — OAuth redirect handler

**Tests:**
- Mock provider JWKS endpoint, validate ID token
- Verify correct `did:web` identity format per provider
- Verify peer-signed JWT issued after successful auth
- Verify peer-signed JWT accepted by AuthMiddleware

**Verify:** `mvn test -pl convex-restapi -Dtest=OAuthTest`

---

## Stage 13: Integration and End-to-End

**Module:** `convex-restapi`

**Files to create:**
- `convex-restapi/src/test/java/convex/restapi/test/SigningE2ETest.java`

**End-to-end scenarios:**
1. Self-issued JWT auth → createKey → sign → verify signature
2. Self-issued JWT auth → createAccount → transact → verify on-chain result
3. Self-issued JWT auth → createKey → getSelfSignedJWT → use JWT to auth to a second peer instance
4. Social login mock → createKey → exportKey (with confirmation) → importKey on second identity
5. Two peer instances sharing a store → independent key stores, no conflicts
6. Persist → restart → recover → keys still accessible

**Verify:** `mvn test -pl convex-restapi -Dtest=SigningE2ETest`

---

## Stage 14: Refactor Config — JSON5-Based Peer and API Configuration ✓

**Modules:** `convex-peer`, `convex-restapi` — **DONE** (48 tests pass: 41 PeerConfigTest + 7 PeerConfigFileTest)

**Motivation:** Peer configuration currently uses `HashMap<Keyword, Object>` — untyped, no schema, no file loading. REST and MCP configuration is either hardcoded or piggybacks on the peer config map. This stage adds JSON5-based config loading following the Covia pattern: `AMap<AString, ACell>` wrapped in a typed `PeerConfig` class with accessor methods and sensible defaults. The existing `HashMap<Keyword, Object>` format is preserved via `toLegacy()` for backward compatibility — no existing code is modified.

**Files created:**
- `convex-peer/src/main/java/convex/peer/PeerConfig.java` — typed config wrapper with all sections
- `convex-peer/src/test/java/convex/peer/PeerConfigTest.java` — 41 tests
- `convex-restapi/src/test/resources/config-example.json5` — full example config with comments
- `convex-restapi/src/test/java/convex/restapi/test/PeerConfigFileTest.java` — 7 tests

**No files modified** — fully additive, backward compatible.

**PeerConfig.java:**
- Wraps `AMap<AString, ACell>` loaded via `JSON.parseJSON5()`
- Four sections: `peer`, `rest`, `mcp`, `auth` — each accessed via `getSection(key)`
- `static PeerConfig parse(String json5)` — parse from JSON5 string
- `static PeerConfig load(String path)` — load from file
- `static PeerConfig create(AMap<AString, ACell>)` — wrap existing map
- `HashMap<Keyword, Object> toLegacy()` — convert to existing config format

**Typed accessors (all with sensible defaults):**
- **Peer:** `getPeerPort()`, `getKeypairSeed()`, `getStorePath()`, `getPeerUrl()`, `isRestore()` (true), `isPersist()` (true), `isAutoManage()` (true), `getOutgoingConnections()`, `getSource()`, `getTimeout()`
- **REST:** `getRestPort()`, `getBaseUrl()`, `isFaucetEnabled()` (false)
- **MCP:** `isMcpEnabled()` (true), `isSigningEnabled()` (false), `isElevatedEnabled()` (follows signing), `getToolsConfig()`
- **Auth:** `getTokenExpiry()` (86400), `isPublicAccess()` (true)

**Legacy bridge (`toLegacy()`):**
- Maps peer section keys to `Keywords` constants (PORT, URL, STORE, etc.)
- Converts keypair seed hex string to `AKeyPair` instance
- Maps REST keys to flat config (BASE_URL, FAUCET)
- Only sets explicitly configured keys; omitted keys retain legacy defaults

**Example config file** (`config-example.json5`):
- Full JSON5 with comments documenting every option
- All sections present with defaults; optional fields commented out
- Covers peer (port, keypair, store, url, booleans), rest (port, baseUrl, faucet), mcp (enabled, signing, elevated, tools), auth (tokenExpiry, publicAccess, oauth placeholder)

**Tests:**
- PeerConfigTest (41): parse minimal/null, all typed accessors with defaults, all typed accessors with overrides, boolean defaults and overrides, section access, tools config, JSON5 comments and trailing commas, full config round-trip, legacy bridge for all key types (port, keypair→AKeyPair, booleans, strings)
- PeerConfigFileTest (7): example config parses, peer/rest/mcp/auth defaults correct, toLegacy produces expected values, all sections accessible

**Verify:** `mvn test -pl convex-peer -Dtest=PeerConfigTest && mvn test -pl convex-restapi -Dtest=PeerConfigFileTest`

---

## Stage 15: MCP Skills — Prompts and Guided Workflows

**Module:** `convex-restapi`

**Motivation:** MCP tools are low-level primitives. Agents often need multi-step workflows that combine several tools. The MCP spec defines "prompts" — reusable templates that guide AI through common tasks. This stage adds MCP prompts support, exposing high-level skills like "create and fund an account" or "deploy a smart contract" as guided workflows.

**Files to create:**
- `convex-restapi/src/main/java/convex/restapi/mcp/McpPrompt.java` — base class for prompts
- `convex-restapi/src/main/java/convex/restapi/mcp/McpPrompts.java` — prompt registry and handlers
- `convex-restapi/src/main/resources/convex/restapi/mcp/prompts/*.json` — prompt metadata
- `convex-restapi/src/test/java/convex/restapi/test/McpPromptsTest.java`

**Files to modify:**
- `convex-restapi/src/main/java/convex/restapi/mcp/McpAPI.java` — add `prompts/list` and `prompts/get` method handlers, advertise `prompts` capability

**MCP prompts protocol (per spec):**
- `prompts/list` → returns array of prompt metadata (name, description, arguments)
- `prompts/get` → returns rendered prompt messages for a given prompt + arguments

**McpPrompt.java:**
- Abstract base class, mirrors `McpTool` pattern
- `getName()`, `getDescription()`, `getArguments()` — metadata from JSON
- `AVector<AMap<AString, ACell>> render(AMap<AString, ACell> arguments)` — returns message list (role + content)
- Metadata loaded from JSON resources (same pattern as tool JSON files)

**Initial prompts:**

| Prompt | Arguments | Description |
|--------|-----------|-------------|
| `create-account` | `passphrase`, `faucetAmount?` | Guide: create signing key → create on-chain account → request faucet funds |
| `deploy-contract` | `source`, `address`, `passphrase` | Guide: prepare transaction → sign with signing service → submit |
| `transfer-funds` | `from`, `to`, `amount`, `passphrase` | Guide: prepare transfer tx → sign → submit → verify balance |
| `setup-identity` | `passphrase` | Guide: create signing key → generate self-issued JWT → explain usage |
| `explore-account` | `address` | Guide: describe account → lookup key definitions → resolve CNS if applicable |
| `network-status` | _(none)_ | Guide: peer status → describe key accounts → summarise network state |

**Prompt message format (per MCP spec):**
```json
{
  "messages": [
    {
      "role": "user",
      "content": {
        "type": "text",
        "text": "Create a new Convex account with signing key..."
      }
    }
  ]
}
```

Each prompt renders a sequence of messages that instruct the AI which tools to call and in what order, with the specific arguments filled in from the prompt parameters. The AI then executes the workflow by calling the referenced tools.

**McpAPI changes:**
- Add `"prompts"` to capabilities in `buildInitializeResult()`
- Handle `prompts/list` → return prompt metadata
- Handle `prompts/get` → validate prompt name and arguments, call `render()`, return messages
- Prompt registration follows tool registration pattern

**Tests:**
- `prompts/list` returns all registered prompts with metadata
- `prompts/get` with valid prompt + arguments → returns messages
- `prompts/get` with unknown prompt → error
- `prompts/get` with missing required argument → error
- Each prompt renders correct tool references and argument values
- Prompts configurable via RestConfig (`mcp.prompts.enabled`)

**Verify:** `mvn test -pl convex-restapi -Dtest=McpPromptsTest`

---

## Summary

| Stage | Module | Focus | New Files |
|---|---|---|---|
| 1 ✓ | convex-core | HKDF + AES-256-GCM utilities | 4 (20 tests) |
| 2 ✓ | convex-core | Local Lattice + LWWLattice — OwnerLattice + MapLattice(LWW) | 4 + modify (32 tests) |
| 3 ✓ | convex-peer | SigningService — cursor-based key store (AString params) | 2 + modify (16 tests) |
| 4 ✓ | convex-peer | SigningService — sign + JWT | 0 (extend, +9 tests) |
| 5 ✓ | convex-peer | SigningService — elevated ops, plaintext identity index, LWW timestamps | 0 (extend, +12 tests) |
| 6 ✓ | convex-peer | Multi-peer isolation + key rotation | 0 (extend, +2 tests) |
| 7 ✓ | convex-peer | PeerAuth — two-path JWT verification + peer token issuance | 2 + modify (12 tests) |
| 8 ✓ | convex-restapi | AuthMiddleware — optional/required bearer token handlers | 2 + modify (11 tests) |
| 9 ✓ | convex-restapi | MCP signing tools — signingServiceInfo/CreateKey/ListKeys/Sign/GetJWT | 6 + modify (11 tests) |
| 10 ✓ | convex-restapi | MCP tools — elevated + confirmation flow, SigningMcpTools extraction, MCP SDK client tests | 9 + modify (21 tests) |
| 11 | convex-restapi | MCP tools — Convex convenience | modify |
| 12 | convex-restapi | Social login OAuth flow | 3 |
| 13 | convex-restapi | End-to-end integration | 1 |
| 14 ✓ | convex-peer, convex-restapi | Config refactor — JSON5-based PeerConfig, backward compat, example config | 4 (48 tests) |
| 15 | convex-restapi | MCP Skills — prompts and guided workflows | 3 + modify |
