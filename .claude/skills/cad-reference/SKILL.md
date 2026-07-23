---
name: cad-reference
description: Index of the Convex Architecture Documents (CADs), the normative specifications for Convex. Use to find which CAD governs a subsystem before changing protocol, encoding, consensus or economic behaviour.
---

# Convex Architecture Documents

CADs are the **normative specifications** for Convex. Where the code and a CAD
disagree, that is a bug in one of them — not a licence to pick either. Before
changing behaviour in a specified area, read the governing CAD and say which
one you relied on.

**Read them at `https://docs.convex.world/docs/cad/<slug>`** — the slug is the
CAD directory name without its numeric prefix, e.g. CAD003 is
`https://docs.convex.world/docs/cad/encoding`.

If the `design` repository is checked out alongside this one, the sources are
in `design/docs/cad/<nnn>_<slug>/index.md`. Do not assume it is present.

## Core Protocol

| CAD | Slug | Covers |
|-----|------|--------|
| 000 | `principles` | Design principles behind every other decision |
| 001 | `arch` | Overall architecture |
| 002 | `values` | CVM values |
| 003 | `encoding` | **CAD3 encoding format** — see the `cad3-encoding` skill |
| 004 | `accounts` | Accounts, addresses, controllers |
| 005 | `cvmex` | CVM execution model |
| 033 | `cvmtypes` | CVM type system |

## Resources and Economics

| CAD | Slug | Covers |
|-----|------|--------|
| 006 | `memory` | **Memory accounting and allowances** — see the `memory` skill |
| 007 | `juice` | **Juice accounting and pricing** — see the `juice` skill |
| 020 | `tokenomics` | Coin supply, distribution, denominations |
| 016 | `peerstake` | Peer staking |

## Language and Compilation

| CAD | Slug | Covers |
|-----|------|--------|
| 008 | `compiler` | Compiler |
| 009 | `expanders` | Expanders and macros |
| 011 | `errors` | Error handling and error codes |
| 012 | `numerics` | Numeric semantics |
| 013 | `metadata` | Metadata |
| 026 | `lisp` | Convex Lisp |
| 032 | `reader` | CVX reader syntax |

## Transactions and Consensus

| CAD | Slug | Covers |
|-----|------|--------|
| 010 | `transactions` | Transaction format and lifecycle |
| 015 | `peercomms` | Peer communication protocol |
| 017 | `peerops` | Peer operations |
| 018 | `scheduler` | Scheduled execution |
| 021 | `observability` | Observability |
| 027 | `log` | Event logging |

## Storage and Lattice

| CAD | Slug | Covers |
|-----|------|--------|
| 047 | `etch` | Etch storage format — see the `etch` skill |
| 024 | `data_lattice` | Data lattice |
| 035 | `cursors` | Lattice cursors |
| 036 | `lattice_node` | Lattice node |
| 037 | `kv_database` | KV database |
| 039 | `convex_sql` | Convex SQL — see the `convex-db` skill |
| 040 | `lattice_queue` | Lattice queue |
| 028 | `dlfs` | Data Lattice File System |
| 044 | `json` | JSON on the lattice |
| 045 | `lattice_apps` | Lattice applications |
| 046 | `cell_explorer` | Cell explorer |

## Assets, Identity and Trust

| CAD | Slug | Covers |
|-----|------|--------|
| 019 | `assets` | Asset model |
| 029 | `fungible` | Fungible token standard — see the `token` skill |
| 031 | `nft_metadata` | NFT metadata |
| 030 | `torus` | Torus DEX |
| 022 | `trustmon` | **Trust monitors** — the authorisation model; see the `trust` skill |
| 014 | `cns` | Convex Name System — see the `cns` skill |
| 034 | `curated_registry` | Curated registry |
| 023 | `keystore` | Keystore |
| 025 | `wallet` | HD wallets |
| 038 | `lattice_auth` | Lattice authentication |
| 043 | `did` | Decentralised identity |

## Integration

| CAD | Slug | Covers |
|-----|------|--------|
| 041 | `mcp` | Model Context Protocol |
| 042 | `x402` | x402 protocol |

CAD `0000cads` is the index and describes the CAD process itself.
