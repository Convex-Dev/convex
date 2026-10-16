package convex.lattice.cursor;

import java.util.Objects;
import java.util.function.BinaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * Reconciles a working snapshot with a destination that may have moved since the
 * snapshot was last published.
 *
 * <p>A {@link ForkedCursor} calls this when it syncs. {@code baseline} is the
 * destination value the working copy was last reconciled against, {@code own} the
 * working snapshot and {@code other} the destination's current value. The result is
 * what the fork publishes. The function must be pure: a sync may call it again with
 * a newer {@code other} if the destination moves during publication. It carries no
 * context of its own; whatever policy applies, such as a lattice, a clock or a
 * signer, is closed over by whoever constructed it.</p>
 *
 * @param <V> Value type
 */
@FunctionalInterface
public interface Reconciler<V extends ACell> {

	/**
	 * Reconciles one publication.
	 *
	 * @param baseline Destination value the working copy was last reconciled against
	 * @param own Working snapshot being published
	 * @param other Current destination value
	 * @return Value to publish
	 */
	V reconcile(V baseline,V own,V other);

	/**
	 * Wraps a two-way merge as a reconciler. An unchanged working copy adopts the
	 * destination; an unchanged destination, or equal values, keep the working
	 * snapshot; otherwise the merge decides, with the working snapshot as own.
	 *
	 * @param <V> Value type
	 * @param merge Merge of own and other, typically a lattice merge under a context
	 * @return Reconciler applying the merge only when both sides changed
	 */
	static <V extends ACell> Reconciler<V> of(BinaryOperator<V> merge) {
		Objects.requireNonNull(merge,"Merge function must not be null");
		return (baseline,own,other) -> {
			if (Utils.equals(own,baseline)) return other;
			if (Utils.equals(other,baseline) || Utils.equals(own,other)) return own;
			return merge.apply(own,other);
		};
	}
}
