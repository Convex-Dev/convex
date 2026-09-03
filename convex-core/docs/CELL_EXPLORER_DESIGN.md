# CellExplorer Design

`CellExplorer` renders any CVM `ACell` as JSON5-compatible text truncated to a
caller-specified budget, so LLMs and tools can explore arbitrarily large lattice
structures progressively without flooding their context. The output contract is
specified in [CAD046](https://docs.convex.world/docs/cad/cell_explorer); this document
explains the budget model and the truncation algorithm behind the implementation in
`convex.core.data.util.CellExplorer`.

## Key points

- **Budget is CAD3 storage, not output bytes.** The unit is `Cells.storageSize(cell)`:
  memory size for branch cells, memory size plus encoding length for embedded ones, so
  even a small integer has a non-zero cost. It is cached and O(1).
- **Work is proportional to output, never to input.** Before descending into any cell
  the explorer reads its storage size; a cell that fits is rendered in full, otherwise
  it is truncated and children receive sub-budgets that never exceed the caller's.
- **Never invent data.** Only values present in the cell appear; every annotation
  lives in a `/* */` comment and cannot be mistaken for a value.
- **Empty means empty.** `{}` and `[]` are genuinely empty containers; a truncated one
  carries a comment such as `{/* Map, 5 keys, 47.5MB */}`.
- **Keys before values.** For maps, more entries with less detail beat fewer entries
  fully expanded.
- **No path navigation.** The explorer takes one resolved cell. Navigate with the
  lattice cursors first, then explore.
- **Fully materialised input.** An unresolved ref propagates `MissingDataException`;
  loading is the caller's job.

## Budget model

```
explore(cell, budget):
  size = Cells.storageSize(cell)             // O(1)
  if size <= budget: render in full          // delegates to JSON rendering
  else if leaf: partial form or annotation
  else: reserve a small constant for the container annotation,
        then share the rest among children
```

Storage size is the only cost measure. A container's memory size already sums its
branch children, and embedded children add their encoding length, so the same call
prices any cell. Sharing is not deduplicated: a subtree reachable through several refs
is counted once per ref. Because hex expansion roughly doubles blob output, callers who
need a strict output-byte ceiling should pass about half that number as the budget for
blob-heavy data.

## Truncation algorithm

**Maps** use geometric decay. Entries are visited in the map's own order (hash order
for `AHashMap`, so treat it as non-semantic). Each entry pays its key cost plus a fixed
per-entry overhead; if the value fits in what remains it gets all of it, otherwise it
gets half. Rendering stops when the next value's share drops below a minimum, and a
single merged comment `/* +N more, SZ */` reports the overflow. The first entry
therefore shows the most detail, which tends to reveal the schema.

**Vectors, lists and sets** use a running remainder: each item receives whatever budget
is left, so early small items leave more for later ones, and rendering stops below a
minimum item budget. Sets render as arrays with an inline `/* Set */` marker.

**Strings and blobs** that exceed the budget render a prefix (respecting UTF-8 code
point boundaries for strings, whole bytes for blobs) followed by `...` and a size
comment. Other leaves have no useful partial form and fall back to an annotation.

**Depth** needs no separate limit: every level reserves a constant, so recursion depth
is bounded by the budget divided by that constant.

## Output forms

| CVM type | Fits | Partial | Fully truncated |
|---|---|---|---|
| `null`, `CVMBool` | `null`, `true` | — | — |
| `CVMLong`, `CVMDouble`, `CVMBigInteger` | `30`, `3.14`, `12345...` | — | `/* Integer, 20 digits */` etc. |
| `AString` | `"hello"` | `"hello wo..." /* String, 4.2KB */` | `/* String, 4.2KB */` |
| `ABlob` | `"0x48656c..."` | `"0x4865..." /* Blob, 12MB */` | `/* Blob, 12MB */` |
| `Address` | `"#42"` | — | `/* Address */` |
| `Keyword`, `Symbol` | `":active"`, `"'foo"` | — | annotation |
| `AVector`, `AList` | `[1, 2, 3]` | `[1, 2, /* +99 more, 390KB */]` | `[/* Vec, 7204 items, 1.1MB */]` |
| `AMap` | `{a: 1}` | `{a: 1, /* +7 more, 47.5MB */}` | `{/* Map, 5 keys, 47.5MB */}` |
| `ASet` | `[1, 2 /* Set */]` | `[1, /* +N more, Set, 48KB */]` | `[/* Set, 5000 items, 48KB */]` |

Non-finite doubles render as the JSON5 literals `NaN`, `Infinity` and `-Infinity`.
Size annotations use `B`, `KB`, `MB` and `GB` with one decimal, and are omitted for
containers below 1 KB where the count alone is informative.

Map keys go through `JSON.jsonKey` to a string, then render unquoted when they form a
valid JSON5 identifier (`name` for the keyword `:name`) and quoted otherwise
(`"hello-world"`, `"42"`, `"#1337"`). JSON5 forbids numeric member names, so integer
keys are always quoted. The keyword-versus-string distinction is lost for keys by
design: this is a one-way projection for reading, not a serialisation.

## API

```java
CellExplorer explorer = new CellExplorer(2048);        // budget in storage units
AString out = explorer.explore(cell);
```

Instances are immutable and reusable across threads; configuration lives on the
instance and per-call state flows through internal parameters. Output is written
straight into a `BlobBuilder` and converted once at the end, with no intermediate Java
strings. A second constructor takes a `compact` flag; pretty-printed output is not yet
implemented, so both modes currently produce the same single-line form.

## Where the code lives

- `convex.core.data.util.CellExplorer` — the renderer and its budget constants.
- `convex.core.data.Cells.storageSize` — the budget unit.
- `convex.core.util.JSON` — leaf rendering (`appendJSON`, `appendJSON5`,
  `appendCVMStringQuoted`) and key coercion (`jsonKey`).
- `convex.core.data.util.CellExplorerTest` — leaf forms, key formatting, truncation
  semantics and JSON5 round trips.

## Related

- [CAD046 CellExplorer](https://docs.convex.world/docs/cad/cell_explorer) — output contract and rendering rules.
- [CAD044 JSON on the Lattice](https://docs.convex.world/docs/cad/json) — the JSON and JSON5 encodings reused for leaves.
- [CAD035 Lattice Cursors](https://docs.convex.world/docs/cad/cursors) and [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md) — navigating to the cell to explore.
