package convex.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.AUpdateCursor;

/**
 * Abstract base class for lattice functions.
 *
 * <p>Lattices represent merge functions for lattice values and support:</p>
 * <ul>
 *   <li>a {@code zero} initial value</li>
 *   <li>ability to validate foreign values (pre-merge checks)</li>
 *   <li>ability to obtain child lattices</li>
 * </ul>
 *
 * <p><b>Tiebreaker convention:</b> when a merge has no clear winner
 * (e.g. equal timestamps in LWW, conflicting leaf values in JSON),
 * implementations should prefer the own (local) value. This reduces
 * risk from malicious or spurious incoming values, retains existing
 * structure beneficial for caching, and avoids unnecessary state churn.</p>
 *
 * <p><b>Untrusted data — {@code merge} is the enforcement point.</b> A lattice may be
 * fed values by anonymous remote peers (see {@code NodeServer}), so {@link #merge} must
 * be safe against an arbitrary {@code otherValue}: it is responsible for rejecting or
 * ignoring invalid, malformed or stale incoming data rather than incorporating it.
 * Validation performed <em>inside</em> the merge is the primary defence and is usually
 * the optimal place for it — a merge that throws on bad data aborts the merge
 * <em>atomically</em> (the cursor retains the prior value; nothing partial is
 * committed), so there is no risk in validating inline. Typical merge-time defences are
 * per-entry signature checks, LWW timestamp monotonicity, and structural validation of
 * child values. A lattice type intended for exposure to untrusted peers MUST enforce
 * these within {@code merge}.</p>
 *
 * <p><b>Robustness is mandatory; content-trust is not merge's job (#561).</b> The audit in
 * #561 makes merge <em>robustness</em> a hard requirement: for <em>any</em> {@code otherValue}
 * — wrong type, malformed, oversized, replayed — merge must never corrupt committed state,
 * exhaust resources (unbounded allocation or recursion), or wedge a value into an
 * un-updatable state. Throwing is an acceptable outcome (it aborts atomically). What merge
 * must <em>not</em> do is second-guess the <em>content</em> of a validly-authenticated value:
 * a lattice is a faithful recorder and convergence of signed claims, so an owner's chosen
 * (even implausible) LWW timestamp is <em>recorded as their claim</em>, not rejected by the
 * merge. Two separate boundaries handle trust: <em>authorization</em> — who may write a given
 * entry — is enforced by the composition layer ({@code OwnerLattice}/{@code SignedLattice});
 * and <em>adoption</em> — whether to merge a given remote's data into your view at all — is a
 * node-level trust decision (see {@code NodeServer}/the propagator). Both are outside
 * {@code merge}. A consequence of proper owner-keying is that a peer can only ever wedge its
 * <em>own</em> slot (self-denial); cross-owner effects require a deliberately shared register
 * or an explicit trust choice, which is the operator's to make, not the merge's to police.
 * The {@link LatticeContext} timestamp is the writer's <em>stamp-on-write</em> clock — the
 * "when" companion to the signing "who" — used when a node stamps its <em>own</em> new values;
 * it is not a filter applied to the timestamps inside others' incoming values.</p>
 *
 * <p>{@link #checkForeign} is by contrast an <em>optional</em> fast-fail pre-check:
 * callers may skip it (the network merge path does), and {@code merge} must never rely
 * on it having run.</p>
 *
 * @param <V> Type of values in this lattice
 */
public abstract class ALattice<V extends ACell> {
	
	/**
	 * The lattice merge function: combines an externally received value into the own
	 * value, returning the merged result.
	 *
	 * <p>This is the enforcement point for untrusted data (see class docs). The
	 * implementation must reject or ignore an invalid, stale or malformed
	 * {@code otherValue} rather than incorporate it, and must not assume
	 * {@link #checkForeign} has been called. Throwing on bad data is safe: the whole
	 * merge is aborted atomically, so the prior value is retained and nothing partial is
	 * committed.</p>
	 *
	 * @param ownValue Own lattice value
	 * @param otherValue Externally received (possibly untrusted) lattice value
	 * @return Merged lattice value
	 */
	public abstract V merge(V ownValue, V otherValue);

