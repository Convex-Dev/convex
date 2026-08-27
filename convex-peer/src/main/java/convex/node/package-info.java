/**
 * Schema-independent lattice replication over the Convex binary protocol.
 *
 * <h2>Ownership boundaries</h2>
 *
 * <ul>
 *   <li>{@link convex.node.NodeServer} owns the supplied lattice root, physical
 *       listener, ordered inbound pipeline and connection-to-view policy. It
 *       never interprets application paths or records.</li>
 *   <li>{@link convex.node.LatticePropagator} owns one publication view, its
 *       store, delta generation and root announcements.</li>
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
 * <p>The transport challenge key configured on {@code NodeServer} is independent
 * of the application signing and owner-verification state in
 * {@code LatticeContext}. An application wrapper may intentionally use the same
 * key for both roles, but the base transport never infers that binding.</p>
 *
 * <h2>Inbound value pipeline</h2>
 *
 * <ol>
 *   <li>The transport performs a bounded queue offer.</li>
 *   <li>The single {@code NodeServer} dispatcher selects the connection's
 *       immutable propagator capability.</li>
 *   <li>Untrusted input is decoded without a store. Trusted partial input may
 *       acquire missing cells into that propagator's store.</li>
 *   <li>The complete value passes ingress policy and lattice validation.</li>
 *   <li>The authoritative root merge and primary publication complete before
 *       secondary propagation is triggered.</li>
 *   <li>An optional {@link convex.node.InboundLatticeListener} may observe the
 *       accepted path and value without changing the merge outcome.</li>
 * </ol>
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
