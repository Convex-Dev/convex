# Signature Validation Throughput

## Problem

Transaction throughput was limited to ~16k TPS. The bottleneck was Ed25519 signature
verification in `TransactionHandler`, which processed incoming client transactions
sequentially in a single thread.

Each Ed25519 verify call takes ~60-80 microseconds (BouncyCastle). At 70us average,
a single thread can verify ~14,000 signatures per second — closely matching the
observed limit.

## Three Verification Layers

Transactions are signature-checked in three different layers:

| Layer | Purpose | Skippable? |
|-------|---------|------------|
| **Ingress** (`TransactionHandler`) | Reject bad client transactions before block creation | No — bad signatures would pollute blocks |
| **Belief merge** (`BeliefPropagator`) | Verify peer Order signatures during gossip | No — peer protocol security |
| **CVM execution** (`State.applyTransaction`) | Deterministic re-verification before state mutation | No — consensus correctness requires it |

The CVM execution layer already had parallel pre-validation (`Peer.validateSignatures()`)
with result caching. The belief merge layer is low-volume (peer Orders only). The
ingress layer was the bottleneck — all verification was sequential in a single thread.

## Design: Three-Phase Batch Processing

`TransactionHandler.processMessages()` splits the work into three phases:

```
drainTo(messages)
      │
      ▼
┌─────────────────────────────────────────┐
│  Phase 1: Extract + Cheap Checks         │  Sequential, ~5us each
│  Format, account, sequence, key match    │
│  Reject failures immediately             │
│  Build list of survivors for Phase 2     │
└──────────────┬──────────────────────────┘
               │
      ▼
┌─────────────────────────────────────────┐
│  Phase 2: Parallel Signature Verify      │  SIGN_POOL, ~70us each, N cores
│  Peer.preValidateSignatures(survivors)   │
│  Results cached on each SignedData        │
└──────────────┬──────────────────────────┘
               │
      ▼
┌─────────────────────────────────────────┐
│  Phase 3: Check cached results + queue   │  Sequential, ~1us each (cache hit)
│  sd.checkSignature() → cached lookup     │
│  Queue valid transactions                │
└─────────────────────────────────────────┘
```

### Why Cheap Checks Before Parallel Verification?

Cheap checks (format, account exists, sequence number, key match) cost <5us combined
and reject a significant fraction of invalid transactions. Running them first means
the parallel phase only processes transactions that have a chance of succeeding —
no wasted CPU on obviously bad messages.

### Peer API

`Peer.checkTransaction()` is split into two methods:

- **`checkTransactionFast(sd)`** — cheap validation only (format, account, sequence,
  key match). Returns error `Result` or `null` if checks pass.
- **`checkTransaction(sd)`** — calls `checkTransactionFast` then `checkSignature()`.
  Used by callers that don't do parallel pre-validation.

`Peer.preValidateSignatures(List<SignedData<ATransaction>>)` — public static method
that submits signature verification to the shared `SIGN_POOL` in chunks of 100.
Results are cached on each `SignedData` instance (`verifiedKey` field and
`VERIFIED_MASK` on `Ref` flags). Reuses the same pool and chunking pattern as the
existing `Peer.validateSignatures()` used during consensus execution.

### Sequence Number Check

The sequence check (`tx.getSequence() <= as.getSequence()`) is a heuristic to
eliminate transactions that are definitely invalid (stale sequence). It compares
against the current consensus state, which is immutable during a batch. It doesn't
need to be perfect — the CVM execution layer enforces strict sequencing.

### Throughput

With 8 cores, signature verification bandwidth goes from ~14k/s (single thread) to
~112k/s. The sequential phases at ~5us per transaction support ~200k TPS. The
bottleneck moves elsewhere (block creation, consensus, CVM execution).

## SignedData Caching

`SignedData.checkSignatureImpl()` caches verification results:

- **`verifiedKey`** field — set to the public key on successful verification
- **`Ref` flags** — `VERIFIED_MASK` (success) or `BAD_MASK` (failure)
- Subsequent calls return immediately from cache (~0.1us)

This means the ingress verification (Phase 2) also benefits the CVM execution layer
(Layer 3) — by the time `State.applyTransaction()` calls `checkSignature(key)`, the
result is already cached.

### `synchronized` on `checkSignatureImpl`

The method holds the instance monitor for the entire Ed25519 verify (~70us). This is
safe but causes contention when the same `SignedData` instance is verified from
multiple threads simultaneously (e.g. ingress and consensus paths overlap). In
practice, different `SignedData` instances are verified in parallel — the lock is
per-instance, not global. See Future Direction for lock-free alternative.

## Future Direction

### Batch Ed25519 Verification

BouncyCastle's `Ed25519.verify()` processes one signature at a time. Some Ed25519
implementations (e.g. ed25519-donna, libsodium) support batch verification where
multiple signatures are verified in a single call with amortised cost. If a Java
binding becomes available, the `SIGN_POOL` chunking naturally supports batch mode —
each chunk would call a batch verify instead of a loop.

### Remove `synchronized` from `checkSignatureImpl`

Replace the instance monitor with a volatile `verifiedKey` field and compare-and-swap
on the `Ref` flags. This eliminates the ~70us lock hold during first verification,
allowing truly lock-free concurrent checks. The worst case under the current
`synchronized` is two threads both trying to verify the same `SignedData` for the
first time — one blocks while the other does the work.

### Connection-Level Rate Limiting

Even with parallel verification, a malicious client can consume CPU by sending a
flood of validly-signed transactions. Per-connection rate limiting (e.g. max N
transactions per second per channel) would bound the verification cost per client
independent of pool capacity.
