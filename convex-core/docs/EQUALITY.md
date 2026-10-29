# Equality

How `convex-core` decides whether two cells are equal, and what a comparison may cost.
[CAD003](https://docs.convex.world/docs/cad/encoding) defines value identity: two cells
are equal exactly when their canonical encodings are equal. This document covers how the
Java classes reach that answer cheaply. Read it before changing any `equals` method,
`Ref`, or `Cells.equalsEncoding`. Embedding itself is described in
[Encoder design](ENCODER_DESIGN.md).

## Key considerations

- **Equal means equal encodings.** Every path gives the answer a byte comparison of
  canonical encodings would give. Paths differ in cost, never in result, because peers
  must agree. Non-canonical Java forms (`VectorArray`, `StringSlice`, a long held as a
  `CVMBigInteger`) compare as their canonical form. `CVMDouble` compares raw bits, as
  encoded: `0.0` and `-0.0` differ, and a NaN equals only the same NaN bits.
- **A ref is encoded as its value or its hash.** An embedded child (encoding of at most
  140 bytes) sits inline in its parent's encoding; a branch child appears as a 33-byte
  hash ref. Comparing two refs means comparing these.
- **Compute only the hashes an encoding contains.** A branch cell's hash is needed
  anyway: every parent encoding holds it, Etch stores by it and peers exchange it, so
  computing it during a comparison is work done early and kept. An embedded cell's hash
  is needed only when the cell is a top-level value, a hash map or set key, or hashed
  explicitly. Equality never computes it. Signing an embedded value signs its encoding,
  not its hash.
- **Bounded work per call, the rest cached.** CVM `=` costs flat juice. One comparison
  may build a cell's encoding and hash branch children that lack a hash, and all of that
  stays cached. A recursive walk through branch children that caches nothing is not
  allowed: repeated calls repeat the work, and shared subtrees make it exponential (a
  vector holding one sub-vector twice, nested *d* deep, has 2<sup>*d*</sup> paths but
  only *d* distinct cells). Comparing embedded cells by value is bounded, because an
  embedded cell and all its embedded descendants fit in 140 bytes.
- **No store reads.** Equality must work on partial data. Soft refs always carry their
  hash and compare by it.
- **Cheapest check first:** identity, then type and cheap invariants, then hashes both
  sides already hold, then content, then hashes that must be computed.

## Where each check happens

### Entry points

| Call | Checks | Then |
|---|---|---|
| `ACell.equals(Object)` (final) | identity; a non-cell is unequal | `equals(ACell)` |
| `Cells.equals(a,b)`, `Utils.equals(ACell,ACell)` | identity; null | `a.equals(b)` |
| `Ref.equals(Object)` | a non-ref is unequal | `equals(Ref)` |

### Cells: `equals(ACell)` in each class

Each class checks, in order:

1. Identity.
2. Type, by `instanceof` or tag, judged against the canonical form.
3. Cheap invariants: count, length, mask, shift, scalar fields.
4. Hashes both sides already hold (`ACell.cachedHash()`, which never computes one).
5. One comparison strategy:
   - **Content**: compare fields or bytes directly. Leaf types only.
   - **Children**: compare child refs pairwise with `Ref.equals`, which takes the
     embedded or branch path for each child. Every type with children compares its
     own typed fields this way, with no generic helper and no virtual child access:
     the tree types their child arrays, records their values vector, ops and coded
     values their two refs, closures and multi-ops their data vector, string trees
     their chunk tree.
   - **Encoding**: `Cells.equalsEncoding` compares encodings. Building an encoding
     inlines embedded children and hashes branch children, so it never computes the
     cell's own hash or any embedded cell's hash. Used only where the classes differ
     but the tags agree (a generic `DenseRecord` or `CodedValue` against the typed
     class with that tag), and by `ACAD3Record`, which is only ever decoded.

The cell level never asks whether a cell is embedded: the encoding and the child refs
already reflect it. A comparison never computes the hash of either cell being
compared, only of branch children that lack one.

### Refs: `equals(Ref)`, the embedded or branch decision

Every child comparison goes through a ref. `Ref.isEmbedded()` is the single decision
point, and it is the same test `Ref.encode` and `Ref.createEncoding` use to choose
between inline bytes and a hash ref. The encoding and children strategies therefore
agree by construction.

`RefDirect.equals`:

1. Same ref, same value instance, or null.
2. Either ref is soft, or both already hold a hash: compare hashes.
3. `Ref.isEmbedded()`, a cached flag or else a length check bounded by 140 bytes at
   each level. The first check on a fresh tree examines a few children per level, which
   is sublinear in the tree and far below hashing it, and the flag is then cached.
   Embedded on one side only: unequal. Embedded on both: `Cells.equals` on the values.
4. Branch on both: `getHash()` on each, computed and cached on the refs.

`RefSoft.equals` compares its hash with the other ref's hash, computing the other's if
needed.

## Exceptions

| Class | Deviation | Reason |
|---|---|---|
| Leaf types: `CVMLong`, `CVMDouble`, `CVMBool`, `CVMChar`, `Keyword`, `Symbol`, `Address`, `AExtensionValue`, `StringShort`, `AArrayBlob` (with `Blob`, `Hash`, `AccountKey`) | Compare content and may skip step 4 | No children, and canonical blobs hold at most 4096 bytes: a direct comparison is cheap, bounded and allocates nothing. A longer `Blob` is non-canonical and compares encodings. |
| `CVMLong`, `CVMBigInteger` | Compare numerically across classes, with no tag check | A non-canonical `CVMBigInteger` holding a long equals the `CVMLong` of that value, although its tag differs. |
| `RefSoft`, and `RefDirect` against a soft ref | May compute the hash of an embedded value on the direct side | Reading the soft ref's value may need the store. Soft refs normally point at stored branch cells. |

A new exception belongs in this table with its reason. No exception may change a result.

## Consequences

- Comparing a new version of a tree with its predecessor hashes the branch nodes below
  the changed path; shared children match by identity or cached hash, and the roots
  themselves are never hashed or encoded. The work is kept if that version is stored
  or published. Updates inside a lattice draft do not compare values (only `sync()`
  does), so high-rate writers batch there.
- Separately built equal values compare equal without either root being encoded or
  hashed. `convex.test.Assertions.assertEqualityCachesNothing` checks this for each
  type with children.
- `ACell.hashCode()` uses the encoding, so it agrees with equality but builds the
  encoding. `Ref.hashCode()` computes the hash, embedded or not. Java maps keyed by
  cells or refs pay for this; key by `Hash` where the hash already exists.
