# Encoder Architecture Design

## Overview

The encoder hierarchy handles serialisation and deserialisation of all Convex data types. This document describes the current architecture, the `DecodeState` optimisation, and the planned migration.

## Current Architecture

### Encoder Hierarchy

```
AEncoder<T>                         Abstract base, format-independent
  └── CAD3Encoder                   CAD3 format: data structures, signed data, numerics
        └── CVMEncoder              CVM types: ops, transactions, consensus records
```

Each store creates a store-bound encoder: `new CVMEncoder(this)`. The storeless `CVMEncoder.INSTANCE` singleton handles static `Format.read()` calls.

### Encode Path

Encoding is cell-driven. Each `ACell` subclass implements:
- `encode(byte[] bs, int pos)` — writes tag + fields into byte array
- `getEncoding()` — cached: creates once via `createEncoding()`, returns same Blob thereafter

No encoder involvement — cells know how to encode themselves.

### Decode Path (Current)

Decoding is encoder-driven with static delegation to type classes:

```
AStore.decode(Blob encoding)
  └── CVMEncoder.decode(Blob)           // entry point, manages thread-local store
        └── read(Blob, 0)               // extracts tag
              └── read(tag, Blob, offset)  // virtual dispatch by tag category
                    └── Type.read(Blob, pos)   // static method on type class
```

Each type's static `read(Blob, int)` method:
1. Reads fields sequentially using `Format.readRef(b, pos)`
2. Tracks position manually: `epos += ref.getEncodingLength()`
3. Attaches encoding: `result.attachEncoding(b.slice(pos, epos))`

### Multi-Cell Decode (Current)

Network messages use multi-cell encoding: top cell followed by VLQ-prefixed child cells.

```
CAD3Encoder.decodeMultiCell(Blob data)
  1. Set thread-local store (or install MessageStore for storeless decode)
  2. Read top cell via read(data, 0)
  3. Read VLQ-prefixed child cells into HashMap<Hash, ACell>
  4. Replacement scan: resolve non-embedded Refs from child map
  5. Restore thread-local store
```

### Problems with Current Decode

1. **Thread-local store dependency**: `Format.readRef()` → `Ref.readRaw()` → `RefSoft.createForHash(hash)` captures `Stores.current()`. Every non-embedded ref created during decode binds to whatever store the thread-local happens to hold.

2. **Manual position tracking**: Every type `read()` method must call `epos += ref.getEncodingLength()` after each `Format.readRef()`. This is:
   - Error-prone (easy to forget, get wrong)
   - Indirect (`getEncodingLength()` → `getEncoding().size()`, though O(1) after read)
   - Verbose (two lines per ref read instead of one)

3. **Static scattered decode**: ~33 type classes each have a static `read(Blob, int)` method. The encoder dispatches to them but doesn't own the decode logic.

## DecodeState Design

### Principle

Separate concerns:
- **DecodeState**: Format-independent mutable cursor over `byte[]`. Lives on `AEncoder`.
- **CAD3Encoder**: Owns CAD3-format-specific operations (`readRef`, `readVLQCount`, tag dispatch). Uses `this.store` for ref creation.
- **Type classes**: Structural read logic stays close to the type (optional; can migrate to encoder later).

### DecodeState (inner class on AEncoder)

```java
public abstract class AEncoder<T> {

    /**
     * Mutable decode cursor over a byte array. Format-independent:
     * just tracks position. No domain-specific operations.
     *
     * Constructed from a Blob, extracts the backing byte[] for
     * direct array access (no Blob indirection per byte read).
     */
    public static class DecodeState {
        public final byte[] data;   // backing array
        public int pos;             // current absolute position
        public final int limit;     // end boundary

        public DecodeState(Blob source) {
            this.data = source.getInternalArray();
            this.pos = source.getInternalOffset();
            this.limit = this.pos + (int) source.count();
        }

        /** Read and advance past one byte */
        public byte readByte() {
            return data[pos++];
        }

        /** Advance to new absolute position, throws if past limit */
        public void advanceTo(int newPos) { ... }

        /** Remaining bytes */
        public int remaining() {
            return limit - pos;
        }
    }
}
```

### CAD3 Operations (on CAD3Encoder)

