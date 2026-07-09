# Changelog

Notable changes to Convex core modules will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.8.8] - 2026-07-09

### Added

- CVM: new `cat` core function — raw byte concatenation of BlobLike values and Characters (v1 protocol upgrade, #633).
- CVM: new `splice` core function — positional byte overwrite of a Blob or String (v1 protocol upgrade, #632).
- UCAN: root-authority verification with pluggable `DIDVerifier` / `RootAuthorityPolicy` — signature verification at every hop for any DID method, per-hop delegation attenuation, and root-authority checks per requested capability, with `did:key` / `did:convex` and self-sovereign defaults (#635).
- DLFS: UCAN delegated drive access now supports delegation chains (per-hop attenuation enforced) and explicit DID-URL drive references (`did:key:zOwner.../drive`), authorised via the shared convex-core root-authority check (#635).
- CLI: `peer -c/--config` now actually loads the specified JSON5 peer config file (previously the option was accepted but ignored); explicit command line options take precedence over config file values (#625).
- CLI: `peer start --address` is passed to peer launch and verified after startup — the CLI warns when the specified controller does not match the authoritative on-chain controller (noting that an actor controller such as a trust monitor may still permit control) (#624).
- CLI: the faucet commands (`account create --faucet`, `account fund`) accept a scheme and/or port in `--host`, e.g. `https://peer.example.com:8443` — previously the REST port was hardcoded to 8080 (#627).
- CLI: `local start --norest` disables the REST API server, matching `peer start` (#630).

### Changed

- CLI: all `account` subcommands share a consistent `-a/--address` option (with `CONVEX_ADDRESS`); `account info` accepts `-a` when the positional address is omitted (#630).
- CLI: `convex help <command>` now shows the named subcommand's help everywhere (standard picocli help command, replacing a custom implementation that ignored its argument) (#630).

- CLI: a failed query or transaction result now exits non-zero (previously `convex query` / `convex transact` / `convex account info` / `convex peer create` printed the error result but exited 0, so scripts could not detect failure). The result is still printed to stdout as before.
- CLI: `convex transact --output-file` (offline transaction encoding) no longer opens a network connection to the default peer.
- CLI: `key delete` and `key list` no longer create an empty keystore as a side effect when the specified keystore does not exist (exit code 66, NOINPUT).
- CLI: an ambiguous `--key` hex prefix matching multiple keystore entries is now an error instead of silently using an arbitrary matching key.
- CLI: `key import` auto-detection now recognises BIP39 mnemonic phrases and PEM text (previously only hex input was auto-detected, despite the documented behaviour), and gives a clear error when the type cannot be inferred.
- CLI: `convex etch --help` and `convex desktop --help` now work like other command groups; group usage headers show the full command path (e.g. `convex peer` rather than `peer`).

### Fixed

- `convex.asset`: `owns?` on a map-of-assets form always returned true (misplaced paren); now checks each entry correctly. Applied via the v1 protocol upgrade (#621).
- `asset.multi-token`: `offer` for a token the caller did not yet hold wiped the caller's other token holdings; now preserved. Applied via the v1 protocol upgrade (#620).
- Asset actors (`nft.simple`, `nft.basic`, `box.actor`): `offer` keyed by the raw receiver, so non-fungible assets could not be transferred into boxes; now normalised, with the `get-offer` SPI added and `box.actor/burn` input coerced. Applied via the v1 protocol upgrade (#622).
- Trust library: `trust/trusted?` now fails closed against a defective trust monitor — it catches errors and boolean-coerces the result rather than propagating; `convex.trust.delegate` control action aligned to `:control`; `remove-upgradability!` undefines the generated `change-control`. Applied via the v1 protocol upgrade (#623).
- CLI: `key generate --count N` stored keys 2..N under a corrupted password (the password buffer was wiped in-place after the first key), making them impossible to unlock. All generated keys are now encrypted with the supplied password.
- CLI: `convex status` could hang indefinitely (unbounded `join()` on the status request); it now honours the connection timeout. Client commands also close their peer connections properly.
- CLI: `account balance` with multiple addresses generated a query without separators between addresses; `peer start --peer-port` ignored its documented default of 18888 (always picking a random port); `local start --count 0` crashed with an internal error instead of a usage error; `peer create` stored the generated peer key under the controller key password rather than the peer key password.
- CLI: numerous error messages now include the underlying cause and use appropriate exit codes; prompting for input without a console gives a clear error instead of a crash.

## [0.8.7] - 2026-07-06

### Added

- Network upgrade mechanism (#413): protocol upgrades can be scheduled on-chain to activate at a consensus timestamp, applying a versioned state migration and incrementing the protocol version, without ever changing the genesis hash. New governance-gated core functions `schedule-upgrade` / `unschedule-upgrade` (callable only by system accounts below `#8`). A peer whose release cannot apply a scheduled upgrade warns its operator ahead of the activation, then cleanly withdraws from consensus at the boundary — staying available for queries rather than diverging — and rejoins after the software is updated. The first upgrade (to protocol version 1) also bundles every core bug fix known at this point, so activating the mechanism brings a network fully up to date rather than leaving known bugs for a later upgrade (see **Changed** and **Fixed** below). See `convex-core/docs/UPGRADE.md`.
- `gensym` core function, installed at protocol v1: returns a fresh, unique symbol (optionally with a name prefix), so macros can introduce bindings that cannot capture user symbols (#598, #602).
- NodeServer: a configurable inbound value-size limit (`:maxInboundValueSize` in `NodeConfig`, default the transport message cap) — an oversized `LATTICE_VALUE` is rejected before its merge runs on the receive thread, bounding merge cost from untrusted peers. Set it below the transport cap when exposing a node to untrusted peers (#564).
- NodeServer: per-connection inbound statistics (`getInboundStats()`) with a circuit-breaker — a connection is closed after a configurable number of consecutive rejected or undecodable messages (`:maxConsecutiveRejects`, default 100; 0 disables), so sustained abuse costs the sender its connection while a single accepted merge resets the streak (#566).
- NodeServer: a configured public URL is validated at launch (scheme/host/port required; loopback, private-range and link-local IP literals rejected), so a misconfigured node fails fast instead of advertising an unreachable address into the signed node registry. `:allowPrivateURL` opts out for dev networks with intentional private addressing (#567).
- Peer: the maximum number of inbound client connections is configurable (`:max-connections`, default 1024) — previously a fixed compile-time cap (#482).
- MCP: `signingListAccounts` can resolve the on-chain addresses controlled by each signing key (pass `resolve=true`; opt-in because it scans the account table) (#551).
- MCP: configurable Origin allow-list (`mcp.allowedOrigins`) — requests carrying a different Origin are rejected with 403, giving localhost and private deployments the DNS-rebinding protection the MCP spec requires. Public peers keep the allow-all default (#552).
- Developer experience: Maven wrapper (`./mvnw`) and `.editorconfig`, so builds and editor settings work out of the box without a local Maven install (#581).

### Changed

- Multiply (`*`) now charges juice proportional to the O(n·m) cost of big-integer multiplication, from protocol v1 — closing a juice under-charge that let large multiplications run far more cheaply than the work they cost. `+` and `-` are unaffected (their cost is genuinely linear) (#603).
- Consensus: a peer no longer confirms a block dated beyond its own clock plus a small skew allowance (`MAX_BLOCK_FORWARD`, 30s), so a future-dated block cannot teleport the consensus clock forward — closing a clock-manipulation weakness amplified by the upgrade mechanism (firing scheduled upgrades early). A far-future block is also stably demoted to the back of the unconfirmed ordering so it does not wedge later in-time blocks behind it (a partition, not a timestamp sort, so back-dating cannot be used to jump the ordering). Peer-local confirmation policy in belief merge, not a validity rule, so replay determinism is unchanged (#595, see `convex-core/docs/CONSENSUS.md`).
- Peer: best-efforts stake withdrawal ahead of an unsupported network upgrade. A peer whose release cannot apply a scheduled upgrade sheds its own stake at a randomised instant in a pre-activation window, so a withdrawn-but-still-staked cohort does not prevent the remaining upgraded peers from reaching supermajority. Guarded against removing the last viable peer; gated on `:auto-manage` (default on) (#597).
- Lattice: write timestamps are injected through `LatticeContext` rather than read from the system clock inside lattice implementations (KV, Queue, P2P), so a driver- or test-supplied timestamp makes every write deterministic; the wall clock remains the fallback for standalone use (#561).
- NodeServer: `setMergeContext` is configuration-time only and throws if called after launch — the context is published safely by thread start and can no longer change under an in-flight merge (#568).
- Lattice: boundary cursors reworked onto a shared update-on-write base, adding structural JSON writes, generic write interception and `resolve()`; whole-value last-write-wins is decomposed into orthogonal lattice layers, and merges no longer re-encode unchanged values. The legacy `JSONValueLattice` (additive per-key JSON merge, superseded by `JSONLattice`) is removed.
- CLI: a client command that connects to the production Protonet peer by default (`peer.convex.live`) now prints a one-line notice, so the default is never silent — override with `--host` or `CONVEX_HOST` (#582).

### Fixed

- `update` / `update-in` now apply all arguments in their 5-or-more argument arities (previously the first extra argument was dropped, and `update-in`'s variadic arity errored). Activates at protocol v1. Reported and first fixed by @jeroenvandijk (#533, #534).
- `convex.fungible` `add-mint` allows unlimited minting when `:max-supply` is unspecified, instead of defaulting the cap to zero and blocking all mints. Activates at protocol v1 (#528).
- Convex Lisp correctness fixes, activating at protocol v1: quasiquote of sets/maps containing unquotes now produces the set/map rather than a call-form list; a top-level `` `~false `` yields `false`; `define` no longer evaluates its value twice; `call` with the wrong number of arguments is an `:ARITY` error instead of silently expanding to `nil`; and `dotimes` accepts any count expression, not only a literal (#598).
- `for`, `for-loop` and `switch` no longer capture user bindings that collide with their internal loop variables (macro hygiene). Activates at protocol v1 (#602).
- Core function docstrings: around twenty corrections where the documented behaviour contradicted the implementation — including `bit-not` arity, `comp` composition order, `map` / `empty` return types, and the `symbol` name-length limit. Activates at protocol v1 (#600).
- Integer `div`, `quot` and `rem` now return correct results for negative divisors and big-integer operands, with `div` applying Euclidean division consistently (#599).
- `Shutdown.addHook` no longer races on its shared hook map under concurrent registration, which could throw or lose a hook when multiple servers or nodes launched in parallel (#604).
- `set-peer-data` now updates the peer named by its key argument, which was previously ignored (the peer was derived from the caller's own key). A no-change `set-stake` / `set-peer-stake` returns `0` rather than a stale value; a wrong-length peer key is consistently an `:ARGUMENT` error rather than `:CAST`; and several arity error messages that reported the wrong argument count are corrected (#601).
- LatticePropagator: a clean shutdown could silently lose the final lattice value — the propagation loop could observe the stop flag and exit in the instant before the value was queued, so `close()` returned without persisting the most recent writes. The closing thread now drains and processes anything left in the trigger queue after the loop exits, making shutdown a durability guarantee point in every interleaving.
- `computeSupply` no longer subtracts the reward pool (account `#0` holds issued coins in transit to peers, not a burn or reserve), matching the CVM `coin-supply` definition of issued supply (#598).
- Lattice queues/topics: partition index is computed with `floorMod`, so a key whose hash is `Long.MIN_VALUE` no longer produces a negative array index (#561).
- `recur` outside a function or loop now reports its intended descriptive message ("attempt to recur or tail call outside of a function body") — a missing `else` had let the generic "Unhandled Exception" text overwrite it. Error code unchanged; replay hash unaffected (#115).
- CLI: `key generate` always shows the BIP39 mnemonic (on stderr), even at verbosity `-v0` — previously it was silently discarded, and a lost mnemonic is unrecoverable (#583).

### Security

- NodeServer: inbound lattice values from untrusted peers are handled defensively. Wrong-type values are explicitly rejected leaving state unchanged (#562); merge failures — including engineered `StackOverflowError` from adversarially deep structures (DLFS nodes among them) — are contained rather than allowed to kill the receive thread (#561); and malformed KV entries are rejected at validation instead of poisoning later store-wide reads (#561).
- Lattice: container lattices (Owner, Keyed, Map, Index, Topic) now route foreign entries through per-child validation even when merging into an empty region — previously a single message to a fresh node or unpopulated sub-path could commit a wrong-typed child (permanently blocking that slot) or, for owner-signed lattices, seed forged entries bypassing signature verification (#561).
- Convex DB: the Postgres wire decoder validates frame lengths and count fields before allocation, closing a pre-authentication denial of service — a client could previously declare a near-2GB frame length and force unbounded buffering, or supply negative/oversized counts causing crashes on the receive path. Malformed frames now close the connection. Contributed by @PrazwalR (#596).
- MCP: seed-carrying tools (`transact`, `sign`, `signAndSubmit`, `transfer`, `keyGen` with a supplied seed, `signingImportKey`) refuse cleartext HTTP from non-loopback clients, so a misconfigured peer can no longer silently accept Ed25519 seeds in transit. HTTPS (directly or via `X-Forwarded-Proto` from a TLS-terminating proxy) is required; `allowHttpSeeds` opts out for trusted private networks (#554).
- Peer transport: malformed-frame rejections are logged at debug rather than WARN, so a hostile client cannot spam the operator log; oversized declared frame lengths are covered by a server-level test (#41).

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
- NodeServer: synchronous commit on the primary propagator — `cursor.sync()` runs announce + setRootData + broadcast on the caller's thread, returning only after primary durability; secondaries remain async; persistence errors propagate to the caller (#569)

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


