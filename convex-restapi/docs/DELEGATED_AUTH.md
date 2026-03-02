# Delegated Auth for Convex

## Design Document — Capability Delegation Protocol

---

## 1. Problem

Autonomous agents operating on Convex need to delegate signing authority to other agents without sharing private keys. An agent coordinating a multi-step workflow — say, acquiring an NFT through a broker agent — must grant that broker limited, time-bound authority to transact on its behalf. Today this requires either sharing seeds (catastrophic) or on-chain controller relationships (inflexible, no attenuation).

We need a system where:

- Agent A can delegate a **subset** of its authority to Agent B
- Agent B can **sub-delegate** a further narrowed subset to Agent C
- Every delegation is **time-bound**, **capability-scoped**, and **independently verifiable**
- The entire negotiation and delegation flow is **machine-driven with zero human involvement**
- Failed authorisation produces **actionable capability challenges** that agents can resolve autonomously

---

## 2. Architecture Overview

The system operates entirely off-chain as a policy layer over the peer's MCP signing service (see `MCP_AUTH.md`). No on-chain contracts are required for delegation itself — the CVM remains the execution layer while UCAN tokens govern who may invoke the signing service for which operations.

```
┌─────────────┐    UCAN token    ┌──────────────────┐    signingTransact    ┌─────────┐
│  Agent B    │ ───────────────► │  Convex Peer     │ ──────────────────► │  Convex │
│  (delegate) │                  │  (validates chain │                     │  CVM    │
└─────────────┘                  │   enforces caps)  │                     └─────────┘
                                 └──────────────────┘
                                        ▲
                                        │ signing key held here
                                        │ (never leaves peer)
```

The peer is the enforcement point. It holds signing keys in its encrypted signing service, validates UCAN delegation chains, checks that the requested action falls within the attenuated capabilities, and only then signs and submits the transaction.

**Trust model:** Delegators already trust the peer with their signing keys (this is the existing `signingTransact` model — see `MCP_AUTH.md`). UCAN delegation extends that trust outward: it allows the key owner to authorise third-party agents to request specific actions through the peer, without those agents ever accessing the key.

---

## 3. Token Structure

All tokens are native CVM values encoded via CAD3. Signatures are Ed25519 over the CAD3-encoded payload bytes. No JWT, no JSON, no base64 — pure Convex data end-to-end. See §10 for why CAD3.

### 3.1 Header

```clojure
{:alg :EdDSA
 :ucv "0.10.0"}
```

### 3.2 Payload

```clojure
{:iss "did:key:z6Mk..."       ;; delegator's Ed25519 public key
 :aud "did:key:z6Mk..."       ;; delegate's Ed25519 public key
 :exp 1740009600               ;; expiry (unix seconds)
 :nbf 1739998800               ;; not-before (optional)
 :nnc "f47ac10b58cc"           ;; nonce (unique token identity)
 :att [<capability> ...]       ;; attenuated capabilities (see §4)
 :prf [<parent-token> ...]     ;; full parent UCAN tokens (see §5)
 :fct {:chain "convex-testnet" ;; optional facts
       :origin "#42"}}
```

### 3.3 Complete Token

```clojure
{:header {:alg :EdDSA :ucv "0.10.0"}
 :payload {:iss "did:key:z6Mk05c7612e0d72e8dd25c063315609686f2ce3bc3f607ea614d17c31a53aa9221b"
           :aud "did:key:z6Mk86b4b9967a3d64f0bceaaf5c736cc9c1f712f4ffb24430f27d83dc7e601bcd9f"
           :exp 1740009600
           :nbf 1739998800
           :nnc "f47ac10b58cc"
           :att [{:with "convex:account:#42"
                  :can  "convex/transfer"
                  :nb   {:max_amount 1000000000
                         :to #{55 78 100}}}
                 {:with "convex:actor:#100"
                  :can  "convex/call"
                  :nb   {:fn #{"send-msg" "read-inbox" "list-tasks"}}}]
           :prf []}
 :sig 0xb5e2f5cd620c2717b9d82a23e63f3414aaab33eab2b9bdb630d9a51d1901efda76d543b6ac07f0101226d771548b9375ec74e7e8d6bf5eeaed6cc51059bdb90e}
```

The `:sig` is the Ed25519 signature of the CAD3 encoding of the `:payload` value, signed by the `:iss` key.

---

## 4. Capability Schema

Each capability in `:att` specifies a resource, an action, and caveats that narrow the scope.

### 4.1 Resource URI Scheme

```
convex:{type}:{address}
```

Resources are pinned to concrete addresses at delegation time.

