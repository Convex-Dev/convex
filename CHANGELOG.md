# Changelog

Notable changes to Convex core modules will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.8.16-SNAPSHOT] - Unreleased

### Added

- P2P nodes can advertise Point of Presence node keys and explicit relay
  willingness in signed NodeInfo. Opt-in PoP nodes forward bounded end-to-end
  signed messages over authenticated routes; outbound-only nodes can exchange
  public values or ECIES-encrypted private values through a shared PoP.
- P2P lattice nodes can bootstrap from one authenticated node key and TCP address.
  The connecting node pushes only its own signed NodeInfo entry, allowing the remote
  node to challenge it on the same full-duplex socket and explicitly upgrade that
  inbound connection into an authenticated outbound propagation route, then pulls
  `:p2p`, `:id` and currently desired social-owner paths so late joiners receive their
  follow-filtered application state. Outbound-only nodes may publish an empty transport vector and
  synchronise through that original connection without a listener or reverse dial.
  `NodeConfig.localNetwork()` provides loopback NodeInfo publication with OS-assigned
  ports for isolated development networks.
- Social users support owner-scoped forks, allowing several feed and follow actions to
  be published as one signed user value.
- Social owners and follow targets are canonical DIDs, with `did:key`, pinned numeric
  `did:convex`, and authenticated `did:web`/`alsoKnownAs` key authorisation. The
  top-level `:following` value uses LWP over a DID-keyed `:follows` map with whole-record
  LWW edits and a cached last-validated signer.
- The supported `convex.peer` module API now exports `convex.node`. Host settings
  use `NodeConfig`, while each independently composed propagation group uses
  `LatticePropagatorConfig` and exposes lifecycle and contained-failure status for
  application supervision.

### Changed

