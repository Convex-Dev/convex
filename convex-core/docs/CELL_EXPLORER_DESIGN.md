# CellExplorer Design

**Status:** Ready for implementation — 2026-04-05 (all open questions resolved)

---

## Overview

CellExplorer produces a JSON5-compatible text representation of any CVM `ACell` value,
truncated to fit within a caller-specified storage-size budget. It enables LLMs and
tools to progressively explore arbitrarily large lattice structures without overwhelming
context windows.

**Scope note:** CellExplorer assumes the cell is fully materialised. If the caller
passes a partial lattice with unresolved refs, `MissingDataException` propagates to
the caller — it is the caller's responsibility to load what they need first.

Output is designed to be:
- Readable by any LLM without Convex-specific training
- Parseable as JSON5
- Unambiguous — real data versus structural annotation
- Progressively explorable via path-based drill-down
- Efficient: work proportional to rendered output, never to input size — a
  billion-cell lattice with budget=1KB costs the same as a 1KB cell with budget=1KB

---

## Goals

**Budget unit:** bytes of CAD3 storage as reported by `Cells.storageSize(cell)`.
This helper (in `convex.core.data.Cells`) returns `getMemorySize()` for non-embedded
cells and `getMemorySize() + getEncodingLength()` for embedded cells — so every cell,
including small primitives like `CVMLong` that have `getMemorySize() == 0`, carries
a meaningful non-zero cost. `getMemorySize()` itself is cached in etch and
incrementally maintained; `storageSize` wraps it in O(1). It is a good proxy for
size in bytes including overhead, and correlates with LLM token consumption, memory
cost, and wire size.

**Core rules:**
1. Never invent data. Output only values present in the cell.
2. Annotations live in `/* */` comments only — they cannot be mistaken for values.
3. Empty containers signal truly empty. `{/* ... */}` signals truncation.
4. Budget flows downward. A parent allocates a sub-budget to each child; the child's
   budget consumption never exceeds its allocation.
5. Keys before values. For maps, showing more entries with truncated values is more
   useful than showing fewer fully-expanded entries.

---

## Algorithm: O(n) Budget-Gated Exploration

### Core Invariant

CellExplorer never inspects more cell material than the budget permits. Budget is
checked before every recursive call; each child's allocation never exceeds the
caller's remaining budget. The mechanism is:

> Before descending into any cell, read `Cells.storageSize(cell)` in O(1). If it fits
> within the remaining budget, render in full. Otherwise, truncate: for primitives,
> emit an annotation or partial form; for containers, subtract a small structural
> reserve and divide the remainder among children.

`Cells.storageSize(cell)` is the single source of truth for budget consumption.
Non-embedded parents already sum the storage cost of their non-embedded children in
`getMemorySize()`; embedded cells contribute their own encoding length instead. The
budget counter is independent of `BlobBuilder.count()` — output bytes and budget
units are not the same quantity.

### Top-Level Algorithm

```
explore(cell, bb, budget, indent):
  cellSize = Cells.storageSize(cell)         // O(1), uses etch-cached memorySize

  if cellSize <= budget:
    renderFull(cell, bb, indent)             // fast path — whole subtree fits; delegates to JSON.appendJSON
    return

  // Cell is too large — truncate
  if isLeaf(cell):                           // primitives, strings, blobs
    renderLeafTruncated(cell, bb, budget)
    return

  // Container truncation
  reserve = ANNOTATION_RESERVE               // small constant for /* Map, N keys, SZ */
  if reserve >= budget:
    renderFullyTruncated(cell, bb)           // e.g. {/* Map, 5 keys, 47.5MB */}
    return

  contentBudget = budget - reserve

  if isMap(cell):
    renderMapTruncated(cell, bb, contentBudget, indent)
  else:
    renderSequenceTruncated(cell, bb, contentBudget, indent)
```

### Budget Accounting

Every cell type — primitive or container — exposes `Cells.storageSize(cell)` in O(1).
There is no per-type estimate table: the same call is the budget cost of rendering
any `ACell`.

