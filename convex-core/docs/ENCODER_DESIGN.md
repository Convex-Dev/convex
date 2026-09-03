# Encoder Design

How `convex-core` turns cells into CAD3 encodings and back. The byte format itself is
specified by [CAD003](https://docs.convex.world/docs/cad/encoding); this document
explains the shape of the Java implementation: why encoding is cell-driven and decoding
is encoder-driven, how encoding lengths and embedding are computed without rendering
bytes, how multi-cell messages are assembled and decoded, and where the store comes
in. Read it before touching `ACell`, `Ref`, `Format`, the encoder hierarchy or
`Message`.

## Key points

- **Encoding is cell-driven, decoding is encoder-driven.** Every `ACell` writes its own
  bytes; an `AEncoder` bound to a store reads them back, so decoded refs are bound to
  the right store with no thread-local state.
- **Encoding length is arithmetic, never rendered.** For every cell,
  `encoding length == calcHeaderLength() + Σ getRef(i).getEncodingLength()`. Cells
  never encode themselves just to measure; `createEncoding()` allocates the exact size
  and panics if the invariant fails.
- **Embedding is a property of the child alone.** A cell whose encoding is at most
  `Format.MAX_EMBEDDED_LENGTH` (140 bytes) is written inline; anything larger is a
  branch written as a 33-byte hash ref. The judgement is always against 140, never
  against the parent's remaining budget.
- **Canonical means CAD3 form, not Java class.** A canonical cell is one whose encoding
  is its CAD3 representation (`Hash` and `AccountKey` are canonical); non-canonical
  Java representations (`VectorArray`, `StringSlice`, derived blobs) delegate every
  encoding question to `getCanonical()`.
- **Length calculation has useful side effects.** Computing a length settles whether
  the cell is embedded (cached on the ref flags) and, for fully embedded trees, its
  memory size (zero), so later persistence and message sizing pay nothing.
- **Bounded recursion.** `getEncodingLength(limit)` passes the remaining budget down
  through each child ref and returns zero as soon as it is exceeded, so deep structures
  cost only as much work as the limit allows.
- **The encoder knows nothing about message limits.** `Format.encodeMultiCell` builds
  an encoding of whatever size the value needs; the `maxSize` overload throws when a
  caller's own limit is exceeded. Choosing limits is the message layer's job.
- **Storeless decode is allocation-free for the common case.** A single-cell message
  with no branches decodes with no map, no temporary store and no extra encoder.

## Encoding path

### Cell responsibilities

Each `ACell` subclass implements `encode(byte[] bs, int pos)` (tag plus fields) and
usually `encodeRaw` (fields only). `getEncoding()` caches the result: the first call
runs `createEncoding()`, every later call returns the same `Blob`. Data structures write
their children through `Ref.encode`, which inlines an embedded child and writes a hash
ref for a branch.

### Encoding length and headers

`ACell.calcHeaderLength()` returns the number of bytes a cell contributes beyond its
child refs: the tag, VLQ counts, inline primitive fields and any structural bytes.
`getEncodingLength()` then sums the header with each child's `Ref.getEncodingLength()`,
which is the child's own encoding length when embedded and 33 otherwise. This is the
one invariant every cell must satisfy, and `ObjectsTest` checks it for every sample
value.

Composite cases follow the same rule. A `Syntax` header includes its inline metadata
map; a `List`, closure or record encodes a vector body under its own tag, so its header
is the vector's header plus the tag difference. A cell whose canonical form differs from
its Java representation returns the canonical cell's answer.

`getEncodingLength(int limit)` is the bounded variant. It threads the remaining budget
through each `Ref.getEncodingLength(limit)` and exits with zero the moment the running
total passes the limit. The embedding check uses it with `MAX_EMBEDDED_LENGTH`, so
deciding whether a 10 MB map is embedded looks at a handful of bytes, never the whole
structure.

### Embedding and refs

A ref's embedded status is cached in its flags (`KNOWN_EMBEDDED_MASK` and
`NON_EMBEDDED_MASK`), so the question is answered at most once per ref. When a full
length calculation finds that a cell and every descendant are embedded, the cell's
memory size is set to zero as well: a fully embedded tree occupies no storage of its
own because it always travels inside its parent.

### Canonical and non-canonical cells

`isCanonical()` answers whether this Java object's encoding is the CAD3 encoding of
the value. Non-canonical cells exist for performance (`VectorArray` for cheap appends,
`StringSlice` and `ADerivedBlob` for zero-copy views) and must delegate encoding
length, ref counts and refs to `getCanonical()`. Typed blobs such as `Hash` and
`AccountKey` are canonical: they are already in CAD3 form, and `toCanonical()`
returns the same object.

