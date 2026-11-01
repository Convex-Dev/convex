package convex.p2p;

import java.util.function.Function;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALocalComponent;
import convex.lattice.MapComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * What this node has learned about other nodes, kept as local state at
 * {@code [:local <node key> :p2p]}: for each node key, the last authenticated
 * contact, the failures since it and the last failure. It is never shared with other
 * nodes, and only an authenticated connection counts as contact.
 *
 * <pre>
 * {:timestamp t
 *  :contacts {&lt;node key&gt; {:last-contact t, :failures n, :last-failure t}}}
 * </pre>
 *
 * <p>Each event edits a private copy, which signs nothing and leaves the host alone.
 * The application syncs the copy into the node's {@code :local} slot when its policy
 * says, signing the slot once, and the host persists it with the rest of its root.
 * After a restart the copy starts from the record the host retained. The record is
 * small and synced whole, so it is not persisted separately.</p>
 */
public final class ContactRecords extends ALocalComponent {

	static final Keyword CONTACTS=Keyword.intern("contacts");
	static final Keyword LAST_CONTACT=Keyword.intern("last-contact");
	static final Keyword FAILURES=Keyword.intern("failures");
	static final Keyword LAST_FAILURE=Keyword.intern("last-failure");

	private final Supplier<CVMLong> clock;
	private final MapComponent<AccountKey,AHashMap<Keyword,ACell>> contacts;

	ContactRecords(AForkableCursor<Index<Keyword,ACell>> root,AccountKey owner,
			Function<AHashMap<Keyword,ACell>,SignedData<AHashMap<Keyword,ACell>>> signer,Supplier<CVMLong> clock) {
		super(root,P2PLattice.KEY_P2P,owner,signer,clock,null);
		this.clock=clock;
		this.contacts=new MapComponent<>(new MapComponent<Keyword,ACell>(cursor())
			.<AHashMap<AccountKey,AHashMap<Keyword,ACell>>>path(CONTACTS));
	}

	/** Records authenticated contact with a node, clearing its failures. */
	public void contacted(AccountKey node) {
		CVMLong now=clock.get();
		contact(node).update(record -> record.assoc(LAST_CONTACT,now).assoc(FAILURES,CVMLong.ZERO));
	}

	/** Records a failed attempt to reach a node. */
	public void failed(AccountKey node) {
		CVMLong now=clock.get();
		contact(node).update(record -> record.assoc(FAILURES,CVMLong.create(failures(record)+1)).assoc(LAST_FAILURE,now));
	}

	/** Returns the contact record for a node, or null if this node has none. */
	public AHashMap<Keyword,ACell> get(AccountKey node) {
		return contacts.get(node);
	}

	private AForkableCursor<AHashMap<Keyword,ACell>> contact(AccountKey node) {
		return contacts.<AHashMap<Keyword,ACell>>path(node).withDefault(Maps.empty());
	}

	private static long failures(AHashMap<Keyword,ACell> record) {
		return (record.get(FAILURES) instanceof CVMLong count)?count.longValue():0;
	}
}
