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

## Stage 9: MCP Tools — Core Signing

**Module:** `convex-restapi`

**Files to create/modify:**
- `convex-restapi/src/main/java/convex/restapi/mcp/McpAPI.java` (add new tools)
- Tool JSON definitions in `convex-restapi/src/main/resources/convex/restapi/mcp/tools/`
- `convex-restapi/src/test/java/convex/restapi/test/SigningMcpTest.java`

**New MCP tools (standard tier):**
- `signingServiceInfo` — no auth required, returns capabilities
- `createKey` — calls `SigningService.createKey()`
- `listKeys` — calls `SigningService.listKeys()`
- `sign` — calls `SigningService.sign()`
- `getSelfSignedJWT` — calls `SigningService.getSelfSignedJWT()`

Each tool handler: extract identity from context attribute, extract params, delegate to SigningService, format response.

**Tests:**
- `signingServiceInfo` returns expected structure
- Authenticate → `createKey` → `listKeys` → verify key appears
- Authenticate → `createKey` → `sign` → verify signature externally
- Authenticate → `createKey` → `getSelfSignedJWT` → verify JWT externally
- Tool call without auth → error
- Wrong passphrase → MCP error response with `isError: true`

**Verify:** `mvn test -pl convex-restapi -Dtest=SigningMcpTest`

---

## Stage 10: MCP Tools — Elevated Operations and Confirmation Flow

**Module:** `convex-restapi`

**Files to create/modify:**
- `convex-restapi/src/main/java/convex/restapi/auth/ConfirmationService.java`
- Add elevated tools to `McpAPI.java`
- `convex-restapi/src/main/java/convex/restapi/web/ConfirmPage.java` (or similar)
- `convex-restapi/src/test/java/convex/restapi/test/ElevatedMcpTest.java`

**ConfirmationService.java:**
- In-memory store: `confirmToken → {identity, tool, params, expiresAt, approved}`
- `createConfirmation(identity, tool, params)` → returns confirmToken + confirmUrl
- `approveConfirmation(confirmToken)` → marks as approved
- `validateConfirmation(confirmToken, identity, tool, params)` → returns true if approved, matches, not expired

**Elevated MCP tools:**
- `importKey`, `exportKey`, `deleteKey`, `changePassphrase`
- Without confirmToken → return `confirmation_required` response
- With valid confirmToken → execute via SigningService

**Confirm endpoint:**
- `GET /confirm?token=ct_...` — renders confirmation page showing action details
- `POST /confirm?token=ct_...` — approves the confirmation

**Tests:**
- Call exportKey without confirmToken → `confirmation_required` response with URL
- Approve confirmation via POST → retry exportKey with confirmToken → succeeds
- Expired confirmToken → rejected
- Reused confirmToken → rejected (single-use)
- confirmToken for different tool/params → rejected (scope-bound)
- Full round-trip: createKey → exportKey (confirm) → importKey (confirm) on different identity

**Verify:** `mvn test -pl convex-restapi -Dtest=ElevatedMcpTest`

---

## Stage 11: MCP Tools — Convex Convenience Layer

**Module:** `convex-restapi`

**Files to modify:**
- Add tools to `McpAPI.java`
- `convex-restapi/src/test/java/convex/restapi/test/ConvexMcpTest.java`

**New MCP tools:**
- `transact` — resolve key for address, prepare tx, sign, submit
- `createAccount` — createKey + create Convex account (+ optional faucet)
- `listAccounts` — listKeys + query network for addresses per key

**Tests:**
- `createAccount` with faucet → returns address + public key, account exists on network
- `listAccounts` → shows the created account
- `transact` with created account → executes CVX code, returns result
- `transact` with wrong passphrase → error
- `transact` with address not managed by this identity → error

**Verify:** `mvn test -pl convex-restapi -Dtest=ConvexMcpTest`

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
| 9 | convex-restapi | MCP tools — core signing | modify + JSON |
| 10 | convex-restapi | MCP tools — elevated + confirmation flow | 3 + modify |
| 11 | convex-restapi | MCP tools — Convex convenience | modify |
| 12 | convex-restapi | Social login OAuth flow | 3 |
| 13 | convex-restapi | End-to-end integration | 1 |
