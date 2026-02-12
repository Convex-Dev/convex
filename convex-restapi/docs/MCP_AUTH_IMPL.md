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
| HKDF | **Missing** | Need new utility in `convex.core.crypto` |
| AES-256-GCM | **Missing** | Need new utility in `convex.core.crypto` |
| Auth middleware | **Missing** | No bearer token / JWT verification in restapi |
| SigningService | **Missing** | Core service to be built in `convex-peer` |

---

## Stage 1: Core Crypto — HKDF and AES-256-GCM

**Module:** `convex-core`

**Files to create:**
- `convex-core/src/main/java/convex/core/crypto/HKDF.java`
- `convex-core/src/main/java/convex/core/crypto/AESGCM.java`
- `convex-core/src/test/java/convex/core/crypto/HKDFTest.java`
- `convex-core/src/test/java/convex/core/crypto/AESGCMTest.java`

**HKDF.java:**
- Static utility wrapping BouncyCastle `HKDFBytesGenerator`
- `byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length)`
- Uses SHA-256 as the underlying hash

**AESGCM.java:**
- Static utility for AES-256-GCM authenticated encryption
- `byte[] encrypt(byte[] key, byte[] plaintext)` — generates random 12-byte nonce, prepends to ciphertext
- `byte[] decrypt(byte[] key, byte[] ciphertext)` — extracts nonce from prefix, decrypts
- Uses BouncyCastle or JDK `javax.crypto.Cipher` with `AES/GCM/NoPadding`

**Tests:**
- HKDF: known test vectors from RFC 5869, different inputs produce different outputs
- AESGCM: round-trip encrypt/decrypt, wrong key fails, tampered ciphertext fails (authentication tag check)

**Verify:** `mvn test -pl convex-core -Dtest=HKDFTest,AESGCMTest`

---

## Stage 2: Local Lattice — OwnerLattice Convention

**Module:** `convex-core`

**Files to create:**
- `convex-core/src/main/java/convex/lattice/LocalLattice.java` (or similar — thin wrapper/helper for the `:local` convention)
- `convex-core/src/test/java/convex/lattice/LocalLatticeTest.java`

**Purpose:** Establish the `:local` OwnerLattice convention as a tested core primitive. The signing service and any future peer-local services build on top of this.

**LocalLattice (helper/convention):**
- `:local` keyword constant
- Helper to get/set a peer's owned slot: navigate root → `:local` → OwnerLattice → signed slot for a given peer key
- Helper to initialise `:local` in a root if absent
- Wraps `OwnerLattice<AHashMap<ACell, SignedData<V>>>` with convenience for the per-peer-key pattern

**Tests:**
- Create a root with `:local`, write a value under peer key A, read it back
- Two peer keys writing to the same root — independent slots, no conflict
- Merge two roots with different peer keys — both slots preserved
- Merge two roots where same peer key has different values — latest signed value wins
- Peer A cannot write to peer B's slot (signature verification)
- Round-trip through EtchStore: `setRootData()` → `getRootData()` → `:local` data intact
- Nested structure: peer slot contains `Index<Keyword, ACell>` with sub-keys — verify path navigation works

**Verify:** `mvn test -pl convex-core -Dtest=LocalLatticeTest`

---

## Stage 3: Signing Service — Encrypted Key Store

**Module:** `convex-peer`

**Files to create:**
- `convex-peer/src/main/java/convex/peer/signing/SigningService.java`
- `convex-peer/src/test/java/convex/peer/signing/SigningServiceTest.java`

**SigningService.java — first slice:**
- Constructor takes `AKeyPair peerKeyPair, AStore store`
- `encryptionSecret` management: generate on first start, store encrypted in lattice at `:local → <peerKey> → :signing → :secret`, load on subsequent starts
- `AccountKey createKey(String identity, String passphrase)` — generate keypair, encrypt seed, store in `:keys`, update `:users`
- `List<AccountKey> listKeys(String identity)` — decrypt user index, return public keys
- Internal: `wrapKey()`, `unwrapKey()`, `lookupHash()`, `userIndexKey()`
- Lattice persistence via `store.setRootData()`

**Tests:**
- Create a key, verify it appears in listKeys
- Create multiple keys for same identity
- Create keys for different identities — compartmentalised
- Persist to store, reload from store, keys survive
- encryptionSecret round-trip: generate, store encrypted, reload, decrypt, still works

**Verify:** `mvn test -pl convex-peer -Dtest=SigningServiceTest`

---

## Stage 4: Signing Service — Sign and JWT

**Module:** `convex-peer`

**Extend:** `SigningService.java`, `SigningServiceTest.java`

**New methods:**
- `ABlob sign(String identity, AccountKey publicKey, String passphrase, ABlob bytesToSign)` — decrypt key, sign, zero, return signature
- `String getSelfSignedJWT(String identity, AccountKey publicKey, String passphrase, String audience, Map<String,Object> claims, long lifetime)` — decrypt key, build JWT with `sub=did:key:...`, sign with `JWT.signPublic()`, zero, return

**Tests:**
- Sign bytes, verify signature with public key
- Wrong passphrase → fails
- Wrong identity → fails (different lookup hash)
- getSelfSignedJWT: verify result with `JWT.verifyPublic()`
- getSelfSignedJWT: verify `sub` and `iss` are correct `did:key`
- getSelfSignedJWT: verify `aud` claim when audience provided
- getSelfSignedJWT: verify custom claims merged into payload

**Verify:** `mvn test -pl convex-peer -Dtest=SigningServiceTest`

---

## Stage 5: Signing Service — Elevated Operations