- `Format.encodeMultiCell` no longer knows about message limits and never silently
  drops cells: a new overload takes a maximum size and throws `IllegalArgumentException`
  when it would be exceeded, with zero or less meaning no maximum. `Message.getMessageData`
  applies the maximum message length at the transport boundary, and connections refuse
  to send, with a logged warning, a message that cannot fit one frame (#726).
- Lattice query replies are sized by the requesting connection's trust and decided from
  cached memory sizes, so a query never makes a node encode more than it will send. An
  untrusted connection receives a value in full only when it fits one message of the
  size the node accepts from it, and otherwise the root alone to acquire the rest; a
  trusted connection also receives bounded `DATA` chunks ahead of the root, as a
  broadcast would.
- Cells now compute their encoding length arithmetically from a per-type header
  length plus their child refs, never by rendering an encoding, and `createEncoding`
  allocates the exact size. `estimatedEncodingSize` has been removed from
  `IWriteable`, `ACell` and `Ref`; callers needing a size should use
  `getEncodingLength`.
- `Hash` and `AccountKey` are now canonical cells: they encode exactly as 32-byte
  blobs, so `getCanonical()` returns the same instance rather than a plain `Blob`.
- Convex clients preserve reserved transaction sequences after timeouts and CVM
  errors, preventing a shared client from reusing a sequence whose outcome is
  unknown or which the transaction consumed.
- CAD036 lattice paths now use one canonical vector representation. Lattice nodes
  use `[:LV path value]` for optimistic pushes and `[:LV id path value]` for
  confirmed pushes, and reject scalar, missing or ambiguous paths.
- `NodeServer` now owns only the authoritative lattice merge, node-store root,
  attached-group lifecycle and isolated update notification. Calling applications
  must construct and configure each `LatticePropagator`; nodes no longer create
  an implicit primary group. Applications also own `LatticeListener` transport
  composition, permitting shared connection routing or independent transports;
  `NodeServer` no longer exposes a port, address or inbound selector. Propagators
  own their connection sets, trust, protocol queues, acquisition, filters and
  serving stores, and a failed group cannot break node publication or another
  group.

### Fixed

- `LatticePropagator.pullPath` merged a partial reply as if complete when its store
  held the root without every branch, since `MemoryStore` returns a missing ref rather
  than throwing. Completeness is now checked explicitly and a partial value is acquired
  from the peer before it is merged; the peer client is given the propagator store before
  the query so a partial reply decodes lazily instead of being rejected.
- Multi-cell messages containing an `Index` node deeper than 64 hex digits with
  non-embedded children could not be decoded by a receiver that did not already
  hold those children, because the embedding check dereferenced them. A lattice
  query for such a value failed on the requesting node, so a joining node could
  not bootstrap from a peer holding it (#723). `Index` now computes its encoding
  length from its refs without loading any child.
- DLFS directory entries with names longer than the embedded string limit (138 to
  254 bytes) were listed but could not be opened, statted, moved or deleted once
  their directory index had been reloaded from an Etch store, for example after a
  restart or when a cached node was evicted from memory. Single-entry `Index` nodes
  decoded with a non-embedded key now derive their depth from the key instead of
  assuming the maximum, so lookups agree with iteration.
- String interning is thread-safe. `StringStore` kept the process-wide intern
  index in plain `HashMap`s written without synchronisation, so concurrent
  `Strings.intern` calls could corrupt it and later lookups then recursed in
  `HashMap$TreeNode.find` until `StackOverflowError`. Entries are now published
  under a lock onto concurrent maps; `LoadMonitor`'s per-thread map is synchronised.
- Social cached follow signers recognise canonical 32-byte Blob keys after CAD3 decode,
  while active follow identities remain DIDs.

### Security

- Point-message relays verify the invariant source signature, destination,
  lifetime and path before forwarding, use authenticated routes only, and bound
  message size, hops, fan-out, recent replay state and inbound rate.
- Lattice peer trust now requires a valid two-sided Ed25519 challenge/response binding
  a random nonce, both node-key audiences and a fixed protocol context. An assigned
  inbound socket remains an untrusted public route until that proof and an admitted
  owner-signed NodeInfo both succeed.
- Unverified connections can submit only complete, size-bounded lattice values and
  cannot stage unsolicited `DATA` or trigger missing-cell acquisition. P2P social
  ingress validates the complete signed owner slot and DID signer before persistence,
  retains only local users, pins and direct follows, and bounds explicit plus discovered
  desired peers with `maxDesiredPeers`.

## [0.8.15] - 2026-08-24

### Added

- Minimal ECIES-style Blob encryption for an `AccountKey`, using RFC 9180 HPKE with X25519, HKDF-SHA256 and AES-128-GCM. Ciphertext Blobs use the compact `enc || ct` layout with 48 bytes of overhead; recipients decrypt with their `AKeyPair` or Ed25519 seed, with a reusable decryptor for efficient batch reads.
- REST transaction concurrency is configurable via `transact-limit` (server-wide) and `transact-limit-client` (per client, 0 to disable), so a well-resourced peer can serve more. Clients are identified by direct socket address and forwarded client-address headers are never consulted, since a limit keyed on a caller-supplied header is trivially bypassed. Behind a reverse proxy, set `transact-limit-client` to 0 and let the proxy apply per-client policy, which it can do against the true client address; the server-wide cap still bounds total load.
- Lattice and CPoS propagation split oversized deltas into bounded `:DATA` batches followed by a root announcement. Per-message and total eager-materialisation limits are independently configurable with `maxDeltaMessageSize` / `maxDeltaBroadcastSize` and `:max-belief-delta-message-size` / `:max-belief-delta-broadcast-size`; complete inbound values retain their separate size policy.

### Changed

- The REST `/transact` concurrency cap rises from 2 server-wide to 10000 server-wide with 100 per client. A permit is held for the whole consensus wait, so the previous cap meant two slow transactions could stall every other caller. Request handling runs on virtual threads, so a waiting request is cheap and the higher ceiling is affordable; the per-client bound keeps one caller from taking the whole allowance.

### Fixed

- Priority own-Order broadcasts now carry their novel cells inline when they fit the priority message limit, and novelty from a superseded priority message is folded into the next full Belief broadcast. Announcing the Order previously consumed announce-novelty without delivering it, so no later delta carried the new Block data and receivers fell back to status polling for every confirmation round — observed as ~1.5s median transaction confirmation on an otherwise idle local 5-peer network, restored to ~35ms by this fix.
- A peer's confirmed consensus points no longer retreat during Belief merge. Recomputing levels from the current voting set could lower consensus and finality whenever a lagging copy of another peer's Order supplied the stake that tipped the 2/3 threshold; the peer then signed the receded Order and silently truncated already-executed state, so a client could transiently observe state missing a transaction whose result had already been reported. CAD051 requires that confirmed points never retreat; consensus and finality are now ratchets (proposal may still recede when switching proposals). The bounded delta propagation in this release made lagging Order copies routine, surfacing the latent defect and causing repeated truncate-and-replay churn under load.
- Convex DB components now retain their containing application-policy hierarchy, so nested table persistence reaches a hosted `RootComponent` without moving cursors and database/schema forks keep the same persistence policy while synchronising only to their original cursor (#698).
- Delta fan-out is isolated per peer so a full receiver queue cannot block healthy peers, queued bytes are bounded independently of message count, and a failed delta encoding no longer prevents the announced lattice root or publication future from advancing. CPoS prioritises a coalesced own-Order root ahead of best-effort full-Belief replication; root sync and pulls recover dropped data.
- The GUI passphrase strength estimate awards the documented 2 bits per character category used, rather than 1. The multiply sat inside `Integer.bitCount`, where doubling a bit mask cannot change the count.

### Security

- PKCS12 key stores now protect each key entry with a freshly generated random salt for password-based key derivation. Every entry was previously written with a fixed all-zero salt, so a single precomputed PBKDF2 table would have applied to every key store Convex has ever written. Existing key stores still open, since the salt is stored in the file itself; an entry is re-salted when it is next written.
- Password-based key derivation for key store entries now runs 220,000 PBKDF2-HMAC-SHA512 iterations, up from 100,000, following current OWASP guidance. The count is recorded in each key store, so existing entries keep their own count until they are next written. Unlocking a key costs roughly 140ms rather than 65ms.
- PEM key export now encrypts with PBES2, using AES-256-CBC and PBKDF2-HMAC-SHA512 with a random salt, replacing PKCS#5 v1.5 with RC2 at 65,536 iterations. The scheme is recorded in the PEM, so keys exported earlier still import. A stock OpenSSL 3 reads the new files directly, whereas RC2 requires its legacy provider.
- PEM key import parses the PKCS#8 structure through `AKeyPair.createFromPKCS8` instead of taking the trailing 32 bytes as the seed. RFC 8410 permits an optional public key field, and an encoding carrying one was previously imported as a silently different key pair; encodings for other algorithms are now rejected rather than reinterpreted.
- The peer signing service derives its `:keys` lookup hash with HKDF keyed by the peer-held encryption secret, replacing an unsalted SHA-256 of identity, public key and passphrase. The old hash let anyone holding a replica of the store brute-force the passphrase offline. Credential inputs are also canonically encoded, so field boundaries cannot be shifted. Existing entries are rewritten to the new scheme on first successful load, when the passphrase is available.
- Locking a wallet entry now blocks signing as well as key access. `HotWalletEntry.sign` previously ignored the lock entirely.

## [0.8.14] - 2026-08-19

### Changed

- The default client transaction timeout is raised from 8 to 20 seconds, so ordinary scheduling delay on a loaded machine no longer surfaces as a spurious `:TIMEOUT` result. Connection establishment and internal queue backpressure keep the previous 8 second bound under the new `Config.DEFAULT_INTERNAL_TIMEOUT`.
- `LatticeContext` is now an extensible application policy: timestamps and signing can be resolved dynamically after one root installation, and delegated overrides replace individual capabilities. Owned data is authored through `signAs`, one authorisation rule shared with merge-time owner verification, so a wallet or key-store backed policy can write for any identity it holds and a write that no peer would accept fails locally instead.
- Singleton `Ref` constants moved off `Ref` to break class initialisation cycles: `Ref.NULL_VALUE` is now `RefDirect.NULL_VALUE`, and `Ref.TRUE_VALUE` / `Ref.FALSE_VALUE` are now `Refs.TRUE_VALUE` / `Refs.FALSE_VALUE`. `ErrorMessages.INVALID_NUMERIC` moved to `ErrorValue.INVALID_NUMERIC`, and `ARecord.DEFAULT_VALUE` is now internal to the `Record` type descriptor.

### Fixed

- Class initialisation cycles in core removed, each of which could deadlock two threads initialising the two ends at once: `Ref` built its own `RefDirect` subclass and read `CVMBool`, and `ARecord` constructed a `Block`.
- Merging signed data for an owner this node cannot sign for retains the local value, rather than aborting the whole merge or attaching a non-owner signature. Merges converge the owners a node holds keys for and leave the rest to their owners.
- Signing and timestamping a lattice write happen once per logical write, not once per compare-and-set attempt, so contention no longer multiplies calls into a wallet, key store or remote signer.
- DLFS cursor-level embedders can supply their host store to `DLFS.connect` or `DLFS.open`, so NIO `Files.copy` and output-stream writes persist blob branches incrementally instead of retaining files larger than the JVM heap (#702).
- DLFS mutations use the driving cursor's `LatticeContext` timestamp exactly; filesystem-level timestamp setters have been removed, and equal-timestamp merge-back preserves the current local (`own`) value, including live names competing with stale tombstones (#703).

## [0.8.13] - 2026-08-17

### Added

- Lattice applications compose local or NodeServer-hosted component trees through `ALatticeApplication`, with multi-owner DLFS applications, per-owner drives and temporary forks, and the same host-neutral stack in P2P.
- Standalone `convex.dlfs.Main` boots a complete NodeServer-hosted DLFS without the Convex CLI, configured in JSON5 for storage, identity, bootstrap peers, HTTP limits and exposure policy.
- CVM: `char?` type predicate for Character values (v1 protocol, #92).
- DLFS exposes its canonical WebDAV mount path as `DLFSWebDAV.MOUNT_PATH` for embedders (#699).

### Changed

- DLFS streamed writes persist blob data incrementally, keeping large uploads off heap.
- DLFS moves are structural operations, supporting replacement without materialising file data.
- DLFS WebDAV/MCP transport takes explicit drive routing, and accepts authentication policy independently of lattice application ownership.
- Lattice peer connections configured with a node key stay isolated from propagation and store access until challenge-response verifies the remote identity.
- Lattice root publication is configured by host infrastructure, while `sync()` publication and store `flush()` durability remain explicit, separate boundaries.

### Fixed

- DLFS WebDAV `COPY` structurally shares same-drive blobs, preventing out-of-memory failures on large files.
- DLFS drive deletion and rename retain lattice tombstones, so stale replicas cannot resurrect deleted names (#647).
- DLFS moves use the operation timestamp, so moved entries correctly supersede older destination tombstones after replication (#688).
- DLFS nodes reject updates more than 30 seconds ahead of the host clock, configurable through `maxFutureTimestampSkew`.
- Peer servers close only the stores they create, leaving caller-supplied stores open, and retire temporary Etch files at shutdown.
- `FileUtils.getPath` and its file-loading callers resolve relative paths against the process working directory (#701).

## [0.8.12] - 2026-08-13

### Added

- Ed25519 JWT signing accepts explicit verification-method key IDs, while self-contained verification recognises bare multikey, `did:key` and multikey-fragment DID URL forms.
- Lattice propagators can own filtered outbound views, preserving store-local refs and complete pending inbound merges while excluding filtered cells from announced roots across startup, replication, restore and shutdown.
- Lattice nodes can pull and merge a selected cursor path without transferring unrelated sibling regions.
- Etch gains offline strict validation of index structure, canonical CAD3 records, content hashes and root-tree completeness, shared with copy-out repair.
- Etch CLI maintenance commands open encrypted v3 stores through protected key files, standard input or header-hinted keystores, and support explicit cipher, index and rekeying policy for fresh migration destinations.

### Changed

- Lattice value messages support efficient post-merge acknowledgements through an optional request ID, while normal gossip remains fire-and-forget.
- Peer and lattice-node launch configuration now resolves encrypted Etch stores from their header key hint by default, records the configured identity as the hint for new encrypted stores, and supports an explicit runtime key resolver for external secret providers.
- The experimental `Symmetric` helper now uses authenticated AES-GCM and explicitly rejects ciphertext from its former unauthenticated AES-CBC implementation. Its ciphertext representation is not a stable storage or interchange format.

### Fixed

- Lattice root sync advertises only values already available from its serving store, while inbound acquisition now resolves lazy missing references correctly and rejects malformed data responses.
- Lattice propagation no longer drops a rapid follow-up delta behind its former 50 ms broadcast throttle; background updates remain coalesced before processing.
- Consecutive CVX reader discard markers (`#_`) each discard one following form, matching cumulative Clojure reader behaviour (#264).
- DLFS distinguishes missing paths from corrupt stored nodes, preserves access to readable branches, supports safe entry replacement or deletion for recovery, and rejects malformed replicated subtrees.

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