## Decoding path

### Encoder hierarchy

```
AEncoder<T>                         Format-independent base; owns DecodeState
  └── CAD3Encoder                   CAD3 types: data structures, signed data, numerics
        └── CVMEncoder              CVM types: ops, transactions, consensus records
```

`AEncoder.DecodeState` is a mutable cursor over the backing `byte[]` (position, limit
and a count of branch refs met so far). Reads advance it, so there is no manual
position arithmetic in the decoders. Tag dispatch happens in `read(DecodeState)`, with
`CVMEncoder` overriding the coded-data, dense-record and extension branches to produce
CVM-specific cells.

### Store binding

Every `AStore` constructs its own `CVMEncoder`, and `readRef` builds hash refs against
that store. The store is a field on the encoder, set at construction; nothing in the
decode chain consults a thread-local. The static `CVMEncoder.INSTANCE`, which has no
store, is the entry point for decoding complete values outside any store.

### Storeless decode

A storeless encoder cannot create a store-backed ref, so each encoder class keeps a
singleton bound to `NullStore`. Refs decoded through it are placeholders that are either
replaced by child cells carried in the same message or reported as missing with
`PartialMessageException`. That exception means the bytes are well formed but the
message is partial: a store is required to decode it. Store-bound decode never throws
it; unresolved branches simply remain lazy refs into the store.

## Multi-cell encoding

Network messages carry a top-level cell followed by VLQ-prefixed encodings of its
branches, so a receiver can rebuild a complete value without a store. This format is
shared with the lattice node protocol described in
[CAD036](https://docs.convex.world/docs/cad/lattice_node).

### Encoding

`Format.encodeMultiCell(cell, everything)` writes the top cell and then either every
reachable branch (`everything` true) or only the novelty the caller has chosen. It has
no size limit of its own. `encodeMultiCell(cell, everything, maxSize)` throws
`IllegalArgumentException` before allocating if the result would exceed `maxSize`
(zero or negative means unlimited). `encodeDataResult` wraps the same logic for
`Result` payloads.

Limits belong to callers. `Message.getMessageData()` applies
`CPoSConstants.MAX_MESSAGE_LENGTH`; transports refuse a message whose encoding fails
that check rather than sending a truncated one. Anything that must reply within a
smaller bound decides what to include from `getMemorySize()` first, then encodes once;
speculatively encoding a large value to see whether it fits is a denial-of-service
risk, and `Message.createDataMessages` exists to split the remainder into bounded
follow-up messages.

### Decoding

`AEncoder.decodeMultiCell(Blob)`:

1. Choose the reader: the encoder itself when store-bound, the `NullStore` singleton
   when storeless.
2. Read the top cell.
3. If the data is consumed and no branch refs were met, return it. A store-bound
   decoder also returns here when branches were met, because they resolve lazily.
4. Otherwise read the child cells into a map keyed by hash and replace branch refs
   with the decoded children. Storeless decode throws `PartialMessageException` for any
   branch not in the map.

`Message` exposes two accessors: `getPayload()` returns the cached payload or null and
never decodes; `getPayload(store)` decodes on demand, storelessly when `store` is null
(complete messages, client side) or against the store when given (partial messages,
peer side).

## Where the code lives

| Concern | Location |
|---|---|
| Length invariant, embedding, caching | `convex.core.data.ACell`, `convex.core.data.Ref`, `convex.core.data.Format` |
| Encoder hierarchy | `convex.core.data.AEncoder`, `convex.core.data.CAD3Encoder`, `convex.core.cvm.CVMEncoder` |
| Storeless decode | `convex.core.store.NullStore`, `convex.core.exceptions.PartialMessageException` |
| Multi-cell encode | `Format.encodeMultiCell`, `Format.encodeDataResult` |
| Message limits | `convex.core.message.Message`, `convex.core.cpos.CPoSConstants` |
| Tests | `EncodingTest`, `ObjectsTest`, `MessageTest`, `FormatFuzzTest`, `AdversarialDataTest` |

## Related

- [CAD003 Encoding Format](https://docs.convex.world/docs/cad/encoding) — the normative byte format.
- [CAD036 Lattice Node](https://docs.convex.world/docs/cad/lattice_node) — message framing and value encoding on the lattice protocol.
- `cad3-encoding` skill under `.claude/skills/` — conventions when changing encoding code.
- `convex-peer/docs/MESSAGING.md` — how messages move between peers and clients.
