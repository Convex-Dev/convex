# Encoder Architecture Design

## Overview

The encoder hierarchy handles serialisation and deserialisation of all Convex data types.
The target architecture eliminates thread-local store dependency from the decode chain,
uses `DecodeState` for efficient position-tracked decoding, and provides allocation-free
fast paths for the common case (single-cell messages with no branches).

## Design Rationale

### Why Encoder-Owned Decode?

The encoder owns the decode path because it needs to bind refs to the correct store.
Each store creates a store-bound encoder (`new CVMEncoder(this)`), and `readRef` creates
refs against `this.store` — no thread-local lookup, no implicit state. The encoder's
virtual `read` methods handle tag-based dispatch, with `CVMEncoder` extending `CAD3Encoder`
to decode CVM-specific types (transactions, ops, consensus records).

### Why DecodeState?

DecodeState is a mutable cursor over `byte[]` that auto-advances on each read operation.
This replaces the old pattern of manual position tracking (`epos += ref.getEncodingLength()`)
which was error-prone and verbose. DecodeState extracts the backing byte array from Blob
at construction, giving direct array access without Blob indirection on every byte read.

### Why NullStore for Storeless Decode?

Network messages are multi-cell encoded: a top cell followed by VLQ-prefixed child cells
(branches). 90%+ of messages are single-cell with no branches. To decode these without
a store (client-side), we need `readRef` to not throw when it encounters Tag.REF — but
we also don't want to allocate a temporary store for every message.

Solution: use a NullStore-backed encoder (static singleton, zero allocation). `readRef`
creates NullStore-backed refs that are temporary placeholders, replaced during
`resolveRefs` with actual child cell data from the message. If the message has no
branches (90%+ case), no resolution is needed and the cell is returned immediately.

This replaces the previous `MessageStore` pattern which allocated a HashMap, a new
store, and a new encoder instance for every message — even single-cell messages that
never used any of it.

### Why Branch Counter?

`DecodeState.branchCount` tracks how many non-embedded refs (Tag.REF) were encountered
during decode. This enables the zero-allocation fast path: if `branchCount == 0` and all
data is consumed, the message is a complete single cell — return immediately without
allocating a HashMap or calling `resolveRefs`.

### Why PartialMessageException?

