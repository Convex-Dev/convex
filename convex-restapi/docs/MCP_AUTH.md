# Convex MCP Signing Service — Design Specification

## Overview

An Ed25519 key management and signing service that Convex peer operators can provide as an optional MCP module. Users authenticate through a browser redirect to the peer's web UI, then manage keypairs and request signatures entirely through MCP-capable agents.

The core service is a **generic Ed25519 signing service** — it stores keypairs, manages access via passphrases, and signs arbitrary bytes. It has no knowledge of Convex transactions, addresses, or blockchain state. A separate **Convex convenience layer** builds on top to provide transaction preparation, signing, and submission in a single call.

This document is intended as input for an implementation plan. The implementor will have full access to the Convex peer source code and the existing MCP server infrastructure.

## Design Principles

- **Agent-first ergonomics**: After one-time browser auth, agents operate autonomously. No credentials in chat, no copy-paste, no config files.
- **100% agentic interface**: All operations are MCP tools. Sensitive operations require user confirmation via web UI redirect, but the agent orchestrates the full flow.
- **Generic Ed25519**: The core signing service knows nothing about Convex. It stores keys and signs bytes.
- **No provider registration**: Peers validate social login JWTs using providers' public JWKS endpoints. No OAuth app registration required for Google/Apple.
- **User = Agent**: No permission distinction for standard operations. Sensitive operations require explicit user confirmation via browser.
- **Passphrase via secure UI**: Passphrases are entered in the peer's web UI via redirect, never in agent chat.
- **Progressive security**: Non-empty passphrase protects against peer data-at-rest compromise. Blank passphrase is a valid default.

## User Experience

### Typical Session

```
User:   "Connect to the Convex signing service at peer.example.com"

Agent:  I'll connect you now. Please sign in when the page opens.
        → [Browser opens: peer.example.com/auth]

        ┌────────────────────────────────┐
        │                                │
        │   Sign in to Convex Peer       │
        │                                │
        │   [Sign in with Google]        │
        │   [Sign in with Apple]         │
        │                                │
        └────────────────────────────────┘

        User clicks "Sign in with Google" → OAuth → redirect back

Agent:  You're connected! You have 2 keys, both ready to use.

User:   "Transfer 50 CVX from #42 to #87"

Agent:  [calls transact — no passphrase, no extra auth needed]
        Done. Transaction confirmed, hash: 0xdef456...
```

### Elevated Operation

```
User:   "Export my key for #42, I want to back it up"

Agent:  For security, I need you to confirm this in the browser.
        → [Browser opens: peer.example.com/confirm?token=ct_abc123]

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

        User clicks Confirm → redirect back

Agent:  Here's your seed: 0x5b3c...
        Store this securely — it controls your account.
```

## Operation Tiers

### Standard Operations (peerToken only)

Routine operations that agents perform autonomously after initial authentication. No additional user interaction required.

| Tool | Description |
|---|---|
| `createKey` | Generate new keypair |
| `listKeys` | List registered public keys |
| `unlock` | Unlock a key (via web UI passphrase entry) |
| `lock` | Lock a key for the session |
| `sign` | Sign arbitrary bytes |
| `listTokens` | List active sessions |
| `revokeToken` | Revoke a session |
| `createAccount` | Create keypair + Convex account |
| `listAccounts` | List keys with Convex addresses |
| `transact` | Prepare + sign + submit transaction |

### Elevated Operations (peerToken + user confirmation)

Sensitive or destructive operations that require the user to explicitly confirm via the peer's web UI. These are not typical agent actions — they involve revealing secrets, destroying keys, or changing security parameters.

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

**Step 3: Agent redirects user to confirmUrl**

The peer's web UI displays the action details and asks for confirmation. The page shows exactly what is being authorised (which tool, which key, what will happen).

**Step 4: User confirms in browser**

User clicks "Confirm". Peer marks the `confirmToken` as approved and redirects back to the agent.

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

## Authentication

### Flow

```
Agent ──→ MCP connect to peer ──→ peer requires auth
  │
  ├── Agent redirects user to peer.example.com/auth
  │         │
  │         ├── User clicks "Sign in with Google/Apple"
  │         ├── OAuth flow completes
  │         ├── Peer validates JWT against provider JWKS
  │         ├── Peer creates session, issues peerToken
  │         └── Redirect back to agent with peerToken
  │
  └── Agent receives peerToken via callback
      All subsequent MCP calls authenticated via bearer token
```

