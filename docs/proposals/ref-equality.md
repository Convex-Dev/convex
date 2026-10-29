# Ref equality: hash branch children only when reached

- Status: Implemented on `develop` (all four changes, plus the same children path for
  `MapTree`, `SetTree`, `Index`, `DenseRecord` and `MapEntry`)
- Last updated: 2026-10-29
- Specification: [CAD003](https://docs.convex.world/docs/cad/encoding) defines value
  identity: two cells are equal exactly when their canonical encodings are equal. Nothing
  here changes a result, only which hashes are computed and when.
- Design: [Equality](../../convex-core/docs/EQUALITY.md) describes how equality works
  once these changes are in. This document is the change set and its evidence.

## Summary

There are two ways to compare cells that have children:

- **Encoding** (`Cells.equalsEncoding`): build both encodings and compare the bytes.
  `Ref.encode` writes the hash of every branch child, so this hashes every unhashed
  branch child on both sides before anything is compared.
- **Children**: compare child refs pairwise and stop at the first difference. A branch
  child is hashed only if the comparison reaches it and the two refs are not already
  known to be equal by identity or by cached hashes.

The children path should be the cheaper of the two. For small values it is not, because
`RefDirect.equals` hashes every child that differs by identity, including embedded
children whose hashes no encoding contains. Six classes compare values of their own class
by encoding instead, and so hash every branch child up front.

Proposed:

1. `RefDirect.equals` compares embedded values by value, and branch values by hash.
2. `RefSoft.equals` always compares hashes. A soft ref always holds one. Today it can read
   the store instead, and throws when the value is missing.
3. After change 1, the six classes move to the children path, one class per change.
4. `StringTree` compares its chunk trees (a decision is needed first).

Every path keeps three properties: the result a comparison of canonical encodings would
give, no store reads, and symmetry (`a.equals(b)==b.equals(a)`).

## Current behaviour

### Calls to `equalsEncoding`

There are 18 calls. Twelve are fallbacks for a value of a different class:

- Leaf types: `Address`, `AExtensionValue`, `CoreFn`, `Local`, `Special`. An encoding is
  at most about 10 bytes and nothing is hashed.
- Records: `ARecordGeneric`, `State`, `Block`, `BlockResult`, `Order`, `AccountStatus`,
  `PeerStatus.equals(ACell)`. The other side can only be a generic `DenseRecord` with the
  same tag, which holds nothing but hashes for its branch children. Any correct answer
  needs this side's branch hashes, which is what building the encoding computes.

A fallback is reached only when the other side is a generic value with the same tag.
Stores decode through `CVMEncoder`, so this happens only with values that came through a
non-CVM decoder. These calls stay as they are.

Six calls compare two values of the same class:

| Call | Classes | Reached in production by | Branch children |
|---|---|---|---|
| `AOp.equals` | every op except `Local` and `Special` | CVM `=` on ops | sometimes: a `Constant` of a large value, a long `Do` |
| `AFn.equals` | `Fn`, `MultiFn` | CVM `=` on closures | often: environment, body |
| `ATransaction.equals` | `Transfer`, `Invoke`, `Call`, `Multi` | direct calls only (`SignedData` compares signature, key, then the value ref) | an `Invoke` command, `Multi` |
| `PeerStatus.equals(PeerStatus)` | `PeerStatus` | CVM `=` | the stakes `Index` |
| `CodedValue.equals` | generic coded values | lattice data with tags the CVM doesn't define | yes |
| `StringTree.equals(AString)` | strings over 4096 bytes | CVM `=`, lattice merges | always: every chunk |

`PeerStatus` is the only `ARecordGeneric` subclass whose same-class comparison uses the
encoding. The others compare their values vectors.

### `RefDirect.equals`

```java
public boolean equals(Ref<T> a) {
	if (a == this) return true;
	if (a instanceof RefDirect) {
		T va=((RefDirect<T>) a).value;
		if (value==va) return true; // fast path
		if (value==null) return va==null; // catch nulls
		if (this.hash != null) {
			Hash ha=a.hash;
			if (ha != null) return this.hash.equals(ha);
		}
	}
	return getHash().equals(a.getHash());
}
```

If the two refs are not the same object and don't hold the same value instance, and
either lacks a cached hash, the comparison falls through to `getHash()`. That builds the
value's encoding and runs SHA3-256 over it. For an embedded child the hash is computed
only for this comparison: no parent encoding contains it.

### `RefSoft.equals`

```java
public boolean equals(Ref<T> a) {
	if (a.hash!=null) {
		// prefer hash comparison, this avoid potential store lookups
		return hash.equals(a.hash);
	}
	// compare by value
	return Cells.equals(getValue(),a.getValue());
}
```

When the other ref has no hash yet (an unhashed `RefDirect`), this reads its own value
and compares the two values. That read goes to the store if the soft reference was
cleared or never set. If the value is not in the store, it throws, while the same
comparison the other way round succeeds:

```java
// v is a branch vector; soft refers to its hash in a store that lacks it
Ref<AVector<ACell>> soft=Ref.forHash(v.getHash(), new MemoryStore());
direct1.equals(soft); // true: RefDirect compares hashes
soft.equals(direct2); // MissingDataException
```

`direct1` and `direct2` are refs to two separately built copies of `v`, neither yet
hashed.

## Change 1: `RefDirect.equals` compares embedded values by value

```java
@Override
public boolean equals(Ref<T> a) {
	if (a == this) return true;
	if (a instanceof RefDirect) {
		T va=((RefDirect<T>) a).value;
		if (value==va) return true; // fast path
		if ((value==null)||(va==null)) return false; // only one is null
		Hash h=this.hash;
		if (h != null) {
			Hash ha=a.hash;
			// use hash if available for both Refs
			if (ha != null) return h.equals(ha);
		}
		// Embedded values compare by value: bounded, and their hashes are rarely needed
		boolean em=isEmbedded();
		if (em!=a.isEmbedded()) return false;
		if (em) return value.equals(va);
	}
	// Branch values compare by hash, computed and cached, so shared subtrees stay linear
	return getHash().equals(a.getHash());
}
```

### Why the result is unchanged

- **Embedded status depends only on the canonical encoding.** `Ref.isEmbedded()` uses
  `ACell.getEncodingLength(int)`, which measures the canonical form when a cell is not
  canonical. Equal values have the same canonical encoding, so if only one side is
  embedded the values cannot be equal.
- **Comparing embedded values gives the same answer as comparing their hashes.** The walk
  covers at most 140 bytes of embedded content. Any branch child inside is compared by
  hash at its own level, and that hash is cached.
- **Branch values still compare by hash.** The hash is computed and cached on the refs, so
  shared subtrees are visited once and a deep comparison never goes exponential.
- **Cached hashes are checked before embedded status.** Two refs that both hold a hash
  are answered without looking at the values.

### Cost

- **Branch values:** `isEmbedded()` now runs before `getHash()`. Its first call on a
  freshly built value costs more as the tree grows (see "Open questions"), but much less
  than hashing that tree, and the result is then cached on the ref.
- **Second comparison of the same pair:** embedded children are walked again. Today the
  hashes cached by the first comparison would answer it.
- **Classes reached:** embedded children now reach their own class's `equals`. For
  example, comparing two separately decoded `SignedData` transactions reaches
  `ATransaction.equals`, and comparing two `Do` ops reaches `Constant.equals`. Classes
  still on the encoding path then build encodings of at most 140 bytes instead of
  hashing. That is cheaper, but it leaves them on the path that change 3 replaces.

Timings for classes that already compare child by child (µs, fresh pairs unless noted):

| Value | Equal | Last element differs | Second comparison |
|---|---|---|---|
| Vector of 16 longs | 7.2 → 0.32 | 7.5 → 0.29 | 0.29 → 0.24 |
| Map of 8 entries | 3.9 → 0.34 | 0.56 → 0.06 | 0.30 → 0.18 |
| Vector of 2000 longs | 114 → 123 | 127 → 127 | 0.66 → 0.72 |
| Index of 500 entries | 176 → 173 | 194 → 197 | 0.21 → 0.93 |

Small values gain the most: today both sides hash every embedded child. For large values
the time is dominated by hashing the branch nodes, which both versions must do. The
regression is in the Index's second comparison, where its embedded nodes are walked again.

## Change 2: `RefSoft.equals` compares hashes only

```java
@Override
public boolean equals(Ref<T> a) {
	// A soft ref always has a hash: compare hashes, never read the store
	return hash.equals(a.getHash());
}
```

### Why the hash is always there

The class contract says a `RefSoft` must always have a non-null hash, so that its value
can be looked up in a store. The code keeps to it:

- `RefSoft.create` computes the hash from the value.
- `RefSoft.createForHash`, reached through `Ref.forHash` for hash refs that decoders and
  stores read, takes the hash as given.
- `withFlags`, `withStore` and `withValue` copy it.
- `validate()` rejects a null hash.

Nothing else constructs a `RefSoft`.

### Effect

- **No store reads.** Equality works on partial data, which fixes the exception above.
- **Symmetric.** `RefDirect.equals` already compares hashes when the other ref is soft.
- **Cost:** an unhashed direct ref on the other side gets hashed.
  - A branch value needs that hash anyway.
  - An embedded value does not, and `EQUALITY.md` lists this as an exception. Soft refs
    normally point at stored branch cells, so it is rare.
  - Checking embedded status first does not help. A soft ref created from a hash has
    unknown flags, so its `isEmbedded()` would read the store.

Not timed. In place of a value read and a value comparison, it computes at most one hash.

## Change 3: same-class comparisons use the children

These depend on change 1. Without it the children path hashes every embedded child, and
on small values it is slower than the encoding. Each lands as its own change, shown
before and after first.

| Class | Today | Proposed |
|---|---|---|
| `ATransaction` | `equalsEncoding` | Delete the override and inherit `ARecordGeneric.equals`: tag, cached hashes, then `values.equals`. `ARecordGeneric.encode` is final and writes `[tag][values body]`, so this is exact. |
| `PeerStatus` | `equalsEncoding` for its own class | Delete both overrides, with the same effect. |
| `ACodedOp`: `Constant`, `Def`, `Lambda`, `Let`, `Lookup`, `Query`, `Set`, `Try` | `AOp.equals` → `equalsEncoding` | Tag, cached hashes, `code` ref, `value` ref. The encoding is `[tag][code][value]`. |
| `AMultiOp`: `Cond`, `Do`, `Invoke` | the same | Tag, cached hashes, `ops.equals`. The encoding is `[tag][ops body]` (`AFlatMultiOp`). |
| `AClosure`: `Fn`, `MultiFn` | `AFn.equals` → `equalsEncoding` | Cached hashes, `data.equals`. Both encode as `[FN][data body]`, and the first data element tells them apart. |
| `CodedValue` | `equalsEncoding` | Tag, cached hashes, `codeRef`, `valueRef`. |

Every class keeps `equalsEncoding` as the fallback for other classes, since a generic value
with the same tag may be on the other side. For example, `ACodedOp`:

```java
@Override
public boolean equals(ACell o) {
	if (o instanceof ACodedOp<?,?,?> op) {
		if (this==op) return true;
		if (tag!=op.tag) return false;
		Hash h=cachedHash();
		if (h!=null) {
			Hash ho=op.cachedHash();
			if (ho!=null) return h.equals(ho);
		}
		return code.equals(op.code)&&value.equals(op.value);
	}
	return Cells.equalsEncoding(this, o); // generic value with an op tag
}
```

### Expected effect

These timings come from a prototype in which every class compares embedded children by
value and branch children by hash. They show changes 1 and 3 together, not either alone.

Values holding one branch child: a vector of 2000 longs, or for `PeerStatus` a stakes
Index of 500 entries. µs, encoding → children:

| Scenario | `PeerStatus` | `Fn` | `Invoke` | `CodedValue` | `Do` |
|---|---|---|---|---|---|
| Child shared, equal | 77 → 0.6 | 94 → 0.7 | 90 → 0.2 | 92 → 0.2 | 90 → 6.4 |
| Child shared, earlier field differs | 80 → 0.4 | 93 → 0.4 | 94 → 0.2 | 88 → 0.1 | 93 → 0.1 |
| Built twice, earlier field differs | 175 → 0.4 | 178 → 0.4 | 181 → 0.2 | 187 → 0.2 | 182 → 0.2 |
| Built twice, equal | 164 → 166 | 186 → 180 | 178 → 182 | 181 → 179 | 181 → 177 |
| Decoded, equal | 2.2 → 1.7 | 1.1 → 0.71 | 0.76 → 0.40 | 0.51 → 0.33 | 0.66 → 0.41 |
| Second comparison of the same pair | 0.26 → 1.2 | 0.14 → 0.44 | 0.12 → 0.23 | 0.13 → 0.19 | 0.12 → 0.19 |

- **Built twice, equal:** no change. Hashing the branch child is the answer either way.
- **Shared child, or an earlier field differs:** 100 to 1000 times cheaper. The encoding
  path hashes the branch child before it looks at anything.
- **`Do` with a shared child:** 6.4 µs, nearly all of it the first `isEmbedded()` check on
  the fresh vector. `Constant` behaves the same.
- **Second comparison:** the encoding path is ahead by 0.1 to 1 µs, because it caches the
  encodings it builds.

Small values whose children are all embedded (`Transfer`, `Invoke`, `PeerStatus`,
`CodedValue`, `Constant`, `Lookup`, `Do`, `Fn`). Time for children relative to encoding:

| Scenario | Children ÷ encoding |
|---|---|
| Built twice, equal | 0.56–0.73 |
| Built twice, unequal | 0.33–0.67 |
| Children shared | 0.10–0.26 |
| Decoded as one cell, equal | 2.7–7.1 (19–35 ns → 62–238 ns) |
| Second comparison of the same pair | 2.3–5.6 (12–44 ns → 34–160 ns) |

The decoded and repeated cases lose because `CAD3Encoder.decode` and an earlier
comparison leave an encoding cached, and comparing two cached encodings costs only a byte
comparison.

## Change 4: `StringTree`

Strings of 50 KB, µs:

| Scenario | Encoding (today) | Chunk trees | Bytes |
|---|---|---|---|
| Built twice, equal | 168 | 168 | 4.7 |
| Chunks shared | 83 | 0.14 | 0.30 |
| First chunk differs | 166 | 13.9 | 0.32 |
| Last chunk differs | 167 | 170 | 4.8 |
| Decoded, equal | 0.15 | 0.15 | 1.6 |
| Second comparison of the same pair | 0.23 | 1.2 | 4.4 |

Recommended: compare the chunk trees. Add `if (b instanceof StringTree bt) return
data.equals(bt.data);` after the count check, and keep `equalsEncoding` for other string
classes. Comparing bytes is much faster the first time, but it caches nothing. Every CVM
`=` would cost time proportional to the string's length for flat juice, and because
`BlobTree.append` reuses whole subtrees, a string's length can grow much faster than its
number of distinct cells. This needs a decision before it lands.

## Order and verification

1. Change 2 is independent and fixes a bug, so it can land first.
2. Change 1 goes in before any part of change 3.
3. Then change 3, one class per change, and change 4 once decided.

For each change:

- Run the convex-core tests while iterating, then the full `./mvnw -B clean verify`.
- Results must not change. The existing equality tests cover results.
- `EQUALITY.md` already describes the behaviour after changes 1 and 2. It lands with them.

New deterministic tests:

- **Change 2**, in `RefTest`: a soft ref whose value is absent from its store, compared
  in both directions with unhashed direct refs to equal and unequal values. Neither
  direction throws, and both directions give the same answer.
- **Change 1**: comparing two separately built vectors of small values leaves their child
  refs without cached hashes. Equality computes no embedded hash.

## Open questions

These need no change in this proposal.

- **The first `isEmbedded()` on a fresh value grows with the tree.**
  - Times: 0.9 µs for a vector of 100 longs, 4.8 µs for 2,000, 23 µs for 32,000, 120 µs
    for 512,000, and 472 µs for 4 million.
  - Cause: `Ref.getEncodingLength(limit)` calls `isEmbedded()` first, so each child's
    status is judged against the full 140 bytes rather than the budget left, at every
    level.
  - The flag is then cached, and hashing the same tree costs far more.
  - `EQUALITY.md` says this check "stops at 140 bytes", which the code does not do. That
    sentence needs correcting, or the check needs bounding.
- **Comparing cached encodings first.** This would help only values decoded with
  `CAD3Encoder.decode`. `decodeMultiCell`, which decodes messages, attaches no encoding,
  not even on its single-cell fast path. Not proposed.
- **Inline encoding comparisons** in `ACAD3Record.equals`, `MapEntry.equals(AVector)`,
  `VectorLeaf` against a non-leaf vector, and `Blob` over 4096 bytes raise the same
  question with less at stake. They are left for later.

## Measurement notes

Timings come from throwaway programs run against `convex-core` classes on JDK 27. They
are not in the repository. Each figure is the best of three timed rounds after two
warm-up rounds, with fresh pairs in each round.

| Term | Meaning |
|---|---|
| Built twice | The two values are constructed separately from equal parts, with no cached hashes or encodings. |
| Shared | Both values hold the same child instance. |
| Decoded | Each value is decoded from its encoding. Values with a branch child come from `decodeMultiCell`, and small values from `decode`. |
| Second comparison | The same pair is compared again, so whatever the first comparison cached is available. |
