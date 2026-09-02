package convex.node;

import convex.core.data.ACell;

/**
 * Path-aware admission and projection policy for complete inbound lattice values.
 * Returning {@code null} rejects the value before it is persisted or merged.
 */
@FunctionalInterface
public interface LatticeIngressFilter {
	/**
	 * Applies inbound admission policy to one complete value.
	 *
	 * @param path canonical path within the authoritative lattice; do not mutate
	 * @param value complete inbound value
	 * @return value to merge, or {@code null} to reject it
	 */
	ACell filter(ACell[] path, ACell value);
}