Login is purely about proving identity. No passphrase at login.

### Social Login Provider Compatibility

| Provider | Type | Stable User ID | Registration Required? |
|---|---|---|---|
| Google | OIDC | `sub` claim in ID token | No |
| Apple | OIDC | `sub` claim in ID token | No |
| GitHub | OAuth2 | `id` from `/user` API | Yes (per peer) |
| Discord | OAuth2 | `id` from `/users/@me` API | Yes (per peer) |

Google and Apple require zero registration — the peer validates JWTs against publicly available JWKS endpoints. GitHub and Discord require the peer operator to register an OAuth application.

### Identity Format

```
"google:118234567890"
"apple:001135.4ae9301edbe64c94adb03e9c9bda155b.1433"
"github:12345"
"discord:157730590492196864"
```

### peerToken

| Property | Value |
|---|---|
| Format | Opaque random string (256-bit, base64url-encoded) |
| Lifetime | Configurable (recommended: 4–24 hours) |
| Revocation | Instant — delete from session store |
| Multiple tokens | Supported — concurrent sessions for same identity |

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  Convex Convenience Layer                                │
│                                                          │
│  transact(address, source)                               │
│  createAccount(faucet?)                                  │
│  listAccounts()                                          │
├──────────────────────────────────────────────────────────┤
│  Core Signing Service (Generic Ed25519)                  │
│                                                          │
│  Standard:                                               │
│    createKey, listKeys, unlock, lock, sign               │
│    listTokens, revokeToken                               │
│                                                          │
│  Elevated (requires user confirmation via web UI):       │
│    importKey, exportKey, deleteKey, changePassphrase     │
├──────────────────────────────────────────────────────────┤
│  Authentication                                          │
│                                                          │
│  Social login via peer web UI (Google, Apple, ...)       │
│  peerToken issuance and session management               │
│  confirmToken issuance and verification                  │
└──────────────────────────────────────────────────────────┘
```

## Threat Model and Security

### Content-Based Key Encryption

```
wrappingKey = HKDF(peerSecret, socialID || publicKey || passphrase)
lookupHash  = hash(socialID || publicKey || passphrase)
```

The key store contains only opaque `lookupHash → encryptedSeed` mappings. No metadata.

### Security Factors

| Attack Scenario | Keys Exposed? |
|---|---|
| peerToken stolen (key unlocked) | Attacker can sign via API until session expires. Cannot export, delete, or change passphrase (requires browser confirmation). |
| peerToken stolen (key locked) | No — still needs passphrase |
| Peer data-at-rest stolen (with passphrase) | No |
| Peer data-at-rest stolen (blank passphrase) | Vulnerable if user identity known |
| Peer runtime compromised | Yes |

### Elevated Auth as Damage Limitation

Even with a stolen peerToken during an active unlocked session, an attacker **cannot**:
- Export key material (needs browser confirmation)
- Delete keys (needs browser confirmation)
- Change passphrases (needs browser confirmation)
- Import malicious keys (needs browser confirmation)

They **can** sign arbitrary bytes until the session expires. This is mitigated by session lifetime, rate limits, and audit logging.

### Session State (In-Memory Only)

```
sessions/
  peerToken → {
    identity: "google:118234...",
    unlockedKeys: {publicKey → passphrase},
    pendingConfirmations: {confirmToken → {tool, params, expiresAt, approved}},
    issuedAt, expiresAt
  }