| Resource | Example | Scope |
|----------|---------|-------|
| Account operations | `convex:account:#42` | Transfers from this account |
| Actor invocations | `convex:actor:#100` | Function calls on this actor |
| Wildcard | `convex:account:*` | Any account the issuer controls |

### 4.2 Action Namespace

```
convex/{operation}
```

| Action | Permits |
|--------|---------|
| `convex/transfer` | Coin/token transfers |
| `convex/call` | Actor function invocations |
| `convex/deploy` | Deploy on behalf of |
| `convex/*` | Full authority — root delegation |

### 4.3 Caveats (`:nb`)

Caveats are the attenuation mechanism. Each sub-delegation can only **narrow**, never widen.

**Transfer caveats:**

```clojure
{:max_amount 100000000       ;; max copper per transaction
 :to #{55 78}                ;; allowed recipient addresses
 :token nil                  ;; nil = native CVM coin, or actor address for fungible token
 :seq 42}                    ;; optional: pin to exact sequence number (see §4.5)
```

**Call caveats:**

```clojure
{:fn #{"send-msg" "read-inbox"}  ;; allowed function names
 :max_juice 50000}               ;; max juice (computation) per call
```

**Temporal caveats (in payload, not `:nb`):**

```clojure
{:exp 1740003600    ;; must be ≤ parent's :exp
 :nbf 1739998800}   ;; must be ≥ parent's :nbf
```

### 4.4 Attenuation Rules

For a child capability `C` to be valid under parent capability `P`:

| Field | Rule |
|-------|------|
| `:with` | `C.with == P.with` (exact match) |
| `:can` | `C.can == P.can` or `P.can == "convex/*"` |
| `:nb :max_amount` | `C ≤ P` |
| `:nb :to` | `C ⊆ P` |
| `:nb :fn` | `C ⊆ P` |
| `:nb :max_juice` | `C ≤ P` |
| `:nb :seq` | `C == P` (if parent specifies, child must match) |
| `:exp` | `C ≤ P` |

### 4.5 Sequence Caveats

The `:seq` caveat pins a delegation to a specific account sequence number. This is the strongest form of replay and reuse protection — the token is valid for exactly one transaction at exactly one position in the account's history.

```clojure
{:with "convex:account:#42"
 :can  "convex/transfer"
 :nb   {:max_amount 50000000
        :to #{55}
        :seq 1337}}           ;; only valid for sequence number 1337
```

**Properties:**

- **Single-use by construction.** Once the transaction at sequence 1337 is executed (or any other transaction consumes that slot), the token is permanently spent. No revocation needed.
- **No replay possible.** The Convex CVM rejects duplicate sequence numbers at the consensus level. The UCAN caveat simply restricts which sequence the delegate may use.
- **Ordering guarantee.** The delegator knows exactly where in their transaction history the delegated action will land.
- **Composable with other caveats.** A token can be both sequence-pinned and amount-capped: "transfer at most 0.05 CVM to #55, at sequence 1337, before expiry."

**When to use:**

| Scenario | Caveat |
|----------|--------|
| One-shot delegation (pay this invoice) | `:seq 1337` |
| Ongoing service (message relay) | `:exp` only |

The peer validates `:seq` by querying the account's current sequence number. If the account has already advanced past the specified sequence, the token is rejected as spent.

---

## 5. Delegation Chains — Inline Proofs

The `:prf` field carries **full parent tokens**, not hashes. The validator needs the complete content to verify signatures and check attenuation at each link. Hashes would require a content-addressable store for resolution — unnecessary complexity for chains that are typically 2–3 links deep.

### 5.1 Chain Example: Root → Agent A → Agent B

**Token 1 — Root grants Agent A broad authority:**

```clojure
{:header {:alg :EdDSA :ucv "0.10.0"}
 :payload {:iss "did:key:z6MkRoot..."
           :aud "did:key:z6MkAgentA..."
           :exp 1740009600
           :nnc "a1b2c3d4e5f6"
           :att [{:with "convex:account:#42"
                  :can  "convex/transfer"
                  :nb   {:max_amount 1000000000
                         :to #{55 78 100}}}
                 {:with "convex:actor:#100"
                  :can  "convex/call"
                  :nb   {:fn #{"send-msg" "read-inbox" "list-tasks"}}}]
           :prf []}
 :sig 0x<Root-signs-payload>}
```

**Token 2 — Agent A sub-delegates to Agent B with narrowed scope:**