```java
public class CAD3Encoder extends AEncoder<ACell> {
    protected final AStore store;

    /**
     * Read a Ref, advancing state.pos. For non-embedded refs,
     * creates RefSoft bound to this.store (no thread-local).
     */
    public <T extends ACell> Ref<T> readRef(DecodeState ds) throws BadFormatException {
        byte tag = ds.data[ds.pos];
        if (tag == Tag.REF) {
            ds.pos++;
            Hash h = Hash.wrap(ds.data, ds.pos);
            ds.pos += Hash.LENGTH;
            Ref<T> ref = RefSoft.createForHash(h, store);
            return ref.markEmbedded(false);
        }
        if (tag == Tag.NULL) {
            ds.pos++;
            return Ref.nil();
        }
        // Embedded cell — dispatch through this encoder's virtual read
        ACell cell = this.read(ds);
        if (!cell.isEmbedded()) throw new BadFormatException("Non-embedded cell as ref");
        return cell.getRef();
    }

    /** Read VLQ count, advancing state.pos */
    public long readVLQCount(DecodeState ds) throws BadFormatException {
        // Delegates to Format.readVLQCount(byte[], int) and advances pos
    }

    /** Tag-dispatch read from DecodeState */
    public ACell read(DecodeState ds) throws BadFormatException {
        int startPos = ds.pos;
        byte tag = ds.readByte();
        return read(tag, ds, startPos);
    }

    /** Override point: dispatch by tag to type-specific reads */
    protected ACell read(byte tag, DecodeState ds, int startPos) throws BadFormatException {
        // Mirrors existing read(byte, Blob, int) switch
    }
}
```

### Type Read Methods (migrated form)

```java
// VectorLeaf — before:
static VectorLeaf read(long count, Blob b, int pos) {
    int rpos = pos + 1 + Format.getVLQCountLength(count);
    Ref[] items = new Ref[n];
    for (int i = 0; i < n; i++) {
        Ref ref = Format.readRef(b, rpos);   // uses Stores.current()!
        items[i] = ref;
        rpos += ref.getEncodingLength();      // manual tracking
    }
    result.attachEncoding(b.slice(pos, rpos)); // caches encoding
}

// VectorLeaf — after:
static VectorLeaf read(long count, CAD3Encoder enc, DecodeState ds) {
    Ref[] items = new Ref[n];
    for (int i = 0; i < n; i++) {
        items[i] = enc.readRef(ds);
    }
    if (count > MAX_SIZE) pfx = enc.readRef(ds);
    return new VectorLeaf(items, pfx, count);
    // No attachEncoding — type re-encodes on demand if needed
}
```

## Store Propagation

### Current: Thread-Local

```
Stores.setCurrent(store)  →  Format.readRef()  →  Ref.readRaw()
                                                     →  RefSoft.createForHash(hash)
                                                          →  Stores.current()  // captures!
```

### After: Encoder Field

```
encoder.readRef(ds)  →  RefSoft.createForHash(hash, this.store)
```

No thread-local involved in the decode chain. The encoder's `store` field is set at construction time (one per store instance). The `decode()` / `decodeMultiCell()` entry points create the DecodeState and the encoder provides all format operations.

## Performance Characteristics

| Aspect | Current | With DecodeState |
|--------|---------|-----------------|
| Byte access | `Blob.byteAt(i)` = `store[offset+i]` | `data[pos]` = direct array |
| Ref position tracking | `readRef` + `getEncodingLength()` (2 calls) | `readRef(ds)` (1 call, auto-advance) |
| Encoding attachment | `b.slice(pos, epos)` = Blob.wrap (cached) | None — re-encode on demand (less GC pressure) |
| Store lookup | Thread-local get per ref | Field access on encoder |
| Allocations per decode | Cell + Refs + encoding Blob | Same + 1 DecodeState (reused) |

`Blob.slice()` and `Blob.wrap()` are both O(1) — they share the backing `byte[]`.

## Migration Plan

### Phase 1: Additive Infrastructure (no behaviour change)

Add DecodeState to AEncoder. Add `readRef(DecodeState)`, `readVLQCount(DecodeState)`, `read(DecodeState)` to CAD3Encoder. Override dispatch in CVMEncoder. Both old and new decode paths coexist.

Files: `AEncoder.java`, `CAD3Encoder.java`, `CVMEncoder.java`

### Phase 2: Migrate Core Data Structures

Convert type `read(Blob, int)` → `read(CAD3Encoder, DecodeState)` for:
VectorLeaf, VectorTree, Vectors, MapLeaf, MapTree, Maps, SetLeaf, SetTree, Sets, Index, BlobTree, SignedData, CodedValue, DenseRecord

~12 files. Encoder dispatch methods call new versions.

### Phase 3: Migrate CVM Types

Ops (~11), transactions (~4), consensus (~4), other (~2). ~18 files.

### Phase 4: Wire DecodeState into Production Entry Points

Once all type reads are migrated (Phase 2-3), the bridge `read(byte, DecodeState, int)` is gone and all reads go through DecodeState natively. Now wire DecodeState into the production decode entries.

**Modify:** `CAD3Encoder.java`
- `decode(Blob)`: Create `DecodeState`, call `read(ds)`. No thread-local needed.
- `decodeMultiCell(Blob)`: Create `DecodeState`, read top cell via `read(ds)`, read children via `readChildCells(acc, ds)`. No thread-local needed.
- Delete `decodeMultiCellWithStore(AStore, Blob, int)` — thread-local management no longer required.
- Delete `resolveChildren(ACell, Blob, int, int)` — replaced by DecodeState path.
- Delete `readChildCells(HashMap, Blob, int, int)` — replaced by DecodeState version.
- Simplify or delete `MessageStore` inner class — may still be needed for storeless decode if `readRef(ds)` encounters a non-embedded ref with `this.store == null`. Evaluate whether storeless decode is a real use case or can be rejected.