- **Embedded primitives** (`CVMLong`, `CVMBool`, short `Keyword`/`Symbol`/`Address`/
  `String`, `null`): `getMemorySize()` is zero, but `Cells.storageSize` adds the
  cell's `encodingLength`, so the value rendered always has a non-zero cost. This
  prevents the pathological case where a container of embedded values has near-zero
  budget cost but produces unbounded output.
- **Non-embedded primitives and containers:** `getMemorySize()` includes own encoding
  plus recursive sum of child ref memory sizes. A container whose storage size fits
  within budget renders in full.
- **Sharing:** `getMemorySize()` sums per-ref, so a subtree reachable via multiple
  refs contributes once per ref. Budget accounting does not deduplicate sharing.

The budget unit is deliberately not output-bytes. Callers needing a strict output-byte
ceiling should pick a budget proportionate to their output target (roughly 1:1 for
structured data, ~0.5:1 for blob-heavy data where hex expansion doubles size).

### Leaf Rendering Reuse

For any cell that fits the remaining budget, `renderFull` delegates to the existing
`JSON.appendJSON(BlobBuilder, ACell)` (exposed as public for this purpose). That
method already handles every leaf type correctly — `CVMLong`, `CVMDouble` (NaN /
Infinity), `CVMBigInteger`, `CVMBool`, `CVMChar`, `ABlob` (hex), `AString`
(escaping), `Address`, `ASymbolic`, plus containers via recursive descent.
CellExplorer's own logic exists only for:

- budget gating at each recursion,
- container truncation (partial key/item rendering),
- partial forms for large strings and blobs,
- size and overflow annotations (`/* ... */`).

For map keys, CellExplorer calls `JSON.jsonKey(ACell)` (already public) to coerce
any CVM value to a String, then decides whether that String can be rendered as an
unquoted JSON5 identifier or must be quoted. This means integer keys are
automatically quoted (`"42"`) as JSON5 requires — no special-case code.

### Map Rendering (truncated path)

For maps the key-first principle requires knowing which entries will be shown before
rendering any value. This is done in a single forward scan:

```
renderMapTruncated(map, bb, contentBudget, indent):
  totalEntries = map.count()
  keyCost = 0
  visible = []

  // Scan entries to determine how many we can show.
  // Phase 1 is bounded by visible entries, not total entries — O(output).
  for entry in map:
    kCost = Cells.storageSize(entry.key) + ENTRY_OVERHEAD  // colon, separator, indent
    if keyCost + kCost + MIN_VALUE_BUDGET > contentBudget: break
    visible.add(entry)
    keyCost += kCost

  overflow = totalEntries - visible.size()
  valueBudget = (contentBudget - keyCost) / max(1, visible.size())

  // Render visible entries
  for i, entry in enumerate(visible):
    renderKey(bb, entry.key)              // key formatting (see Key Formatting)
    bb.append(": ")
    explore(entry.value, bb, valueBudget, indent + 1)
    if i < visible.size() - 1: bb.append(separator)

  if overflow > 0:
    bb.append("/* +" + overflow + " more */")
```

Value budget is split equally among visible entries (v1). Surplus from small values is
not redistributed.

### Leaf Truncation

`renderLeafTruncated(cell, bb, budget)` handles the cases where an individual leaf
cell's `storageSize` exceeds the remaining budget. The only leaf types that have a
useful partial form are `AString` and `ABlob`; all other leaves fall back to the
annotation-only form.

- **`AString`:** emit `"` + prefix (as many characters as fit in roughly
  `budget * 4` bytes, respecting UTF-8 code-point boundaries — never truncate
  mid-surrogate) + `..."` + size annotation. Escaping of the prefix reuses
  `JSON.appendCVMStringQuoted`.
- **`ABlob`:** emit `"0x` + hex of the first `N` bytes (where `N ≈ budget * 2`,
  since each byte produces 2 hex characters) + `..."` + size annotation. Uses
  `BlobBuilder.appendHexByte(byte)` per byte.