```

Never persisted to disk. Server restart invalidates all sessions.

### Auto-Unlock for Blank Passphrases

On session creation, the peer attempts to unlock all of the user's keys with empty passphrase `""`. Keys that succeed are marked as unlocked automatically. Zero-friction for users who don't set passphrases.

## MCP Tool Specification — Core Signing Service

### Public Tools (No Auth)

#### `signingServiceInfo`

Returns peer capabilities.

**Parameters:** None

**Returns:**

```json
{
  "enabled": true,
  "peerIdentity": "0xpeer...",
  "supportedProviders": ["google", "apple"],
  "exportAllowed": true,
  "maxKeysPerUser": 10,
  "version": "1.0.0"
}
```

### Standard Tools (peerToken Required)

#### `createKey`

Generates a new Ed25519 keypair and stores the encrypted seed. Uses blank passphrase by default. To create a key with a passphrase, the agent should redirect the user to the peer's `/keys/create` web UI page.

**Parameters:**

| Name | Type | Required | Default | Description |
|---|---|---|---|---|
| `passphrase` | string | no | `""` | Passphrase for encryption. Prefer web UI redirect. |

**Returns:**

```json
{
  "publicKey": "0xf041..."
}
```

**Behaviour:**
1. Resolve identity from session
2. Generate Ed25519 keypair
3. Compute wrapping key and lookup hash
4. Encrypt seed, store in key store
5. Update user index
6. Auto-unlock the key for the current session
7. Zero seed material from memory
8. Return public key

#### `listKeys`

Lists the user's registered public keys and their lock status.

**Parameters:** None

**Returns:**

```json
{
  "keys": [
    {"publicKey": "0xf041...", "status": "unlocked"},
    {"publicKey": "0xa3b2...", "status": "locked"}
  ]
}
```

#### `unlock`

Unlocks a key for the current session. Agent should redirect user to peer web UI for passphrase entry.

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `publicKey` | string | yes | Ed25519 public key to unlock |
| `passphrase` | string | yes | Passphrase for this key |

**Returns:** `{"unlocked": true}`

**Web UI flow:** Agent redirects to `peer.example.com/unlock?key=0xf041...` where the user enters their passphrase securely. Peer caches passphrase in session and redirects back.

#### `lock`

Clears a cached passphrase from the session.

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `publicKey` | string | yes | Ed25519 public key to lock |

**Returns:** `{"locked": true}`

#### `sign`

Signs arbitrary bytes with an Ed25519 key. Key must be unlocked.

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `publicKey` | string | yes | Ed25519 public key to sign with |
| `bytesToSign` | string | yes | Data to sign (hex-encoded) |

**Returns:**

```json
{
  "signature": "0xabc123..."
}
```

**Behaviour:**
1. Verify key is unlocked in session
2. Retrieve passphrase from session
3. Compute lookup hash and wrapping key
4. Decrypt seed from key store
5. Ed25519 sign the bytes
6. Zero seed and wrapping key
7. Log to audit log
8. Return signature

#### `listTokens`

Lists active sessions for the authenticated identity.

**Parameters:** None

**Returns:**

```json
{
  "tokens": [
    {"tokenId": "pt_abc1...", "issuedAt": "...", "expiresAt": "..."},
    {"tokenId": "pt_def4...", "issuedAt": "...", "expiresAt": "..."}
  ]
}
```

#### `revokeToken`

Revokes a session and clears all cached passphrases.

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `tokenId` | string | yes | Token identifier from `listTokens` |

**Returns:** `{"revoked": true}`

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

Returns the decrypted seed. Elevated because it exposes secret key material. Key must be unlocked. Only available if peer allows export.

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `publicKey` | string | yes | Ed25519 public key |
| `confirmToken` | string | no | Confirmation token from web UI |

**Returns (with valid confirmToken):**

```json
{
  "seed": "0x5b3c...",
  "publicKey": "0xf041..."
}
```

#### `deleteKey`

Permanently destroys a stored encrypted seed. Elevated because it is irreversible. Key must be unlocked.

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `publicKey` | string | yes | Ed25519 public key |
| `confirmToken` | string | no | Confirmation token from web UI |

**Returns (with valid confirmToken):** `{"deleted": true}`

#### `changePassphrase`

Re-encrypts a key with a new passphrase. Elevated because it changes security parameters. Key must be currently unlocked. The new passphrase is entered on the confirmation web page, not passed as a parameter.

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `publicKey` | string | yes | Ed25519 public key |
| `confirmToken` | string | no | Confirmation token from web UI |

The confirmation web page at `confirmUrl` includes a passphrase entry field. The new passphrase is submitted directly to the peer via the web UI and never passes through the agent.

**Returns (with valid confirmToken):** `{"updated": true}`

## MCP Tool Specification — Convex Convenience Layer

These tools build on the core signing service and add Convex-specific functionality.

#### `transact`

Full convenience — resolves signing key, prepares, signs, and submits.

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `address` | string | yes | Convex address (e.g., `"#42"`) |
| `source` | string | yes | CVX source code |

**Returns:**

```json
{
  "result": "...",
  "hash": "0xdef456..."
}
```

**Behaviour:**
1. Query Convex network for public key of address
2. Verify key is managed by signing service and unlocked
3. Prepare transaction
4. Call core `sign(publicKey, txBytes)`
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
    {"publicKey": "0xf041...", "addresses": ["#42", "#108"], "status": "unlocked"},
    {"publicKey": "0xa3b2...", "addresses": ["#87"], "status": "locked"}
  ]
}
```

