# Convex Peer Authentication and MCP Signing Service

## Overview

This document specifies two services provided by Convex peers:

1. **Peer Authentication Service** — a general-purpose authentication layer that identifies users across all peer interfaces (MCP, REST API, web UI). Supports social login and Ed25519 signature-based authentication. Can act as an OAuth 2.1 authorisation server for third-party clients.

2. **MCP Signing Service** — an Ed25519 key management and signing service exposed as MCP tools. Built on top of the peer authentication service. Includes a generic signing layer (stores keypairs, signs arbitrary bytes) and a Convex convenience layer (transaction preparation, signing, and submission).

The primary use case is agent-driven key management and transaction signing via MCP. The authentication service is designed to be reusable across all peer interfaces.

Implementation spans three modules:
- **`convex-core`** — lattice primitives (`:local`, `OwnerLattice`), crypto (`JWT`, `Multikey`, HKDF), Etch storage
- **`convex-peer`** — signing service backend (key lifecycle, encryption, lattice persistence, auth token issuance)
- **`convex-restapi`** — API layer (MCP tools, auth endpoints, Convex convenience layer)

## Design Principles

- **Agent-first ergonomics**: After one-time auth, agents operate autonomously. No credentials in chat, no copy-paste, no config files.
- **Generic authentication**: The peer auth service is not MCP-specific. It issues bearer tokens usable across all peer interfaces. Multiple auth methods (social login, Ed25519 signature) serve different use cases.
- **Generic Ed25519 signing**: The core signing service knows nothing about Convex. It stores keys and signs bytes.
- **100% agentic interface**: All signing operations are MCP tools. Sensitive operations require user confirmation via web UI, but the agent orchestrates the full flow.
- **User = Agent**: No permission distinction for standard operations. Sensitive operations require explicit user confirmation via browser.
- **Stateless key access**: The peer never caches passphrases or key material in session state. Every operation that accesses a key includes the passphrase as a parameter. The peer decrypts, operates, and zeros — nothing lingers.
- **Progressive security**: Non-empty passphrase protects against peer data-at-rest compromise. Blank passphrase is a valid default for convenience.

## User Experience (MCP)

### Typical Session — Social Login

```
User:   "Connect to the Convex signing service at peer.example.com"

Agent:  I'll connect you now. Please sign in when the page opens.
        → [Agent interface opens browser: peer.example.com/auth]

        ┌────────────────────────────────┐
        │                                │
        │   Sign in to Convex Peer       │
        │                                │
        │   [Sign in with Google]        │
        │   [Sign in with Apple]         │
        │                                │
        └────────────────────────────────┘

        User signs in → OAuth completes → MCP session authenticated

Agent:  You're connected! You have 2 keys registered.

User:   "Transfer 50 CVX from #42 to #87"

Agent:  [calls transact with address, source, and passphrase]
        Done. Transaction confirmed, hash: 0xdef456...
```

### Typical Session — Signature Auth (Self-Issued JWT)

For agents or services that already hold an Ed25519 keypair, no browser interaction is needed:

```
Agent:  [connects to MCP server]
        [creates self-issued EdDSA JWT: sub=did:key:z6Mkf5rG..., exp=5min]
        [sends JWT as Authorization: Bearer header]
        [peer verifies signature → authenticated as did:key:z6Mkf5rG...]

        Ready. Authenticated as did:key:z6Mkf5rG...

User:   "Transfer 50 CVX from #42 to #87"

Agent:  [calls transact]
        Done. Transaction confirmed, hash: 0xdef456...
```

### Elevated Operation

```
User:   "Export my key for #42, I want to back it up"

Agent:  [calls exportKey — receives confirmation_required]
        For security, please confirm this in your browser.
        → [Agent interface opens: peer.example.com/confirm?token=ct_abc123]

        ┌────────────────────────────────┐
        │                                │
        │   Confirm: Export Key          │
        │                                │
        │   Key: 0xf041...              │
        │                                │
        │   ⚠ This will reveal the      │
        │   private seed for this key.   │
        │                                │
        │   [Confirm]    [Cancel]        │
        │                                │
        └────────────────────────────────┘

        User clicks Confirm → agent retries with confirmToken

Agent:  Here's your seed: 0x5b3c...
        Store this securely — it controls your account.
```

### Passphrase Handling

The passphrase protects keys at rest on the peer — it is not a user-facing login credential. Agents include the passphrase in every tool call that accesses key material.

- **Blank passphrase (default)**: Most users. Zero friction — the agent sends `""` with every request. Keys are still encrypted at rest using the identity and peer secret.
- **Non-blank passphrase**: The user communicates the passphrase to the agent (e.g., in chat or via environment configuration). The agent includes it in every signing request. This adds protection if the peer's key store is compromised — an attacker who knows the identity still cannot derive the wrapping key without the passphrase.

In practice, blank passphrases provide the best agent ergonomics and are sufficient when the peer is trusted at runtime. Non-blank passphrases are an opt-in hardening measure for high-value keys.

## Operation Tiers

### Standard Operations (peerToken only)

Routine operations that agents perform autonomously after initial authentication. No additional user interaction required. Operations that access key material include the passphrase as a parameter — the peer decrypts, operates, and zeros immediately.

| Tool | Description |
|---|---|
| `createKey` | Generate new keypair |
| `listKeys` | List registered public keys |
| `sign` | Sign arbitrary bytes |
| `getSelfSignedJWT` | Create self-issued JWT for external auth |
| `createAccount` | Create keypair + Convex account |
| `listAccounts` | List keys with Convex addresses |
| `transact` | Prepare + sign + submit transaction |

### Elevated Operations (peerToken + user confirmation)