- **All other primitives (`CVMBigInteger`, `CVMLong`, `CVMDouble`, `Address`,
  `Keyword`, `Symbol`):** emit annotation-only form (e.g. `/* BigInt, 847 digits */`).
  These types are either small enough to always fit, or have no partial form that
  preserves meaningful information.

### Sequence Rendering (truncated path)

```
renderSequenceTruncated(seq, bb, contentBudget, indent):
  totalItems = seq.count()
  consumed = 0                                    // in storageSize units
  shown = 0

  for item in seq:
    remaining = contentBudget - consumed
    if remaining < MIN_ITEM_BUDGET: break
    itemCost = min(Cells.storageSize(item), remaining)
    explore(item, bb, remaining, indent + 1)    // child gets what's left
    consumed += itemCost
    shown++
    if shown < totalItems: bb.append(separator)

  overflow = totalItems - shown
  if overflow > 0:
    bb.append("/* +" + overflow + " more */")
```

Each item receives the remaining budget at the point it is rendered. Items rendered early
that use less than expected leave more for later items. No look-ahead required.

**TODO (v2):** The running-remainder strategy means an early large item can consume
the full remaining budget and cause all later items to be skipped. For sequences
this may produce worse summaries than equal-splitting: seeing the first few bytes
of every item is often more informative than seeing one item in full. Reconsider
falling back to equal-split (as used for maps) if the first item would otherwise
absorb more than its proportional share.

---

## Budget Flow

Budget passes from caller down through containers in `Cells.storageSize()` units.
Each layer subtracts a structural-overhead allowance before dividing the remainder
among children. The structural-overhead figures below are rough constants expressed
in the same unit as the budget — they represent how much of the budget to reserve
for delimiters, separators, and annotations rather than actual cell storage.

**Structural overhead allowance (examples, in budget units):**

| Container | Overhead components |
|-----------|---------------------|
| Map (pretty) | opening `{\n` and closing `}`; per-entry `,\n` separator plus `2 × indent` whitespace; overflow / size annotation |
| Map (compact) | opening `{` and closing `}`; per-entry `,` separator; overflow / size annotation |
| Vector / List | opening `[` and closing `]`; per-entry separator; overflow / size annotation |
| Set | opening `[` and closing `]`; inline `/* Set */` marker; per-entry separator; overflow / size annotation |

**Minimum budgets (below which annotation-only form is emitted):**

| Form | Min (budget units) |
|------|-----------|
| Fully truncated container | ~30 |
| Single primitive | ~10 |
| Map entry (key + truncated value) | ~40 |
| Annotation-only | ~20 |

---

## Writer Path

All output is written directly to a **`BlobBuilder`** (`convex.core.data.util.BlobBuilder`).
No intermediate Java `String` or `StringBuilder` is created.

- `BlobBuilder.append(String)` / `append(AString)` / `append(byte)` / `appendLongString(long)` —
  the primary write operations
- `Strings.create(bb.toBlob())` — final conversion at the top level only

Public API methods create one `BlobBuilder` per call, pass it through internal
`void explore(...)` methods, and convert once at the end. Budget tracking is
independent of `BlobBuilder.count()` — the budget parameter is in
`Cells.storageSize()` units and flows down as a recursive argument.

---

## JSON Reuse

CellExplorer layers on top of `convex.core.util.JSON` rather than reimplementing
leaf rendering. Two methods need to be exposed as public (both currently package or
`private`):

| Capability | Reused from | Current visibility |
|------------|------------|---------------------|
| Full leaf rendering (all CVM primitive types, escape handling, NaN/Infinity, hex blobs, etc.) | `JSON.appendJSON(BlobBuilder, ACell)` | private — needs to be made public |
| String escaping for partial-form strings (`"hello wo..."`) | `JSON.appendCVMStringQuoted(BlobBuilder, CharSequence)` | private — needs to be made public |
| Map key coercion (any `ACell` → `String` suitable as JSON key) | `JSON.jsonKey(ACell)` | already public |

We do **not** reuse `JSON.appendJSON`'s map and sequence handling — those paths are
unbounded and have no budget awareness, so CellExplorer provides its own truncating
container rendering that recursively calls back into `appendJSON` for leaves only.

