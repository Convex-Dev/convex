---
name: cad3-encoding
description: CAD3 encoding format — cells, embedded vs branch references, value IDs, validity rules. Use when working on encoders, decoders, serialisation, hashing or anything that reads or writes cell encodings.
---

# CAD3 Encoding

CAD3 is the byte-level encoding for all Convex data. Normative spec:
`https://docs.convex.world/docs/cad/encoding`. Read it before changing encoder
or decoder behaviour — this skill is orientation, not a substitute.

## The Model

A **cell** is the unit of encoding. Cells reference other cells, forming a
Merkle DAG: every reference carries the hash of the referenced encoding.

- **Encoding is a byte sequence.** Every cell maps to exactly one encoding, and
  distinct cells map to distinct encodings. Both directions matter — the
  round-trip and the uniqueness.
- **Value ID** = SHA3-256 of the encoding. This is the content address.
- **Maximum encoding length is 16383 bytes**, so any cell fits a fixed buffer
  and most operations stay O(1).
- The first byte is the **tag**, which determines how the rest is read. A tag
  not defined in CAD3 MUST be rejected.

## Embedded vs Branch

The distinction drives both correctness and performance.

- **Embedded**: the child's encoding sits inside the parent's encoding. An
  embedded cell MUST be 140 bytes or less.
- **Branch**: the child is referenced externally by value ID, and must be
  fetched separately.

A cell that is embedded MUST NOT also be referenced externally. Allowing both
would give a parent two valid encodings, breaking uniqueness. When you touch
embedding rules, that invariant is what you are protecting.

Embedding is why `[1 2 3 4 5]` is one encoding rather than six, and why
embedded values cost zero memory — see the `memory` skill.

## Validity

An encoding is valid if some cell produces exactly those bytes. Implementations
MUST reject:

- trailing bytes after a complete valid encoding
- a sequence that ends before the encoding is complete
- undefined or reserved tags

Random bytes are almost always invalid, which is what lets a peer discard
corrupt or hostile input cheaply. Preserve that property — it is load-bearing
for the peer's robustness against malicious messages.

## Traps

**Do not "fix" the decoder to reject non-canonical `NaN`.** Every 64-bit
pattern in a Double is a valid encoding, including every distinct `NaN` payload,
both signed zeroes, infinities and subnormals. Each is a *distinct value* with
its own value ID. The CVM defines one canonical `NaN` (`##NaN`,
`0x1d7ff8000000000000`) and normalises results to it, but that is a **CVM
value-layer concern enforced by coercion** — never by rejecting an encoding.
This looks like a decoder bug and is not one.

Contrast with Integers, where excess leading bytes are genuinely *redundant*
and therefore invalid. The test is whether two byte sequences would denote the
same value: if so, only one may be legal.

**Preserve values you do not understand.** CAD3 is deliberately extensible —
applications assign their own meaning to values, particularly in the `0xAn`,
`0xCn`, `0xDn` and `0xEn` categories. An implementation MUST relay encoded
values it cannot interpret rather than dropping or normalising them.

**Value IDs of non-branch cells may not be in storage.** Only roots and
branches are generally persisted. If you hold a value ID for an intermediate
cell, navigate down from a known root instead of assuming a store lookup will
resolve it.

## Where the Code Lives

Encoding logic is in `convex-core`: see `convex.core.cvm.CVMEncoder`,
`CVMTag`, and `convex-core/docs/ENCODER_DESIGN.md`.

Changes here affect consensus compatibility. `SnapshotStateTest` replays a
fixed state and checks its hash — **if your change moves that hash, it is a
consensus-visible change**, not a refactor, and needs to be gated on a protocol
version rather than shipped unconditionally.