Sensitive or destructive operations that require the user to explicitly confirm via the peer's web UI. These always require a human in the loop — they are unavailable to autonomous agents with no browser access. This is intentional: export, delete, import, and passphrase changes are rare administrative actions, not routine agent operations.

| Tool | Description | Why elevated |
|---|---|---|
| `importKey` | Import an existing seed | Seed material in transit |
| `exportKey` | Reveal a private seed | Exposes secret key material |
| `deleteKey` | Permanently destroy a stored key | Irreversible, potential fund loss |
| `changePassphrase` | Re-encrypt with new passphrase | Changes security parameters |

### Elevated Auth Flow

When an agent calls an elevated tool without a valid `confirmToken`:

**Step 1: Agent makes initial call**
```json
{
  "tool": "exportKey",
  "params": {"publicKey": "0xf041..."}
}
```

**Step 2: Peer returns confirmation required**
```json
{
  "status": "confirmation_required",
  "confirmUrl": "https://peer.example.com/confirm?token=ct_abc123",
  "confirmToken": "ct_abc123"
}
```

**Step 3: Agent interface opens confirmUrl**

The agent interface opens the confirmation URL in the user's browser. The peer's web UI shows the action details and asks for confirmation — exactly what is being authorised (which tool, which key, what will happen).

**Step 4: User confirms in browser**

User clicks "Confirm". Peer marks the `confirmToken` as approved and redirects back.

**Step 5: Agent retries with confirmToken**
```json
{
  "tool": "exportKey",
  "params": {"publicKey": "0xf041...", "confirmToken": "ct_abc123"}
}
```

**Step 6: Peer executes the operation**

Peer verifies the `confirmToken` is approved, single-use, not expired, and matches the exact tool + parameters. Returns the result.

### confirmToken Properties

| Property | Value |
|---|---|
| Format | Opaque random string |
| Lifetime | 5 minutes |
| Usage | Single-use — consumed on successful execution |
| Scope | Bound to specific tool + parameters. Cannot be reused for a different operation. |
| Storage | Server-side, in session store |

## Peer Authentication Service

The peer authentication service is a general-purpose identity layer. It authenticates users, issues session tokens, and can be consumed by any peer interface — MCP tools, REST API endpoints, web UI, or third-party OAuth clients.

Login is purely about proving identity. No passphrase at login.

### Authentication Methods

#### Social Login

The peer acts as an OAuth 2.1 authorisation server. For MCP, the client (agent interface) handles browser redirects per the MCP OAuth 2.1 specification.

```
Client ──→ peer requires auth (HTTP 401)
  │
  ├── Client initiates OAuth 2.1 + PKCE flow
  │         │
  │         ├── Browser opens peer's /auth page
  │         ├── User clicks "Sign in with Google/Apple"
  │         ├── Social provider OAuth flow completes
  │         ├── Peer validates JWT against provider JWKS
  │         ├── Peer issues peer-signed EdDSA JWT (peerToken)
  │         └── Client receives JWT via OAuth redirect
  │
  └── All subsequent calls authenticated via bearer token
```

| Provider | Type | Stable User ID | Registration Required? |
|---|---|---|---|
| Google | OIDC | `sub` claim in ID token | No |
| Apple | OIDC | `sub` claim in ID token | No |
| GitHub | OAuth2 | `id` from `/user` API | Yes (per peer) |
| Discord | OAuth2 | `id` from `/users/@me` API | Yes (per peer) |

Google and Apple require zero registration — the peer validates JWTs against publicly available JWKS endpoints. GitHub and Discord require the peer operator to register an OAuth application.

#### Ed25519 Signature (Self-Issued JWT)

Self-issued EdDSA JWT authentication using an Ed25519 keypair. No browser required — suitable for machine-to-machine auth, agents that already hold key material, and self-sovereign identity without depending on social providers. Compatible with Covia's `KeyPairAuth` pattern.

```
Client creates self-issued EdDSA JWT:
  header:  { "alg": "EdDSA", "kid": "<multikey>" }
  payload: { "sub": "did:key:z6Mk...", "iss": "did:key:z6Mk...", "iat": ..., "exp": ... }

Client sends: Authorization: Bearer <jwt>

Peer verifies:
  1. Decode kid from header → AccountKey via Multikey.decodePublicKey()
  2. Verify Ed25519 signature against decoded key
  3. Check iat/exp (recommended lifetime: ≤ 5 minutes)
  4. Identity = sub claim (did:key)
```

The client creates a fresh short-lived JWT for each request or session. No challenge-response round trip needed — the JWT is self-certifying. The resulting identity is the `did:key` for the signing public key:

```
"did:key:z6Mkf5rGMoatrSj1f4CyvuHBeXJELe9RPdzo2PKGNCKVtZxP"
```

No peerToken is issued — the self-issued JWT serves directly as the bearer token. Each request is independently authenticated.

### Identity Format

Every authenticated user receives a DID as their canonical identity.

**Social login** — `did:web` anchored to the peer, with an `oauth` path segment:

```
"did:web:peer.example.com:oauth:google:118234567890"
"did:web:peer.example.com:oauth:apple:001135.4ae9301edbe64c94adb03e9c9bda155b.1433"
"did:web:peer.example.com:oauth:github:12345"
"did:web:peer.example.com:oauth:discord:157730590492196864"
```

These are peer-specific — the peer is the authority for identities it has authenticated. The peer can serve DID documents for these identities at the standard did:web resolution path (e.g., `/oauth/google/118234567890/did.json`). DID documents for social login identities contain only `id` and `controller` — no `verificationMethod` or `alsoKnownAs`, since linking to keys would leak which keys belong to which social identity.

**Signature auth** — `did:key`, self-certifying:

