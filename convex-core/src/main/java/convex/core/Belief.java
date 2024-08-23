package convex.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.IRefFunction;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.MapEntry;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.lang.RecordFormat;

/**
 * Class representing a Peer's view of the overall network consensus state.
 * 
 * Belief is immutable, and is designed to be independent of any particular Peer
 * so that it can be efficiently merged towards consensus.
 * 
 * Belief can be merged with other Beliefs from the perspective of a Peer. This
 * property is fundamental to the Convex consensus algorithm.
 * 
 * "Sorry to be a wet blanket. Writing a description for this thing for general
 * audiences is bloody hard. There's nothing to relate it to." – Satoshi
 * Nakamoto
 */
public class Belief extends ARecord {

	private static final RecordFormat BELIEF_FORMAT = RecordFormat.of(Keywords.ORDERS);
	
	// Constants
	private static final Index<AccountKey, SignedData<Order>> EMPTY_ORDERS = Index.none();


	/**
	 * The latest view of signed Orders held by other Peers
	 */
	private final Index<AccountKey,SignedData<Order>> orders;

	// private final long timeStamp;

	Belief(Index<AccountKey,SignedData<Order>> orders) {
		super(BELIEF_FORMAT.count());
		this.orders = orders;
	}

	@Override
	public ACell get(Keyword k) {
		if (Keywords.ORDERS.equals(k)) return orders;
		return null;
	}

	@Override
	public Belief updateRefs(IRefFunction func) {
		Index<AccountKey, SignedData<Order>> newOrders = orders.updateRefs(func);
		if (this.orders == newOrders) {
			return this;
		}
		return new Belief(newOrders);
	}

	/**
	 * Gets an empty Belief
	 * @return Empty Belief
	 */
	public static Belief initial() {
		return create(EMPTY_ORDERS);
	}
	
	/**
	 * Create a Belief with a single order signed by the given key pair, using initial timestamp.
	 * @param kp Peer Key pair with which to sign the order.
	 * @param order Order of blocks that the Peer is proposing
	 * @return new Belief representing the isolated belief of a single Peer.
	 */
	public static Belief create(AKeyPair kp, Order order) {
		Index<AccountKey, SignedData<Order>> orders=Index.of(kp.getAccountKey(),kp.signData(order));
		return create(orders);
	}


	private static Belief create(Index<AccountKey, SignedData<Order>> orders, long timestamp) {
		return new Belief(orders);
	}
	
	@SuppressWarnings("unchecked")
	public static Belief create(SignedData<Order>... orders)  {
		Index<AccountKey, SignedData<Order>> newOrders=EMPTY_ORDERS;
		for (SignedData<Order> so:orders) {
			newOrders=newOrders.assoc(so.getAccountKey(), so);
		}
		return new Belief(newOrders);
	}
	

	public static Belief create(HashMap<AccountKey, SignedData<Order>> orderMap) {
		Index<AccountKey, SignedData<Order>> orders=Index.create(orderMap);
		return new Belief(orders);
	}

	private static Belief create(Index<AccountKey, SignedData<Order>> orders) {
		return create(orders, Constants.INITIAL_TIMESTAMP);
	}

	/**
	 * Create a Belief with a single empty order. USeful for Peer startup.
	 * 
	 * @param kp Keypair for Peer
	 * @return New Belief
	 */
	public static Belief createSingleOrder(AKeyPair kp) {
		AccountKey address = kp.getAccountKey();
		SignedData<Order> order = kp.signData(Order.create());
		return create(Index.of(address, order));
	}




