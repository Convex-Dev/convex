# Changelog

Notable changes to Convex core modules will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.8.12-SNAPSHOT] - Unreleased

### Added

### Changed

### Fixed

## [0.8.11] - 2026-08-10

### Added

- Opt-in Etch v3 stores with crash-consistent headers, plaintext or AES-256-CTR / ChaCha20 data, independently configurable index encryption, per-file salts and verified keys. `peer.etch` configures new stores while existing stores retain their recorded format; maintenance inspection and copy-out repair tooling is included.
- New `convex-x402` module implementing CAD042 / x402 v2 exact payments in CVM coin or CAD29 tokens, with REST facilitator endpoints, route gates, local payer signing, replay protection, flexible policies and external-facilitator support.
- New `convex eval` and `convex repl` commands for scriptable or interactive Convex Lisp, using an effortless ephemeral local instance by default or any peer selected with `--host`.
- New `convex mcp` stdio server with query, signed transaction, balance, CNS and status tools; `--query` offers a read-only mode.
- Hacker Tools now builds and inspects EdDSA JWTs and Convex UCAN delegations. Key views also expose canonical, copyable `did:key` identifiers.
- Allocation-free SipHash-2-4 with byte-key, primitive-key and zero-copy byte-range APIs.
- DLFS gains atomic same-drive moves, structurally shared copies and `DLFSOption.RECURSIVE` for whole-subtree copy and move operations.

### Changed

- UCAN authority supports explicit `exp: null`; generic JWT validation treats it as an omitted optional expiry, and proof validity follows UCAN 1.0 execution-time semantics.
- Convex UCAN JWTs now use the strict `0.10.0` profile and accept any valid DID audience. Hand-minted tokens must include `ucv`, explicit `exp`, and vector `att` / `prf` claims; use `UCAN.createJWT` or `buildPayload` plus `signJWT`.
- `key generate` and `key export` protect secret output through the attached console or an explicit owner-only file, with deliberate stdout opt-in for automation.
- Persistent lattice and peer stores keep the fast buffered write path, add periodic checkpoints, and complete a clean checkpoint during orderly shutdown across Etch v1, v2 and v3.

### Fixed