```
"did:key:z6Mkf5rGMoatrSj1f4CyvuHBeXJELe9RPdzo2PKGNCKVtZxP"
```

Not tied to any peer. Resolvable by anyone with a did:key resolver.

**Identity compartmentalisation:** Social login and signature auth produce different DIDs. A user with both a Google identity and an Ed25519 keypair has two separate key stores — keys created under one identity are not accessible from the other. This is intentional. It prevents social provider compromise from exposing keys held under a self-sovereign identity, and vice versa. Users who want a single unified key store should pick one auth method and stick with it.

### peerToken (Social Login)

The peerToken is the OAuth 2.1 access token issued after successful social login. It is a peer-signed EdDSA JWT — verifiable by any party that knows the peer's public key.

```json
{
  "header": { "alg": "EdDSA", "kid": "z6MkPeerPublicKey..." },
  "payload": {
    "sub": "did:web:peer.example.com:oauth:google:118234567890",
    "iss": "did:web:peer.example.com",
    "iat": 1707744000,
    "exp": 1707830400
  }
}
```

| Property | Value |
|---|---|
| Format | EdDSA JWT signed by peer's Ed25519 key |
| Lifetime | Configurable (recommended: 4–24 hours) |
| Verification | Stateless — any party with peer's public key can verify |
| Revocation | Not supported — use short lifetimes. No server-side token tracking. |
| Multiple tokens | Supported — concurrent sessions for same identity |

For Ed25519 signature auth, no peerToken is issued — the client's self-issued JWT serves directly as the bearer token on each request.

### Consumers

The peerToken is a bearer token accepted across all authenticated peer interfaces:

| Interface | Transport | Notes |
|---|---|---|
| MCP tools | MCP OAuth 2.1 | Primary use case — signing service and Convex convenience layer |
| REST API | `Authorization: Bearer` header | Protected endpoints (e.g., peer admin, future authenticated APIs) |
| Web UI | Session cookie or bearer token | Explorer, admin pages |
| Third-party clients | OAuth 2.1 token exchange | Peer acts as authorisation server for external services |

Unauthenticated access remains available for public endpoints (queries, network info, DID documents, etc.).

## Architecture

### Module Layering

```
┌──────────────────────────────────────────────────────────┐
│  convex-restapi                          (API layer)     │
│                                                          │
│  MCP Tools:                                              │
│    Convex: transact, createAccount, listAccounts         │
│    Signing: createKey, listKeys, sign, getSelfSignedJWT  │
│             importKey, exportKey, deleteKey,              │
│             changePassphrase                             │
│                                                          │
│  Auth Endpoints:                                         │
│    /auth — social login (OAuth 2.1, JWKS)                │
│    /confirm — elevated operation approval                │
│                                                          │
│  Consumers: MCP, REST API, Web UI, third-party OAuth     │
├──────────────────────────────────────────────────────────┤
│  convex-peer                           (service layer)   │
│                                                          │
│  SigningService:                                         │
│    Key lifecycle (create, import, export, delete)         │
│    Sign bytes, build self-issued JWTs                    │
│    Passphrase-based encryption/decryption                │
│    User index management                                 │
│    Audit log                                             │
│    Confirmation token management                         │
│                                                          │
│  Peer Authentication:                                    │
│    Social login → peer-signed JWT issuance               │
│    Self-issued JWT verification                          │
│    encryptionSecret management (generate, wrap, unwrap)        │
│                                                          │
│  Cursor-based state (ACursor<ACell>):                    │
│    Server layer provides cursor to :signing subtree      │
│    SigningService reads/writes through cursor             │
│    Persistence/replication is server layer's choice       │
├──────────────────────────────────────────────────────────┤
│  convex-core                         (primitives layer)  │
│                                                          │
│  Lattice: OwnerLattice, SignedLattice, LocalLattice      │
│  Cursor: ACursor, Root, PathCursor (atomic get/set)      │
│  :local registered in Lattice.ROOT                       │
│  Crypto: Ed25519, JWT, Multikey, HKDF, AESGCM           │
│  Data: Hash, ABlob, Keyword, AVector, AccountKey         │
│  Storage: EtchStore, Stores, setRootData/getRootData     │
└──────────────────────────────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Module | Owns | Does NOT own |
|---|---|---|---|
| **Primitives** | `convex-core` | OwnerLattice, LocalLattice, `:local` in `Lattice.ROOT`, ACursor abstraction, JWT, Multikey, HKDF, AESGCM, Etch storage | Key management logic, auth flows, API |
| **Service** | `convex-peer` | SigningService (cursor-based), key encryption/decryption, `:signing` data structure, encryptionSecret management, auth token issuance | Persistence decisions, cursor construction, MCP tools, HTTP routing |
| **API** | `convex-restapi` | MCP tool handlers, auth endpoints, confirm UI, Convex convenience tools, cursor construction and persistence | Encryption, key storage internals |

The API layer constructs the cursor (choosing persistence strategy) and passes it to the service layer. The service layer uses core primitives. No layer reaches down more than one level.

## Threat Model and Security

### Encryption Secret

The `encryptionSecret` used for all key store encryption is a random 256-bit key, generated once when the signing service is first initialised. It is stored encrypted (AES-256-GCM, keyed by the peer's Ed25519 key via [HKDF](https://datatracker.ietf.org/doc/html/rfc5869)) in the peer's OwnerLattice slot:

```
:local → <peerKey> → :signing → :secret → ABlob (encrypted encryptionSecret)
```

**Lifecycle:**
1. First start: generate random 256-bit `encryptionSecret`, encrypt with `HKDF(peerSeed, info: "convex-signing-secret-v1")`, store in `:secret`
2. Normal start: load `:secret` from lattice, decrypt with current peer key
3. Peer key rotation: decrypt `:secret` with old key, re-encrypt with new key, store in new OwnerLattice slot. All signing keys remain valid — only the wrapper around `encryptionSecret` changes, not the secret itself.

This decouples encryption from peer identity. Peer key rotation requires re-wrapping one value, not re-encrypting every stored key.

### Content-Based Key Encryption

```
wrappingKey = HKDF-SHA256(ikm: encryptionSecret, salt: identity || publicKey || passphrase,
                          info: "convex-signing-service-v1")
