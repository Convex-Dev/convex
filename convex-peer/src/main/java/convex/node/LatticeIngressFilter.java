package convex.node;

import convex.core.data.ACell;

/**
 * Path-aware admission and projection policy for complete inbound lattice values.
 * Returning {@code null} rejects the value before it is persisted or merged.
 */
@FunctionalInterface
public interface LatticeIngressFilter {
	ACell filter(ACell[] path, ACell value);
}