CellExplorer's `Address` rendering (`"#42"`) and keyword rendering (`":active"`) differ
from `JSON.appendJSON`'s renderings (bare number / bare name). For these two types
CellExplorer does its own formatting before delegating to the escape primitive.

---

## Truncation Forms by CVM Type

| CVM Type | Fits | Partial | Fully truncated |
|----------|------|---------|-----------------|
| `null` | `null` | — | — |
| `CVMBool` | `true` / `false` | — | — |
| `CVMLong` | `30` | — | `/* Integer, 20 digits */` |
| `CVMDouble` | `3.14159` | — | `/* Double */` |
| `CVMBigInteger` | `12345...` | — | `/* BigInt, 847 digits */` |
| `AString` | `"hello"` | `"hello wo..." /* 4.2KB */` | `/* String, 4.2KB */` |
| `ABlob` | `"0x48656c..."` | `"0x4865..." /* Blob, 12MB */` | `/* Blob, 12MB */` |
| `Address` | `"#42"` | — | `/* Address */` |
| `Keyword` | `":active"` | — | `/* Keyword */` |
| `Symbol` | `"foo/bar"` | — | `/* Symbol */` |
| `AVector` | `[1, 2, 3]` | `[1, 2, /* +99 more, 390KB */]` | `[/* Vec, 7204 items, 1.1MB */]` |
| `AList` | `[1, 2, 3]` | `[1, 2, /* +N more, SZ */]` | `[/* List, N items, SZ */]` |
| `AMap` | `{a: 1}` | `{a: 1, /* +7 more, 47.5MB */}` | `{/* Map, 5 keys, 47.5MB */}` |
| `ASet` | `[1, 2 /* Set */]` | `[1, /* +N more, Set, 48KB */]` | `[/* Set, 5000 items, 48KB */]` |

**Size annotation format:** derived from `Cells.storageSize(cell)`.
`< 1024` → `{n}B` · `< 1MB` → `{n.1f}KB` · `< 1GB` → `{n.1f}MB` · else `{n.1f}GB`.

For strings and blobs, the partial form inserts `...` before the closing quote and appends a
size comment. For containers with both overflow and size, the two are merged into a
single comment of the form `/* +N more, SZ */` (see OQ-3).

---

## Key Formatting

Any `ACell` can be a map key. CellExplorer calls `JSON.jsonKey(ACell)` to coerce the
key to a `String`, then decides whether it can be emitted as an unquoted JSON5
identifier (matches `[a-zA-Z_$][a-zA-Z0-9_$]*`) or must be quoted and escaped.

| Key type | Example cell | `jsonKey` result | Rendered key |
|----------|-------------|------------------|--------------|
| Keyword — valid identifier name | `:name` | `"name"` | `name` (unquoted) |
| Keyword — non-identifier name | `:hello-world` | `"hello-world"` | `"hello-world"` (quoted) |
| String — valid identifier | `"active"` | `"active"` | `active` (unquoted) |
| String — non-identifier | `"hello world"` | `"hello world"` | `"hello world"` (quoted, escaped) |
| Integer | `42` | `"42"` | `"42"` (quoted — JSON5 requires it) |
| Address | `#1337` | `"#1337"` | `"#1337"` (quoted — `#` not identifier) |

Note: JSON5's `MemberName` grammar is `IdentifierName | StringLiteral` — numeric
literals are **not** valid object keys, so integer keys must always be quoted. The
unquoted-identifier test naturally excludes digit-leading strings.

Keys exceeding ~100 bytes are truncated with `...` inside the appropriate quoting
form.

---

## Path Drill-Down

**Out of scope.** CellExplorer operates on a single resolved cell. Callers needing
path-based navigation should resolve the target cell first using the existing
`convex.lattice.cursor` infrastructure (`PathCursor` and friends), then pass the
resolved cell to `CellExplorer.explore()`. CellExplorer does not re-implement path
navigation.

---

## Java API

CellExplorer is an instance class. Configuration (budget, compact/pretty) is held
on the instance; rendering is a method. Instances are immutable and reusable.