lookupHash  = SHA-256(identity || publicKey || passphrase)
```

Where `identity` is the canonical DID (e.g., `"did:web:peer.example.com:oauth:google:118234..."` or `"did:key:z6Mk..."`).

The key store contains only opaque `lookupHash → encryptedSeed` mappings. No metadata.

### Security Factors

| Attack Scenario | Keys Exposed? |
|---|---|
| peerToken stolen (blank passphrase) | Attacker can sign via API until session expires. Cannot export, delete, or change passphrase (requires browser confirmation). |
| peerToken stolen (non-blank passphrase) | No — attacker still needs passphrase to access any key |
| Peer data-at-rest stolen (with passphrase) | No — encrypted seeds require `encryptionSecret` (stored encrypted in lattice) + `identity` + `passphrase` |
| Peer data-at-rest stolen (blank passphrase) | Vulnerable if user identity known |
| Peer runtime compromised | Yes |

### Elevated Auth as Damage Limitation

Even with a stolen peerToken and knowledge of the passphrase, an attacker **cannot**:
- Export key material (needs browser confirmation)
- Delete keys (needs browser confirmation)
- Change passphrases (needs browser confirmation)
- Import malicious keys (needs browser confirmation)

They **can** sign arbitrary bytes until the session expires. This is mitigated by session lifetime, rate limits, and audit logging.

### Session State

**Self-issued JWT auth (Ed25519):** Fully stateless. The peer verifies the JWT signature on each request — no session store entry needed. Identity is extracted from the `sub` claim (`did:key`).

**Peer-signed JWT auth (social login):** Token verification is stateless (check peer signature + expiry). The peer maintains only:

```
confirmations/
  confirmToken → {identity, tool, params, expiresAt, approved}
