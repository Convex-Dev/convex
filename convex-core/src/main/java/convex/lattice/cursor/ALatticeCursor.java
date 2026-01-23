package convex.lattice.cursor;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Abstract base class for lattice-aware cursors that support fork/sync patterns.
 *
 * <h2>Semantic Model</h2>
 * <p>A lattice cursor extends {@link AForkableCursor} with <b>lattice merge semantics</b>.
 * Instead of CAS-based merge that can fail, lattice cursors use algebraic merge
 * operations that always succeed by combining values.</p>
 *
 * <p>The key invariant is that lattice merge is:</p>
 * <ul>
 *   <li><b>Commutative:</b> merge(a, b) = merge(b, a)</li>
 *   <li><b>Associative:</b> merge(merge(a, b), c) = merge(a, merge(b, c))</li>
 *   <li><b>Idempotent:</b> merge(a, a) = a</li>
 * </ul>
 *
 * <p>These properties ensure that concurrent modifications always converge to the
 * same result regardless of ordering (CRDT semantics).</p>
 *
 * <h2>Capabilities</h2>
 * <ul>
 *   <li><b>{@link #fork()}</b> - Create independent working copy</li>
 *   <li><b>{@link #sync()}</b> - Sync changes back to parent (always succeeds)</li>
 *   <li><b>{@link #merge(ACell)}</b> - Merge external value using lattice merge</li>
 *   <li><b>{@link #descend(ACell...)}</b> - Navigate to sub-lattices</li>
 * </ul>
 *
 * <h2>Fork/Sync Pattern</h2>
 * <pre>{@code
 * // Root cursor with Set lattice
 * ALatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(SetLattice.create(), Sets.empty());
 *
 * // Fork for isolated work
 * ALatticeCursor<ASet<CVMLong>> tx1 = root.fork();
 * ALatticeCursor<ASet<CVMLong>> tx2 = root.fork();
 *
 * // Concurrent modifications
 * tx1.updateAndGet(s -> s.include(CVMLong.ONE));
 * tx2.updateAndGet(s -> s.include(CVMLong.create(2)));
 *
 * // Both syncs succeed - order doesn't matter
 * tx1.sync();  // root now contains {1}
 * tx2.sync();  // root now contains {1, 2}
 * }</pre>
 *
 * <h2>Comparison with CAS-Based Merge</h2>
 * <table border="1">
 *   <tr><th>Operation</th><th>CAS (AForkableCursor)</th><th>Lattice (ALatticeCursor)</th></tr>
 *   <tr><td>Concurrent conflict</td><td>One fails, retry needed</td><td>Both succeed, merged</td></tr>
 *   <tr><td>Return type</td><td>boolean (success/fail)</td><td>V (merged value)</td></tr>
 *   <tr><td>Best for</td><td>Exact value control</td><td>Commutative operations</td></tr>
 * </table>
 *
 * <h2>Lattice Hierarchy</h2>
 * <p>Lattice cursors support hierarchical navigation via {@link #descend}. Each level
 * has its own lattice defining merge semantics. For example:</p>
 * <pre>{@code
 * // MapLattice<Keyword, SetLattice<CVMLong>>
 * //   Key "foo" -> SetLattice for set of longs
 * //   Key "bar" -> SetLattice for set of longs
 *
 * ALatticeCursor<ASet<CVMLong>> fooSet = mapCursor.descend(Keywords.FOO);
 * fooSet.merge(Sets.of(1, 2, 3));  // Uses SetLattice merge at this level
 * }</pre>
 *
 * @param <V> Type of cursor values
 * @see AForkableCursor for CAS-based merge that can fail
 * @see ALattice for the lattice interface defining merge semantics
 */
public abstract class ALatticeCursor<V extends ACell> extends AForkableCursor<V> {

	protected final ALattice<V> lattice;
	protected volatile LatticeContext context;

	/**
	 * Creates a lattice cursor with the given lattice and context.
	 *
	 * <p>The lattice defines the merge semantics for this cursor. All merge
	 * operations ({@link #sync()}, {@link #merge(ACell)}) use the lattice's
	 * merge function to combine values.</p>
	 *
	 * <p>The context provides additional information for merge operations
	 * (e.g., timestamps, peer IDs). If null, defaults to {@link LatticeContext#EMPTY}.</p>
	 *
	 * @param lattice The lattice defining merge semantics (must not be null)
	 * @param context The merge context (null defaults to EMPTY)
	 * @param initialValue Initial value for the cursor (may be null)
	 */
	protected ALatticeCursor(ALattice<V> lattice, LatticeContext context, V initialValue) {
		super(initialValue);
		this.lattice = lattice;
		this.context = (context != null) ? context : LatticeContext.EMPTY;
	}

	/**
	 * Gets the lattice that defines merge semantics for this cursor.
	 *
	 * <p>The lattice provides the {@code merge} function used by {@link #sync()}
	 * and {@link #merge(ACell)}. Different lattice types provide different
	 * merge behaviors:</p>
	 * <ul>
	 *   <li>{@code SetLattice} - Set union</li>
	 *   <li>{@code MaxLattice} - Maximum value</li>
	 *   <li>{@code MapLattice} - Recursive merge of map values</li>
	 * </ul>
	 *
	 * @return The lattice for this cursor level (never null)
	 */
	public ALattice<V> getLattice() {
		return lattice;
	}

	/**
	 * Gets the merge context for this cursor.
	 *
	 * <p>The context provides additional information for merge operations,
	 * such as timestamps, peer identifiers, or priority information. The
	 * interpretation depends on the specific lattice implementation.</p>
	 *
	 * @return The current lattice context (never null)
	 */
	public LatticeContext getContext() {
		return context;
	}

	/**
	 * Sets a new context for this cursor and returns this cursor.
	 *
	 * <p>The context affects how subsequent merge operations behave. This
	 * method mutates this cursor in place (not a copy).</p>
	 *
	 * <p>Subclasses may override to provide immutable context handling.</p>
	 *
	 * @param context New context to use (must not be null)
	 * @return This cursor with updated context (for method chaining)
	 */
	public ALatticeCursor<V> withContext(LatticeContext context) {
		this.context = context;
		return this;
	}

	/**
	 * Creates an independent fork of this cursor for isolated modifications.
	 *
	 * <p>The forked cursor starts with a snapshot of the current value and can be
	 * modified independently. Changes don't affect the parent until {@link #sync()}
	 * is called on the fork.</p>
	 *
	 * <h3>Fork Behavior</h3>
	 * <ul>
	 *   <li>Forked cursor has its own local storage</li>
	 *   <li>Modifications to fork don't affect parent</li>
	 *   <li>Multiple forks can exist simultaneously</li>
	 *   <li>Forks can be nested (fork from a fork creates chain)</li>
	 *   <li>Fork inherits parent's lattice and context</li>
	 * </ul>
	 *
	 * <h3>Semantic Model</h3>
	 * <pre>{@code
	 * // Fork captures current value as fork point
	 * V parentValue = this.get();
	 * ALatticeCursor<V> fork = new ForkedLatticeCursor<>(this, parentValue);
	 *
	 * // fork.get() initially equals parentValue
	 * // fork modifications are isolated
	 * // fork.sync() merges back to parent
	 * }</pre>
	 *
	 * @return A new forked cursor with isolated local storage
	 * @see #sync() for merging fork changes back to parent
	 */
	public ALatticeCursor<V> fork() {
		return new ForkedLatticeCursor<>(this, lattice, get(), context);
	}

	/**
	 * Syncs local changes back to the parent cursor using lattice merge semantics.
	 *
	 * <p>This operation <b>always succeeds</b> (unlike CAS-based merge). If the parent
	 * has been modified since the fork, the changes are combined using the lattice's
	 * merge function. The result satisfies: {@code result = lattice.merge(parentValue, localValue)}.</p>
	 *
	 * <h3>Semantic Behavior</h3>
	 * <pre>{@code
	 * // For a forked cursor, sync does:
	 * V localValue = this.get();
	 * V parentValue = parent.get();
	 *
	 * if (parentValue == forkPoint) {
	 *     // No concurrent modification - fast path
	 *     parent.set(localValue);
	 *     return localValue;
	 * } else {
	 *     // Concurrent modification - merge
	 *     V merged = lattice.merge(parentValue, localValue);
	 *     parent.set(merged);
	 *     this.set(merged);        // Update local fork point
	 *     this.forkPoint = merged; // Reset baseline
	 *     return merged;
	 * }
	 * }</pre>
	 *
	 * <h3>Fork Point Update</h3>
	 * <p><b>Important:</b> After sync, this cursor's value and fork point are updated
	 * to the merged result. This enables incremental syncs where subsequent calls
	 * only sync new changes since the last sync.</p>
	 *
	 * <pre>{@code
	 * ALatticeCursor<V> fork = parent.fork();
	 * fork.set(A);
	 * fork.sync();  // Syncs A to parent
	 *
	 * fork.set(B);
	 * fork.sync();  // Only syncs difference between A and B
	 * }</pre>
	 *
	 * <h3>Root Cursor Behavior</h3>
	 * <p>For root cursors (no parent), sync simply returns the current value.</p>
	 *
	 * @return The synced (merged) value, which is also now this cursor's value
	 * @see #fork() for creating a forkable cursor
	 * @see #merge(ACell) for merging external values
	 */
	public V sync() {
		return get();
	}

	/**
	 * Merges an external value into this cursor using lattice merge semantics.
	 *
	 * <p>This operation <b>always succeeds</b> by combining the current value with
	 * the provided value using the lattice's merge function.</p>
	 *
	 * <h3>Semantic Equivalent</h3>
	 * <pre>{@code
	 * V current = this.get();
	 * V merged = lattice.merge(context, current, other);
	 * this.set(merged);
	 * return merged;
	 * }</pre>
	 *
	 * <h3>Use Cases</h3>
	 * <ul>
	 *   <li>Merging values received from the network (replication)</li>
	 *   <li>Combining values from other cursors</li>
	 *   <li>Applying external updates to local state</li>
	 * </ul>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * // SetLattice: merge performs set union
	 * cursor.set(Sets.of(1, 2));
	 * cursor.merge(Sets.of(2, 3));  // Result: {1, 2, 3}
	 *
	 * // MaxLattice: merge takes maximum
	 * cursor.set(CVMLong.create(5));
	 * cursor.merge(CVMLong.create(3));  // Result: 5
	 * cursor.merge(CVMLong.create(7));  // Result: 7
	 * }</pre>
	 *
	 * @param other Value to merge into this cursor (may be null, treated as identity)
	 * @return The merged value (now this cursor's value)
	 */
	public V merge(V other) {
		return updateAndGet(current -> lattice.merge(context, current, other));
	}

	/**
	 * Navigates to a sub-lattice at the specified path.
	 *
	 * <p>Each key navigates one level deeper into the lattice hierarchy. The
	 * returned cursor operates on the nested value with the sub-lattice's merge
	 * semantics.</p>
	 *
	 * <h3>Lattice Hierarchy</h3>
	 * <p>Composite lattices like {@code MapLattice} define sub-lattices for their
	 * child elements. Descending navigates to these sub-lattices:</p>
	 * <pre>{@code
	 * // MapLattice<Keyword, SetLattice<CVMLong>>
	 * ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> mapCursor = ...;
	 *
	 * // Descend to the SetLattice at key "foo"
	 * ALatticeCursor<ASet<CVMLong>> fooCursor = mapCursor.descend(Keywords.FOO);
	 *
	 * // Operations on fooCursor use SetLattice merge
	 * fooCursor.merge(Sets.of(1, 2));  // Set union
	 * }</pre>
	 *
	 * <h3>Semantic Behavior</h3>
	 * <ul>
	 *   <li>Descended cursor reads/writes the nested value at the path</li>
	 *   <li>Merge operations use the sub-lattice's merge function</li>
	 *   <li>Changes propagate to the parent cursor</li>
	 *   <li>Fork/sync operate at the descended level</li>
	 * </ul>
	 *
	 * <h3>Multi-Level Descent</h3>
	 * <p>Multiple keys descend through multiple levels recursively:</p>
	 * <pre>{@code
	 * // Equivalent to: cursor.descend(A).descend(B).descend(C)
	 * cursor.descend(A, B, C);
	 * }</pre>
	 *
	 * @param <T> Type of the descended cursor value
	 * @param keys Path keys to descend through (empty returns this cursor)
	 * @return Cursor at the descended path with appropriate sub-lattice
	 * @throws IllegalArgumentException if no sub-lattice exists at the path
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> ALatticeCursor<T> descend(ACell... keys) {
		if (keys.length == 0) return (ALatticeCursor<T>) this;

		ACell key = keys[0];
		ALattice<T> subLattice = lattice.path(key);
		if (subLattice == null) {
			throw new IllegalArgumentException("No sub-lattice at key: " + key);
		}

		ALatticeCursor<T> descended = new DescendedLatticeCursor<>(this, key, subLattice, context);
		if (keys.length == 1) {
			return descended;
		}

		// Recursively descend remaining keys
		ACell[] remainingKeys = new ACell[keys.length - 1];
		System.arraycopy(keys, 1, remainingKeys, 0, remainingKeys.length);
		return descended.descend(remainingKeys);
	}
}