	/**
	 * Context-aware merge function. Default implementation delegates to simple merge.
	 * Override this method if merge logic requires contextual information (timestamp, signing key, etc.)
	 *
	 * @param context Context for merge operation
	 * @param ownValue Own lattice value
	 * @param otherValue Externally received lattice value
	 * @return Merged lattice root cell
	 */
	public V merge(LatticeContext context, V ownValue, V otherValue) {
		return merge(ownValue, otherValue);
	}
	
	/**
	 * Obtains the "zero" value for the lattice. This may be null, but a non-null zero value is preferred.
	 * 
	 * @return Zero value of the lattice. 
	 */
	public abstract V zero();

	/**
	 * Optional fast-fail pre-check for a foreign value. This is NOT the primary defence:
	 * {@link #merge} is the enforcement point and must independently reject invalid data,
	 * so a caller may skip this entirely (the network merge path does). A merge
	 * implementation may call {@code checkForeign} if a cheap up-front reject is
	 * worthwhile, but validating inline during the merge is usually optimal (a throwing
	 * merge aborts atomically, so there is no risk in deferring the check to merge time).
	 *
	 * <p>Subtypes should check validity as far as any child lattices.</p>
	 *
	 * @param value Value received from foreign source
	 * @return true if the foreign value is an acceptable lattice value
	 */
	public abstract boolean checkForeign(V value);

	/**
	 * Returns true if this is a structural (navigable) region whose container
	 * types are determined by navigation keys rather than by the lattice.
	 *
	 * <p>When true, the cursor write path ({@code LatticeOps.assocIn}) builds
	 * missing intermediates from the key shape (see
	 * {@code LatticeOps.containerForKey}) instead of using {@link #zero()}.
	 * Default is {@code false}.</p>
	 *
	 * @return true if this region builds containers from navigation keys
	 */
	public boolean isStructural() {
		return false;
	}

	/**
	 * Whether navigating {@code key} crosses a write-interception boundary at this
	 * lattice level (e.g. a signing boundary, or a stamp-on-write LWW leaf).
	 *
	 * <p>This is the cheap, allocation-free gate that {@code ALatticeCursor.path}
	 * checks on <em>every</em> key. Only when it returns true is the (allocating)
	 * {@link #createPathCursor} invoked. Default false, so ordinary deep navigation
	 * pays only a boolean call per key and allocates nothing.</p>
	 *
	 * @param key key about to be navigated
	 * @return true if a boundary cursor must be inserted here
	 */
	public boolean isWriteBoundary(ACell key) {
		return false;
	}

	/**
	 * Builds the boundary cursor wrapping {@code base}. Called by
	 * {@code ALatticeCursor.path} <em>only</em> when {@link #isWriteBoundary} is
	 * true for {@code key}, after the accumulated path segment has been flushed —
	 * so {@code base} is already the correct cursor to wrap (no re-wrapping).
	 *
	 * @param base accumulated cursor at this lattice's level
	 * @param key key being navigated
	 * @param context Local context override for the new boundary cursor, or null
	 *                for live inheritance from {@code base}
	 * @return boundary cursor to insert
	 */
	public AUpdateCursor<?, ?> createPathCursor(ALatticeCursor<?> base, ACell key, LatticeContext context) {
		return null;
	}

	/**
	 * At a boundary ({@link #isWriteBoundary} true), whether {@code key} is
	 * consumed crossing it.
	 *
	 * <p>{@code true} (default) for a virtual boundary key (e.g. {@code :value}
	 * crossing {@code SignedData<V> → V}); {@code false} for a transparent,
	 * same-value boundary (e.g. stamp-on-write) where {@code key} keeps navigating
	 * below the wrapper.</p>
	 *
	 * @param key key triggering the boundary
	 * @return true if the key is consumed crossing the boundary
	 */
	public boolean consumesPathKey(ACell key) {
		return true;
	}
	
