---
name: local-network
description: Run isolated local Convex networks and in-process lattice node networks for development. Use when testing against local peers, reproducing peer or P2P replication issues, exercising NodeServer or P2PNode sync, or when no remote network is configured.
---

# Run a Local Convex Network

A local network is the default way to exercise changes in this repository. It
needs no credentials, no remote host, and can be thrown away and recreated
freely. Prefer it over a remote network for anything that is not specifically
about remote behaviour.

Requires a built `convex.jar` — see the `build-convex` skill.

## Start a Temporary Network

```bash
java -jar convex.jar local start
```

Starts a throwaway test network. State is not preserved between runs, which is
what you want for testing.

Useful options:

| Option | Effect |
|--------|--------|
| `--count N` | Number of peers to launch |
| `--ports ...` | Specific peer ports (default: assigned automatically) |
| `--api-port N` | Port for the REST API |
| `--norest` | Do not start the REST server |
| `--no-tray` | No system tray icon |
| `--protocol-version N` | Pin the protocol version |

## Start with the Peer Manager GUI

```bash
java -jar convex.jar local gui
```

Launches the same local network under the peer manager GUI — useful for
watching consensus and inspecting peer state visually.

## Talking to It

Once running, point the client commands at the local peer:

```bash
java -jar convex.jar client query --host localhost --port <PORT> '(balance #12)'
java -jar convex.jar client status --host localhost --port <PORT>
```

## Notes for Tests

Do **not** start a network from a JUnit test by shelling out to the CLI. Tests
construct peers or lattice nodes in-process.

For consensus/CVM peer tests, follow the fixtures in `convex-peer`. For lattice
replication, use `NodeServer`; for the bundled P2P and social regions, use
`P2PNode`. See `convex-peer/src/test/java/convex/node/LatticeNetworkTest.java`
and `convex-p2p/src/test/java/convex/p2p/P2PSocialSyncTest.java`.

For a small lattice network test:

1. Give each node its own store and key pair.
2. Use `NodeConfig.localNetwork()`. It binds port `0`, then publishes the actual
   OS-assigned loopback port in the node's signed `NodeInfo`.
3. Set an inbound propagator policy before launch. `P2PNode.serveAllInbound()` is
   suitable for a deliberately public test node.
4. Tell one node about the other with
   `nodeA.connect(nodeBKey, nodeB.getNodeServer().getHostAddress())`. The future
   completes after B proves its node key, A's own signed `[:p2p :nodes]` record
   has been merged by B, and A has pulled and merged B's current announced root.
   B discovers A from the path-scoped identity update and establishes the reverse
   authenticated connection automatically. This full-root bootstrap makes a late
   join deterministic for the regions configured on A; it does not yet implement
   region subscriptions or follow-filtered ingestion.
   Use `nodeB.whenConnected(nodeAKey)` when a test must wait for that reverse
   admission before publishing in both directions.
   For three nodes, a useful discovery topology is to tell both leaves only
   about one rendezvous node. Wait until its signed registry has reached both
   leaves, then use `whenConnected` to prove the leaves discovered each other
   without another configured endpoint.
   To test late joining, converge the initial nodes before creating the newcomer,
   tell only the newcomer about the rendezvous node, and require `connect()` itself
   to deliver the existing state without a manual rendezvous-node `sync()`.
5. After an application write, call the root application's `sync()` to publish
   the complete root.
   When batching several edits for one signed social owner, fork the
   `SocialUser`, apply its feed and follow actions, sync that fork once, then
   sync the application root. A `Social` fork is outside the owner boundary and
   therefore still signs each user edit inside the unpublished fork.
6. Automatic gossip is fire-and-forget. To verify it without sleeping, capture
   `nextAnnounce()` before publishing and re-arm it until the expected application
   state is present. The announce signals completed acquisition, merge and root
   publication. Use an explicit `receivingServer.pull(connection).get(timeout)`
   only when the test is specifically about pull synchronisation. A ping only
   establishes transport ordering and does not prove acquisition is complete.
7. Close nodes before closing their stores.

Treat a node key as its P2P/transport signer, not automatically as an
application user's identity or signing key. For an `OwnerLattice` keyed by an
indirect owner such as a DID, install a fail-closed owner verifier in the
`LatticeContext`; without one, indirect owners use the compatibility-lenient
fallback. Give identity-sensitive tests separate node keys and application
owner/signing keys. The social cursor API currently accepts
`AccountKey` owners, so use a separate application key and state that limitation
explicitly. Once its DID migration lands, cover `did:key`, `did:convex` and
`did:web` with pinned local state or deterministic resolver fixtures—never
depend on public web resolution in a unit test. `serveAllInbound()` controls
network access and does not replace owner authorisation.

Remember that an `AccountKey` is a typed JVM view over a canonical 32-byte
Blob. A key stored as ordinary CAD3 application data can therefore decode as a
Blob. Domain readers should parse compatible Blob values with
`AccountKey.parse` or `AccountKey.create`; do not use `instanceof AccountKey`
as a wire-format validity check.

The rules in `AGENTS.md` apply: never bind fixed ports and never sleep. Wait on
futures, latches or another API whose contract represents the required state.

Stop the network when finished — it holds ports and a temporary store.