```java
package convex.core.data.util;

public class CellExplorer {

    private final int budget;
    private final boolean compact;

    /** Create with default (pretty) formatting. */
    public CellExplorer(int budget);

    /** Create with explicit compact flag. */
    public CellExplorer(int budget, boolean compact);

    /** Render a cell. */
    public AString explore(ACell cell);
}
```

**Usage:**

```java
CellExplorer explorer = new CellExplorer(2048);
AString out = explorer.explore(cell);
```

`explore` creates a fresh `BlobBuilder` per call, drives internal recursion, and
returns `Strings.create(bb.toBlob())`. The instance carries configuration
(`budget`, `compact`, plus any future additions like depth limits or annotation
style) so the public API stays simple as options accrue; per-call mutable state
(`BlobBuilder`, remaining budget, current indent) flows through internal method
parameters.

---

## Test Plan

**Location:** `convex-core/src/test/java/convex/core/data/util/CellExplorerTest.java`
**Framework:** JUnit 5 (`@Test`, `org.junit.jupiter.api.Assertions`)

Core test cases:

| # | Scenario | Assertion |
|---|----------|-----------|
| 1 | Small map, budget=200 | Exact output, no annotations |
| 2 | ~50KB map | Partial entries; overflow + size annotations present |
| 3 | budget=80, large map | Two fully-truncated vector values; `/* +3 more, SZ */` |
| 4 | budget=40, large map | `{/* Map, 5 keys, 47.5MB */}` |
| 5 | 100K-integer vector, budget=100 | First items + `/* +N more, SZ */` |
| 6 | 50KB string, budget=60 | Partial `"...` form with size annotation |
| 7 | Empty containers and nil | `null`, `{}`, `[]`, `[/* Set */]` — no annotations |
| 8 | Constructor with compact=true | Explore same cell twice, once per flag; different formatting |

Additional cases:
- All primitive types: fits / partial / fully-truncated per table above
- Compact vs pretty modes (indentation difference, same data)
- Mixed-type map keys (keyword, string, integer, address in same map)
- Deeply nested maps — budget constrains depth naturally
- Budget at exact boundary (off-by-one safety check)
- Genuinely empty vs truncated: `{}` vs `{/* Map, ... */}`

---

## Open Questions

Each item below states the question, the options considered, and the decision taken.
All items are resolved as of 2026-04-05; implementation should follow the decisions
recorded here.

---

### OQ-1 · Keyword key rendering — ACCEPTED (Option A)

**Decision (2026-04-05):** Option A — suppress the `:` prefix, emit unquoted when
the keyword's name is a valid JSON5 identifier, quoted otherwise. Confirmed that
LLMs handle unquoted object keys fluently (standard JavaScript / JSON5 syntax).

**Question:** When a keyword (e.g. `:name`) is used as a map key, should the `:`
prefix be suppressed so it renders as an unquoted JSON5 identifier (`name`), or
should it be retained and quoted (`":name"`)?

**Why it matters:** Keywords are the conventional CVM map key type, so almost every
map has keyword keys. This decision affects most output.

**Trade-off:** Suppressing the prefix costs information (a rendered `name` could have
come from either the keyword `:name` or the string `"name"`), but it's shorter, more
LLM-readable, and the colon-separator already marks the position as a key. Keeping
the prefix is unambiguous but noisy.

**Options:**

- **A. Suppress prefix, unquoted when valid identifier.** `:name` → `name`,
  `:hello-world` → `"hello-world"`. Cleanest for LLM readability.
- **B. Always include `:` prefix, always quoted.** `:name` → `":name"`.
  Unambiguously marks the key as a Convex keyword, at the cost of visual noise.
- **C. Hybrid: include `:` when key set is mixed-type, suppress when all-keyword.**
  Stateful and surprising.

**Tentative recommendation: Option A.** The `:` prefix matters most when
distinguishing keyword values from string values in a mixed context; for map keys
the colon-separator is already present and the position itself marks "this is a
key". The lossy round-trip (keyword vs string key with same rendered form) is an
acceptable trade-off for a summary tool — CellExplorer is a one-way projection, not
a faithful serialisation.

