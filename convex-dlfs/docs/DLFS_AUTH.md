# DLFS Authentication and Authorisation

Authentication and authorisation for the DLFS MCP and WebDAV endpoints.

## Authentication

DLFS uses Ed25519 JWT bearer tokens for identity. When a key pair is provided
to `DLFSServer.create()`, the `AuthMiddleware` extracts the caller's DID from
the `Authorization: Bearer <jwt>` header on every request.

Each authenticated user gets their own drive namespace. Anonymous callers
(no token) share a separate anonymous namespace. The same identity model
applies to both WebDAV and MCP endpoints.

### JWT Format

Standard EdDSA JWT with `kid` header containing the multikey-encoded public key:

```
Header:  {"alg": "EdDSA", "typ": "JWT", "kid": "z6Mk..."}
Payload: {"sub": "did:key:z6Mk...", "iss": "did:key:z6Mk...",
          "aud": "did:key:z<server>...", "iat": ..., "exp": ...}
```

The `aud` claim must match the server's DID. Tokens with wrong audience,
expired tokens, or invalid signatures are rejected with HTTP 401.

## Authorisation: Own Drives

By default, each user has full access to their own drives. No UCAN or
capability token is needed — the caller's DID (from the JWT) is used to
resolve drives in the `DLFSDriveManager`.

When `requireAuthForWrites` is enabled on the WebDAV endpoint, mutating
operations (PUT, DELETE, MKCOL, MOVE, COPY) return 401 without a valid JWT.
Read operations are always allowed.

## Authorisation: Delegated Access via UCAN

Cross-user drive access uses UCAN (User Controlled Authorisation Networks)
tokens presented per-request. The drive owner issues a UCAN granting specific
capabilities to another user, who then presents it alongside their own
identity to access the owner's drive.

### UCAN Token Format

