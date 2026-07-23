---
name: ecosystem
description: Orientation in the Convex ecosystem — what lives in which repository, where the specs and docs are, and which client libraries exist. Use when you need context that is not in this repository.
---

# The Convex Ecosystem

Convex is a lattice-based decentralised network and execution platform. Data
merges like CRDTs rather than forming a linear chain, consensus is Convergent
Proof of Stake (CPoS), and the CVM is a lambda-calculus virtual machine for
functional smart contracts.

This repository (`Convex-Dev/convex`) is the reference implementation. Several
things an agent needs are deliberately **not** here.

## Where Things Live

| What | Where |
|------|-------|
| Reference implementation — CVM, consensus, peer, CLI, tooling | `Convex-Dev/convex` (this repo) |
| **CADs** — normative specifications | `Convex-Dev/design`, under `docs/cad/` |
| Glossary and tutorials | `Convex-Dev/design`, under `docs/tutorial/` |
| Rendered docs | `https://docs.convex.world` |

The `design` repository is the authority on *what Convex should do*; this
repository is *what it currently does*. When they disagree, one of them has a
bug — see the `cad-reference` skill for how to find the governing CAD.

Do not assume `design` is checked out locally. Use `https://docs.convex.world`
unless you have confirmed the repository is present alongside this one.

## Module Map

The 13 Maven modules are listed in `AGENTS.md` with their purposes, and each
has its own `README.md`. In short: `convex-core` holds the CVM, consensus and
Etch; `convex-peer` the networking; `convex-cli`, `convex-gui` and
`convex-restapi` the interfaces; `convex-p2p` bundles the node server.

## Client Libraries

| Language | Repository |
|----------|-----------|
| Java | `convex-java` module in this repo |
| Clojure | `Convex-Dev/convex.cljc` |
| TypeScript | `Convex-Dev/convex.ts` |
| Python | `Convex-Dev/convex-api-py` |

Prefer the in-repo `convex-java` module for JVM integration — it is versioned
with the network implementation.

## Networks

- **Protonet** (production): `peer.convex.live`
- Testnets are used for tooling and experimentation; the endpoint in use is
  recorded in `AGENTS.md` under Network Defaults.

Anything you deploy to Protonet is real. Confirm the target network before any
transaction, and prefer a local network for development — see the
`local-network` skill.

## Terminology

Convex has its own vocabulary and it is **not** interchangeable with
blockchain terminology. Never substitute "gas", "fees", "blockchain", "chain",
"miner", "validator", "block", "wei", "satoshi" or "mainnet".

Use: CVM coin, Copper, Juice, Peer, Actor, Lattice, CPoS, Belief, Etch, CAD,
CNS, Protonet. The canonical glossary is at
`https://docs.convex.world/docs/tutorial/glossary` — check it rather than
guessing at a translation.

## Related Projects

The ecosystem page at `https://docs.convex.world` lists community projects,
wallets, demos and integrations. Treat that list as the current source; entries
here would go stale.
