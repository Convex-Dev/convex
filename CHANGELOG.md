# Changelog

Notable changes to Convex core modules will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.8.20-SNAPSHOT] - Unreleased

### Added

### Changed

- Give lattice components one way to move onto new storage, for both forks and transactions; see [Forks, transactions and publication](convex-core/docs/LATTICE_CURSOR_DESIGN.md#forks-transactions-and-publication) when upgrading.

### Fixed

## [0.8.19] - 2026-11-07

### Added

- Address lattice values by plain string paths, using the keys their JSON shows. ([Component paths](convex-core/docs/LATTICE_APPLICATIONS.md#external-component-paths))
- Choose how each Convex DB database merges replicated writes, and which clock timestamps them. ([Merge Policy](convex-db/docs/LATTICE-DB-ARCHITECTURE.md#merge-policy))
- Secure Convex DB's PostgreSQL connections with TLS (proposed by @trsb-standards). ([TLS](convex-db/README.md#tls))
- Support PostgreSQL client transactions and more standard SQL in Convex DB (from @trsb-standards). ([#812](https://github.com/Convex-Dev/convex/issues/812))
- Run a P2P node with `convex p2p start`, joining through seeds and rejoining after a restart. ([Running a node](convex-p2p/README.md#running-a-node))
- Block unwanted P2P nodes by key, address or host. ([Local Evidence](convex-peer/docs/P2P_DESIGN.md#46-local-evidence-partial))
- Name lattice peers by `did:web` as well as by address. ([Networking](convex-peer/docs/LATTICE_NETWORKING.md#connection-capabilities))
- Replicate DLFS nodes in both directions. ([DLFS](convex-dlfs/README.md#run-a-webdav-server))
- Stream large DLFS files over WebDAV, with byte ranges and atomic uploads. ([#790](https://github.com/Convex-Dev/convex/issues/790))
- Give lattice applications more control over networking, persistence and private state. ([Lattice applications](convex-core/docs/LATTICE_APPLICATIONS.md))
- Save lattice services automatically on a schedule, with a full save whenever a host closes. ([Persistence](convex-peer/docs/PERSISTENCE.md#logical-publication-and-durability))
- Set how often lattice nodes re-announce their state to peers, turn it off, or announce on demand. ([Live peer selection](convex-peer/docs/LATTICE_NETWORKING.md#live-peer-selection))

### Changed

- Leave access-list cleanup and capacity limits to the application. ([Lattice applications](convex-core/docs/LATTICE_APPLICATIONS.md#data-and-merge-boundaries))
- Show blob map keys as `0x` hex in all JSON output, including REST API responses. ([CAD044](https://docs.convex.world/docs/cad/json))
- Make lattice cursor reads explicit about missing data and value types; see the [cursor API](convex-core/docs/LATTICE_CURSOR_DESIGN.md#atomic-operations-and-paths) when upgrading.
- Refuse a lattice path write that cannot be made with `PathWriteException`, naming the key that does not fit. ([Cursor API](convex-core/docs/LATTICE_CURSOR_DESIGN.md#atomic-operations-and-paths))
- Speed up Convex DB replication and prepared statements. ([#786](https://github.com/Convex-Dev/convex/issues/786))
- Follow standard SQL for `INSERT` and identifier case in Convex DB, and name databases in JDBC URLs only with `database`; see [Identifier Case](convex-db/README.md#identifier-case) when upgrading.
- Configure P2P nodes with a JSON5 file, and advertise them with `convex p2p start --url`, which replaces `convex dlfs start --url`. ([Running a node](convex-p2p/README.md#running-a-node))
- Serve DID documents that standard DID resolvers read. ([CAD043](https://docs.convex.world/docs/cad/did))
- Keep peers responsive to slow clients; a client may now receive a later block's results first. ([Result reporting](convex-peer/docs/TRANSACTION_PATH.md#result-reporting))
- Report a closed client connection clearly, as `:CONNECT`. ([Client backpressure](convex-peer/docs/CLIENT_BACKPRESSURE.md#aconnection-interface))
- Judge each Belief by its signed Orders, whichever connection brings it. ([Peer trust](convex-peer/docs/PEER_PRIORITY.md))
- Send a lattice node's final state to its peers as it shuts down. ([Shutdown](convex-peer/docs/SHUTDOWN.md))
- Keep peers fast under sustained load with one bounded cache per Etch store; the `enableL2` setting no longer has an effect. ([Etch configuration](convex-core/docs/ETCH_CONFIG.md#options-and-defaults))
- Read Etch stores about twice as fast; `etch validate` still checks every record's hash. ([Etch v3](convex-core/docs/ETCHv3.md#sync))
- Decode messages with less memory, reusing cells a peer already holds and no longer keeping a whole message for the cells taken from it. ([Decoding](convex-core/docs/ENCODER_DESIGN.md#decoding))
- Simplify the Java APIs for lattice applications, networking and Convex DB; see the [design notes](convex-core/docs/LATTICE_APPLICATIONS.md) when upgrading.
- Return results from lattice component transactions, and run one exclusively when it must not repeat; transactions with no result are now `execute`, see [Forks and transactions](convex-core/docs/LATTICE_APPLICATIONS.md#forks-and-transactions) when upgrading.

### Removed

- Remove the legacy generic lattice application type in favour of specialised domain components. ([Lattice applications](convex-core/docs/LATTICE_APPLICATIONS.md))
- Remove path resolution from lattices; each component now resolves its own paths. ([Component paths](convex-core/docs/LATTICE_APPLICATIONS.md#external-component-paths))
- Move the P2P node record helpers into convex-p2p. ([Lattice structure](convex-p2p/README.md#lattice-structure))
- Remove Convex DB's separate table and row lattices; its components now merge their own data. ([Merge Policy](convex-db/docs/LATTICE-DB-ARCHITECTURE.md#merge-policy))

### Fixed

- Keep peers reaching consensus under heavy transaction load. ([Propagation](convex-peer/docs/PROPAGATION.md#delta-construction-and-fan-out))
- Replicate lattice data reliably, for roots of any size and connections under load. ([Propagation](convex-peer/docs/PROPAGATION.md))
- Reach consensus promptly when a peer misses part of a Belief update, as often happens while a network starts. ([Propagation](convex-peer/docs/PROPAGATION.md))
- Keep the peers a lattice node chooses, and close dead connections within 90 seconds. ([Live peer selection](convex-peer/docs/LATTICE_NETWORKING.md#live-peer-selection))
- Connect `convex dlfs start --peer` to the peers it names. ([#629](https://github.com/Convex-Dev/convex/issues/629))
- Shut down peers, lattice nodes and HTTP servers promptly and cleanly.
- Keep Etch stores safe across interrupts and crashed start-ups. ([#748](https://github.com/Convex-Dev/convex/issues/748))
- Look up transactions by hash reliably and quickly, reporting the copy that ran when one is included more than once.
- Keep replicated data consistent and owner-signed, including lattice KV deletes and Convex DB schema changes. ([Replica Ownership](convex-db/README.md#replica-ownership))
- Keep each application's Convex DB connections on its own database. ([#789](https://github.com/Convex-Dev/convex/issues/789))
- Let a Convex DB file open again after a failed attempt, and save DLFS browser edits as you work and at exit. ([#828](https://github.com/Convex-Dev/convex/issues/828))
- Log why a P2P node could not bootstrap from a peer it connected to. ([#827](https://github.com/Convex-Dev/convex/issues/827))
- Improve Convex DB's SQL and PostgreSQL client compatibility, including integers beyond 64 bits (with fixes from @trsb-standards). ([#812](https://github.com/Convex-Dev/convex/issues/812))
- Keep a P2P node's `:local` state private, and MCP signing keys safe from a wrong passphrase. ([MCP Auth](convex-restapi/docs/MCP_AUTH.md#deletekey))
- Handle percent-encoded `did:web` names correctly. ([#816](https://github.com/Convex-Dev/convex/issues/816))
- Read a lattice fork without waiting for its sync or for other writers to it.

## [0.8.18] - 2026-10-25

### Added

- Support lattice applications composed from reusable components, with signed ownership, timestamped records, forks and atomic transactions. ([Design](convex-core/docs/LATTICE_APPLICATIONS.md))
- Let a lattice node manage networking for an application, with optional DID identity. ([Networking](convex-peer/docs/LATTICE_NETWORKING.md))
- Allow services to choose their durability with host checkpoints and scoped automatic flushing. ([Persistence](convex-peer/docs/PERSISTENCE.md))
- Support DLFS file modification times and safe concurrent drive creation. ([DLFS](convex-dlfs/docs/DLFS_DESIGN.md))
- Allow transaction simulation as an alternative to query execution, for cost estimates through REST and the Java client.
- Support fast `Index` seek, slice and concat.

### Changed

- Simplify lattice host, node and cursor APIs around the application model; see the [design notes](convex-core/docs/LATTICE_APPLICATIONS.md) when upgrading. Services save by closing the host, as closing a node no longer saves.
- Extend DLFS data with modification times as a one-way upgrade: back up stores and upgrade all replicas together. ([#688](https://github.com/Convex-Dev/convex/issues/688))
- Improve responsiveness of Java client transactions and the GUI.

### Fixed

- Make CVM integer behaviour consistent across internal representations.
- Improve robustness of shutdown and persistence for lattice services, P2P nodes, DLFS and JDBC file databases.
- Improve reliability of client requests across reconnects, timeouts and duplicate transactions.
- Harden scheduled execution, owner verification and X402 payment confirmation.
- Show text from other parties safely in the GUI, and keep it responsive during network operations. ([#765](https://github.com/Convex-Dev/convex/issues/765))
- Improve PostgreSQL wire protocol handling in Convex DB.

## [0.8.17] - 2026-10-09

### Added

- Back up Etch stores during garbage collection, and export checkpoints from live stores. ([#750](https://github.com/Convex-Dev/convex/issues/750))
- Configure each Etch store separately, and verify any store the same way. ([Configuration](convex-core/docs/ETCH_CONFIG.md))
- Secure peer connections with optional TLS, authenticated by peer key. ([Details](https://github.com/Convex-Dev/convex/commit/5cec9ba4f))
- Shut down one propagation group on a running lattice node without stopping the rest. ([#732](https://github.com/Convex-Dev/convex/issues/732))

### Changed

- Keep peer communication responsive under load, including larger updates; the `:max-belief-delta-broadcast-size` setting is retired. ([Details](https://github.com/Convex-Dev/convex/commit/8fe213ab1))
- Support scoped controllers in multi transactions, with complete logs and juice accounting. ([Details](https://github.com/Convex-Dev/convex/commit/55125e920))

### Fixed

- Make Etch garbage collection, caching and lattice state persistence more reliable. ([#746](https://github.com/Convex-Dev/convex/issues/746))
- Handle deeply nested data, CAD3 messages and malformed character literals robustly.
- Handle PostgreSQL casts and operators accurately in Convex DB. ([Details](https://github.com/Convex-Dev/convex/commit/0c3a4db53))

## [0.8.16] - 2026-09-02

### Added

- Relay signed messages between P2P nodes through opt-in Point of Presence nodes, so outbound-only nodes can exchange public or encrypted private values.
- Bootstrap a P2P node from one authenticated peer, even without a listener of its own, with late joiners receiving their follow-filtered state.
- Publish several social feed and follow actions as one signed update.
- Identify social owners and follow targets by DID: `did:key`, `did:convex` and authenticated `did:web`.

### Changed

- Compose lattice nodes from independent propagation groups and listeners, so one failing group cannot break the rest; see [lattice networking](convex-peer/docs/LATTICE_NETWORKING.md) when upgrading.
- Never drop cells from a message silently: a message too large for one frame is refused with a warning. ([#726](https://github.com/Convex-Dev/convex/issues/726))
- Size lattice query replies to what each connection accepts, so a query never makes a node encode more than it sends.
- Work out cell encoding sizes without rendering the encoding.
- Keep a shared client's transaction sequence numbers correct after timeouts and CVM errors.
- Use one canonical path form in lattice messages, rejecting missing or ambiguous paths, as CAD036 specifies.

### Fixed

- Acquire the rest of a partial lattice pull reply before merging it.
- Decode messages holding deep `Index` nodes whose children the receiver lacks, so a joining node can bootstrap from a peer holding them. ([#723](https://github.com/Convex-Dev/convex/issues/723))
- Open, move and delete DLFS entries with long names after a restart.
- Make string interning safe across threads, preventing rare crashes under concurrent use.
- Recognise cached social follow signers after decoding.

### Security

- Verify relayed point messages fully before forwarding them, and bound their size, hops, fan-out, replays and rate.
- Require a two-sided challenge-response before trusting a lattice peer.
- Accept only complete, size-bounded values from unverified lattice connections, and validate P2P social data before storing it.

## [0.8.15] - 2026-08-24

### Added

- Encrypt data for an account key with standard HPKE (RFC 9180), adding only 48 bytes.
- Tune REST transaction concurrency server-wide and per client, with `transact-limit` and `transact-limit-client`.
- Split oversized lattice and consensus updates into bounded batches, with configurable limits.

### Changed

- Raise REST `/transact` concurrency from 2 to 10,000 server-wide, with 100 per client, so slow transactions no longer hold up other callers.

### Fixed

- Confirm transactions in about 35 ms rather than 1.5 s on an idle local network, by sending new block data with each peer's Order.
- Never let a peer's confirmed consensus points move back during a Belief merge, as CAD051 requires, so a client no longer briefly sees state missing a transaction already reported.
- Persist nested Convex DB tables through a hosted lattice, and keep database and schema forks on the same persistence policy. ([#698](https://github.com/Convex-Dev/convex/issues/698))
- Keep one slow receiver from holding up propagation to healthy peers, and recover dropped updates.
- Score GUI passphrase strength as documented.

### Security

- Protect each key store entry with its own random salt; existing key stores still open, and are re-salted when next written.
- Strengthen key store passwords with 220,000 PBKDF2 iterations, following OWASP guidance.
- Encrypt exported PEM keys with PBES2 and AES-256, which stock OpenSSL 3 reads; keys exported earlier still import.
- Import PEM keys by their PKCS#8 structure, so every valid encoding imports as the right key and other algorithms are refused.
- Protect signing service passphrases against offline guessing from a copy of the store; existing entries upgrade on their next use.
- Block signing, not just key access, when a wallet entry is locked.

## [0.8.14] - 2026-08-19

### Changed

- Allow 20 seconds, not 8, for a client transaction by default, so a loaded machine no longer causes spurious `:TIMEOUT` results.
- Let lattice applications sign and timestamp writes through their own policy, so a wallet or key store can write for any identity it holds, and a write no peer would accept fails locally.

### Fixed

- Remove class initialisation cycles in core that could deadlock two threads; a few core constants moved as a result.
- Keep the local value when merging signed data for an owner this node cannot sign for, instead of failing the whole merge.
- Sign and timestamp each lattice write once, however often it retries.
- Write large files through DLFS's NIO interface without holding them in memory. ([#702](https://github.com/Convex-Dev/convex/issues/702))
- Timestamp DLFS changes exactly from their lattice context, and keep the local value when merged timestamps are equal. ([#703](https://github.com/Convex-Dev/convex/issues/703))

## [0.8.13] - 2026-08-17

### Added

- Compose lattice applications from component trees, locally or on a node, including multi-owner DLFS with per-owner drives and forks.
- Run a complete DLFS node without the Convex CLI, configured in JSON5.
- Test for characters with the new `char?` predicate (v1 protocol, #92).
- Give embedders DLFS's WebDAV mount path (#699).

### Changed

- Write streamed DLFS uploads to the store as they arrive, keeping large uploads off the heap.
- Move DLFS entries without copying their file data.
- Route DLFS WebDAV and MCP requests to drives explicitly, with authentication separate from ownership.
- Keep a lattice peer connection away from propagation and store access until the peer's identity is verified.
- Keep lattice publication and store durability as separate, explicit steps.

### Fixed

- Copy large DLFS files over WebDAV without running out of memory.
- Keep deleted DLFS drives and names deleted when stale replicas sync (#647).
- Let moved DLFS entries replace older deletions at their destination after replication (#688).
- Reject DLFS updates more than 30 seconds ahead of the host clock, configurable with `maxFutureTimestampSkew`.
- Close only the stores a peer server created, and remove temporary Etch files at shutdown.
- Resolve relative file paths against the working directory (#701).

## [0.8.12] - 2026-08-13

### Added

- Sign Ed25519 JWTs with explicit key IDs, and verify bare multikey, `did:key` and DID URL key forms.
- Publish a filtered view of a lattice through each propagator.
- Pull and merge one lattice path without transferring unrelated data.
- Validate Etch stores offline: index structure, canonical records, hashes and root completeness.
- Open encrypted Etch v3 stores in CLI maintenance commands with key files, standard input or keystores, and choose encryption for migrated stores.

### Changed

- Acknowledge lattice values after merge when the sender asks, while ordinary gossip stays fire-and-forget.
- Find an encrypted Etch store's key from the hint in its header by default, with a resolver for external secret providers.
- Use authenticated AES-GCM in the experimental symmetric encryption helper, refusing its old AES-CBC ciphertext.

### Fixed

- Announce only lattice roots a node can serve in full, and acquire missing data correctly.
- Send rapid follow-up lattice updates without waiting out a 50 ms throttle.
- Discard one form per `#_` marker in Convex Lisp, as Clojure does (#264).
- Tell missing DLFS paths from corrupt data, keep readable branches accessible, and reject malformed replicated subtrees.

## [0.8.11] - 2026-08-10

### Added

- Store data in the opt-in Etch v3 format: crash-consistent, optionally encrypted with AES-256 or ChaCha20, with inspection and repair tools.
- Accept x402 payments in Convex coin or tokens with the new `convex-x402` module (CAD042).
- Run Convex Lisp in scripts or interactively with `convex eval` and `convex repl`.
- Serve Convex tools to AI agents over stdio with `convex mcp`, including a read-only mode.
- Build and inspect EdDSA JWTs and UCAN delegations in Hacker Tools, and copy any key as a `did:key`.
- Add allocation-free SipHash-2-4 hashing.
- Move and copy DLFS files and whole directory trees atomically, without duplicating their data.

### Changed

- Accept UCANs that never expire (`exp: null`), following UCAN 1.0.
- Issue Convex UCANs in the strict `0.10.0` profile, for any DID audience; hand-written tokens need the `ucv`, `exp`, `att` and `prf` claims.
- Keep secrets from `key generate` and `key export` off standard output unless you ask for them there.
- Checkpoint persistent stores periodically, and cleanly at shutdown, while keeping fast buffered writes.

### Fixed

- Keep Index keys and DLFS names distinct up to the standard 255-byte name limit (#631, #689).
- Update DLFS file times on writes and truncations, so replication orders changes correctly.
- Reopen Etch checkpoints directly, and keep them correct through later changes and online garbage collection (#650, #686).
- Stop lattice node maintenance cleanly before the final checkpoint.
- Run `Call` transactions received over the network correctly.
- Resolve CNS aliases in named `did:web` documents, and answer deactivated ones with HTTP 410 (#618).
- Protect seeds from remote cleartext HTTP in MCP tools and legacy JSON transactions by default.
- Read long decimals, exponents and JSON5 hexadecimal numbers correctly.

## [0.8.10] - 2026-07-25

### Added

- Run lattice P2P nodes with the new `convex-p2p` module, which bundles the node registry, user identity and the application regions a node serves.
- Add guided MCP prompts for name resolution, actor calls, tokens, transaction diagnosis and access control.
- Configure the REST API and MCP endpoints securely by default: optional routes stay off until enabled, administration needs an authorised key and HTTPS, and resource limits are tunable.

### Changed

- Require the full prepared data on REST `transaction/submit`, so public preparation no longer writes pending state to a peer's store.
- Run public REST queries on their own bounded lane, so HTTP traffic cannot starve consensus queries.
- Limit REST request body sizes, including chunked bodies.
- Use Java's newer memory-mapping API for Etch on Java 22 and later, falling back automatically on Java 21.
- Streamline the built-in MCP prompts, with clearer Convex guidance.
- Return `Index` entry sets as lightweight views instead of copies.
- Keep a lattice node's network work off shared I/O threads, through a bounded queue.
- Cap public lattice node messages at 4 MiB and connections at 256 by default, with a larger tier for verified peers.
- Limit each lattice connection to its assigned propagator's view and store.
- Give lattice nodes explicit lifecycle states, and wait for data acquisition to stop before closing stores.

### Fixed

- Bound REST explorer page sizes (#660).
- Enforce MCP SSE connection limits exactly, and reject reused session IDs (#659).
- Honour `NO_COLOR` whenever it is set, as the [convention](https://no-color.org) says.
- Keep REST query watches connected under load, with the newest result replacing queued ones.
- Skip repeated work for replayed lattice values that change nothing.
- Merge pulled lattice values through the node's root before saving or re-sending them.
- Report a failed checkpoint to the code that asked for the sync.
- Leave a lattice node stopped and safe to retry when its launch or shutdown fails part-way.
- Answer lattice queries as soon as a node launches.
- Contain recoverable stack overflows in lattice merges without hiding fatal errors.
- Let receivers fetch missing branches of lattice deltas and root announcements before merging them.

## [0.8.9] - 2026-07-17

### Added

- Test strings for blankness without allocating.
- Reclaim unreachable Etch data while a store is running, with crash-safe recovery. ([Etch GC](convex-core/docs/ETCH_GC.md))
- Collect, migrate and recover Etch stores offline with `convex etch gc`, `migrate` and `recover`.
- Rehearse protocol upgrades against a live network, or as a local multi-peer drill.
- Watch queries and logs live over SSE from the REST API.
- Control `local start`, `peer start` and `dlfs start` from a system tray icon.
- Scope UCAN delegations to a path subtree.
- Keep the DLFS drive registry across server restarts.

### Changed

- Launch fresh local and test networks at the latest protocol version by default; pin a lower one with `--protocol-version` or `:protocol-version`.
- Read Etch stores without locks.
- Run queries within bounded execution resources.
- Verify any supplied state at peer startup by replaying it from genesis.

### Fixed

- Keep what each Etch store has saved separate when cells are written across stores.
- Report reads from a closed or failing Etch store as errors, not as missing values.
- Isolate Convex DB data changes in transactions, and return scalar row results correctly.
- Return scalar MCP tool results correctly.

## [0.8.8] - 2026-07-09

### Added

- Concatenate blobs, strings and characters as raw bytes with the new `cat` function (v1 protocol, #633).
- Overwrite bytes at a position in a blob or string with the new `splice` function (v1 protocol, #632).
- Verify UCAN chains for any DID method, with per-step attenuation and root-authority checks (#635).
- Delegate DLFS drive access through delegation chains and DID URL drive references (#635).
- CLI: load the JSON5 config given to `peer -c/--config`, with explicit options taking precedence (#625).
- CLI: apply `peer start --address` at launch, checked against the on-chain controller (#624).
- CLI: give faucet commands any scheme and port in `--host` (#627).
- CLI: run `local start --norest` without the REST API server (#630).

### Changed

- CLI: use the same `-a/--address` option, or `CONVEX_ADDRESS`, across every `account` subcommand (#630).
- CLI: show any subcommand's help with `convex help <command>` (#630).
- CLI: exit non-zero when a query or transaction fails, so scripts can detect it.
- CLI: write `transact --output-file` without opening a network connection.
- CLI: run `key delete` and `key list` without creating an empty keystore.
- CLI: refuse an ambiguous `--key` hex prefix instead of picking a key.
- CLI: recognise BIP39 phrases and PEM text in `key import` automatically, as documented.
- CLI: give `etch` and `desktop` the same help as other command groups, with full command paths in usage headers.

### Fixed

- Report `owns?` correctly for a map of assets in `convex.asset` (v1 protocol, #621).
- Keep a caller's other holdings when `asset.multi-token` offers a token they do not hold (v1 protocol, #620).
- Transfer non-fungibles into boxes, and look up offers with the new `get-offer` (v1 protocol, #622).
- Fail `trust/trusted?` closed on defective monitors, and make trust control actions consistent (v1 protocol, #623).
- CLI: unlock every key made by `key generate --count N`.
- CLI: stop `convex status` hanging, by closing client connections properly.
- CLI: fix multi-address `account balance`, the `--peer-port` default, `local start --count 0` and `peer create` key passwords.
- CLI: give clearer error messages and proper exit codes, and report an error when prompting without a console.

## [0.8.7] - 2026-07-06

### Added

- Schedule protocol upgrades on-chain, with peers that cannot upgrade stepping aside until updated; the first, protocol v1, brings every known fix. ([Upgrades](convex-core/docs/UPGRADE.md))
- Make capture-safe macros with the new `gensym` function (protocol v1, #598, #602).
- Limit the size of lattice values accepted from untrusted peers (`:maxInboundValueSize`, #564).
- Drop lattice connections after sustained abuse, with per-connection statistics (`:maxConsecutiveRejects`, #566).
- Check a lattice node's public URLs at launch, so a misconfigured node fails fast (#567).
- Limit a peer's inbound client connections (`:max-connections`, default 1024, #482).
- MCP: list the on-chain addresses each signing key controls (#551).
- MCP: protect private deployments from DNS rebinding with an Origin allow-list (`mcp.allowedOrigins`, #552).

### Changed

- Charge juice for the true cost of big-integer multiplication (protocol v1, #603).
- Refuse to confirm blocks dated beyond a peer's clock plus a small allowance, so a future-dated block cannot push the consensus clock forward (#595).
- Shed stake ahead of an upgrade a peer cannot apply, so the upgraded peers still reach a supermajority (`:auto-manage`, #597).
- Make lattice writes deterministic under a supplied clock (#561).
- Fix a lattice node's merge context when it is configured, so it cannot change during a merge (#568).
- Write JSON lattice values structurally, without re-encoding unchanged values.
- CLI: show a one-line notice when connecting to the production Protonet peer by default, overridable with `--host` or `CONVEX_HOST` (#582).

### Fixed

- Apply every argument of `update` and `update-in` with five or more arguments (protocol v1; reported and first fixed by @jeroenvandijk, #533, #534).
- Allow unlimited minting with `convex.fungible` `add-mint` when `:max-supply` is unset (protocol v1, #528).
- Correct quasiquoted sets and maps, top-level `` `~false ``, `define` evaluation, `call` arity errors and `dotimes` counts in Convex Lisp (protocol v1, #598).
- Keep `for`, `for-loop` and `switch` from capturing user bindings named like their loop variables (protocol v1, #602).
- Correct around twenty core docstrings (protocol v1, #600).
- Compute `div`, `quot` and `rem` correctly for negative divisors and big integers (#599).
- Register shutdown hooks safely when several servers or nodes launch at once (#604).
- Update the peer named by `set-peer-data`'s key argument, with clearer peer-operation errors (#601).
- Send a lattice propagator's final writes when it closes.
- Count issued coin supply without subtracting the reward pool, matching `coin-supply` (#598).
- Partition lattice queues and topics correctly for every key hash (#561).
- Report the intended message for `recur` outside a function or loop (#115).
- CLI: always show the BIP39 mnemonic from `key generate`, even at `-v0` (#583).

### Security

- Handle untrusted lattice values defensively: reject wrong types, contain merge failures and validate KV entries (#561, #562).
- Validate every child of a container lattice, even when merging into an empty region (#561).
- Validate Postgres wire frames in Convex DB before allocating for them (contributed by @PrazwalR, #596).
- MCP: refuse seed-carrying tool calls over cleartext HTTP from non-loopback clients, unless `allowHttpSeeds` is set (#554).
- Log malformed peer frames at debug level, so a client cannot flood the operator log (#41).

## [0.8.6] - 2026-06-22

### Added

- Wait for a lattice propagator's next announcement without polling.
- CLI: see the peer ports `local start` uses in its output.

### Changed

- Keep DLFS deletions in their own index, so listing and navigating directories no longer scans them; existing drives load unchanged (#587).

### Fixed

- Keep every social post made in the same millisecond, instead of overwriting the earlier one.

### Security

- UCAN: verify JWT tokens against the issuer's DID key, not a header the sender controls (#586).
- UCAN: match capability resources on path-segment boundaries, and fail closed on an empty resource (#585).
- DLFS: fail closed on malformed nodes from untrusted peers (#590).

## [0.8.5] - 2026-06-11

### Added

- UCAN: re-check a token's validity period after it has been accepted, and parse a proof chain with its bearer token in one call.
- Publish a lattice node's root synchronously on sync, reporting any failure to the caller (#569).

### Changed

- Avoid most local-variable allocations while compiling and running CVM code (#559).

### Fixed

- Reject faulty or incomplete transactions on arrival, so block production no longer stalls (#531).
- Delete DLFS directories whose entries were all deleted, hide deletions from directory listings, and create a directory over a deleted name (#571).
- Never let an older lattice snapshot replace a newer saved root.
- Exit `convex peer start` with code 130 when interrupted, however early the interrupt arrives.

## [0.8.4] - 2026-04-18

### Added

- Pretty-print lattice data as JSON5 within a size budget.
- Write JSON5, including its extended escapes (#546).
- Authenticate with UCAN capabilities, JWTs and DIDs.
- Support the 2025-11-25 MCP specification, negotiating with older clients.
- Convex DB: speed up queries with index pushdown, merge joins and table statistics.
- Convex DB: create and drop tables through JDBC, and use SQL transactions.
- Convex DB: try replication with Etch stores in a demo.
- DLFS: use it from the CLI, with DID authentication and MCP tools, and sync WebDAV changes as they are made.
- Cache lattice data in a second-level cache.
- Browse Javadoc overviews and package descriptions for every published module.
- Install Convex with install scripts.

### Changed

- Write strictly standard JSON: non-finite numbers become `null` instead of `NaN` (#547).

### Fixed

- Convex DB: order JOIN query results correctly with ORDER BY (#540).
- Fix an MCP SSE race at connection setup.
- Fall back to IPv4 correctly on hosts without IPv6.

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