UCAN tokens are encoded as standard EdDSA JWTs. The payload carries UCAN
claims as defined by the [UCAN specification](https://github.com/ucan-wg/spec):

```
Header:
  {"alg": "EdDSA", "typ": "JWT", "kid": "z6Mk<issuer-multikey>"}

Payload:
  {
    "iss": "did:key:z6Mk<alice>",          // Issuer — the drive owner
    "aud": "did:key:z6Mk<bob>",            // Audience — the delegatee
    "exp": 1775064015,                      // Expiry (unix seconds)
    "nnc": "f21d54bf547a7c758f0d8228",      // Nonce (replay prevention)
    "att": [                                // Capabilities granted
      {
        "with": "did:key:z6Mk<alice>/dlfs/docs",
        "can": "crud/read"
      }
    ],
    "prf": []                               // Proof chain (empty = root grant)
  }
```

### Resource URIs

Resources are **DID URLs** as defined by [W3C DID Core](https://www.w3.org/TR/did-core/#did-url-syntax).
The DID identifies the resource owner; the path scopes into their DLFS namespace:

```
did-url = did path-abempty
```

| Resource | Scope |
|----------|-------|
| `did:key:zAlice...` | Everything (all namespaces) |
| `did:key:zAlice.../dlfs/` | All DLFS drives |
| `did:key:zAlice.../dlfs/docs` | The `docs` drive |
| `did:key:zAlice.../dlfs/docs/specs` | The `specs` subdirectory within `docs` |

Prefix matching is used for attenuation: a grant on `/dlfs/docs` covers
`/dlfs/docs/specs/readme.txt`. This uses `Capability.resourceCovers()` from
convex-core.

### Abilities

Standard UCAN CRUD abilities (defined in `convex.auth.ucan.Capability`):

| Ability | Covers | DLFS Operations |
|---------|--------|-----------------|
| `*` | Everything | All operations |
| `crud` | `crud/read`, `crud/write`, `crud/delete` | All data operations |
| `crud/read` | — | `dlfs_read`, `dlfs_list`, `dlfs_list_drives` |
| `crud/write` | — | `dlfs_write`, `dlfs_mkdir` |
| `crud/delete` | — | `dlfs_delete` |

Ability matching uses UCAN's prefix hierarchy: `crud` covers `crud/read`
because `crud` is a prefix of `crud/read` at a `/` boundary.

### MCP Transport

UCANs are presented as JWT strings in the `ucans` field of MCP tool arguments:

```json
{
  "name": "dlfs_read",
  "arguments": {
    "drive": "docs",
    "path": "specs/readme.txt",
    "ucans": ["eyJhbGci..."]
  }
}
```

Multiple tokens can be provided (e.g. for delegation chains). Each token is
a complete JWT string.

This follows the per-request proof presentation model — no server-side token
store, no session-level capability state. Every request carries its own proofs.

### Verification

When a caller requests a drive they don't own, `DlfsMcpTools.resolveDrive()`
checks the UCAN proofs:

1. **Parse** — each `ucans` entry is parsed as an EdDSA JWT
2. **Signature** — verified using the `kid` header (issuer's public key)
3. **Expiry** — `exp` must be in the future, `nbf` (if present) must be in the past
4. **Audience** — `aud` must match the caller's DID (from their auth JWT)
5. **Issuer owns drive** — the issuer's DID is used to look up the drive in the `DLFSDriveManager`
6. **Capability** — at least one entry in `att` must cover the required resource and ability
7. **Proof chain** — if `prf` contains parent tokens, they are recursively validated with chain link checks (`proof.aud == token.iss`)

If any token provides a valid chain, access is granted. Otherwise, the
request is denied with a "Drive not found" error (uniform error — no
information leakage about drive existence).

### Delegation Chains

An audience can sub-delegate by signing a new UCAN referencing the parent
token in `prf`. The sub-delegation must be attenuated (equal or narrower
scope):

```
Root (Alice → Bob):
  iss: did:key:zAlice, aud: did:key:zBob
  att: [{with: "did:key:zAlice.../dlfs/docs", can: "crud"}]
  prf: []

Sub-delegation (Bob → Carol):
  iss: did:key:zBob, aud: did:key:zCarol
  att: [{with: "did:key:zAlice.../dlfs/docs/public", can: "crud/read"}]
  prf: ["<alice-to-bob-jwt>"]
```

Carol presents the sub-delegation JWT with the parent embedded in `prf`.
The verifier checks: Bob signed it, Carol is the audience, Bob's capabilities
are covered by Alice's grant, Alice owns the resource, both signatures valid,
neither expired.

### Security Properties

| Property | Mechanism |
|----------|-----------|
| **No forged grants** | Ed25519 signature verification; tampered payloads fail |
| **No audience confusion** | `aud` must match the presenting caller's DID |
| **No non-owner grants** | Issuer's DID must resolve to an existing drive in the drive manager |
| **No ability escalation** | `crud/read` grant cannot be used for `crud/write` operations |
| **No resource leakage** | Grant for drive A doesn't work on drive B (prefix matching) |
| **No path escape** | Grant scoped to `/dlfs/docs/public` doesn't cover `/dlfs/docs/secret` |
| **No anonymous abuse** | Unauthenticated callers cannot present UCANs (identity required) |
| **No expired tokens** | Temporal bounds enforced on every validation |
| **Uniform errors** | "Drive not found" for all denial cases (no existence oracle) |

### Relation to UCAN HTTP Bearer Token Spec

The [UCAN HTTP Bearer Token](https://github.com/ucan-wg/ucan-http-bearer-token)
specification defines `Authorization: Bearer <ucan-jwt>` and a `ucans:` header
for proof chains. DLFS currently uses body-level presentation (in MCP tool
arguments) which is more natural for JSON-RPC. Header-based presentation could
be added for WebDAV in future — the verification logic is the same.

### Key Files

| File | Purpose |
|------|---------|
| `convex-core/src/main/java/convex/auth/ucan/UCAN.java` | Token creation, signing, JWT encoding/decoding |
| `convex-core/src/main/java/convex/auth/ucan/UCANValidator.java` | Signature, temporal, and chain validation |
| `convex-core/src/main/java/convex/auth/ucan/Capability.java` | Capability matching and attenuation |
| `convex-core/src/main/java/convex/auth/jwt/JWT.java` | JWT encoding/parsing/verification |
| `convex-dlfs/src/main/java/convex/dlfs/DlfsMcpTools.java` | UCAN enforcement in MCP tools |
| `convex-dlfs/src/test/java/convex/dlfs/test/AuthTest.java` | Auth and UCAN tests (including adversarial) |
