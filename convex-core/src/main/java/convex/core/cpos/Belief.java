package convex.core.cpos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.ARecordGeneric;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

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
public class Belief extends ARecordGeneric {

	private static final RecordFormat BELIEF_FORMAT = RecordFormat.of(Keywords.ORDERS);
	
	private static final long IX_ORDERS=BELIEF_FORMAT.indexFor(Keywords.ORDERS);
	
	// Constants
	private static final Index<AccountKey, SignedData<Order>> EMPTY_ORDERS = Index.none();


	/**
	 * The latest view of signed Orders held by other Peers
	 * 
	 * Initially null, field will be loaded on demand
	 */
	private Index<AccountKey,SignedData<Order>> orders;

	// private final long timeStamp;

	private Belief(Index<AccountKey,SignedData<Order>> orders) {
		super(CVMTag.BELIEF,BELIEF_FORMAT,Vectors.create(orders));
	}

	private Belief(AVector<ACell> newValues) {
		super(CVMTag.BELIEF,BELIEF_FORMAT,newValues);
	}

	@Override
	public ACell get(Keyword k) {
		if (Keywords.ORDERS.equals(k)) return getOrders();
		return null;
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
	
	/**
	 * Creates a Belief from source data , usually an Index of Peer key -> Signed Orders
	 * @param source
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static Belief fromOrders(ACell source) {
		source=RT.cvm(source);
		if (source instanceof Index) {
			return create((Index<AccountKey, SignedData<Order>>)source);
		}
		return null;
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

	public static Belief create(Index<AccountKey, SignedData<Order>> orders) {
		return new Belief(orders);
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
		if (newOrders == getOrders()) return this;
		return Belief.create(newOrders);
	}
	
	public static Belief read(Blob b, int pos) throws BadFormatException {
		AVector<ACell> values=Vectors.read(b, pos);
		int epos=pos+values.getEncodingLength();

		if (values.count()!=1) throw new BadFormatException("Wrong number of values for Belief");

		Belief result=new Belief(values);
		result.attachEncoding(b.slice(pos,epos));
		return result;
	}
	
	/**
	 * Gets the current Order for a given Address within this Belief.
	 * 
	 * @param address Address of peer
	 * @return The chain for the peer within this Belief, or null if noy found.
	 */
	public Order getOrder(AccountKey address) {
		SignedData<Order> sc = getOrders().get(address);
		if (sc == null) return null;
		return sc.getValue();
	}

	/**
	 * Get the map of orders for this Belief
	 * @return Orders map
	 */
	@SuppressWarnings("unchecked")
	public Index<AccountKey, SignedData<Order>> getOrders() {
		if (orders==null) orders=(Index<AccountKey, SignedData<Order>>) values.get(0);
		return orders;
	}
	
	@Override 
	public boolean equals(ACell a) {
		if (a instanceof Belief) return equals((Belief)a);
		return super.equals(a);
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

		if (!getOrders().equals(a.getOrders())) return false;
		return true;
	}

	/**
	 * Gets a new HashMap containing all Orders
	 * @return HashMap of current orders
	 */
	public HashMap<AccountKey, SignedData<Order>> getOrdersHashMap() {
		return getOrders().toHashMap();
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
	
	@Override
	public void validateStructure() throws InvalidDataException {
		super.validateStructure();
		if (!(values.get(IX_ORDERS) instanceof Index)) {
			throw new InvalidDataException("Orders should be an Index",this);
		}
	}

	/**
	 * Propose a new Block at the end of the current Order
	 * @param kp Keypair with which to sign Order
	 * @param signedBlock Signed Block of transactions
	 * @return Updated Belief with new Order
	 */
	@SuppressWarnings("unchecked")
	public Belief proposeBlock(AKeyPair kp, SignedData<Block>... signedBlocks) {
		AccountKey peerKey=kp.getAccountKey();
		Index<AccountKey, SignedData<Order>> orders = getOrders();

		SignedData<Order> mySO=orders.get(peerKey);
		Order myOrder;
		if (mySO==null) {
			throw new IllegalStateException("Trying to propose block without a current ordering for peer "+peerKey);
		} else {
			myOrder=mySO.getValue();
		}

		// Create new order with signed Block
		Order newOrder = myOrder;
		int n=signedBlocks.length;
		for (int i=0; i<n; i++) {
			newOrder=newOrder.append(signedBlocks[i]);
		}
		SignedData<Order> newSignedOrder = kp.signData(newOrder);
		
		Index<AccountKey, SignedData<Order>> newOrders = orders.assoc(peerKey, newSignedOrder);
		Belief newBelief=this.withOrders(newOrders);
		return newBelief;
	}

	@Override
	protected ARecordGeneric withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new Belief(newValues);
	}

}
