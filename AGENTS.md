# Agent Guidelines for Convex

Guidance for coding agents in this repository; `CLAUDE.md` imports this file.
Contributor conventions (terminology, immutable data, security mindset) are in
[CONTRIBUTING.md](CONTRIBUTING.md).

## Build and verify

Maven multi-module project with the checked-in wrapper (`./mvnw`; `.\mvnw.cmd` in
PowerShell). Builds on Java 21+ (CI: JDK 21 and 25; release and Docker: JDK 25) and
targets Java 21 bytecode. [BUILD.md](BUILD.md) covers builds, test setup, CI and
releases.

- One module: `./mvnw -B -T1C test -pl <module> -am`.
- Cross-module, build-system, protocol or release changes: the full
  `./mvnw -B clean verify`.
- Docs and comments: check the diff.

## Branches and commits

- Commit tested work directly to `develop`, the active branch; no feature branch
  unless asked. `master` is the release branch.
- Pushes go in occasional batches: don't push each commit, and don't ask about
  pushing. `origin` is the only remote.
- `develop` has no branch protection or required checks, by choice; don't propose
  adding them.
- Several sessions share this checkout, and local `develop` holds their commits:
  stage only the paths you changed, leave others' uncommitted files alone, and
  check no other Maven build is running before a `clean` build. Build or compare
  other commits in a scratch worktree (`git worktree add`), never by checking
  files out here.
- Exploitable vulnerabilities go through [SECURITY.md](SECURITY.md) as private
  reports; never put exploit detail in a public issue, PR, commit or comment.
- `CHANGELOG.md` records user-visible changes only: no dependency bumps, build
  plumbing, test-only refactors or agent tooling. Entries are one-line highlights
  in the imperative ("Support…", "Speed up…") linking the doc or issue, without
  implementation detail; a breaking change gets one line pointing at its doc.

## Agent tooling

Skills live in `.claude/skills/<name>/SKILL.md`. Codex reads forwarding entries in
`.agents/skills/<name>/SKILL.md`: the same `name` and `description` frontmatter and
a link to the canonical file, so adding, renaming or removing a skill updates both
directories. Convex MCP tools are optional per-user configuration; without them,
use the CLI as the skills describe.

## Specifications and docs

The **CADs** in the sibling `Convex-Dev/design` repository
(https://docs.convex.world/docs/cad) are the normative specifications for encoding,
consensus, resource accounting, assets and identity. Before changing behaviour in a
specified area, find the governing CAD with the `cad-reference` skill and say which
one you relied on. Where code and a CAD disagree, one is wrong: raise it, don't
choose silently. Link CADs as `https://docs.convex.world/docs/cad/<slug>`, with the
unnumbered slug.

Documentation has three layers. A CAD states the rules from the outside: what
clients, peers and operators see and can rely on. A module's `docs/` (and
`docs/proposals/`) covers its API surface, configuration and embedding contracts,
linking the CAD rather than repeating it. Internals (invariants, races, protected
methods) live only in code comments. Each module also has a README.

## Design rules

- Protocol version 1 is the target: write and test against v1 semantics. Before
  changing CVM behaviour, read the `protocol-versions` skill.
- Changes to core data and encoding code (`Index`, `CAD3Encoder`, `Format`, `Ref`,
  `ACell`, equality) start with the analysis and proposed design, agreed before any
  edit: these paths decide canonical encodings, hashes and memory accounting.
- CVM operations give the same result for value-identical cells whatever their Java
  representation (`CVMLong` or non-canonical `CVMBigInteger`, blob- or
  `BigInteger`-backed): peers hold different representations, so any difference is a
  consensus divergence. Test both forms; use `AInteger.isLong`, not
  `instanceof CVMLong`.
- Peers and the REST API are public by default and must withstand malicious
  messages; lattice nodes are private by default, and operators choose whom they
  share with and merge from.
- Generic components make no application policy (merge semantics, path resolution,
  sync scheduling); they offer defaults and extension points. A downstream request
  belongs upstream only for a real defect or a missing extension point.
- Hand work to another thread only at the edge, where it lets independent work run
  concurrently. Atomic updates and deterministic processes never hand off, and local
  paths avoid it where they can: a hand-off costs a thread wake, about as long as a
  whole local round trip.

## Modules

| Module | Purpose |
|--------|---------|
| `convex-core` | CVM, consensus (CPoS), data structures, Etch database, lattice components |
| `convex-peer` | Peer implementation, binary protocol, networking, lattice nodes |
| `convex-p2p` | Rollup P2P package: infrastructure regions (`:p2p` `:id` `:kad`), bundled app regions, node server |
| `convex-cli` | Command-line tools and scripting |
| `convex-gui` | Swing desktop application |
| `convex-restapi` | HTTP REST API server |
| `convex-java` | Java client library |
| `convex-x402` | x402 payment protocol: model, exact scheme, facilitator core, paying client |
| `convex-db` | SQL database with JDBC and PostgreSQL protocol |
| `convex-dlfs` | Distributed Lattice File System (WebDAV server) |
| `convex-social` | Social network primitives — a region served by the `convex-p2p` node |
| `convex-benchmarks` | JMH benchmarks only; application-level performance trials live in the `flair` repository |
| `convex-observer` | Network monitoring tools |
| `convex-integration` | Build assembly (produces convex.jar) |
