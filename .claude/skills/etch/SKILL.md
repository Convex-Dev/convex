---
name: etch
description: Inspect and maintain an Etch store — Convex's content-addressed database. Use when examining peer storage, diagnosing corruption, garbage collecting, or reading data by hash.
allowed-tools: Bash
argument-hint: "[info|validate|dump|read|gc|migrate|repair] <etch-file>"
---

# Etch Store Operations

Etch is Convex's content-addressed store: values are keyed by hash, and a
single root hash reaches everything retained. Peers keep their state here.

Every subcommand takes the store file with `-e` / `--etch`:

```bash
java -jar convex.jar etch info -e /path/to/store.etch
```

## Inspecting

| Command | Purpose |
|---------|---------|
| `etch info` | Summary of the database — start here |
| `etch validate` | Check store integrity; `-m/--max-failures N` to bound reporting |
| `etch dump` | Export contents, CSV by default (value ID, type, memory size, encoding) |
| `etch read <hash>...` | Read specific values by hash; `--limit N` to cap output |

`info`, `dump` and `read` take `-o` / `--output-file` to write to a file rather
than the terminal — use it for `dump` on any real store, which is large.

Start a diagnosis with `info`, then `validate`. If validation reports failures,
capture the output before doing anything that mutates the store.

## Maintenance

These **modify or destroy data**. Confirm with the user, and make sure the peer
using the store is stopped first — operating on a live store risks corruption.

| Command | Effect |
|---------|--------|
| `etch gc` | Garbage collect: retains the root and everything reachable, discards the rest |
| `etch clear` | Clears the root data. Does *not* collect garbage |
| `etch migrate --into <dest>` | Copy everything into another store; `--set-root` to set the destination root |
| `etch repair --into <dest>` | Reconstruct a fresh store from independently validated cells; source unchanged |
| `etch recover` | Adopt a completed GC cutover and roll forward — for a store interrupted mid-GC |
| `etch write -c/--cvx <source>` | Write a CVM value into the store |

`migrate` is the safe way to compact or relocate: it leaves the source intact,
so prefer it to `gc` when the store matters and disk allows.

`repair` is the offline salvage path for a dirty or damaged source. It holds an
exclusive source lock, scans through physical EOF, and writes only canonical
CAD3 values whose stored content hashes verify. A complete result requires both
a fully persisted selected root and an exhaustive scan. If either condition
fails, the command reports failure but may leave a valid partial destination;
never replace the source with that output automatically.

Recovering an interrupted GC is what `recover` is for — reach for it before
concluding a store is lost.