```

No key material, no passphrases stored in session state. Confirmation tokens are ephemeral and never persisted to disk. JWTs are not tracked server-side — short lifetimes are the only expiry mechanism.

## MCP Tool Specification — Core Signing Service

### Error Handling

All tools return errors as MCP tool result errors per the MCP specification — `isError: true` with a human-readable message. Common error conditions:

| Condition | Message |
|---|---|
| Wrong passphrase | `"Invalid passphrase for key 0xf041..."` |
| Key not found | `"No key found for the given identity and public key"` |
| Key limit exceeded | `"Maximum keys per user (10) reached"` |
| Export disabled | `"Key export is disabled on this peer"` |
| Rate limit hit | `"Rate limit exceeded — try again in 30s"` |
| Auth required | `"Authentication required"` |
| Confirmation required | `{"status": "confirmation_required", "confirmUrl": "...", "confirmToken": "..."}` |

The `confirmation_required` response is not an error — it is a standard response for elevated tools that triggers the browser confirmation flow.

### Public Tools (No Auth)

#### `signingServiceInfo`

Returns peer capabilities.

**Parameters:** None

**Returns:**

```json
{
  "enabled": true,
  "peerIdentity": "0xpeer...",
  "authMethods": {
    "socialProviders": ["google", "apple"],
    "signatureAuth": true
  },
  "exportAllowed": true,
  "maxKeysPerUser": 10,
  "version": "1.0.0"
}
```

### Standard Tools (peerToken Required)

#### `createKey`

Generates a new Ed25519 keypair and stores the encrypted seed.

**Parameters:**

| Name | Type | Required | Default | Description |
|---|---|---|---|---|
| `passphrase` | string | no | `""` | Passphrase for at-rest encryption |

**Returns:**

```json
{
  "publicKey": "0xf041..."
}
```

**Behaviour:**
1. Resolve identity from session
2. Generate Ed25519 keypair
3. Compute wrapping key and lookup hash from identity + publicKey + passphrase
4. Encrypt seed, store in key store
5. Update user index
6. Zero seed material from memory
7. Return public key

#### `listKeys`

Lists the user's registered public keys.

**Parameters:** None

**Returns:**

```json
{
  "keys": [
    {"publicKey": "0xf041..."},
    {"publicKey": "0xa3b2..."}
  ]
}
```

#### `sign`

Signs arbitrary bytes with an Ed25519 key. Passphrase is required to decrypt the key for each request — the peer holds no key material in session state.

**Parameters:**

| Name | Type | Required | Default | Description |
|---|---|---|---|---|
| `publicKey` | string | yes | — | Ed25519 public key to sign with |
| `bytesToSign` | string | yes | — | Data to sign (hex-encoded) |
| `passphrase` | string | no | `""` | Passphrase for key decryption |

**Returns:**

```json
{
  "signature": "0xabc123..."
}
```

**Behaviour:**
1. Resolve identity from session
2. Compute lookup hash from identity + publicKey + passphrase
3. Compute wrapping key via HKDF
4. Decrypt seed from key store (fails if passphrase wrong)
5. Ed25519 sign the bytes
6. Zero seed and wrapping key from memory
7. Log to audit log
8. Return signature

#### `getSelfSignedJWT`

Creates a self-issued EdDSA JWT signed by a managed key. Useful for authenticating to other services (Covia venues, other Convex peers, third-party APIs) that accept `did:key` bearer tokens. Compatible with Covia's `KeyPairAuth` verification.

**Parameters:**

| Name | Type | Required | Default | Description |
|---|---|---|---|---|
| `publicKey` | string | yes | — | Ed25519 public key to sign with |
| `passphrase` | string | no | `""` | Passphrase for key decryption |
| `audience` | string | no | — | `aud` claim — the intended recipient (e.g., `"did:web:venue.example.com"`) |
| `claims` | object | no | `{}` | Additional claims to include in payload |
| `lifetime` | integer | no | `86400` | Token lifetime in seconds (default 24 hours, user's choice — no server-enforced maximum) |

**Returns:**

```json
{
  "jwt": "eyJhbGciOiJFZERTQSIs...",
  "did": "did:key:z6Mkf5rGMoatrSj1f4CyvuHBeXJELe9RPdzo2PKGNCKVtZxP",
  "expiresAt": "2026-02-12T15:00:00Z"
}
```

**Behaviour:**
1. Resolve identity from session
2. Compute lookup hash, decrypt key using passphrase
3. Construct JWT: `sub` = `did:key:<multikey>`, `iss` = same, `iat` = now, `exp` = now + lifetime, `aud` = audience (if provided)
4. Merge any additional `claims` into payload
5. Sign with `JWT.signPublic(claims, keyPair)`
6. Zero key material
7. Return JWT string and DID

This enables agents to use keys managed by the peer's signing service to authenticate to external services — creating a bridge between social-login-based key management and the broader federated ecosystem.

### Elevated Tools (peerToken + confirmToken)

All elevated tools follow the same pattern:
1. Agent calls the tool without a `confirmToken`
2. Peer returns `confirmation_required` with a `confirmUrl` and `confirmToken`
3. Agent redirects user to `confirmUrl`
4. User confirms in browser
5. Agent retries the tool call with the `confirmToken`
6. Peer executes the operation

Every elevated tool accepts an optional `confirmToken` parameter. If absent or invalid, the peer returns the confirmation flow. If valid and approved, the operation executes.

#### `importKey`

Imports an existing Ed25519 seed. Elevated because seed material is in transit.

**Parameters:**

| Name | Type | Required | Default | Description |
|---|---|---|---|---|
| `seed` | string | yes | — | Ed25519 seed (hex-encoded) |
| `passphrase` | string | no | `""` | Passphrase for encryption |
| `confirmToken` | string | no | — | Confirmation token from web UI |

**Returns (without confirmToken):**

```json
{
  "status": "confirmation_required",
  "confirmUrl": "https://peer.example.com/confirm?token=ct_abc123",
  "confirmToken": "ct_abc123"
}
```

**Returns (with valid confirmToken):**

```json
{
  "publicKey": "0xf041..."
}
```

#### `exportKey`

Returns the decrypted seed. Elevated because it exposes secret key material. Only available if peer allows export.

**Parameters:**

| Name | Type | Required | Default | Description |
|---|---|---|---|---|
| `publicKey` | string | yes | — | Ed25519 public key |
| `passphrase` | string | no | `""` | Passphrase for key decryption |
| `confirmToken` | string | no | — | Confirmation token from web UI |

**Returns (with valid confirmToken):**

```json
{
  "seed": "0x5b3c...",
  "publicKey": "0xf041..."
}
```

#### `deleteKey`

Permanently destroys a stored encrypted seed. Elevated because it is irreversible.

**Parameters:**

| Name | Type | Required | Default | Description |
|---|---|---|---|---|
| `publicKey` | string | yes | — | Ed25519 public key |
| `passphrase` | string | no | `""` | Passphrase for key decryption (to verify ownership) |
| `confirmToken` | string | no | — | Confirmation token from web UI |

**Returns (with valid confirmToken):** `{"deleted": true}`

#### `changePassphrase`

Re-encrypts a key with a new passphrase. Elevated because it changes security parameters.

**Parameters:**

| Name | Type | Required | Default | Description |
|---|---|---|---|---|
| `publicKey` | string | yes | — | Ed25519 public key |
| `passphrase` | string | no | `""` | Current passphrase |
| `newPassphrase` | string | yes | — | New passphrase for re-encryption |
| `confirmToken` | string | no | — | Confirmation token from web UI |

**Returns (with valid confirmToken):** `{"updated": true}`

## MCP Tool Specification — Convex Convenience Layer

These tools build on the core signing service and add Convex-specific functionality.

#### `transact`

Full convenience — resolves signing key, prepares, signs, and submits.

**Parameters:**

| Name | Type | Required | Default | Description |
|---|---|---|---|---|
| `address` | string | yes | — | Convex address (e.g., `"#42"`) |
| `source` | string | yes | — | CVX source code |
| `passphrase` | string | no | `""` | Passphrase for key decryption |

**Returns:**

```json
{
  "result": "...",
  "hash": "0xdef456..."
}
```

**Behaviour:**
1. Query Convex network for public key of address
2. Verify key is managed by signing service for this identity
3. Prepare transaction
4. Decrypt key using passphrase, sign transaction bytes, zero key material
5. Submit signed transaction
6. Return result

#### `createAccount`

Creates a keypair and a Convex account in one step.

**Parameters:**

| Name | Type | Required | Default | Description |
|---|---|---|---|---|
| `faucet` | integer | no | `0` | Initial balance from faucet (copper) |
| `passphrase` | string | no | `""` | Passphrase for key encryption |

**Returns:**

```json
{
  "address": "#42",
  "publicKey": "0xf041..."
}
```

#### `listAccounts`

Lists keys with their associated Convex addresses.

**Parameters:** None

**Returns:**

```json
{
  "accounts": [
    {"publicKey": "0xf041...", "addresses": ["#42", "#108"]},
    {"publicKey": "0xa3b2...", "addresses": ["#87"]}
  ]
}
```

## Storage Design — Lattice-Backed Signing Database

All persistent signing service state is stored in a `:local` subtree of the peer's lattice using Convex immutable data structures. The `:local` subtree uses `OwnerLattice` keyed by peer public key — each peer signs its own slot, so multiple peers can safely share a store and replication never conflicts.

Signing data is encrypted with the owning peer's `encryptionSecret`, so even in a shared store, one peer cannot read another's key material. The `OwnerLattice` provides structural separation and cryptographic attribution; the encryption provides confidentiality.

The `:local` subtree may live in the peer's main Etch store or in a separate store — the lattice structure is the same either way. Using a separate store is a deployment option for isolation, not an architectural requirement.

### Lattice Structure

```
:local → OwnerLattice<AccountKey, Index<Keyword, ACell>>
│
├── <peer-key-A> → Signed<Index<Keyword, ACell>>
│   │
│   ├── :signing → Index<Keyword, ACell>
│   │   ├── :secret → ABlob (encryptionSecret, encrypted with peer key)
│   │   │
│   │   ├── :keys → Index<Hash, ABlob>
│   │   │     key:   lookupHash — SHA-256(identity ‖ publicKey ‖ passphrase)
│   │   │     value: encryptedSeed — AES-256-GCM ciphertext (includes nonce)
│   │   │
│   │   ├── :users → Index<Hash, ABlob>
│   │   │     key:   identityHash — SHA-256(canonical DID string)
│   │   │     value: encrypted payload — AES-256-GCM
│   │   │            plaintext: AVector<AccountKey> (list of public keys)
│   │   │            wrapping key: HKDF(encryptionSecret, identity)
│   │   │
│   │   ├── :audit → Index<ABlob, AVector<ACell>>
│   │   │     key:   12-byte Blob (8-byte epoch millis BE + 4-byte counter BE)
│   │   │     value: [tool, identity, publicKey, tokenFingerprint, bytesHash]
│   │   │
│   │   └── :version → CVMLong (schema version, currently 1)
│   │
│   └── (future peer-local services can add sibling keys here)
│
├── <peer-key-B> → Signed<Index<Keyword, ACell>>
│   └── :signing → ...  (independent, non-conflicting)
│
└── ...
```

**`:local`** uses `OwnerLattice` — each peer writes only to its own slot, signed with its peer key. Merge takes the latest signed value per owner. This enables:

- **Shared store** — multiple peers coexist in the same Etch store without conflict
- **Safe replication** — `:local` can be replicated between peers for fault tolerance; each peer's data is signed and can't be tampered with by others
- **Independent encryption** — each peer has its own `encryptionSecret` (stored encrypted with its peer key); peers sharing a store cannot read each other's key material

**`:signing`** is the signing service's subtree within each peer's slot. Other peer-local services can add sibling keys without collision.

All `Index` keys are `ABlobLike` types (`Hash`, `ABlob`, `Keyword`) — compatible with the radix tree implementation.

### Data Structures

**`:keys` — Encrypted Key Store**

`Index<Hash, ABlob>` mapping lookup hashes to encrypted seeds.

- Key: `Hash.of(identity ‖ publicKey ‖ passphrase)` — 32-byte SHA-256
- Value: `Blob.wrap(ciphertext)` — AES-256-GCM encrypted Ed25519 seed (nonce prepended)
- Wrapping key: `HKDF(encryptionSecret, salt: identity ‖ publicKey ‖ passphrase, info: "convex-signing-service-v1")`

No metadata stored alongside the ciphertext. The lookup hash is the only way to find a key — you must know identity, public key, and passphrase to compute it.

**`:users` — User Index**

`Index<Hash, ABlob>` mapping identity hashes to encrypted key lists.

- Key: `Hash.of(canonicalDID)` — 32-byte SHA-256 of the DID string
- Value: `Blob.wrap(ciphertext)` — AES-256-GCM encrypted `AVector<AccountKey>`
- Wrapping key: `HKDF(encryptionSecret, salt: identity, info: "convex-user-index-v1")`

Enables `listKeys` — the peer decrypts the user's key list using only their identity (no passphrase needed, since this reveals public keys, not secrets). On data-at-rest compromise, reveals key ownership but not key material.

**Tombstones:** When a key is deleted, the user index retains the public key marked with a tombstone (e.g., a `nil` or sentinel value in the `AVector`). This ensures that deletion survives lattice merge — without a tombstone, merging with a replica that still has the key would resurrect it. The `:keys` Index entry is removed (`dissoc`), but the tombstone in `:users` prevents the key from reappearing in `listKeys` after a merge.

**`:audit` — Audit Log (Optional)**

`Index<ABlob, AVector<ACell>>` with chronologically sortable keys. Audit logging is optional — configurable by the peer operator. When enabled:

- Key: 12-byte `Blob` — 8-byte big-endian epoch millis + 4-byte big-endian counter. Radix tree ordering = chronological order.
- Value: `AVector` of `[tool, identity, publicKey, tokenFingerprint, bytesHash]`
- `identity` is the caller's DID (`did:key:...` or `did:web:...`)
- Append-only — entries are never modified or deleted

**`:version` — Schema Version**

`CVMLong` for future schema migrations. Currently `1`.

### Cursor-Based Access Pattern

The `SigningService` operates on an `ACursor<ACell>` pointing at the `:signing` subtree. This decouples key management from persistence — the server layer controls how the cursor is backed (EtchStore, in-memory `Root`, OwnerLattice path, etc.). Follows the same pattern as `convex.lattice.kv.LatticeKV` / `KVDatabase`.

```java
// Server layer constructs the cursor (choice of persistence belongs here)
// Option A: in-memory (testing, ephemeral)
ACursor<ACell> cursor = new Root<>((ACell) null);

