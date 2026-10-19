package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.lattice.cursor.AForkableCursor;

/**
 * A named region with a typed child, usable directly or as an application base.
 * Existing children retain their destination and context; factories receive a
 * cursor bound to the named region. Only the child merges and publishes state.
 *
 * @param <C> Region component type
 */
public class SingleRegion<C extends ALatticeComponent<?>> extends AKeyedRegions {

	private final C component;

	/** Wraps an existing, correctly bound component, which may be shared. */
	public SingleRegion(LatticeHost<Index<Keyword,ACell>> host,Keyword key,C component) {
		this(host.cursor(),host.getContext(),key,component);
	}

	/** Constructs a new component at the named region of a host. */
	protected <V extends ACell> SingleRegion(LatticeHost<Index<Keyword,ACell>> host,
			Keyword key,RegionFactory<V,C> factory) {
		this(host.cursor(),host.getContext(),key,factory);
	}

	/** Wraps an existing component at a root or nested container. */
	public SingleRegion(AForkableCursor<Index<Keyword,ACell>> cursor,LatticeContext context,
			Keyword key,C component) {
		super(cursor,context);
		this.component=region(key,component);
	}

	/** Constructs a new component at a root or nested container. */
	protected <V extends ACell> SingleRegion(AForkableCursor<Index<Keyword,ACell>> cursor,
			LatticeContext context,Keyword key,RegionFactory<V,C> factory) {
		super(cursor,context);
		component=createRegion(key,factory);
	}

	/** Creates a single region and its child at the host root. */
	public static <V extends ACell,C extends ALatticeComponent<?>> SingleRegion<C> create(
			LatticeHost<Index<Keyword,ACell>> host,Keyword key,RegionFactory<V,C> factory) {
		return new SingleRegion<C>(host,key,factory);
	}

	/** Creates a single region and its child at a root or nested container. */
	public static <V extends ACell,C extends ALatticeComponent<?>> SingleRegion<C> create(
			AForkableCursor<Index<Keyword,ACell>> cursor,LatticeContext context,
			Keyword key,RegionFactory<V,C> factory) {
		return new SingleRegion<C>(cursor,context,key,factory);
	}

	/** Returns the exact registered component, including its pending edits. */
	public final C component() {
		return component;
	}
}