---

### OQ-2 · Exposing JSON internals for reuse — ACCEPTED

**Decision (2026-04-05):** Promote `JSON.appendJSON(BlobBuilder, ACell)` and
`JSON.appendCVMStringQuoted(BlobBuilder, CharSequence)` to public. `JSON.jsonKey`
is already public.

**Question:** CellExplorer wants to reuse `JSON.java`'s existing leaf rendering
(`appendJSON(BlobBuilder, ACell)`) and string escaping (`appendCVMStringQuoted`)
rather than reimplementing them. Both are currently private. How do we expose them?

**Tentative recommendation:** Promote both to public:

- `public static void appendJSON(BlobBuilder bb, ACell value)` — the full CVM leaf
  renderer, used by CellExplorer for any cell that fits budget.
- `public static void appendCVMStringQuoted(BlobBuilder bb, CharSequence cs)` —
  the string-escape primitive, used by CellExplorer for partial-form strings.

`JSON.jsonKey(ACell)` is already public and needs no change. Rationale: minimal
surface-area change, zero allocations in the hot path, no duplication of leaf-type
handling, and `JSON` is the natural home for JSON/CVM text primitives.

---

### OQ-3 · Overflow annotation format — ACCEPTED (Option B)

**Decision (2026-04-05):** Option B — single merged comment `/* +99 more, 390KB */`.
Already threaded through the Truncation Forms table and test-case column.

**Question:** When a container is partially shown and has both an overflow count and a
size, should these appear as two separate `/* */` comments or merged into one?

**Options:**

- **A. Two separate comments:** `[1, 2, /* +99 more */ /* 390KB */]`
- **B. Single merged comment:** `[1, 2, /* +99 more, 390KB */]`
- **C. Single comment, parenthetical size:** `[1, 2, /* +99 more (390KB) */]`

**Tentative recommendation: Option B** — single merged comment. Saves 4 bytes per
truncated container (matters at tight budgets), reads naturally, and is still
unambiguous. The spec does not mandate separate comments; they appear separately in the
spec examples only because they're described as independent fields. Option A is fine too
if Mike prefers visual separation.

---

### OQ-4 · Surplus redistribution in map value rendering — ACCEPTED (Option A for v1)

**Decision (2026-04-05):** Option A for v1 — discard surplus, equal-split per value
slot. Options B/C deferred to v2 backlog.

**Question:** When a map value renders smaller than its equal-share budget allocation,
the leftover bytes are currently discarded (v1 equal-split). Should surplus be
redistributed to remaining values?

**Options:**

- **A. Discard surplus (v1)** — equal split, take-it-or-leave-it per value slot. Simple,
  single-pass, deterministic.
- **B. Redistribute in a second pass** — after rendering all values, re-render those
  that truncated using the combined surplus. Requires buffering intermediate results or
  a second traversal.
- **C. Running redistribution** — after each value is rendered, divide remaining budget
  equally among remaining slots. Still single-pass; later values benefit from earlier
  cheap values. Slightly more complex accounting.

**Tentative recommendation: Option A for v1.** Options B and C increase complexity
significantly and break the clean single-pass property. In practice, values within a
map tend to be similar in size, so equal-split works well. Add to v2 backlog.

---

### OQ-5 · Sequence item sampling strategy — ACCEPTED (Option C)

**Decision (2026-04-05):** Option C — head-only rendering in v1, with an extensible
internal signature so `strategy=head|tail|both` can be added later without changing
the public API.

**Question:** The spec notes a future option `strategy=head|tail|both`. Should this be
implemented in v1 or deferred?

**Options:**

- **A. Head-only, no parameter (v1)** — always show items from index 0. Simple.
- **B. Add `strategy` enum parameter in v1** — implement head, tail, both. Tail requires
  knowing total count and skipping, which costs O(skipped) iteration. `both` requires
  splitting the budget and two ranges.
