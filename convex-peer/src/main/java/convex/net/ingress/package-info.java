/**
 * Message ingress: decoding and routing of received messages on workers of their
 * own, off the threads that read network connections, with fair service between
 * connections, bounded memory, and backpressure from each message's destination
 * to the connection it came on.
 *
 * <p>The design is in {@code docs/proposals/peer-ingress-routing.md}.</p>
 */
package convex.net.ingress;
