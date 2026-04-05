# CellExplorer Design

**Status:** Draft — April 2026

---

## Overview

CellExplorer produces a JSON5-compatible text representation of any CVM `ACell` value,
truncated to fit within a caller-specified byte budget. It enables LLMs and tools to
progressively explore arbitrarily large lattice structures without overwhelming context
windows.

Output is designed to be:
- Readable by any LLM without Convex-specific training
- Parseable as JSON5
- Unambiguous — real data versus structural annotation
- Progressively explorable via path-based drill-down
- Efficient: O(n) in output size, space-bounded by the budget

---

## Goals

**Budget unit:** bytes of CAD3 storage as reported by `cell.getMemorySize()`. This
value is cached in etch, incrementally recomputed as structures mutate, and covers
both the cell's own bytes and its referenced children — making it a natural
hierarchical measure with no rendering required. It is a good proxy for size in
bytes including overhead, and correlates with LLM token consumption, memory cost,
and wire size.

**Core rules:**
1. Never invent data. Output only values present in the cell.
2. Annotations live in `/* */` comments only — they cannot be mistaken for values.
3. Empty containers signal truly empty. `{/* ... */}` signals truncation.
4. Budget flows downward. Parent allocates budget to children; children never exceed it.
5. Keys before values. For maps, showing all visible keys with truncated values is more
   useful than showing fewer fully-expanded entries.

---

## Algorithm: O(n) Budget-Gated Exploration

### Core Invariant

CellExplorer never inspects more cell material than the budget permits. Every decision
to render (or not render) a subtree is made before recursing into children. The
mechanism is:

> Before recursing into any cell, read its `getMemorySize()` in O(1). If it fits within
> the remaining budget, render in full. Otherwise, truncate and recurse only as far as
> the budget allows.

`cell.getMemorySize()` is the single source of truth for budget consumption. It is
cached in etch and inherently hierarchical — a parent's memory size already includes
its children — so budget accounting requires no speculative writes and no rollback.

### Top-Level Algorithm

```
explore(cell, bb, budget, indent):
  cellSize = cell.getMemorySize()            // O(1), cached in etch

  if cellSize <= budget:
    renderFull(cell, bb, indent)             // fast path — whole subtree fits
    return

  // Cell is too large — truncate
  if isPrimitive(cell):
    renderPrimitiveTruncated(cell, bb, budget)
    return

  // Container truncation
  overhead = containerOverhead(cell)         // structural cells: {, }, commas, annotation
  if overhead >= budget:
    renderFullyTruncated(cell, bb)           // e.g. {/* Map, 5 keys, 47.5MB */}
    return

  contentBudget = budget - overhead

  if isMap(cell):
    renderMapTruncated(cell, bb, contentBudget, indent)
  else:
    renderSequenceTruncated(cell, bb, contentBudget, indent)
```

### Budget Accounting

Every cell type — primitive or container — exposes `getMemorySize()` in O(1), served
from an etch-cached field. There is no per-type estimate table: the same call is the
budget cost of rendering any `ACell`.

- **Primitives:** `cell.getMemorySize()` is the cell's own storage cost. A `CVMLong`
  costs roughly the same as a short `CVMKeyword`, regardless of the number of decimal
  digits it will render to. Output bytes may be larger or smaller than budget units
  for an individual primitive; this is expected.
- **Containers:** `cell.getMemorySize()` already includes the aggregate cost of all
  reachable children. A container whose total memory size fits within budget can be
  rendered in full with no further gating.
- **Sharing:** structurally shared subtrees are counted once in the storage layer, so
  budget accounting naturally rewards sharing.

The budget unit is deliberately not output-bytes. Callers who need a strict output-byte
ceiling should pick a budget proportionate to their output target (roughly 1:1 for
structured data, ~0.5:1 for blob-heavy data where hex expansion doubles size).

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
    kCost = entry.key.getMemorySize() + ENTRY_OVERHEAD  // colon, separator, indent
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

### Sequence Rendering (truncated path)