- **C. Head-only for v1, but design the internal API to accommodate strategy later**
  (i.e. don't make assumptions that close the door on tail/both).

**Tentative recommendation: Option C.** Ship head-only. Keep the internal sequence
render method signature extensible (e.g. a `strategy` parameter that defaults to HEAD
and is not yet exposed in the public API). This costs nothing now and avoids the
O(skipped) iteration of tail mode until it's actually needed.

---

### OQ-6 · Depth limits — ACCEPTED (Option A)

**Decision (2026-04-05):** Option A — no hard depth limit. Budget constrains depth
naturally; stack depth is bounded by `budget / MIN_CONTAINER_OVERHEAD`, which for
any realistic budget stays well within JVM defaults.

**Question:** Should there be a hard maximum recursion depth, or is budget-natural
depth limiting sufficient?

**Options:**

- **A. No hard depth limit** — budget constrains depth naturally. At budget=500, even a
  structure with 1000 levels of nesting can only output ~500 bytes, so depth is bounded.
- **B. Configurable `maxDepth` parameter** (default unlimited or e.g. 50).
- **C. Hard-coded limit** (e.g. 50 levels) as a safety rail against pathological inputs.

**Tentative recommendation: Option A.** Budget is already a natural depth limit. A
structure deep enough to overflow the stack would also exhaust the budget long before
doing so. The call stack depth is bounded by `budget / MIN_CONTAINER_OVERHEAD` ≈
`budget / 30`, so a budget of 10,000 bytes gives at most ~333 recursion levels, well
within JVM defaults. No additional guard needed.

---

### OQ-7 · ~~`getMemorySize()` fast-path reliability for blob-heavy containers~~ — RESOLVED

**Resolution:** The budget unit has been clarified as bytes of CAD3 storage per
`getMemorySize()`, not bytes of JSON output. Hex expansion when rendering blobs no
longer creates a correctness issue — the budget measures source cell material, not
output bytes. A 500-byte blob consumes 500 budget units and produces ~1010 output
bytes; this is expected and documented.

Callers needing a strict output-byte ceiling should pass a smaller budget
(approximately half the desired output ceiling for blob-heavy data) or post-truncate.

---

### OQ-8 · `NaN` and `Infinity` rendering for `CVMDouble` — ACCEPTED (narrowed v1)

**Decision (2026-04-05):** CellExplorer special-cases `CVMDouble` before delegating
to `JSON.appendJSON`. Non-finite values render as JSON5 literals: `NaN`, `Infinity`,
`-Infinity`. Finite values fall through to the existing `JSON.appendJSON` path.

**Why narrowed from the original Option A:** The original plan was for CellExplorer
to delegate all leaf rendering to `JSON.appendJSON` (OQ-2) and rely on `JSON.java`
to emit JSON5 literals for non-finite doubles. Investigation showed `JSON.java` has
**no JSON5 writer path at all** — only a single `appendJSON` method with inconsistent
behaviour (renders `NaN` as the literal `NaN`, but renders `±Infinity` as `null` with
the literal form commented out at lines 213-216). Fixing `JSON.java` would affect
every existing caller and is out of scope for CellExplorer v1. The narrow
CellExplorer-internal special-case keeps the JSON5 claim honest with zero blast
radius on other JSON callers.

**Follow-up issues filed:**

- #546 — Add a proper `JSON.appendJSON5(BlobBuilder, ACell)` writer as a peer to
  the existing `JSON5Reader`. Once it lands, CellExplorer's `CVMDouble` special-case
  can migrate to delegate to the new method.
- #547 — Audit `JSON.appendJSON` for strict JSON compliance. Emitting `NaN` as a
  literal is non-standard JSON; `null` is the conventional substitute. Fix requires
  understanding the existing caller set — separate decision from this design.
- #548 — Add test coverage for `JSON5Reader` round-tripping `NaN`, `Infinity`,
  `-Infinity` literals.

---

### OQ-9 · ~~Path segment matching: keyword vs string lookup order~~ — MOOT

**Resolution (2026-04-05):** Path drill-down has been removed from CellExplorer's
scope entirely. Callers navigate to the target cell using the existing
`convex.lattice.cursor` infrastructure before calling `explore()`. CellExplorer
never sees a path, so the lookup-order question no longer applies.
