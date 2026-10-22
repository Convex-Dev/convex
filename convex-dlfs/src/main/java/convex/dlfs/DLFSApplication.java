package convex.dlfs;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.store.AStore;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.Regions;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.fs.DLFSLocation;

/**
 * Root application serving a multi-owner DLFS region, normally at {@code :fs}.
 *
 * <p>The registered region defines delivery and publication policy; no separate
 * root lattice is required. The default region is a live view of the supplied
 * cursor. Supply an explicit region to share it with another application or to
 * stage edits in a {@link DLFSRegion#fork() fork}. In either case {@link #dlfs()}
 * and {@link #drives} use that same component, and sync follows its boundary.</p>
 *
 * <p>The host's store is passed to drives for incremental blob checkpoints.
 * The application owns neither the host nor the store lifecycle. Host sync
 * persists and announces the root; host flush makes it durable.</p>
 */
public class DLFSApplication extends Regions {

	private final AStore store;
	private final DLFSRegion dlfs;

	/** Creates a live DLFS application at {@code :fs}, or an explicit path of region keys. */
	public DLFSApplication(LatticeHost<Index<Keyword,ACell>> host,Keyword... path) {
		this(host.cursor(),host.store(),host.getContext(),path);
	}

	/**
	 * Binds a live view to a root cursor, including a transaction draft.
	 * Missing containing indexes are created only by a changed write.
	 */
	public DLFSApplication(AForkableCursor<Index<Keyword,ACell>> cursor,AStore store,
			LatticeContext context,Keyword... path) {
		super(cursor,context);
		this.store=store;
		Keyword[] keys=path.length==0?new Keyword[]{Keywords.FS}:path.clone();
		Regions parent=this;
		for (int i=0;i<keys.length-1;i++) {
			parent=parent.add(keys[i],new Regions(
				parent.cursor().withDefault(Index.none()).path(keys[i]),context));
		}
		dlfs=parent.add(keys[keys.length-1],configured(DLFSRegion.connect(
			parent.cursor().withDefault(Index.none()).path(keys[keys.length-1]),store,
			DLFSLocation.at(cursor,keys))));
	}

	/**
	 * Registers an existing region at a root key, retaining its cursor, policy and
	 * publication boundary. The caller binds the region to its intended destination.
	 */
	public DLFSApplication(LatticeHost<Index<Keyword,ACell>> host,Keyword key,DLFSRegion files) {
		super(host);
		store=host.store();
		dlfs=add(key,files);
	}

	/** Returns the store available for new components' blob checkpoints. */
	protected AStore store() {
		return store;
	}

	/** Returns the exact registered region, for both domain edits and incoming merges. */
	public final DLFSRegion dlfs() {
		return dlfs;
	}

	/** Returns one owner's named drives in this application's working region. */
	public DLFSDrives drives(AccountKey owner) {
		return dlfs.drives(owner);
	}
}