```
renderSequenceTruncated(seq, bb, contentBudget, indent):
  totalItems = seq.count()
  consumed = 0                                   // in getMemorySize units
  shown = 0

  for item in seq:
    remaining = contentBudget - consumed
    if remaining < MIN_ITEM_BUDGET: break
    itemCost = min(item.getMemorySize(), remaining)
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

---

## Budget Flow

Budget passes from caller down through containers in `getMemorySize()` units. Each
layer subtracts a structural-overhead allowance before dividing the remainder among
children. The structural-overhead figures below are rough constants expressed in the
same unit as the budget — they represent how much of the budget to reserve for
delimiters, separators, and annotations rather than actual cell memory.

**Structural overhead allowance (examples, in budget units):**

| Container | Overhead shape |
|-----------|---------|
| Map (pretty) | `{\n` + `}` + annotation + `(n−1) × `,\n`` + `indent × 2` per entry |
| Map (compact) | `{` + `}` + annotation + `(n−1) × `,`` |
| Vector/List | `[` + `]` + overflow annotation + separators |
| Set | `[` + `/* Set */` + `]` + overflow annotation + separators |

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
independent of `BlobBuilder.count()` — the budget parameter is in `getMemorySize()`
units and flows down as a recursive argument.

---

## JSON Reuse

CellExplorer layers on top of existing infrastructure in `convex.core.util.JSON` and
`convex.core.data.util.BlobBuilder`. We do not reimplement what already exists:

| Capability | Reused from |
|------------|------------|
| String JSON escaping | `JSON.escape(String)` or expose `appendCVMStringQuoted` as package-private in `JSON` |
| Long formatting | `BlobBuilder.appendLongString(long)` |
| Blob hex encoding | `ABlob.toHexString()` or `BlobBuilder.appendHexByte(byte)` |
| Double formatting | `Double.toString(double)`, with special-case for `NaN` / `Infinity` |

We do **not** reuse JSON's map and collection rendering methods — they are unbounded and
have no budget awareness.

We do **not** reuse JSON's `Address` rendering (`longValue()` → bare number) or its
`ASymbolic` rendering (bare name string). CellExplorer uses `"#42"` for addresses and
`":active"` for keywords, which differ from JSON's output.

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
| `AVector` | `[1, 2, 3]` | `[1, 2, /* +99 more */ /* 390KB */]` | `[/* Vec, 7204 items, 1.1MB */]` |
| `AList` | `[1, 2, 3]` | `[1, 2, /* +N more */ /* List, SZ */]` | `[/* List, N items, SZ */]` |
| `AMap` | `{a: 1}` | `{a: 1, /* +7 more */ /* 47.5MB */}` | `{/* Map, 5 keys, 47.5MB */}` |
| `ASet` | `[1, 2 /* Set */]` | `[1, /* +N more */ /* Set, 48KB */]` | `[/* Set, 5000 items, 48KB */]` |

**Size annotation format:** derived from `cell.getMemorySize()`.
`< 1024` → `{n}B` · `< 1MB` → `{n.1f}KB` · `< 1GB` → `{n.1f}MB` · else `{n.1f}GB`.

For strings and blobs, the partial form inserts `...` before the closing quote and appends a
size comment. For containers, the overflow annotation `/* +N more */` precedes the size
comment if both are needed.

---

## Key Formatting

Map keys use JSON5 unquoted identifiers when the rendered key name matches the pattern
`[a-zA-Z_$][a-zA-Z0-9_$]*`. Otherwise quoted.

| Key type | Example cell | Rendered key |
|----------|-------------|--------------|
| Keyword — valid identifier name | `:name` | `name` (unquoted) |
| Keyword — non-identifier name | `:"hello world"` | `":hello world"` (quoted with `:`) |
| String — valid identifier | `"active"` | `active` (unquoted) |
| String — non-identifier | `"hello world"` | `"hello world"` (quoted) |
| Integer | `42` | `42` (unquoted) |
| Address | `#1337` | `"#1337"` (quoted with `#`) |

Keys exceeding ~100 bytes are truncated with `...` inside the appropriate quoting form.