```clojure
{:header {:alg :EdDSA :ucv "0.10.0"}
 :payload {:iss "did:key:z6MkAgentA..."
           :aud "did:key:z6MkAgentB..."
           :exp 1740003600                ;; shorter expiry (≤ parent)
           :nnc "e23d91a7cc01"
           :att [{:with "convex:account:#42"
                  :can  "convex/transfer"
                  :nb   {:max_amount 100000000    ;; 0.1 CVM (narrowed from 1 CVM)
                         :to #{55}}}              ;; single recipient (narrowed from 3)
                 {:with "convex:actor:#100"
                  :can  "convex/call"
                  :nb   {:fn #{"send-msg"}}}]     ;; single function (narrowed from 3)
           :prf [{:header {:alg :EdDSA :ucv "0.10.0"}
                  :payload {:iss "did:key:z6MkRoot..."
                            :aud "did:key:z6MkAgentA..."
                            :exp 1740009600
                            :nnc "a1b2c3d4e5f6"
                            :att [{:with "convex:account:#42"
                                   :can  "convex/transfer"
                                   :nb   {:max_amount 1000000000
                                          :to #{55 78 100}}}
                                  {:with "convex:actor:#100"
                                   :can  "convex/call"
                                   :nb   {:fn #{"send-msg" "read-inbox" "list-tasks"}}}]
                            :prf []}
                  :sig 0x<Root-signs-payload>}]}
 :sig 0x<AgentA-signs-payload>}
```

### 5.2 Chain Invariants

At every link in the chain:

1. `parent.aud == child.iss` — the audience of Token 1 is the issuer of Token 2
2. `child.att ⊆ parent.att` — capabilities only narrow
3. `child.exp ≤ parent.exp` — cannot outlive parent
4. Each token's `:sig` validates against its `:iss` public key over its CAD3-encoded `:payload`

---

## 6. Invocation — Requesting an Action

The invocation format is application-defined (UCAN does not specify it). For Convex MCP tools, existing authenticated tools gain an optional `:auth` parameter:

```clojure
;; signingTransact with UCAN auth
{:address    "#42"
 :source     "(transfer #55 50000000)"
 :passphrase ""                         ;; peer-held key passphrase
 :auth       <Token 2 from §5.1>}       ;; UCAN delegation chain
```

The peer:

1. Authenticates the caller via the existing bearer token (see `MCP_AUTH.md`)
2. Validates the full UCAN chain in `:auth` — the chain's final `:aud` must match the caller's identity
3. Parses `:source` and matches against the token's `:att` capabilities
4. Confirms `(transfer #55 50000000)` satisfies: action is `convex/transfer`, recipient `#55 ∈ #{55}`, amount `50000000 ≤ 100000000`
5. Executes `signingTransact` using the stored signing key for `#42`

If validation fails, the tool returns a capability challenge (see §7) instead of executing.

---

## 7. Capability Challenge — Negotiable 403

When authorisation fails, the peer returns a **machine-readable challenge** describing exactly what capability token would satisfy the request. Since Convex global state is public, there is no information leakage risk — the challenge can be fully transparent.

### 7.1 Challenge Structure

```clojure
{:status 403
 :reason :insufficient-capability
 :request {:source "(transfer #55 50000000)"
           :addr "#42"}
 :required {:with "convex:account:#42"
            :can  "convex/transfer"
            :nb   {:min_amount 50000000
                   :to #{55}}}
 :ttl {:min 60 :suggested 3600}}
```

### 7.2 Autonomous Resolution Flow

The challenge transforms auth failure into a capability negotiation protocol that agents resolve without human intervention:

```
Agent B                     Peer                       Agent A
  │                           │                          │
  │──── invocation ──────────►│                          │
  │                           │ validate: no/bad token   │
  │◄─── 403 + challenge ─────│                          │
  │                                                      │
  │ (parse challenge, identify delegator)                │
  │                                                      │
  │──── delegation request ─────────────────────────────►│
  │     {:required <from challenge>                      │
  │      :aud "did:key:z6MkAgentB..."}                   │
  │                                                      │
  │◄─── UCAN token ─────────────────────────────────────│
  │     (attenuated to cover requested caps)             │
  │                                                      │
  │──── invocation + token ──►│                          │
  │                           │ validate: OK             │
  │◄─── result ──────────────│                          │
```

---

## 8. Validation Algorithm

```
validate(token, requested_action) → {:ok effective_caps} | {:err reason}

1. Decode token, extract :header, :payload, :sig
2. Verify :sig is valid Ed25519 over CAD3(:payload) using :iss pubkey
3. Check :exp > now
4. Check :nbf ≤ now (if present)
5. For each parent in :prf:
     a. validate(parent, token.:att)  — recursive
     b. Assert parent.:aud == token.:iss  — chain links
     c. Assert token.:att ⊆ parent.:att  — attenuation
     d. Assert token.:exp ≤ parent.:exp  — temporal narrowing
6. Match requested_action against :att capabilities:
     a. Resource URI matches :with
     b. Action matches :can
     c. Arguments satisfy :nb caveats
7. Return effective capabilities (intersection of chain)
```