When storeless decode encounters a branch that cannot be resolved from the message's
own child cells, the format is correct but the message is partial — it references data
not included in the encoding. This is not a `BadFormatException` (encoding is valid)
nor a `MissingDataException` (that's for store lookups). `PartialMessageException`
signals that a store is required to decode this message.

Store-based decode never throws `PartialMessageException` — unresolvable branches
are left as lazy refs into the store, resolved on demand.

## Target Architecture

### Encoder Hierarchy

```
AEncoder<T>                         Abstract base, format-independent
  ├── DecodeState (inner class)     Mutable cursor: byte[] data, int pos, int limit, int branchCount
  └── CAD3Encoder                   CAD3 format: data structures, signed data, numerics
        └── CVMEncoder              CVM types: ops, transactions, consensus records
```

Each store creates a store-bound encoder: `new CVMEncoder(this)`. The storeless
`CVMEncoder.INSTANCE` singleton handles `Format.read()` calls. Each encoder subclass
caches a NullStore-backed singleton for storeless multi-cell decode:

- `CAD3Encoder.NULL_STORE_ENCODER` — CAD3 types only
- `CVMEncoder.NULL_STORE_CVM_ENCODER` — CVM-specific types

### Encode Path

Encoding is cell-driven. Each `ACell` subclass implements:
- `encode(byte[] bs, int pos)` — writes tag + fields into byte array
- `getEncoding()` — cached: creates once via `createEncoding()`, returns same Blob thereafter

No encoder involvement — cells know how to encode themselves.

### Decode Path

Decoding is encoder-driven via DecodeState:

```
encoder.decode(Blob)
  └── read(DecodeState ds)           // tag dispatch
        ├── readNumeric(tag, ds)
        ├── readBasicObject(tag, ds)
        ├── readDataStructure(tag, ds)  // readVector, readMap, readSet, readIndex
        ├── readSignedData(tag, ds)
        ├── readCodedData(tag, ds)      // CVMEncoder overrides for ops
        ├── readDenseRecord(tag, ds)    // CVMEncoder overrides for transactions, consensus
        └── readExtension(tag, ds)      // CVMEncoder overrides for Address, Core defs
```

`readRef(ds)` handles branch refs:

```java
readRef(DecodeState ds):
  Tag.REF  → ds.branchCount++; Ref.forHash(hash, this.store)  // non-embedded branch
  Tag.NULL → Ref.nil()                                         // null ref
  other    → this.read(ds); cell.getRef()                      // embedded cell
```

### Multi-Cell Decode

Network messages use multi-cell encoding: top cell followed by VLQ-prefixed
non-embedded child cells (branches).

```
CAD3Encoder.decodeMultiCell(Blob data):
  1. Select encoder:
     - storeless (store==null): nullStoreEncoder() — static NullStore-backed singleton
     - store-based: this
  2. Read top cell via readEncoder.read(ds)
  3. Fast path checks:
     a. branchCount==0 && pos==limit → return immediately (zero allocations)
     b. store-based && pos==limit → return immediately (branches resolve lazily)
  4. Allocate HashMap, read VLQ-prefixed child cells
  5. resolveRefs: replace branch refs using child map
     - storeless: throw PartialMessageException if branch not in child map
     - store-based: leave unresolvable branches as lazy store-backed refs
```

#### getPayload Strategy (Message.java)

Three `getPayload` methods provide explicit control over decode:

| Method | Behaviour | Use case |
|--------|-----------|----------|
| `getPayload()` | Pure accessor, returns cached payload or null | Safe to call anywhere, no side effects |
| `getPayload(null)` | Storeless decode via `CVMEncoder`, RefDirect tree | Client code, complete messages |
| `getPayload(store)` | Store-based decode, branches resolved from store | Server code, partial messages |

#### Allocation Profile

| Scenario | Allocations |
|----------|-------------|
| Single cell, no branches (90%+ of messages) | DecodeState + decoded cell only |
| Single cell with branches, store-based | Same — branches resolve lazily from store |
| Single cell with branches, storeless | Throws PartialMessageException (partial message) |
| Multi-cell, store-based | + HashMap + child cells |
| Multi-cell, storeless | + HashMap + child cells (refs replaced via resolveRefs) |

### Store Propagation

```
encoder.readRef(ds)  →  Ref.forHash(hash, this.store)
```

No thread-local involved in the decode chain. The encoder's `store` field is set at
construction time (one per store instance). The `decode()` / `decodeMultiCell()` entry
points create the DecodeState and the encoder provides all format operations.

### Performance

| Aspect | Old (Format.readRef) | Target (DecodeState) |
|--------|----------------------|----------------------|
| Byte access | `Blob.byteAt(i)` | `data[pos]` — direct array |
| Ref position tracking | `readRef` + `getEncodingLength()` (2 calls) | `readRef(ds)` (1 call, auto-advance) |
| Encoding attachment | `b.slice(pos, epos)` per cell | None — re-encode on demand |
| Store lookup | Thread-local get per ref | Field access on encoder |
| Multi-cell fast path | Always allocates HashMap + MessageStore | Zero allocations for single-cell |

## Remaining Migration

### Status

Phases 1–4 are complete: DecodeState exists, all core data structure and CVM type reads
are on the encoder, `decode()` and `decodeMultiCell()` use DecodeState natively, and
the NullStore encoder pattern replaces MessageStore.

The old static decode infrastructure (`Format.readRef`, `Type.read(Blob, int)`,
thread-local store management) still exists alongside the new path. It needs to be
removed.

### Phase 5: Delete Old Decode Infrastructure

Remove all static decode methods that are now dead code.

**Delete from `Format.java`:**
- `readRef(Blob, int)` — 36 call sites, all migrated except REST API (see below)

**Delete from `Ref.java`:**
- `readRaw(Blob, int)` — only caller is `Format.readRef`, removed above
- `forHash(Hash)` (1-arg) — delegates to `RefSoft.createForHash(hash)` using thread-local.
  13 callers, mostly tests.

**Delete from `RefSoft.java`:**
- `createForHash(Hash)` (1-arg) — uses `Stores.current()`. Only 2 callers:
  `Ref.forHash(Hash)` and 1 test.

**Delete from type classes (~44 files):**
- Old static `read(Blob, int)` methods — no longer called from encoder dispatch.

**Migrate external callers:**
- `convex-restapi/ChainAPI.java` — `Format.readRef(h, 0)` → `Ref.forHash(hash, store)`
- `convex-restapi/McpAPI.java` — same pattern
- `Result.java` — `Format.readRef(messageData, rpos)` → migrate to DecodeState or `Ref.forHash`

**Migrate test callers:**
- `RefTest.java` — 2 `Format.readRef`, 9 `Ref.forHash(Hash)` → 2-arg with explicit store
- `StoresTest.java` — 2 `Ref.forHash(Hash)` → 2-arg
- `GenTestAnyValue.java` — 2 `Ref.forHash(Hash)` → 2-arg
- `MemoryStoreTest.java` — 1 `RefSoft.createForHash(Hash)` → 2-arg
- `SignatureBenchmark.java` — 1 `Ref.forHash(Hash)` → 2-arg

### Phase 6: Remove Thread-Local Store from Decode Chain

With all decode paths using `encoder.readRef(ds)` (which uses `this.store` directly),
the thread-local is no longer needed in the decode chain.

**Modify:** `CAD3Encoder.java`
- Remove all `Stores.current()` / `Stores.setCurrent()` calls from `decode()` and
  `decodeMultiCell()` (if any remain from the old path)

**Verify:** `Stores.current()` is not called anywhere in the decode chain. The
thread-local remains available for other uses (e.g. `ACell.persist()`, direct store
access) but decode is fully decoupled.

## File Inventory

### Encoder hierarchy
- `convex-core/.../data/AEncoder.java` — abstract base + DecodeState inner class
- `convex-core/.../data/CAD3Encoder.java` — CAD3 format operations + multi-cell decode
- `convex-core/.../cvm/CVMEncoder.java` — CVM type dispatch
- `convex-core/.../store/NullStore.java` — singleton store for storeless decode
- `convex-core/.../exceptions/PartialMessageException.java` — storeless decode failure

### Core data structures (reads on encoder)
- `readVector` — VectorLeaf, VectorTree
- `readMap` — MapLeaf, MapTree
- `readSet` — SetLeaf, SetTree
- `readIndex` — Index
- `readBlobTree` — BlobTree
- `readSignedData` — SignedData
- `readCodedData` — CodedValue (CAD3), ops (CVMEncoder)
- `readDenseRecord` — DenseRecord (CAD3), transactions/consensus (CVMEncoder)
- `readExtension` — ExtensionValue (CAD3), Address/Core (CVMEncoder)

### Legacy static infrastructure (to be removed in Phase 5)
- `Format.java` — `readRef(Blob, int)` (36 call sites)
- `Ref.java` — `readRaw(Blob, int)`, `forHash(Hash)` 1-arg (13 callers)
- `RefSoft.java` — `createForHash(Hash)` 1-arg (2 callers)
- ~44 type classes with static `read(Blob, int)` methods