---

## Path Drill-Down

```java
CellExplorer.explore(cell, "/bids", 500)
CellExplorer.explore(cell, "/bids/0", 200)
CellExplorer.explore(cell, "/config/max-orders", 100)
```

Path is split on `/`. Each segment navigates:
- String or keyword segment → `AMap.get(key)` — tried first as `Keyword`, then as `AString`
- Numeric segment → `ASequence.get(n)` index access

Navigation completes before any rendering. The resolved cell is rendered with the full
budget; no parent context is included. On failure (missing key, out-of-bounds index, or
wrong container type), the output is:

```
/* path not found: /bids/99999 */
```

An empty path `""` or `"/"` is equivalent to no path — the root cell is rendered.

---

## Java API

```java
package convex.core.data.util;

public class CellExplorer {

    /** Explore cell with default pretty-printed output. */
    public static AString explore(ACell cell, int budget);

    /** Explore cell; compact=true suppresses indentation and newlines. */
    public static AString explore(ACell cell, int budget, boolean compact);

    /** Navigate to path within cell, then explore with full budget. */
    public static AString explore(ACell cell, String path, int budget);

    /** Navigate to path, then explore in compact or pretty mode. */
    public static AString explore(ACell cell, String path, int budget, boolean compact);
}
```

All methods create a single `BlobBuilder`, call internal `void explore(...)` helpers, and
return `Strings.create(bb.toBlob())`.

---

## Test Plan

**Location:** `convex-core/src/test/java/convex/core/data/util/CellExplorerTest.java`
**Framework:** JUnit 5 (`@Test`, `org.junit.jupiter.api.Assertions`)

Spec examples:

| Example | Scenario | Assertion |
|---------|----------|-----------|
| 1 | Small map, budget=200 | Exact output, no annotations |
| 3 | ~50KB map | Partial entries; overflow + size annotations present |
| 4 | budget=80, large map | Two fully-truncated vector values; `/* +3 more */` |
| 5 | budget=40, large map | `{/* Map, 5 keys, 47.5MB */}` |
| 8 | 100K-integer vector, budget=100 | First items + `/* +N more */` + size annotation |
| 11 | 50KB string, budget=60 | Partial `"...` form with size annotation |
| 13 | Empty containers and nil | `null`, `{}`, `[]`, `[/* Set */]` — no annotations |
| 6, 7 | Path drill-down `/bids/0`, `/bids` | Correct cell resolved; budget applied |
| — | Invalid path | `/* path not found: ... */` |

Additional cases:
- All primitive types: fits / partial / fully-truncated per table above
- Compact vs pretty modes (indentation difference, same data)
- Mixed-type map keys (keyword, string, integer, address in same map)
- Deeply nested maps — budget constrains depth naturally
- Budget at exact boundary (off-by-one safety check)
- Genuinely empty vs truncated: `{}` vs `{/* Map, ... */}`

---

## Open Questions

Each item below states the question, the options considered, a tentative recommendation,
and the reasoning. Mike should review and mark each as accepted, rejected, or deferred
before implementation begins.

---

### OQ-1 · Keyword key rendering — contradiction between spec examples

**Question:** When a keyword (e.g. `:name`) is used as a map key, should the `:` prefix
be suppressed so it renders as an unquoted JSON5 identifier (`name`), or should it be
retained and quoted (`":name"`)?

**Why it's open:** The spec is self-contradictory. Example 1 shows
`{name: "Alice", age: 30, active: true}`, implying keyword keys `:name`, `:age`, `:active`
render as bare unquoted identifiers with no prefix. But Example 14 says explicitly:
"integer keys unquoted, keywords/addresses quoted with prefix" — which implies `":name":`.

**Options:**

- **A. Suppress prefix, unquoted when valid identifier** (matches Example 1).
  `:name` → `name`, `:"hello world"` → `"hello world"`. Cleanest for LLM readability.
  A keyword in key position doesn't need the `:` to be understood as a key.