## Storage Design

### Encrypted Key Store (Persistent)

```
keystore/
  <lookupHash> → <encryptedSeed>
```

- `lookupHash = hash(socialID || publicKey || passphrase)`
- Encrypted with `wrappingKey = HKDF(peerSecret, socialID || publicKey || passphrase)`

### User Index (Persistent)

```
user-index/
  hash(socialID) → encrypted([publicKey, ...])
```

Encrypted with `HKDF(peerSecret, socialID)`. Reveals key ownership on data-at-rest compromise, not key material.

### Session Store (In-Memory Only)

```
sessions/
  peerToken → {
    identity,
    unlockedKeys: {publicKey → passphrase},
    pendingConfirmations: {confirmToken → {tool, params, expiresAt, approved}},
    issuedAt, expiresAt
  }
```

Never persisted. Server restart invalidates all sessions.

### Audit Log (Persistent)

```json
{
  "timestamp": "2026-02-12T14:30:00Z",
  "publicKey": "0xf041...",
  "tokenFingerprint": "abc1...",
  "bytesHashSigned": "0x9a8b7c...",
  "tool": "sign"
}
```

Elevated operations are logged with the tool name and confirmation status.

## Peer Operator Configuration

| Setting | Description | Recommended |
|---|---|---|
| `supportedProviders` | OAuth providers to accept | `["google", "apple"]` |
| `maxKeysPerUser` | Key store limit | 10 |
| `exportAllowed` | Whether `exportKey` is available | `true` |
| `sessionLifetime` | Session duration | 4–24 hours |
| `confirmTokenLifetime` | How long user has to confirm elevated ops | 5 minutes |
| `faucetEnabled` | Whether `createAccount` can fund from faucet | Testnet only |
| `rateLimits` | Per-session signing rate limits | 100 signs/hour |

### Peer Web UI

The peer must serve:

| Path | Purpose |
|---|---|
| `/auth` | Social login page. Login buttons, OAuth flow, peerToken issuance. |
| `/unlock` | Passphrase entry. Accepts `?key=0x...`. Caches passphrase in session. |
| `/confirm` | Elevated operation confirmation. Accepts `?token=ct_...`. Shows action details, confirm/cancel. For `changePassphrase`, includes new passphrase entry field. |
| `/keys/create` | (Optional) Key creation with passphrase entry via secure UI. |

All pages are minimal and fast-loading. They exist solely for secure credential entry and action confirmation. All other operations happen through MCP tools.

## Implementation Notes

### Cryptographic Primitives

| Primitive | Algorithm | Notes |
|---|---|---|
| Key derivation | HKDF-SHA256 | Info: `"convex-signing-service-v1"` |
| Seed encryption | AES-256-GCM | Nonce derived or stored with ciphertext |
| Signing | Ed25519 | Use `convex.core.crypto` |
| Lookup hash | SHA-256 | |
| Token generation | CSPRNG | 256-bit, base64url-encoded |

### Memory Safety

Seed material and wrapping keys zeroed immediately after signing. Session-cached passphrases cleared on session expiry, token revocation, or `lock` call. Use byte arrays over strings where the runtime permits.

### JWKS Caching

Cache with 24-hour TTL, refresh on validation failure:
- Google: `https://www.googleapis.com/oauth2/v3/certs`
- Apple: `https://appleid.apple.com/auth/keys`

### Recommended Agent Behaviour

1. Check `signingServiceInfo` on first connection
2. Initiate auth redirect if unauthenticated
3. Call `listKeys` or `listAccounts` after auth
4. Blank-passphrase keys are auto-unlocked — use immediately
5. For locked keys, redirect user to `/unlock` — never ask for passphrase in chat
6. Prefer `transact` for Convex operations
7. Use core `sign` for non-transaction signing
8. For elevated operations, handle the `confirmation_required` response by redirecting user to `confirmUrl` and retrying with `confirmToken`
9. Call `lock` after completing sensitive operations

### User Migration

1. `exportKey` on old peer → `importKey` on new peer (both require confirmation)
2. Create new key on new peer → `set-key` on-chain → transfer assets
3. Create fresh account on new peer, transfer assets

### Future Enhancements

- **Per-token policy layer**: Spending limits and function whitelists per peerToken
- **TEE-based signing**: Trusted Execution Environment for runtime protection
- **Multi-party computation**: Split keys for high-value accounts
- **Hardware token support**: WebAuthn/passkey as passphrase alternative