// Option B: path into EtchStore-backed lattice root
// Root<ACell> latticeRoot = ...;  // backed by EtchStore
// ACursor<ACell> cursor = latticeRoot.path(LOCAL, peerKey, SIGNING);

// SigningService operates through the cursor — doesn't know or care about backing
SigningService svc = new SigningService(peerKeyPair, cursor);
svc.init();  // generates or loads encryptionSecret via cursor

// Key operations read/write through the cursor atomically
AccountKey pk = svc.createKey("did:key:alice", "passphrase");
List<AccountKey> keys = svc.listKeys("did:key:alice");
byte[] seed = svc.loadKey("did:key:alice", pk, "passphrase");
```

The cursor provides `get()`, `set()`, and `updateAndGet()` with `AtomicReference` semantics. The `SigningService` uses `updateAndGet()` for mutations, ensuring thread-safe read-modify-write.

### Write-Through Persistence

Persistence is the server layer's responsibility. The server can flush the cursor's backing store after each operation, batch writes, or rely on the cursor's own persistence semantics. The `SigningService` itself is persistence-agnostic — it writes through the cursor and trusts the cursor to be durable.

### Concurrency

The cursor abstraction provides atomic updates via `updateAndGet()`. All mutations go through this, so concurrent access is safe without external locking. Read operations (`get()`) return immutable snapshots — Convex data structures are persistent/immutable, so readers never see partial writes.

### Backup, Recovery, and Replication

Persistence and replication are the server layer's responsibility. When backed by an Etch store, the store is self-consistent at all times — the root hash in the file header points to a complete, content-addressed tree. The `:local` subtree travels with the store.

| Operation | Method |
|---|---|
| Backup | Copy the Etch file (file-level lock during copy) |
| Restore | Replace the Etch file, restart peer |
| Merge | Lattice merge on `:local` — OwnerLattice takes latest signed value per peer key, no conflicts |
| Verify | Walk the tree — content-addressed nodes are self-verifying; OwnerLattice entries are signature-verified |

Because `:local` uses `OwnerLattice`, replication is safe:

- **Fault tolerance** — replicate `:local` between peers sharing a signing service. If one peer goes down, its encrypted signing data is still available in the shared store. Only that peer's `encryptionSecret` (which requires the peer key to unwrap) can decrypt it, so a replacement peer with the same keypair can resume service.
- **No conflicts** — each peer writes only to its own signed slot. Merge is per-owner, latest-signed-value wins.
- **No leakage** — encrypted data is opaque to other peers. OwnerLattice provides structural separation; AES-256-GCM provides confidentiality.
- **Selective sync** — peers can choose whether to replicate `:local` or keep it store-local. The lattice structure works either way.

### Confirmation Store (In-Memory Only)

Pending confirmations and token revocations are **not** stored in the lattice — they are ephemeral:

```
confirmations/
  confirmToken → {identity, tool, params, expiresAt, approved}