- **B. Always include `:` prefix, always quoted** (matches Example 14 text).
  `:name` → `":name"`. Unambiguously identifies the key as a Convex keyword, at the
  cost of visual noise.
- **C. Hybrid: include `:` when key set is mixed-type, suppress when all-keyword**.
  Complex, stateful, surprising.

**Tentative recommendation: Option A** (suppress prefix, unquoted when valid identifier).
Example 1 is the primary illustrative case and the cleaner output. The `:` prefix matters
most when distinguishing keyword VALUES from string values in a mixed context; for map
keys the colon-separator is already present. If Example 14 was intended to be
authoritative, clarify and we'll switch.

---

### OQ-2 · String escaping reuse: how to access `appendCVMStringQuoted`

**Question:** `JSON.java` contains a private `appendCVMStringQuoted(BlobBuilder, String)`
method with correct JSON string escaping. CellExplorer needs the same logic.
`CellExplorer` will be in `convex.core.data.util`; `JSON` is in `convex.core.util` —
different packages, so package-private won't bridge them.

**Options:**

- **A. Add `public static void appendEscapedString(BlobBuilder bb, AString s)` to
  `JSON.java`**. A new, well-named public method that exposes the escaping primitive
  cleanly. No duplication, no intermediate allocation.
- **B. Use existing `JSON.escape(String)`** which returns an `AString`. Call
  `bb.append(JSON.escape(s.toString()))`. Clean caller-side, but allocates an
  intermediate `String` + `AString` per string cell rendered.
- **C. Duplicate the escaping logic in CellExplorer**. No dependency, but two copies
  of the same escaping table to maintain.
- **D. Move `appendCVMStringQuoted` to `BlobBuilder` or a new `JSONUtils` helper**
  accessible to both. More involved refactor.

**Tentative recommendation: Option A** — add one public method to `JSON.java`. Minimal
change, zero allocations, no duplication, natural place for it. Option B is acceptable
if allocation is not a concern and Mike prefers no changes to `JSON.java`.

---

### OQ-3 · Overflow annotation format: two comments vs one merged comment

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

### OQ-4 · Surplus redistribution in map value rendering

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

### OQ-5 · Sequence item sampling strategy (head/tail/both)

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

### OQ-6 · Depth limits

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

### OQ-8 · `NaN` and `Infinity` rendering for `CVMDouble`

**Question:** JSON5 supports `NaN`, `Infinity`, and `-Infinity` as unquoted literals.
Standard JSON does not. `JSON.java` currently renders `NaN` as the literal `NaN` but
renders positive/negative Infinity as `null` (with the Infinity path commented out).
What should CellExplorer emit?

**Options:**

- **A. Follow JSON5: emit unquoted `NaN`, `Infinity`, `-Infinity`** — consistent with
  the spec's claim that output is JSON5-compatible.
- **B. Emit quoted strings: `"NaN"`, `"Infinity"`** — valid JSON, but misleading
  (strings, not numbers).
- **C. Emit annotation-only: `/* Double, NaN */`** — loses the value but avoids the
  JSON vs JSON5 ambiguity.

**Tentative recommendation: Option A** — JSON5 unquoted form. The spec explicitly states
output is JSON5-compatible and names these values. Quoted strings would be parsed as
strings by a JSON5 parser, which is wrong. Option A is correct for JSON5.

---

### OQ-9 · Path segment matching: keyword vs string lookup order

**Question:** When navigating a path segment like `config`, should the implementation
first try looking up a `Keyword` `:config`, then fall back to an `AString` `"config"`,
or vice versa?

**Options:**

- **A. Keyword first, then AString** (current plan).
- **B. AString first, then Keyword**.
- **C. Exact-type hint in path syntax** — e.g. `:config` means keyword, `config` means
  string. Requires more complex path parsing.

**Tentative recommendation: Option A** — keyword first. Keywords are the conventional
CVM map key type for structured data. A path of `/config` almost always means `:config`.
If a map happens to have both `:config` and `"config"` as keys (unusual), keyword wins.
Defer Option C (typed path segments) to v2 if ambiguity proves a problem in practice.
