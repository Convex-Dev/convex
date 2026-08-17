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
| `etch gc` | Garbage collect: retains the root and everything reachable, discards the rest. In-place by default; `-o/--output <file>` collects into a fresh file instead |
| `etch clear` | Clears the root data. Does *not* collect garbage |
| `etch migrate --into <dest>` | Copy everything into another store; `--set-root` to set the destination root |
| `etch repair --into <dest>` | Reconstruct a fresh store from independently validated cells; source unchanged |
| `etch recover` | Adopt a completed GC cutover and roll forward — for a store interrupted mid-GC |
| `etch write -c/--cvx <source>` | Write a CVM value into the store |

To compact safely, prefer `gc -o <new-file>`: it collects into a fresh file and
leaves the source unmodified (note: status levels above PERSISTED, e.g.
ANNOUNCED, survive an in-place GC but not `--output`). Use `migrate` to copy
into another (possibly non-empty) store, or to change the store's format
version or encryption — see below.

`repair` is the offline salvage path for a dirty or damaged source. It holds an
exclusive source lock, scans through physical EOF, and writes only canonical
CAD3 values whose stored content hashes verify. A complete result requires both
a fully persisted selected root and an exhaustive scan. If either condition
fails, the command reports failure but may leave a valid partial destination;
never replace the source with that output automatically.

Recovering an interrupted GC is what `recover` is for — reach for it before
concluding a store is lost.

## Encrypted Stores (Etch v3)

Etch format v3 supports encrypted stores — format spec in
`convex-core/docs/ETCHv3.md`.

**Opening** an encrypted store: any etch subcommand takes `--etch-key <alias>`
(keystore key alias or public-key prefix, password via `--etch-keypass`) or
`--etch-key-file <file>` (raw or hex 32-byte master key; `-` reads stdin).
With neither, the v3 header's public-key hint selects a keystore key
automatically, or an interactive session prompts for a hex key.

**Converting** a store: `etch migrate` (and `gc -o`) accept destination
options — `--into-version` (1, 2 or 3), `--into-cipher` (`none`,
`aes-256-ctr` or `chacha20`), `--into-key` / `--into-key-file` /
`--into-keypass` for the destination key, `--into-encrypt-index` (negatable)
and `--into-public-key-hint`. Encryption options require version 3; without a
destination key the resolved source key is reused.