**Module:** `convex-peer`

**Extend:** `SigningService.java`, `SigningServiceTest.java`

**New methods:**
- `AccountKey importKey(String identity, ABlob seed, String passphrase)` — store existing seed
- `ABlob exportKey(String identity, AccountKey publicKey, String passphrase)` — return decrypted seed
- `void deleteKey(String identity, AccountKey publicKey, String passphrase)` — remove from `:keys`, tombstone in `:users`
- `void changePassphrase(String identity, AccountKey publicKey, String oldPass, String newPass)` — decrypt with old, re-encrypt with new, update both `:keys` entries

**Tests:**
- Import a known seed, verify public key matches
- Export a key, verify seed matches what was imported
- Delete a key, verify gone from listKeys, verify tombstone in user index
- Delete + merge with pre-delete state → tombstone wins, key stays deleted
- Change passphrase, verify old passphrase fails, new passphrase works
- Import duplicate public key (same identity, same passphrase) → idempotent or error

**Verify:** `mvn test -pl convex-peer -Dtest=SigningServiceTest`

---

## Stage 6: Signing Service — Multi-Peer and Key Rotation

**Module:** `convex-peer`

**Extend:** `SigningServiceTest.java`

**Focus:** Verify the OwnerLattice isolation and merge properties work correctly with SigningService.

**Tests:**
- Two SigningService instances with different peer keys sharing the same store
- Each creates keys independently — no conflicts
- Each can only see its own keys via listKeys
- Merge the two lattice roots → both peers' data preserved
- One peer cannot decrypt the other's encryptionSecret (wrong peer key)
- Peer key rotation: create service with key A, create signing keys, simulate rotation to key B (re-wrap encryptionSecret), verify all signing keys still accessible

**Verify:** `mvn test -pl convex-peer -Dtest=SigningServiceTest`

---

## Stage 7: Auth — JWT Verification

**Module:** `convex-peer`

**Files to create:**
- `convex-peer/src/main/java/convex/peer/auth/PeerAuth.java`
- `convex-peer/src/test/java/convex/peer/auth/PeerAuthTest.java`

**PeerAuth.java:**
- `String verifyBearerToken(String jwt, AccountKey peerKey)` — returns identity (DID string) or null
  - Try self-issued: decode `kid` from header → `Multikey.decodePublicKey()` → `JWT.verifyPublic(jwt)` → return `sub` claim (must be `did:key:...`)
  - Try peer-signed: `JWT.verifyPublic(jwt, peerKey)` → return `sub` claim (must be `did:web:...`)
  - Check `iat`/`exp` validity
- `String issuePeerToken(AccountKey peerKey, AKeyPair peerKeyPair, String identity, long lifetime)` — create peer-signed JWT with `sub=identity`, `iss=did:web:...`

**Tests:**
- Self-issued JWT: create with known keypair, verify, correct identity returned
- Self-issued JWT: expired → rejected
- Self-issued JWT: tampered signature → rejected
- Self-issued JWT: `kid` doesn't match signing key → rejected
- Peer-signed JWT: issue and verify with peer key → correct identity
- Peer-signed JWT: verify with wrong peer key → rejected
- Peer-signed JWT: expired → rejected
- Identity format: self-issued returns `did:key:...`, peer-signed returns whatever `sub` says

**Verify:** `mvn test -pl convex-peer -Dtest=PeerAuthTest`

---

## Stage 8: REST API — Auth Middleware

**Module:** `convex-restapi`

**Files to create/modify:**
- `convex-restapi/src/main/java/convex/restapi/auth/AuthMiddleware.java`
- `convex-restapi/src/main/java/convex/restapi/RESTServer.java` (add middleware registration)
- `convex-restapi/src/test/java/convex/restapi/auth/AuthMiddlewareTest.java`

**AuthMiddleware.java:**
- Javalin `beforeMatched` handler for protected routes
- Extracts `Authorization: Bearer <jwt>` header
- Calls `PeerAuth.verifyBearerToken()`
- Sets identity on Javalin context attribute (e.g., `ctx.attribute("identity", did)`)
- Returns 401 if no/invalid token

**RESTServer.java changes:**
- Register middleware on MCP and protected API routes
- Leave public routes unprotected (queries, DID docs, `signingServiceInfo`)

**Tests (using existing ARESTTest pattern):**
- Request without bearer token → 401
- Request with valid self-issued JWT → 200, identity set correctly
- Request with valid peer-signed JWT → 200, identity set correctly
- Request with expired JWT → 401
- Request with garbage token → 401
- Public endpoints still accessible without auth

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
| 1 | convex-core | HKDF + AES-256-GCM utilities | 4 |
| 2 | convex-core | Local Lattice — OwnerLattice convention | 2 |
| 3 | convex-peer | SigningService — key store basics | 2 |
| 4 | convex-peer | SigningService — sign + JWT | 0 (extend) |
| 5 | convex-peer | SigningService — elevated ops + tombstones | 0 (extend) |
| 6 | convex-peer | OwnerLattice multi-peer + key rotation | 0 (extend) |
| 7 | convex-peer | PeerAuth — JWT verification | 2 |
| 8 | convex-restapi | Auth middleware | 2 + modify |
| 9 | convex-restapi | MCP tools — core signing | modify + JSON |
| 10 | convex-restapi | MCP tools — elevated + confirmation flow | 3 + modify |
| 11 | convex-restapi | MCP tools — Convex convenience | modify |
| 12 | convex-restapi | Social login OAuth flow | 3 |
| 13 | convex-restapi | End-to-end integration | 1 |
