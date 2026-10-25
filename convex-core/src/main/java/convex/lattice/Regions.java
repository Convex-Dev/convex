package convex.lattice;

import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.lattice.cursor.AForkableCursor;

/**
 * A configurable collection of named region components. An empty collection is
 * inert. Add children before the first merge, path delivery or sync; subsequent
 * registration is rejected. Publication follows registration order.
 */
public class Regions extends AKeyedRegions {

	/** Creates an empty collection at the host root. */
	public Regions(LatticeHost<Index<Keyword,ACell>> host) {
		super(host);
	}

	/** Creates an empty collection at a root or nested container. */
	public Regions(AForkableCursor<Index<Keyword,ACell>> cursor) {
		super(cursor);
	}

	/** Registers existing components in the supplied map's iteration order. */
	public Regions(LatticeHost<Index<Keyword,ACell>> host,
			Map<Keyword,? extends ALatticeComponent<?>> components) {
		this(host.cursor(),components);
	}

	/** Registers existing components without rebinding them or changing their policy. */
	public Regions(AForkableCursor<Index<Keyword,ACell>> cursor,
			Map<Keyword,? extends ALatticeComponent<?>> components) {
		super(cursor);
		components.forEach(this::region);
	}

	/** Adds an existing, correctly bound component, which may be shared. */
	public <C extends ALatticeComponent<?>> C add(Keyword key,C component) {
		return region(key,component);
	}

	/** Constructs and adds a component bound to the named region. */
	public <V extends ACell,C extends ALatticeComponent<?>> C create(
			Keyword key,RegionFactory<V,C> factory) {
		return createRegion(key,factory);
	}
}