---

## 9. MCP Tool Extensions

The existing MCP signing tools already provide the cryptographic primitives needed for UCAN token construction. An agent with access to `encode` and `signingSign` can manually build valid tokens. One new tool formalises the protocol-level operation.

### 9.1 `delegate` — Create a UCAN Token

Creates a correctly structured UCAN token using a key stored in the peer's signing service. Validates attenuation rules at creation time.

**Parameters:**

```clojure
{:publicKey  "0x05c7612e..."              ;; issuer's public key (stored in signing service)
 :passphrase "my-passphrase"              ;; passphrase for the stored key
 :aud        "did:key:z6MkAgentB..."      ;; delegate's public key
 :att        [{:with "convex:account:#42"
               :can  "convex/transfer"
               :nb   {:max_amount 100000000
                      :to #{55}
                      :seq 1337}}]
 :exp        1740003600                   ;; expiry (unix seconds)
 :prf        []                           ;; parent tokens (for sub-delegation)
 :nbf        1739998800                   ;; optional: not-before
 :fct        {:purpose "NFT purchase"}}   ;; optional: facts
```

**Behaviour:**

1. Generates `:nnc` (random nonce)
2. Derives `:iss` `did:key` from the stored key's public key
3. If `:prf` is non-empty, validates attenuation — every capability in `:att` must be a subset of the parent token's capabilities, `:exp` must be ≤ parent `:exp`, and parent `:aud` must match derived `:iss`
4. Assembles payload, encodes via CAD3, signs with the stored Ed25519 key
5. Returns complete token (header + payload + sig)

**Returns:**

```clojure
{:token {:header {:alg :EdDSA :ucv "0.10.0"}
         :payload {:iss "did:key:z6Mk05c7..."
                   :aud "did:key:z6MkAgentB..."
                   :exp 1740003600
                   :nnc "7f3a91bc02dd"
                   :att [...]
                   :prf []}
         :sig 0xb5e2f5cd...}
 :cad3  "8206..."                      ;; CAD3 encoding of full token
 :hash  "5195c5e8..."}                 ;; SHA3 hash (token identity)
```

**Error (attenuation violation):**

```clojure
{:error :attenuation-violation
 :detail "capability convex/transfer max_amount 200000000 exceeds parent 100000000"}
```

### 9.2 Existing Tools — Auth-Aware Behaviour

Existing authenticated tools (`signingTransact`, `transfer`, etc.) gain an optional `:auth` parameter accepting a UCAN token. When present, the peer validates the full delegation chain and checks the requested action against the token's effective capabilities before executing. See §6.

---

## 10. CAD3 Encoding

Using CAD3 as the canonical encoding (rather than JSON/JWT) provides:

- **Deterministic** — identical logical values always produce identical bytes, eliminating canonicalisation attacks
- **Native CVM values** — tokens are valid Convex data structures
- **Compact** — binary encoding is significantly smaller than JSON for structured data
- **Hashable** — `(hash (encoding token))` produces a content address computable both off-chain and on-chain
- **Lattice-compatible** — tokens compose naturally with Convex's data model

---

## 11. Security Properties

**No key sharing.** Signing keys never leave the peer's encrypted signing service. Delegation is purely authorisation metadata.

**Attenuation-only.** Capabilities can only narrow through the chain. A delegate can never exceed the authority of its delegator.

**Time-bounded.** Every token expires, and sub-delegations cannot outlive their parents.

**Independently verifiable.** Any party with the public keys can verify a delegation chain — no central authority needed.

---

## 12. Implementation

### 12.1 Module Placement

- **Token library** (creation, validation, attenuation) in `convex-core` — reusable by any Convex application including Covia
- **MCP tool integration** (`delegate` tool, `:auth` parameter enforcement) in `convex-restapi`

### 12.2 Phases

| Phase | Scope |
|-------|-------|
| 1 | Token library in `convex-core`: token struct, creation, chain validation, attenuation rules |
| 2 | `delegate` MCP tool: wraps library, uses stored signing keys |
| 3 | `:auth` parameter on `signingTransact` and friends: validation + enforcement |
| 4 | Capability challenge (§7): machine-readable 403 responses |

### 12.3 Future Extensions

- **Nonce replay protection** — maintain a seen-set at the peer, prunable after token expiry
- **Revocation** — nonce-based revocation list for time-bounded tokens (sequence-pinned tokens are inherently single-use)
- **Cumulative budgets** — `:cumulative` caveat for total spend across multiple uses (requires persistent state)
- **On-chain spending controls** — complementary CVM-level enforcement as defence in depth