- Index keys and DLFS path components remain distinct through the standard 255-byte filesystem name limit; extended depths use canonical VLQ encoding and stack-safe cold paths (#631, #689).
- DLFS writes and truncations advance file update times, giving replication conflicts and NIO modification times the right content ordering.
- Etch checkpoints now publish directly reopenable v3 stores, track later mutations correctly, release encrypted resources promptly and preserve cipher ownership through online GC (#650, #686).
- NodeServer shutdown cleanly joins periodic maintenance before the final checkpoint.
- Network-decoded `Call` transactions now populate their argument list and invoke the target function correctly.
- Named `did:web` documents resolve CNS aliases and scoped records; deactivated records return HTTP 410 with DID metadata (#618).
- Seed-returning MCP tools and legacy JSON transactions protect remote cleartext HTTP by default while preserving loopback development.
- JSON readers correctly handle long decimals, exponent notation and JSON5 hexadecimal values outside the integer fast path.

## [0.8.10] - 2026-07-25

### Added

- New `convex-p2p` module: a rollup package for lattice P2P nodes, bundling the P2P regions (`:p2p` node registry, `:id` user identity, reserved `:kad`), the application regions a node serves, and the `P2PNode` server that serves them. `node.p2p(userID).cursor()` gives an application a cursor onto one user's owned area, signed on write.
- MCP: five new guided prompts — `resolve-name`, `call-actor`, `token`, `diagnose-transaction` and `manage-access` — covering name resolution, actor calls, fungible tokens (CAD029), transaction diagnosis, and access control (CAD022).
- REST API: typed, secure-by-default configuration for the HTTP and MCP endpoints via the `rest`, `mcp` and `auth` sections of the JSON5 config. The faucet, query watch, process-administration routes and the generic Peer-message endpoint are each gated and stay off unless explicitly enabled; administration additionally requires an authorised operator key (the venue and consensus-controller keys by default, or a configured `did:key` allowlist) and HTTPS for non-loopback access. The public-surface resource limits are operator-tunable too — a global concurrent-request cap (`rest.maxConcurrentRequests`), the request-body ceiling (`rest.maxRequestBytes`) and the public query lane's concurrency, wait and Juice (`rest.query.*`).

### Changed

- REST API: the `transaction/submit` endpoint now requires the complete `data` value returned by `transaction/prepare`; public transaction preparation no longer writes pending state into the Peer's primary store. Clients that previously submitted only a transaction hash must now return the full prepared data.
- REST API: public queries run on a bounded, isolated lane separate from the Peer's network query queue, so public HTTP traffic cannot starve consensus queries. The lane admits a slice of the available cores, waits briefly for a free slot under a burst rather than rejecting, and only sheds load — with a clean "server busy" result — when saturation persists. A global concurrent-request cap bounds overall in-flight work, with long-lived SSE streams kept on their own connection limits.
- REST API: request bodies are size-bounded even when chunked, so a streaming parser sees the same limit as a request with a declared content length.
- Etch: version-2 stores use a Foreign Function & Memory (FFM) mapped-file backend on Java 22+, falling back to the `MappedByteBuffer` backend on Java 21; `convex.jar` ships both as a multi-release jar and selects the backend at runtime.
- MCP: improved and streamlined the built-in prompts — clearer, accurate Convex guidance, with the shared reference (system accounts, coin units, CNS) consolidated into `convex-guide` so the task prompts stay focused.
- `AIndex.entrySet()` now returns a lightweight immutable view, avoiding a full ordered set copy on every call while preserving sorted iteration order.
- NodeServer: network work is dispatched through a bounded queue, keeping decode, lattice merge, synchronous persistence and response encoding off shared Netty event-loop threads
- NodeServer: public-node defaults now cap encoded messages at 4 MiB and inbound connections at 256, while cryptographically verified outbound Peers may use a separately configurable larger message tier.
- NodeServer: lifecycle is represented by explicit starting, running, stopping and stopped states; merge context and propagator topology freeze when first launch begins, and propagator access returns an immutable snapshot.
- Lattice propagation: propagator-managed Peers and operator-assigned inbound connections now receive `DATA_REQUEST` access only to their selected propagator store; unassigned NodeServer requests are rejected, while membership and verification remain operator policy.
- NodeServer: inbound lattice connections require an explicit, immutable propagator assignment; queries use that propagator's view and partial values are fully acquired and size-checked in its store before the ordered merge path can touch the cursor or primary checkpoint.
- Lattice acquisition: `Acquiror` now owns a cancellable worker and request lifecycle, and lets NodeServer await termination before closing propagator stores.

### Fixed

- REST API: explorer pagination is now bounded by a maximum page size, so public endpoints can't be driven into unbounded historical reads (#660).
- MCP: SSE connections enforce the connection cap atomically and reject reused session IDs cleanly (#659).
- CLI: the `NO_COLOR` environment variable now follows the [convention](https://no-color.org) of suppressing colour whenever it is set to any non-empty value. Previously its value was parsed as a boolean, so a common `NO_COLOR=1` made every command fail with "'1' is not a boolean" before it ran.
- REST API: query watches on fast-changing queries stay connected under load — the newest result supersedes queued events.
- NodeServer: replayed lattice values that do not change local state no longer trigger repeated announce and root persistence work.
- NodeServer: explicitly pulled values now merge through the authoritative root before persistence or re-propagation, preventing a dominated peer value from demoting the announced or persisted root.
- NodeServer: a primary-store failure during a synchronous checkpoint now propagates to the sync caller; memory is not rolled back, root publication is unconfirmed, and recovery remains operator policy.
- NodeServer: a NodeInfo checkpoint failure during launch now closes every service started by that launch, leaving the node stopped and safe to retry.
- NodeServer: an inbound dispatcher drain timeout now leaves shutdown explicitly incomplete and retryable, preventing relaunch from creating a second ordered consumer while the original thread is still active.
- NodeServer: fresh and restored nodes seed their announced snapshot during launch, so lattice queries work immediately without an extra application sync.
- NodeServer: lattice merge containment now rejects recoverable stack overflows without swallowing fatal JVM errors, preserving the dispatcher's fail-closed error boundary.
- Lattice propagation: delta and root-sync encodings now retain the `LATTICE_VALUE` protocol envelope, allowing receivers to identify the path and acquire missing branches before merge.

## [0.8.9] - 2026-07-17

### Added

- `AString.isBlank()` — allocation-free blank test on UTF-8 bytes.
- Etch online garbage collection: reclaim unreachable store data while running, with crash-safe recovery (see `convex-core/docs/ETCH_GC.md`).
- CLI: `convex etch gc`, `migrate` and `recover` subcommands for offline store collection, migration and recovery.
- `VerifyNetworkUpgrade` runnable tool — rehearses a protocol upgrade against a live network with state-diff and coin-supply checks.
- `RehearseNetworkUpgrade` runnable tool — deterministic local multi-peer upgrade activation drill.
- REST API: live query watches over SSE — `/api/v1/watch` follows a query against finalised state (enable with `queryWatch`); `/api/v1/watch/logs` streams filtered log events.
- CLI: system tray icon and controls for `local start`, `peer start` and `dlfs start`.
- UCAN: caveat path predicates to scope delegations to a path subtree.
- DLFS: drive registry persists across server restarts.

### Changed

- Fresh local/test networks launch at the latest protocol version by default (all migrations applied at genesis); pin lower with `--protocol-version` (CLI) or `:protocol-version` (peer config).
- Etch reads are now fully lock-free.
- Queries run with bounded execution resources.
- Peer startup verifies any supplied state by local replay from genesis.

### Fixed

- Etch: cross-store writes no longer copy Ref status earned in a different store.
- Etch: reads on a closed or failing store throw `StoreException` instead of reporting values as absent.
- Convex DB: DML transaction isolation and scalar row results.
- MCP: scalar tool results.

## [0.8.8] - 2026-07-09

### Added

- CVM: `cat` core function — raw byte concatenation of BlobLike values and Characters (v1 protocol, #633).
- CVM: `splice` core function — positional byte overwrite of a Blob or String (v1 protocol, #632).
- UCAN: pluggable `DIDVerifier` / `RootAuthorityPolicy` — chain verification for any DID method, per-hop delegation attenuation, and self-sovereign root-authority checks (#635).
- DLFS: delegated drive access supports delegation chains and DID-URL drive references (`did:key:zOwner.../drive`) (#635).
- CLI: `peer -c/--config` actually loads the JSON5 config file; explicit options take precedence (#625).
- CLI: `peer start --address` is applied at launch and verified against the on-chain controller (#624).
- CLI: faucet commands accept scheme and port in `--host` (previously hardcoded to 8080) (#627).
- CLI: `local start --norest` disables the REST API server (#630).

### Changed

- CLI: consistent `-a/--address` option across all `account` subcommands, with `CONVEX_ADDRESS` (#630).
- CLI: `convex help <command>` shows the named subcommand's help everywhere (#630).
- CLI: failed queries and transactions now exit non-zero, so scripts can detect failure.
- CLI: `transact --output-file` no longer opens a network connection.
- CLI: `key delete` and `key list` no longer create an empty keystore as a side effect.
- CLI: an ambiguous `--key` hex prefix is now an error instead of silently picking a key.
- CLI: `key import` auto-detects BIP39 phrases and PEM text, as documented.
- CLI: `etch --help` and `desktop --help` work like other command groups; usage headers show the full command path.

### Fixed

- `convex.asset`: `owns?` on a map of assets always returned true (v1 protocol, #621).
- `asset.multi-token`: `offer` of an unheld token wiped the caller's other holdings (v1 protocol, #620).
- NFT and box actors: `offer` receiver now normalised so non-fungibles can be transferred into boxes; `get-offer` SPI added (v1 protocol, #622).
- Trust: `trust/trusted?` fails closed on defective monitors; delegate control action aligned to `:control`; `remove-upgradability!` also removes `change-control` (v1 protocol, #623).
- CLI: `key generate --count N` corrupted the password for keys after the first, making them impossible to unlock.
- CLI: `convex status` could hang indefinitely; client connections now close properly.
- CLI: multi-address `account balance` queries, the `--peer-port` default, `local start --count 0`, and `peer create` key passwords all fixed.
- CLI: clearer error messages with causes and proper exit codes; prompting without a console errors instead of crashing.

## [0.8.7] - 2026-07-06

### Added

- **Network upgrade mechanism (#413)**: protocol upgrades can be scheduled on-chain to activate at a consensus timestamp — applying a state migration and bumping the protocol version, with the genesis hash unchanged. Peers that can't apply an upgrade warn their operator, then cleanly step out of consensus at the boundary and rejoin once updated. The first upgrade (protocol v1) also bundles every known bug fix, so switching on the mechanism brings a network fully up to date. New `schedule-upgrade` / `unschedule-upgrade` core functions (system accounts only). See `convex-core/docs/UPGRADE.md`.
- `gensym` core function — a fresh unique symbol for capture-safe macros (protocol v1, #598, #602).
- NodeServer: inbound value-size limit to bound merge cost from untrusted peers (`:maxInboundValueSize`, #564).
- NodeServer: per-connection inbound stats and a circuit-breaker that drops connections after sustained abuse (`:maxConsecutiveRejects`, #566).
- NodeServer: public URLs are validated at launch, so a misconfigured node fails fast instead of advertising an unreachable address (#567).
- Peer: configurable inbound client connection limit (`:max-connections`, default 1024, #482).
- MCP: `signingListAccounts` can resolve the on-chain addresses each key controls (`resolve=true`, #551).
- MCP: configurable Origin allow-list for DNS-rebinding protection on private deployments (`mcp.allowedOrigins`, #552).
- Maven wrapper (`./mvnw`) and `.editorconfig` — builds and editor settings work out of the box (#581).

### Changed

- Multiply (`*`) now charges juice for the true O(n·m) cost of big-integer multiplication (protocol v1, #603).
- Consensus: peers won't confirm a block dated beyond their clock plus a small skew allowance, so a future-dated block can't teleport the consensus clock forward (#595, see `convex-core/docs/CONSENSUS.md`).
- Peer: a peer that can't apply a scheduled upgrade sheds its stake in a randomised pre-activation window, so the remaining upgraded peers still reach supermajority (`:auto-manage`, #597).
- Lattice: write timestamps flow through `LatticeContext` (KV, Queue, P2P), making writes deterministic under a supplied clock (#561).
- NodeServer: `setMergeContext` is now configuration-time only, so the merge context can't change under an in-flight merge (#568).
- Lattice: boundary cursors reworked onto a shared update-on-write base (structural JSON writes, `resolve()`, no re-encoding of unchanged values); the superseded `JSONValueLattice` is removed.
- CLI: connecting to the production Protonet peer by default now prints a one-line notice — override with `--host` or `CONVEX_HOST` (#582).

### Fixed

- `update` / `update-in` apply all arguments in their 5+ argument arities (protocol v1; reported and first fixed by @jeroenvandijk, #533, #534).
- `convex.fungible` `add-mint` allows unlimited minting when `:max-supply` is unset, instead of blocking all mints (protocol v1, #528).
- Convex Lisp correctness: quasiquoted sets/maps, top-level `` `~false ``, double-evaluation in `define`, `call` arity errors, and `dotimes` count expressions (protocol v1, #598).
- `for`, `for-loop` and `switch` no longer capture user bindings that collide with their loop variables (protocol v1, #602).
- Around twenty core docstring corrections where the docs contradicted the implementation (protocol v1, #600).
- Integer `div`, `quot` and `rem` are correct for negative divisors and big-integer operands (#599).
- `Shutdown.addHook` no longer races when multiple servers or nodes launch in parallel (#604).
- `set-peer-data` updates the peer named by its key argument, plus assorted peer-op error-code and message fixes (#601).
- LatticePropagator: `close()` now drains the final writes, making shutdown a durability guarantee point.
- `computeSupply` no longer subtracts the reward pool, matching the `coin-supply` definition of issued supply (#598).
- Lattice queues/topics: partition index uses `floorMod`, so a `Long.MIN_VALUE` key hash can't go negative (#561).
- `recur` outside a function or loop reports its intended message again (#115).
- CLI: `key generate` always shows the BIP39 mnemonic on stderr, even at `-v0` — a lost mnemonic is unrecoverable (#583).

### Security

- NodeServer: inbound lattice values from untrusted peers are handled defensively — wrong types rejected (#562), merge failures (including engineered `StackOverflowError`) contained rather than killing the receive thread (#561), and malformed KV entries rejected at validation (#561).
- Lattice: container lattices validate foreign entries per-child even when merging into an empty region, closing a path that could commit wrong-typed or forged children to a fresh node (#561).
- Convex DB: the Postgres wire decoder validates frame lengths and counts before allocation, closing a pre-auth denial of service (contributed by @PrazwalR, #596).
- MCP: seed-carrying tools refuse cleartext HTTP from non-loopback clients, so Ed25519 seeds can't leak in transit (`allowHttpSeeds` opts out for private networks, #554).
- Peer transport: malformed-frame rejections log at debug, so a hostile client can't spam the operator log (#41).

## [0.8.6] - 2026-06-22

### Added

- LatticePropagator: `nextAnnounce()` future for awaiting the next announced value, replacing the need to poll `getLastAnnouncedValue()`
- CLI: `local start` now reports the actual peer ports in use (`Peer ports: ...`) — previously auto-assigned ports were not discoverable from the output

### Changed

- DLFS: deletions are now tracked in a separate per-directory tombstone index (an optional 5th node element, present only when non-empty) instead of as tombstone nodes inside the live entries; existing drives load unchanged. Live directory operations (listing, emptiness, navigation) no longer scan tombstones (#587)

### Fixed

- Social: posts created in the same millisecond no longer collide on timestamp keys — previously the later post silently overwrote the earlier one

### Security

- UCAN: JWT-encoded tokens are now verified against the public key bound in the `iss` DID, not the sender-controlled `kid` header — closes an issuer-spoofing authentication bypass that allowed forging any issuer (#586)
- UCAN: `Capability` resource matching now enforces path-segment boundaries (a grant on `w/notes` no longer covers the sibling `w/notesSECRET`) and fails closed on an empty/absent resource — closes a capability attenuation escape and fail-open (#585)
- DLFS: lattice merge fails closed on malformed nodes from untrusted peers instead of throwing, preventing a merge-path denial of service (#590)

## [0.8.5] - 2026-06-11

### Added

- UCANValidator: `checkTemporalBounds` for post-ingress re-validation of `nbf`/`exp` outside the parse path
- UCANValidator: `parseTransportUCANsWithBearer` helper merging proof chain and bearer token in a single call
- NodeServer: synchronous publication on the primary propagator — `cursor.sync()` runs announce + setRootData + broadcast on the caller's thread, returning after primary store publication; secondaries remain async; publication errors propagate to the caller (#569)

### Changed

- CVM: cache `Local` op instances for small positions, eliminating most `Local` allocations during compile and execute (#559)

### Fixed

- TransactionHandler: reject faulty or incompletely-referenced transactions at intake; block production no longer stalls on MissingDataException (#531)
- DLFS: directories with tombstoned-only entries now correctly delete; iteration via `Files.newDirectoryStream` skips tombstones; `mkdir` over a tombstoned name succeeds (#571)
- LatticePropagator: serialise `processSnapshot` and `persist` pipelines so the propagator is the sole writer of `setRootData` per store and an older snapshot cannot demote the root pointer after a newer snapshot's sync returned (sole-writer invariant)
- Server: `waitForShutdown` now always surfaces an interrupt as `InterruptedException`, even if the interrupt flag was set before the wait began — previously a pre-wait interrupt returned silently, so `convex peer start` could exit 0 instead of 130 when interrupted

## [0.8.4] - 2026-04-18

### Added

- CellExplorer: budgeted JSON5 pretty-printer for lattice data with truncation and partial-form rendering
- JSON5 writer (`JSON.appendJSON5`, `JSON.printJSON5`) with extended escape handling — \v, \xHH, line continuation, lenient fallback (#546)
- UCAN capabilities, JWT validation, and DID-based authentication flows
- MCP spec 2025-11-25 target with backward-compatible version negotiation and server extensibility hooks
- ConvexDB: Calcite convention pipeline with index pushdown, merge joins, and table statistics
- ConvexDB: DDL support (CREATE TABLE, DROP TABLE via JDBC) and SQL transaction support
- ConvexDB: replication demo with Etch stores
- DLFS: CLI, DID authentication, MCP tools, and WebDAV sync after mutations
- L2 lattice caching
- Javadoc overview pages and package descriptions across all published modules
- Docker Hub automated push in CI workflows
- Install scripts

### Changed

- `JSON.appendJSON` strictly JSON-compliant: non-finite doubles emit `null` instead of the non-standard `NaN` literal (#547)

### Fixed

- ConvexDB: ORDER BY with JOIN queries (#540)
- MCP SSE race condition on connection setup
- NettyServer IPv4 fallback missing `sync()`; NIOServer gained matching IPv4 fallback for IPv6-unavailable hosts
- Flaky test compile: `JSONTest` `cannot access CharStream` under JPMS with ANTLR as automatic module
- Flaky CI tests (EncodingTest, McpTest, DLFSBrowser)

## [0.8.3] - 2026-03-02

### Added

- Challenge/response peer verification protocol
- UCAN delegation and JWT authentication support
- Lattice P2P infrastructure with context-aware merges
- ConvexDB SQL query layer (Calcite integration)
- Social lattice application framework
- Belief snapshot acquisition and testing against live network

### Changed

- Peer URLs normalised to `tcp://` scheme (legacy `host:port` handled as fallback)
- Content-negotiated REST API error responses (JSON, CVX, plain text)
- Improved CVX and JSON parser error messages with source locations
- Netty client threads now daemon (standalone tools exit cleanly)
- Better connection management and inbound peer verification

### Fixed

- PeerStatus metadata silently lost after decode when delegated stake changed
- GitHub Actions release workflow

## [0.8.2] - 2025-11-21

### Added

- Peer Explorer web application
- Lattice Cursor functionality
- Improved file / resource handling utilities
- Improved JSON Functionality
- New string handling functions
- Base58 encoding support
- Experimental MCP Server support

### Changed

- Better AString interning
- Improved REST API for transactions / block details

### Fixed

- Potential synchronisation issue with ConvexLocal
- BIP39 paths for CLI key import

## [0.8.1] - 2025-03-14

### Added

- Merge operation subsystem for standard lattice types
- JSON parser and ANTLR4 grammar
- Web based explorer interface on REST API server

### Changed

- Improved JSON handling

### Fixed

- Minor GUI updates


## [0.8.0] - 2024-12-24 - PROTONET

### Added

- New Netty Server implementation
- Better CNS functionality
- Support for tagged forms in Convex Reader
- `dissoc-in`, `update` and `update-in` core functions
- `switch` conditional macro
- Tagged values in Reader (e.g. `#Index {}`)
- `evict-peer` core function to remove old / under-staked peers
- Automatic distribution of rewards to peers / delegated stakers
- Generalised CAD3 data support
- Logging for fungible token transfer events

### Changed

- Class hierarchy refactoring
- Many GUI updates
- Reader performance enhancements
- Booleans no longer cast to the Integers 0 / 1
- Update some errors thrown for failed casts
- `set!` now allows pending definitions
- Better internal handling of peer fees
- Better `and` and `or` macros
- Rename `stake` to `set-stake`

## [0.7.15] - 2024-09-17

### Added
- Docker `Dockerfile` build for self-contained peer container

### Changed
- Better format / HTML building for peer web app with j2html
- Better CLI design for `convex account balance` and `convex account info`
- Better handling for JSON results in REST client

## [0.7.14] - 2024-09-10 - MAVEN CENTRAL ISSUE

NOTE: Due to to an apparent issue in Maven Central, this release was only partially uploaded. It is recommended to avoid depending upon this release.

### Added
- New main class for both GUI and desktop ("MainGUI")
- New `convex-integration` module
- DLFS base implementation and browser
- Better Result and `log` information

### Changed
- Updated CLI and GUI functionality
- Significant internal refactoring
- Upgrades to default REST API and OpenAPI documentation
- Better error handling
- Convert core modules to JPMS

## [0.7.13] - 2024-05-21

### Added
- New Convex Deskup GUI interface ("MainGUI")
- BIP39 key generation support
- Observability support initial version
- Support for `@` and `resolve` for CNS resolution
- Extra special ops: `*nop*`, `*parent*`, `*controller*`, `*memory-price*` and `*env*`
- `query-as` function (query mode equivalent to `eval-as`)
- Convex Lisp `quasiquote` implementation
- Extra example code and tests
- Data Lattice File System (Prototype)

### Changed
- Updated CLI and GUI functionality
- Significant internal refactoring
- Encoding changes for better memory efficiency
- Upgrades to default REST API
- More efficient signing protocol / SignedData compressed format
- CNS improvements
- More efficient encodings for CVM core definitions and ops

## BREAKING CHANGES
- Switch from `:callable?` to `:callable` for metadata on callable actor functions
- Etch encoding changes. Will require fresh Etch database. 
- Renamed `BlobMap` to `Index`

### Fixed
- Miscellaneous edge cases and error handling
- Bug fixes for message decoding

## [0.7.12] - 2023-07-12

### Added
- Asset ownership based trust monitor `convex.trust.ownership-monitor`
- Peers now utilise quick Belief broadcasts (own Order changes only)
- Basic fork detection and recovery from historical states

### Changed
- Account controllers can now be any scoped actor
- Various adjustments to improve CPoS latency
- Experimental adjustments to CVM constants

### Fixed
- Bug fixes for message decoding

## [0.7.11] - 2023-05-30

### Added
- Scoped Actors 
- BIP39 compatible seed generation
- Variable sized Etch index levels
- Ability to set `*juice-limit* in a Context (thanks @helins!)
- Extra consensus confirmation levels and configuration options

### Changed
- Make `blob` casts support arbitrary sized integers
- Remove unnecessary generic type parameter from Context class
- Updates to GUI implementation
- `*juice*` now starts at 0 and counts upwards towards a juice limit

### Fixed
- Various improvements for efficient consensus

## [0.7.10] - 2023-04-28

### Added
- Support for arbitrary sized integers (Part 1)
- Better support for CI builds
- Allow `merge` and `slice` to work with Indexs

### Changed
- General update of dependencies to most recent versions as of Apr 2023
- Refactoring of Sodium crypto libraries to separate convex-sodium module
- Sequence numbers are now incremented at end of transaction. *sequence* behaves "as-if" already updated.
- New networking message model

### Fixed
- Fix for printing of single quotes (see #407)
- Fixed most Javadoc warnings
- Fixed issue with encoding of `set!` Op
- Tighten casting behaviour
- Better management of message queues and server threads

## [0.7.9] - 2022-09-22
### Fixed
- Fix for Java 11 compatibility with Etch

## [0.7.8] - 2022-09-13
### Fixed
- Refactoring Etch seekMap for Java 11 support see #394
- Avoid static initialisation for executor thread pool used in stress testing

## [0.7.7] - 2022-09-05
### Added 
- REST API Server
- Support for parameterised asset paths in `convex.asset` as per CAD19
- Multi-token reference implementation for single actor supporting many fungible assets
- Add missing `double?` predicate
- OpenAPI REST specification

### Changed
- Static compilation enabled for `convex.core` functions
- Better JSON utility support

### Fixed
- Correct handling for negative zero in min and max 
- Fixed handling for octal and unicode escape sequences in Reader

## [0.7.6] - 2022-05-24
### Added 
- `print` core function for readable representations
- `split` and `join` core functions for Strings
- `slice` core function
- Add `VectorBuilder` utility class for fast Vector construction
- `declare` core macro
- Additional benchmarks
- Mnemonic refactoring, add BIP39 word list

### Fixed
- Edge cases around UTF-8 string handling

## [0.7.5] - 2022-03-30
### Added 
- Adversarial test cases for Encodings
- Efficient BlobBuilder utility class

### Changed
- Convert CVM Characters to be Unicode code points
- Convert CVM Strings to be UTF-8 (backed by Blobs)
- Import convex-java as a submodule

### Fixed
- Miscellaneous edge cases with canonical encodings
- Update logback dependency to fix potential security issues
- Better validation for canonical Cells and Refs

## [0.7.4] - 2022-02-18
### Changed
- Require all Blocks in an Order to be Signed
- Support `empty?` predicate on all `Countable` CVM values
- Update `Block` format to remove Peer Key (get this from Signature)

### Fixed
- Catch NIOServer CancelledKeyException on Linux (thanks Otto!)

## [0.7.3] - 2021-11-28
### Added
- Constant compilation for `:static` declarations in core / other libraries

### Changed
- Additional validation for message formats

### Fixed
- Make `empty?` work on all Countable data types

## [0.7.2] - 2021-11-01
### Added
- Set can now be constructed with any Countable

### Changed
- Convex.queryXXX methods now return a CompletableFuture instead of Future
- Some Juice cost adjustments
- `empty?` now works on any Countable structure (including Strings and Blobs)
- `RefSoft` instances now directly reference a store instead of relying on thread locals
- Miscellaneous internal refactoring for Peers

### Fixed
- Eliminate non-canonical NaN values

## [0.7.1] - 2021-09-28
### Added
- Server now generates a keypair automatically if required
- Added `for-loop` for imperative C-style looping
- Support casting Longs <-> Blobs
- Bitwise Long operations bit-and, bit-or, bit-xor and bit-not
- Convenience overloads for Convex client API query and transact with String values

## Fixed
- Fix for Etch data length persistent issue


## [0.7.0] - 2021-09-08
### Added
- Initial Public Alpha release
- Core CVM
- Convergent Proof Of Stake Consensus
- Command Line Interface (CLI)
- GUI Testing Interface
- Benchmark Suites