### Phase 5: Delete Old Decode Infrastructure

Remove all static decode methods that are now dead code.

**Delete from `Format.java`:**
- `readRef(Blob, int)` — 36 call sites, all migrated by Phase 2-3 except REST API (see below)

**Delete from `Ref.java`:**
- `readRaw(Blob, int)` — only caller is `Format.readRef`, removed above
- `forHash(Hash)` (1-arg) — delegates to `RefSoft.createForHash(hash)` using thread-local. 13 callers, mostly tests.

**Delete from `RefSoft.java`:**
- `createForHash(Hash)` (1-arg) — uses `Stores.current()`. Only 2 callers: `Ref.forHash(Hash)` and 1 test.

**Delete from type classes (~44 files):**
- Old static `read(Blob, int)` methods — either delete outright or make them delegate to the new `read(CAD3Encoder, DecodeState)` form. Deletion is cleaner since the encoder dispatch methods no longer call them.

**Migrate external callers:**
- `convex-restapi/ChainAPI.java:991` — `Format.readRef(h, 0)` → use `Ref.forHash(hash, store)` directly
- `convex-restapi/McpAPI.java:493` — same pattern
- `Result.java:565` — `Format.readRef(messageData, rpos)` → migrate to DecodeState or use `Ref.forHash`

**Migrate test callers:**
- `RefTest.java` — 2 calls to `Format.readRef`, 9 calls to `Ref.forHash` (1-arg) → use 2-arg version with explicit store
- `StoresTest.java` — 2 calls to `Ref.forHash` (1-arg) → use 2-arg version
- `GenTestAnyValue.java` — 2 calls to `Ref.forHash` (1-arg) → use 2-arg version
- `MemoryStoreTest.java` — 1 call to `RefSoft.createForHash` (1-arg) → use 2-arg version
- `SignatureBenchmark.java` — 1 call to `Ref.forHash` (1-arg) → use 2-arg version

### Phase 6: Remove Thread-Local Store from Decode Chain

With all decode paths using `encoder.readRef(ds)` (which uses `this.store` directly), the thread-local is no longer needed in the decode chain.

**Modify:** `CAD3Encoder.java`
- Remove all `Stores.current()` / `Stores.setCurrent()` calls from `decode()` and `decodeMultiCell()` (10 call sites total)
- Remove `MessageStore` inner class if storeless decode is handled differently (e.g. reject, or require store at encoder construction)

**Verify:** `Stores.current()` is no longer called anywhere in the decode chain. The thread-local remains available for other uses (e.g. `ACell.persist()`, direct store access) but decode is fully decoupled.

## File Inventory

### Encoder hierarchy
- `convex-core/.../data/AEncoder.java` — abstract base + DecodeState inner class
- `convex-core/.../data/CAD3Encoder.java` — CAD3 format operations
- `convex-core/.../cvm/CVMEncoder.java` — CVM type dispatch

### Core data structures using Format.readRef
- `VectorLeaf.java`, `VectorTree.java` — vector nodes
- `MapLeaf.java`, `MapTree.java` — hash map nodes
- `SetLeaf.java`, `SetTree.java` — hash set nodes
- `Index.java` — sorted index
- `BlobTree.java` — large blob tree nodes
- `SignedData.java` — signed data wrapper
- `CodedValue.java`, `DenseRecord.java` — generic CAD3 containers

### CVM types using Format.readRef
- `ops/Set.java`, `ops/Def.java`, `ops/Lambda.java`, `ops/Lookup.java`, `ops/Query.java`
- `ops/Cond.java`, `ops/Do.java`, `ops/Invoke.java`, `ops/Try.java`, `ops/Constant.java`, `ops/Special.java`
- `transactions/Transfer.java`, `transactions/Call.java`, `transactions/Invoke.java`, `transactions/Multi.java`
- `cpos/Block.java`, `cpos/BlockResult.java`, `cpos/Belief.java`, `cpos/Order.java`
- `Syntax.java`, `State.java`

### Static utility (to be cleaned up in Phase 5)
- `Format.java` — `readRef(Blob, int)` static method (36 call sites)
- `Ref.java` — `readRaw(Blob, int)`, `forHash(Hash)` 1-arg (13 callers, mostly tests)
- `RefSoft.java` — `createForHash(Hash)` 1-arg (2 callers)

### External callers (to be migrated in Phase 5)
- `convex-restapi/ChainAPI.java` — `Format.readRef(h, 0)` for hash-based ref lookup
- `convex-restapi/McpAPI.java` — same pattern
- `convex-core/Result.java` — `Format.readRef(messageData, rpos)` for result decoding
