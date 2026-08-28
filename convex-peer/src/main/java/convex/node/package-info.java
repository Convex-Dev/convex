/**
 * Schema-independent lattice replication over the Convex binary protocol.
 *
 * <h2>Ownership boundaries</h2>
 *
 * <ul>
 *   <li>{@link convex.node.NodeServer} owns the authoritative lattice root,
 *       node-store publication and durability, physical listener lifecycle and
 *       isolated update notifications. It never interprets application paths
 *       or records.</li>
 *   <li>{@link convex.node.LatticePropagator} owns one application-configured
 *       policy group: its routes, protocol endpoint, filters, serving store,
 *       delta generation and root announcements.</li>
 *   <li>{@link convex.node.NodeConfig} configures the host listener and
 *       authoritative persistence, while
 *       {@link convex.node.LatticePropagatorConfig} independently configures
 *       one policy group's routes, queues and publication limits.</li>
 *   <li>{@link convex.node.LatticeConnectionManager} owns bounded connection
 *       intent, outbound dialing, remote-key admission and outbound routes for
 *       exactly one propagator. Discovery adapters supply transport metadata.</li>
 *   <li>{@link convex.node.LatticeInboundVerifier} proves an application-admitted
 *       remote key on a physically inbound connection before that connection may
 *       be upgraded into an outbound route.</li>
 *   <li>{@link convex.lattice.ALattice} and {@link convex.lattice.LatticeContext}
 *       own value validation, merge semantics and application-owner
 *       authorisation. Transport authentication never replaces merge
 *       authorisation.</li>
 * </ul>
 *
 * <h2>Connection states</h2>
 *
 * <p>A physical socket and an outbound route are deliberately different
 * capabilities. An accepted inbound socket starts untrusted and may be assigned
 * by operator policy to one propagator view. A manager-opened outbound socket is
 * held in verification limbo until the expected node key answers a challenge.
 * A physically inbound socket becomes an outbound route only after
 * {@code LatticeInboundVerifier} proves its key and
 * {@code LatticeConnectionManager.upgradeInboundConnection} records the
 * explicit upgrade.</p>
 *
 * <p>The transport challenge key configured on a propagator is independent of
 * the application signing and owner-verification state in
 * {@code LatticeContext}. An application wrapper may intentionally use the same
 * key for both roles, but the base transport never infers that binding.</p>
 *
 * <h2>Inbound value pipeline</h2>
 *
 * <ol>
 *   <li>The shared listener assigns the connection to one application-selected
 *       propagation group.</li>
 *   <li>That group's endpoint performs a bounded queue offer.</li>
 *   <li>Untrusted input is decoded without a store. Trusted partial input may
 *       acquire missing cells into that propagator's store.</li>
 *   <li>The complete value passes ingress policy and lattice validation.</li>
 *   <li>The authoritative node merge and root publication complete before each
 *       propagation group is notified independently.</li>
 *   <li>An optional {@link convex.node.InboundLatticeListener} may observe the
 *       accepted path and value without changing the merge outcome.</li>
 * </ol>
 *
 * <p>Contained group failures are observable through
 * {@link convex.node.LatticePropagator#getStatus()} and
 * {@link convex.node.LatticePropagator#nextFailure()}. They do not become node
 * failures: the application decides whether a degraded group should be restarted,
 * replaced or left running.</p>
 *
 * <p>The calling application constructs and configures every propagation group
 * before attaching it. {@code NodeServer} creates no default group and a node
 * with no groups remains a valid local store-backed lattice host.</p>
 *
 * <p>Application modules own discovery schemas, selective replication and
 * transient application protocols. They compose those policies through lattice,
 * filter, listener and message-handler interfaces; this package does not contain
 * special cases for {@code :p2p}, {@code :social} or any other region.</p>
 *
 * <p>This package implements the lattice-node model in CAD036. Message framing
 * and transport trust follow CAD015; signed application ownership is enforced
 * independently at the merge boundary as specified by CAD038.</p>
 */
package convex.node;