	/**
	 * Updates this Belief with a new set of Chains for each peer address
	 * 
	 * @param newOrders New map of peer keys to Orders
	 * @return The updated belief, or the same Belief if no change.
	 */
	public Belief withOrders(Index<AccountKey, SignedData<Order>> newOrders) {
		if (newOrders == orders) return this;
		return Belief.create(newOrders);
	}
	


	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=getTag();
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 1+orders.estimatedEncodingSize()+12;
	}
	
	public static Belief read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		
		Index<AccountKey, SignedData<Order>> orders = Format.read(b,epos);
		if (orders == null) throw new BadFormatException("Null orders in Belief");
		epos+=Format.getEncodingLength(orders);
		
		Belief result= new Belief(orders);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}
	
	/**
	 * Encodes this Belief
	 * @param bs Array to write to
	 */
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=Format.write(bs,pos, orders);
		return pos;
	}


	@Override
	public byte getTag() {
		return Tag.BELIEF;
	}

	/**
	 * Gets the current Order for a given Address within this Belief.
	 * 
	 * @param address Address of peer
	 * @return The chain for the peer within this Belief, or null if noy found.
	 */
	public Order getOrder(AccountKey address) {
		SignedData<Order> sc = orders.get(address);
		if (sc == null) return null;
		return sc.getValue();
	}

	/**
	 * Get the map of orders for this Belief
	 * @return Orders map
	 */
	public Index<AccountKey, SignedData<Order>> getOrders() {
		return orders;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (orders == null) throw new InvalidDataException("Null orders", this);
		orders.validateCell();
	}
	
	@Override 
	public boolean equals(ACell a) {
		if (!(a instanceof Belief)) return false;
		Belief as=(Belief)a;
		return equals(as);
	}
	
	/**
	 * Tests if this Belief is equal to another
	 * @param a Belief to compare with
	 * @return true if equal, false otherwise
	 */
	public boolean equals(Belief a) {
		if (this == a) return true; // important optimisation for e.g. hashmap equality
		if (a == null) return false;
		Hash h=this.cachedHash();
		if (h!=null) {
			Hash ha=a.cachedHash();
			if (ha!=null) return Cells.equals(h, ha);
		}

		if (!orders.equals(a.orders)) return false;
		return true;
	}

	@Override
	public int getRefCount() {
		return orders.getRefCount();
	}
	
	@Override 
	public <R extends ACell> Ref<R> getRef(int i) {
		return orders.getRef(i);
	}

	/**
	 * Gets a new HashMap containing all Orders
	 * @return HashMap of current orders
	 */
	public HashMap<AccountKey, SignedData<Order>> getOrdersHashMap() {
		int n=orders.size();
		HashMap<AccountKey, SignedData<Order>> hm=new HashMap<>(n);
		for (int i=0; i<n; i++) {
			MapEntry<AccountKey, SignedData<Order>> entry=orders.entryAt(i);
			AccountKey key=RT.ensureAccountKey(entry.getKey());
			hm.put(key, entry.getValue());
		}
		return hm;
	}

	@Override
	public RecordFormat getFormat() {
		return BELIEF_FORMAT;
	}

	/**
	 * Extract a collection of Orders from a Cell, suitable for Belief merge
	 * @param payload Cell to extra orders from
	 * @return Collection of signed orders
	 */
	@SuppressWarnings("unchecked")
	public static Collection<SignedData<Order>> extractOrders(ACell payload) {
		ArrayList<SignedData<Order>> result=new ArrayList<>();
		if (payload instanceof SignedData) {
			SignedData<?> sd=(SignedData<?>)payload;
			if (sd.getValue() instanceof Order) {
				result.add((SignedData<Order>) sd);
			}
		} else if (payload instanceof Belief) {
			Belief b=(Belief)payload;
			Index<AccountKey, SignedData<Order>> porders = b.getOrders();
			int n=porders.size();
			for (int i=0; i<n; i++) {
				result.add(porders.entryAt(i).getValue());
			}
		}
		return result;
	}

	/**
	 * Propose a new Block at the end of the current Order
	 * @param kp Keypair with which to sign Order
	 * @param signedBlock Signed Block of transactions
	 * @return Updated Belief with new Order
	 */
	public Belief proposeBlock(AKeyPair kp, SignedData<Block> signedBlock) {
		AccountKey peerKey=kp.getAccountKey();
		Index<AccountKey, SignedData<Order>> orders = getOrders();

		SignedData<Order> mySO=orders.get(peerKey);
		Order myOrder;
		if (mySO==null) {
			myOrder=Order.create();
		} else {
			myOrder=mySO.getValue();
		}

		// Create new order with signed Block
		Order newOrder = myOrder.append(signedBlock);
		SignedData<Order> newSignedOrder = kp.signData(newOrder);
		
		Index<AccountKey, SignedData<Order>> newOrders = orders.assoc(peerKey, newSignedOrder);
		Belief newBelief=this.withOrders(newOrders);
		return newBelief;
	}





}
