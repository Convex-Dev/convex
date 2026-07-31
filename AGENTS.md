# Agent Guidelines for Convex

Guidance for any coding agent working in this repository. `CLAUDE.md` simply
includes this file, so there is one source of truth.

## Project Overview

Convex is a lattice-based decentralised network and execution platform built as a multi-module Maven project. The codebase implements a full-stack decentralised platform with custom virtual machine, consensus layer, peer networking, and developer tooling.

## Build System

Maven 3.7+ multi-module project structure. Builds on Java 21+ (CI and Docker use JDK 25); artifacts target Java 21 bytecode.

See BUILD.md for detailed build and release instructions.

Quick start:
```bash
mvn clean install          # Full build with local install
```

## Agent Tooling

Task-specific instructions live as skills in **`.claude/skills/`**, one
directory per skill with a `SKILL.md` inside:

- *Orientation* — `ecosystem`, `cad-reference`
- *Working on Convex* — `build-convex`, `local-network`, `peer`, `etch`
- *Protocol internals* — `protocol-versions`, `cad3-encoding`, `juice`, `memory`
- *Writing CVM code* — `convex-lisp`, `trust`, `deploy`, `query`, `transact`
- *Using a network* — `account`, `transfer`, `token`, `cns`, `convex-db`

`convex-lisp` holds the shared CVM conventions the others assume; read it
before writing CVM source.

Claude Code discovers these automatically. **Other agents should read the
relevant `SKILL.md` directly** — they are plain Markdown and carry no
tool-specific syntax beyond `$ARGUMENTS` placeholders for user input. Check for
a skill covering your task before working out a procedure from scratch.

Convex MCP tooling (live query, transact and signing against a running network)
is optional and configured per user, not in this repository. Without it, use the
CLI as the skills describe.

## Specifications

The **CADs** (Convex Architecture Documents) are the normative specifications,
maintained in the sibling `Convex-Dev/design` repository and rendered at
https://docs.convex.world/docs/cad. They govern encoding, consensus, resource
accounting, assets and identity.

Where the code and a CAD disagree, one of them is wrong — that is a question to
raise, not a choice to make silently. Before changing behaviour in a specified
area, find the governing CAD via the `cad-reference` skill and say which one
you relied on.

## Protocol Versions

Networks carry a protocol version, counted from `0` at genesis. **Protocol
version 1 is the target**: it bundles the upgrade bootstrap and every bug known
at genesis, and it is what networks will be running.

Write and test against v1 semantics — `InitTest.UPGRADED` is the `ACVMTest`
default for exactly this reason. `Migrations.LIVE_VERSION` tracks what live
peers run today and gates releases against breaking them; genesis states are
pinned only where genesis is the point.

Changing CVM behaviour without forking the network has rules — which tier a
change falls into, and when a version gate is mandatory. See the
`protocol-versions` skill and `convex-core/docs/UPGRADE.md`.

## Verifying Changes

Do not report work as complete on the strength of a compile. Run what CI runs:

```bash
mvn -B clean install
```

Scope it down while iterating with `-pl <module> -am`, but a full run is the
bar for "done". If tests fail, say so and quote the failure — never describe a
red build as passing.

## Module Structure

14 Maven modules with clear separation of concerns:

| Module | Purpose |
|--------|---------|
| `convex-core` | CVM, consensus (CPoS), data structures, Etch database |
| `convex-peer` | Peer implementation, binary protocol, networking |
| `convex-p2p` | Rollup P2P package: infrastructure regions (`:p2p` `:id` `:kad`), bundled app regions, node server |
| `convex-cli` | Command-line tools and scripting |
| `convex-gui` | Swing desktop application |
| `convex-restapi` | HTTP REST API server |
| `convex-java` | Java client library |
| `convex-x402` | x402 payment protocol: model, exact scheme, facilitator core, paying client |
| `convex-db` | SQL database with JDBC and PostgreSQL protocol |
| `convex-dlfs` | Distributed Lattice File System (WebDAV server) |
| `convex-social` | Social network primitives — a region served by the `convex-p2p` node |
| `convex-benchmarks` | JMH performance benchmarks |
| `convex-observer` | Network monitoring tools |
| `convex-integration` | Build assembly (produces convex.jar) |

Each module has its own README.md explaining specific functionality.

## Code Organization

### Package Naming
Standard reverse-domain convention: `convex.<module>.<feature>`

Examples:
- `convex.core.data` - Core data structures
- `convex.peer.Server` - Peer server implementation
- `convex.restapi.test` - REST API tests

### Test Structure
Tests follow JUnit 6 conventions with `src/test/java` mirroring `src/main/java` structure.

Two rules matter for the networked modules, where violations produce tests that
pass locally and fail intermittently in CI:

- **Never sleep to wait for something.** Wait on a real signal — a future, a
  latch, or a synchronous API that returns once the work is done. If no such
  signal exists, that is a missing affordance in the main code: add it there
  rather than papering over it with a timeout in the test.
- **Never bind a fixed port.** Bind port `0` and ask the server for the port it
  actually got. Fixed ports collide when tests run in parallel.

### Language
Concise language, British English spelling

## Key Technologies

- **Netty** - Async networking
- **JUnit 6** - Testing
- **SLF4J** - Logging
- **Ed25519** - Cryptographic signatures (Bouncy Castle Library)
- **ANTLR4** - Parser generation (requires IDE source path configuration)

## Running Convex

See README.md for download links and detailed running instructions.

Execution is typically using CLI commands on convex.jar uberjar (all dependencies included):
```bash
java -jar convex.jar desktop   # Launch GUI
```

Typically should set up small convenience wrapper script to run `java -jar ...` and allow commands like:
```bash
convex key generate
```

## Development Workflow

### Branch Strategy
- `develop` - Active development (default branch)
- `master` - Release branch
- Feature branches as needed

### Commit Identity
Commit identity is **per repository** — never assume the global git config.
Check `git config user.name` and `git config user.email` in this repository
before committing, and leave them as you found them.

### CHANGELOG
`CHANGELOG.md` records **user-visible changes only**. Dependency bumps, build
plumbing, test-only refactors and agent tooling do not belong in it. If a change
alters nothing an operator, contributor or API consumer would notice from the
outside, leave the CHANGELOG alone.

### Release Process
See BUILD.md for complete release workflow.

### Known Issues
- ANTLR4 generated sources may need manual IDE source path configuration
- Add `target/generated-sources/antlr4` as source directory if needed

## Key Principles

- **Immutable Data** - Use immutable lattice data structures (ACell hierarchy)
- **Lambda calculus VM** for functional smart contracts, pure functions that update state
- **Lattice technology** - data merges like CRDTs, not linear blockchain
- **Global state model** with atomic transactions on Convex with self-sovereign accounts like #1337
- **Peer / REST API Security** these are public by default and should be robust to malicious messages
- **Lattice Node security** These are private by default. Operators chooses who to share / merge with

## Terminology

Canonical glossary: `docs/tutorial/glossary.md` in the sibling `design`
repository, rendered at https://docs.convex.world/docs/tutorial/glossary — use
the URL if you do not have that repository checked out alongside this one. Use canonical terms — never substitute "gas", "fees", "blockchain", "chain", "miner", "validator", "block", "wei", "satoshi", or "mainnet". Key terms: CVM coin, Juice, Copper, Peer, Actor, Lattice, CPoS, Belief, Etch, CAD, CNS, Protonet.

## Network Defaults

- **Protonet** (production): `peer.convex.live`
- **Testnet** (default for tooling/MCP): `https://mikera1337-convex-testnet.hf.space`

## Resources

See README.md for community links and project resources.