	/**
	 * Resolves an external key (e.g. JSON string) to the canonical CVM key
	 * used by this lattice level. Returns null if the key cannot be resolved
	 * to a valid child key.
	 *
	 * <p>Used by JSON-based APIs to translate path elements before calling
	 * standard lattice operations like {@code descend()}. Normal CVM code
	 * that already uses canonical key types does not need this.
	 *
	 * <p>Default implementation returns the key unchanged.
	 *
	 * @param key External key to resolve
	 * @return Canonical CVM key, or null if the key is not valid
	 */
	public ACell resolveKey(ACell key) {
		return key;
	}

	/**
	 * Converts a canonical CVM key to its JSON-compatible representation.
	 * Keywords become their name strings; blobs become hex strings;
	 * other types (AString, AInteger) pass through unchanged.
	 *
	 * @param key CVM key to convert
	 * @return JSON-compatible key
	 */
	public static ACell toJSONKey(ACell key) {
		if (key instanceof Keyword k) return k.getName();
		if (key instanceof ABlob b) return Strings.create(b.toHexString());
		return key;
	}

	/**
	 * Resolves a JSON path (array of external keys) to canonical CVM keys by
	 * walking the lattice hierarchy. Each key is resolved via {@link #resolveKey}
	 * at the appropriate lattice level.
	 *
	 * @param jsonPath External keys to resolve
	 * @return Array of canonical CVM keys, or null if resolution fails at any level
	 */
	public ACell[] resolvePath(ACell... jsonPath) {
		int n=jsonPath.length;
		// Copy-on-change: start with the input array and clone only when a key first
		// resolves to a different canonical key. If resolution is the identity (keys
		// already canonical), the input is returned unchanged — no reconstruction.
		ACell[] out=jsonPath;
		ALattice<?> current=this;
		for (int i=0; i<n; i++) {
			ACell resolved=current.resolveKey(jsonPath[i]);
			if (resolved==null) return null;
			if (resolved!=jsonPath[i]) {
				if (out==jsonPath) out=jsonPath.clone();
				out[i]=resolved;
			}
			if (i<n-1) {
				current=current.path(resolved);
				if (current==null) return null;
			}
		}
		return out;
	}

	/**
	 * Resolves a sequence of external keys to canonical CVM keys — the
	 * {@link ASequence} form of {@link #resolvePath(ACell...)}, to which it delegates.
	 *
	 * @param jsonPath Sequence of external keys to resolve
	 * @return Array of canonical CVM keys, or null if resolution fails at any level
	 */
	public ACell[] resolvePath(ASequence<ACell> jsonPath) {
		return resolvePath(jsonPath.toCellArray());
	}

	/**
	 * Get the sub-lattice at the specified path
	 * @param <T>
	 * @param path Path of ACell keys
	 * @return Sub-lattice instance, or null if invalid path
	 */
	public final  <T extends ACell> ALattice<T> path(ACell... path) {
		return path(path,0);
	}
	
	/**
	 * Get this lattice (with an empty path)
	 * @return This lattice cast to specified type
	 */
	public ALattice<V> path() {
		return this;
	}
	
	/**
	 * Get a child lattice
	 * @return The child lattice (may be null)
	 */
	public abstract <T extends ACell> ALattice<T> path(ACell childKey);
	
	@SuppressWarnings("unchecked")
	protected <T extends ACell> ALattice<T> path(ACell[] path, int pos) {
		if (path.length<=pos) return (ALattice<T>) this;
		ALattice<?> child=path(path[pos]);
		if (child==null) return null;
		return child.path(path,pos+1);
	}
	
	/**
	 * Get this lattice (with an empty path)
	 * @return This lattice cast to specified type
	 */
	public <T extends ACell> ALattice<T> path(Object childKey) {
		return path(RT.cvm(childKey));
	}

	
	/**
	 * Get the sub-lattice at the specified path
	 * @param <T>
	 * @param path Path of keys
	 * @return Sub-lattice instance, or null if invalid path
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> ALattice<T> path(Object... path) {
		int d=path.length;
		if (d==0) return (ALattice<T>) path();
		if (d==1) return (ALattice<T>) path((ACell)RT.cvm(path[0]));

		ACell[] cellPath=Cells.toCellArray(path);
		return path(cellPath,0);
	}

}
