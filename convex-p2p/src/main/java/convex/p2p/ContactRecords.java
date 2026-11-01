package convex.p2p;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
 *  :contacts {&lt;node key&gt; {:last-contact t, :failures n, :last-failure t}}
 *  :blocked  {&lt;node key or address&gt; {}}}
 * </pre>
 *
 * <p>The same record holds this node's {@link Blocklist} at {@code :blocked}, edited
 * through {@link #cursor()}, so both are synced and signed together.</p>
 *
 * <p>A node that answered and has not failed since keeps that standing whether or not
 * this node is connected to it: time alone is no evidence against it. At most
 * {@link #MAX_NODES} nodes have a record, since nodes connecting in can create them. A
 * new node beyond that replaces one with failures if there is one, otherwise the one
 * this node has been out of touch with longest. A live route is reported as contact
 * again while it stays healthy, so it is never the oldest.</p>
 *
 * <p>Each event edits a private copy, which signs nothing and leaves the host alone.
 * The application syncs the copy into the node's {@code :local} slot when its policy
 * says, signing the slot once, and the host persists it with the rest of its root.
 * After a restart the copy starts from the record the host retained. The record is
 * small and synced whole, so it is not persisted separately.</p>
 */
public final class ContactRecords extends ALocalComponent {

	/** Most nodes with a record. Each is about 100 bytes, so the slot stays near 100 KB. */
	static final int MAX_NODES=1024;

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
		record(node,record -> record.assoc(LAST_CONTACT,now).assoc(FAILURES,CVMLong.ZERO));
	}

	/** Records a failed attempt to reach a node. */
	public void failed(AccountKey node) {
		CVMLong now=clock.get();
		record(node,record -> record.assoc(FAILURES,CVMLong.create(failures(record)+1)).assoc(LAST_FAILURE,now));
	}

	/** Applies an event to a node's record, making room for a new node by dropping the least useful record. */
	private void record(AccountKey node,UnaryOperator<AHashMap<Keyword,ACell>> event) {
		contacts.cursor().update(all -> {
			AHashMap<AccountKey,AHashMap<Keyword,ACell>> records=(all==null)?Maps.empty():all;
			AHashMap<Keyword,ACell> record=records.get(node);
			if (record==null && records.count()>=MAX_NODES) records=records.dissoc(leastUseful(records));
			return records.assoc(node,event.apply((record==null)?Maps.empty():record));
		});
	}

	/**
	 * The node to forget first: one with failures, which there is reason to doubt,
	 * before one without; then the one whose latest contact or failure is oldest.
	 */
	private static AccountKey leastUseful(AHashMap<AccountKey,AHashMap<Keyword,ACell>> records) {
		AccountKey chosen=null;
		boolean chosenFailing=false;
		long chosenTime=Long.MAX_VALUE;
		for (Map.Entry<AccountKey,AHashMap<Keyword,ACell>> entry:records.entrySet()) {
			AHashMap<Keyword,ACell> record=entry.getValue();
			boolean failing=failing(record);
			long time=Math.max(time(record,LAST_CONTACT),time(record,LAST_FAILURE));
			if (chosen==null || (failing && !chosenFailing) || (failing==chosenFailing && time<chosenTime)) {
				chosen=entry.getKey();
				chosenFailing=failing;
				chosenTime=time;
			}
		}
		return chosen;
	}

	private static long time(AHashMap<Keyword,ACell> record,Keyword field) {
		return (record.get(field) instanceof CVMLong time)?time.longValue():Long.MIN_VALUE;
	}

	/** Returns the contact record for a node, or null if this node has none. */
	public AHashMap<Keyword,ACell> get(AccountKey node) {
		return contacts.get(node);
	}

	/** Every contact record this node keeps, by node key. */
	AHashMap<AccountKey,AHashMap<Keyword,ACell>> all() {
		AHashMap<AccountKey,AHashMap<Keyword,ACell>> all=contacts.get();
		return (all==null)?Maps.empty():all;
	}

	/** Whether a contact record shows a node that answered and has not failed since. */
	static boolean answered(AHashMap<Keyword,ACell> record) {
		return record!=null && record.get(LAST_CONTACT)!=null && failures(record)==0;
	}

	/** Whether a contact record shows failures since the last contact, if any. */
	static boolean failing(AHashMap<Keyword,ACell> record) {
		return record!=null && failures(record)>0;
	}

	private static long failures(AHashMap<Keyword,ACell> record) {
		return (record.get(FAILURES) instanceof CVMLong count)?count.longValue():0;
	}
}