revoked-tokens/  (optional)
  jti → expiresAt
```

Server restart invalidates all pending confirmations. This is intentional — confirmations are short-lived (5 minutes). JWTs are stateless and not tracked server-side.

## Peer Operator Configuration

| Setting | Description | Recommended |
|---|---|---|
| `supportedProviders` | Social login providers to accept | `["google", "apple"]` |
| `signatureAuthEnabled` | Allow Ed25519 self-issued JWT auth | `true` |
| `maxKeysPerUser` | Key store limit | 10 |
| `exportAllowed` | Whether `exportKey` is available | `true` |
| `sessionLifetime` | Session duration | 4–24 hours |
| `confirmTokenLifetime` | How long user has to confirm elevated ops | 5 minutes |
| `faucetEnabled` | Whether `createAccount` can fund from faucet | Testnet only |
| `auditEnabled` | Log signing operations to lattice | `true` |
| `rateLimits` | Per-session signing rate limits | 100 signs/hour |

### Peer Web UI

The peer must serve:

| Path | Purpose |
|---|---|
| `/auth` | Authentication page. Social login buttons, OAuth flow, peer-signed JWT issuance. |
| `/confirm` | Elevated operation confirmation. Accepts `?token=ct_...`. Shows action details, confirm/cancel. For `changePassphrase`, includes new passphrase entry field. |

`/auth` is a browser-facing page for social login. `/confirm` is a browser-facing page for elevated operation approval. Ed25519 signature auth requires no web UI — clients present self-issued JWTs directly as bearer tokens.

## Implementation Notes

### Primitives (convex-core)

Crypto and lattice primitives used by the service layer. These exist (or will be added) in `convex-core` and are shared with Covia.

| Primitive | Algorithm | Class / Package |
|---|---|---|
| Ed25519 signing | Ed25519 | `convex.core.crypto` |
| JWT signing/verification | EdDSA | `convex.core.json.JWT` — `signPublic()`, `verifyPublic()` |
| Multikey encoding | Ed25519 → multibase | `convex.core.crypto.util.Multikey` — `encodePublicKey()`, `decodePublicKey()` |
| HKDF key derivation | [HKDF](https://datatracker.ietf.org/doc/html/rfc5869)-SHA256 | `convex.core.crypto.HKDF` — `derive(ikm, salt, info, length)`, `derive256()` |
| AES-256-GCM | Symmetric authenticated encryption | `convex.core.crypto.AESGCM` — `encrypt(key, plaintext)`, `decrypt(key, data)` |
| OwnerLattice | Per-owner signed data | `convex.lattice.generic.OwnerLattice` |
| LocalLattice | `:local` OwnerLattice convention | `convex.lattice.LocalLattice` — registered in `Lattice.ROOT`, helpers for per-peer slot access |
| ACursor | Atomic mutable reference | `convex.lattice.cursor.ACursor` — `get()`, `set()`, `updateAndGet()`, `path()` |
| Etch storage | Content-addressed persistence | `convex.etch.EtchStore`, `convex.core.store.Stores` |

### Service Layer (convex-peer)

The signing service backend. Owns key lifecycle and encryption. Operates on an `ACursor<ACell>` — decoupled from persistence/replication, which is the server layer's responsibility. Follows the `LatticeKV` / `KVDatabase` cursor pattern.

**SigningService** — `convex.peer.signing.SigningService`:
- Constructor: `SigningService(AKeyPair peerKeyPair, ACursor<ACell> cursor)`
- `init()` — generates or loads `encryptionSecret` via cursor
- Key lifecycle: `createKey`, `listKeys`, `loadKey` (import, export, delete, changePassphrase in Stage 5)
- Sign bytes, build self-issued JWTs (Stage 4)
- `encryptionSecret` management: generate random 256-bit key on first start, store encrypted with peer key in cursor
- Wrapping key derivation: `HKDF-SHA256(ikm: encryptionSecret, salt: identity ‖ publicKey ‖ passphrase, info: "convex-signing-service-v1")`
- Lookup hash: `SHA-256(identity ‖ publicKey ‖ passphrase)`
- User index encryption: `HKDF(encryptionSecret, salt: identity, info: "convex-user-index-v1")`
- Audit log append (Stage 5)
- Memory safety: zero seed material and wrapping keys after each operation

**Peer Authentication responsibilities:**
- Social login: OAuth 2.1 flow, JWKS validation, peer-signed JWT issuance
- Self-issued JWT verification (Ed25519 signature auth)
- JWKS caching (24-hour TTL, refresh on failure): Google (`googleapis.com/.../certs`), Apple (`appleid.apple.com/auth/keys`)

### API Layer (convex-restapi)

MCP tool handlers and HTTP endpoints. Thin layer that delegates to the service.

**MCP tools** — map 1:1 to SigningService methods. Each tool handler:
1. Extracts identity from bearer token (self-issued or peer-signed JWT)
2. Validates parameters
3. Calls the corresponding SigningService method
4. Returns the result

**Auth endpoints:**

| Path | Layer | Purpose |
|---|---|---|
| `/auth` | restapi | Browser page for social login → delegates to peer auth service |
| `/confirm` | restapi | Browser page for elevated op confirmation → updates in-memory confirmation store |

**Convex convenience tools** (`transact`, `createAccount`, `listAccounts`) — combine SigningService calls with Convex network operations. Implemented in restapi since they depend on the peer's network connection.

Ed25519 signature auth requires no endpoint — clients present self-issued JWTs directly as bearer tokens, verified by the service layer.

### Covia Compatibility

The `convex-core` primitives are shared with Covia's authentication system:

| Convex Peer | Covia Venue | Shared `convex-core` Class |
|---|---|---|
| Self-issued JWT verification | `AuthMiddleware.tryVerifySelfIssued()` | `JWT.verifyPublic(jwt)` |
| Peer-signed JWT issuance | Venue-signed JWT issuance | `JWT.signPublic(claims, keyPair)` |
| `did:key` identity encoding | `KeyPairAuth.didKey` | `Multikey.encodePublicKey(ak)` |

Covia's `AuthMiddleware` uses the same two-path verification:
1. **Self-issued JWT** — `kid` header matches `did:key` in `sub` claim → verify with decoded key
2. **Venue-signed JWT** — verify with venue's known public key → trust `sub` claim

This ensures:
- Self-issued JWTs work identically across Convex peers and Covia venues
- Peer-signed JWTs can be verified by Covia venues that know the peer's public key
- `getSelfSignedJWT` produces tokens directly consumable by Covia's `KeyPairAuth` verification path

### Recommended Agent Behaviour

1. Check `signingServiceInfo` on first connection to discover capabilities
2. Authenticate:
   - **Interactive agents** (user present): MCP client handles OAuth flow, opening a browser for social login → peer issues peer-signed JWT
   - **Autonomous agents** (no user present): Create a self-issued EdDSA JWT with a locally held keypair — no browser needed, no token exchange
3. Call `listKeys` or `listAccounts` after auth to discover available keys
4. Use blank passphrase (`""`) by default. If the user has set a passphrase, they will need to communicate it to the agent — remember it for the duration of the conversation
5. Prefer `transact` for Convex operations — handles preparation, signing, and submission in one call
6. Use core `sign` for non-transaction signing (e.g., DID authentication, off-chain proofs)
7. Use `getSelfSignedJWT` to create JWTs for authenticating to external services (other peers, Covia venues)
8. For elevated operations: when receiving `confirmation_required`, open the `confirmUrl` in the user's browser, wait for confirmation, then retry the tool call with the `confirmToken`
9. Agents should never ask users to type private keys, seeds, or sensitive cryptographic material in chat — use `createKey` or the elevated `importKey` flow instead

### User Migration

1. `exportKey` on old peer → `importKey` on new peer (both require confirmation)
2. Create new key on new peer → `set-key` on-chain → transfer assets
3. Create fresh account on new peer, transfer assets

### Future Enhancements

- **Third-party OAuth clients**: Peer as authorisation server for external services (dApps, Covia venues). Requires scoped tokens and client registration.
- **Per-token policy layer**: Spending limits and function whitelists per peerToken
- **TEE-based signing**: Trusted Execution Environment for runtime protection
- **Multi-party computation**: Split keys for high-value accounts
- **Hardware token support**: WebAuthn/passkey as passphrase alternative
