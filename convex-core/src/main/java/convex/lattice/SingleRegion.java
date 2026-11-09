package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.lattice.cursor.AForkableCursor;

/**
 * A named region with a typed child, usable directly or as an application base.
 * Existing children retain their destination and policy; factories receive a
 * cursor bound to the named region. Only the child merges and publishes state.
 *
 * @param <C> Region component type
 */
public class SingleRegion<C extends ALatticeComponent<?>> extends AKeyedRegions {

	private final Keyword key;

	/** Wraps an existing component at a root or nested container. */
	public SingleRegion(AForkableCursor<Index<Keyword,ACell>> cursor,
			Keyword key,C component) {
		super(cursor);
		this.key=key;
		region(key,component);
	}

	/**
	 * Constructs a new component at a root or nested container. The factory runs
	 * during this constructor, so a subclass that overrides {@link #cursor()} cannot
	 * use it: the subclass's view is not yet initialised.
	 */
	protected <V extends ACell> SingleRegion(AForkableCursor<Index<Keyword,ACell>> cursor,
			Keyword key,RegionFactory<V,C> factory) {
		super(cursor);
		this.key=key;
		createRegion(key,factory);
	}

	/** Binds to new working storage with a source's region, for a {@link #rebind}. */
	protected SingleRegion(AForkableCursor<Index<Keyword,ACell>> cursor,SingleRegion<C> source) {
		super(cursor,source);
		this.key=source.key;
	}

	/** Creates a single region and its child at a root or nested container. */
	public static <V extends ACell,C extends ALatticeComponent<?>> SingleRegion<C> create(
			AForkableCursor<Index<Keyword,ACell>> cursor,
			Keyword key,RegionFactory<V,C> factory) {
		return new SingleRegion<C>(cursor,key,factory);
	}

	@Override
	public SingleRegion<C> rebind(AForkableCursor<Index<Keyword,ACell>> cursor) {
		return new SingleRegion<C>(cursor,this);
	}

	/** Returns the exact registered component, including its pending edits. */
	public final C component() {
		return registered(key);
	}
}
