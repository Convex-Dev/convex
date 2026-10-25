package convex.node;

import java.io.IOException;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.store.AStore;
import convex.lattice.ALattice;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;

/** Assembles a host with one live application over a supplied whole-value lattice. */
public final class RelayHost {
	private RelayHost() {}

	/** Opens a relay host with no signing authority or networking configuration. */
	public static <V extends ACell> LatticeHost<V> open(ALattice<V> lattice,AStore store) throws IOException {
		return open(lattice,store,LatticeContext.EMPTY);
	}

	/** Opens a relay with explicit merge policy; the host owns retention and durability. */
	public static <V extends ACell> LatticeHost<V> open(ALattice<V> lattice,AStore store,
			LatticeContext context) throws IOException {
		Objects.requireNonNull(lattice,"Lattice must not be null");
		var host=LatticeHost.open(lattice.zero(),store);
		var application=LatticeApplication.live(lattice,host);
		application.setContext(context);
		host.addApplication(application);
		return host;
	}

}
