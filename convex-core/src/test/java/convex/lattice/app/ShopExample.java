package convex.lattice.app;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.lattice.AKeyedRegions.RegionFactory;
import convex.lattice.IndexComponent;
import convex.lattice.MapComponent;
import convex.lattice.Regions;
import convex.lattice.TransactionalComponent;
import convex.lattice.UnionSet;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;

/**
 * A shop assembled from three regions that know nothing of the shop or of each
 * other, held in memory with no store or network:
 * <pre>{@code
 * {:catalogue #{items} :stock {item count} :orders {id item}}
 * }</pre>
 *
 * <ul>
 *   <li>The catalogue is a library {@link UnionSet}, used as it is.</li>
 *   <li>{@link Stock} holds counts; concurrent changes to a count both apply.</li>
 *   <li>{@link Orders} holds orders by unique identifier, built live or staged by
 *       whichever factory the shop is given, {@link #LIVE_ORDERS} or
 *       {@link #STAGED_ORDERS}.</li>
 * </ul>
 *
 * <p>{@link Shop} extends {@link Regions}. Its operations are transactions across
 * the regions they touch, and compose into larger transactions; each region also
 * transacts on its own, exclusively or not. {@link #open} builds one over a plain
 * root. A service that stores or replicates the shop registers it with a host
 * instead, {@code host.addApplication(new Shop(host.cursor(),LIVE_ORDERS))}.</p>
 */
public final class ShopExample {

	public static final Keyword CATALOGUE=Keyword.intern("catalogue");
	public static final Keyword STOCK=Keyword.intern("stock");
	public static final Keyword ORDERS=Keyword.intern("orders");

	public static final Keyword TEA=Keyword.intern("tea");
	public static final Keyword COFFEE=Keyword.intern("coffee");

	/** An order book whose edits land in the shop at once. */
	public static final RegionFactory<AHashMap<CVMLong,Keyword>,Orders> LIVE_ORDERS=Orders::new;

	/** An order book that keeps its own edits until the shop syncs. */
	public static final RegionFactory<AHashMap<CVMLong,Keyword>,Orders> STAGED_ORDERS=cursor -> new Orders(cursor).fork();

	private ShopExample() {}

	/** Lists, orders and restocks in a fresh shop, printing its value after each step. */
	public static void main(String[] args) {
		var shop=open(STAGED_ORDERS);
		shop.list(TEA,2);
		System.out.println("Listed:     "+shop.value());
		shop.order(1,TEA);
		System.out.println("Ordered:    "+shop.value());
		shop.stock().exclusive().execute(stock -> {
			shop.order(2,TEA);
			stock.restock(TEA,10);
		});
		System.out.println("Restocked:  "+shop.value());
		shop.orders().record(3,TEA);
		System.out.println("Staged:     "+shop.value());
		shop.sync();
		System.out.println("Synced:     "+shop.value());
	}

	/** Builds a shop over a fresh in-memory root, with its order book from the given factory. */
	public static Shop open(RegionFactory<AHashMap<CVMLong,Keyword>,Orders> orders) {
		return new Shop(new Root<>(Index.<Keyword,ACell>none()),orders);
	}

	/** The application: three regions, and operations that span them. */
	public static final class Shop extends Regions implements TransactionalComponent<Index<Keyword,ACell>,Shop> {
		public Shop(AForkableCursor<Index<Keyword,ACell>> cursor,RegionFactory<AHashMap<CVMLong,Keyword>,Orders> orders) {
			super(cursor);
			create(CATALOGUE,(AForkableCursor<ASet<Keyword>> c) -> new UnionSet<>(c));
			create(STOCK,(AForkableCursor<Index<Keyword,CVMLong>> c) -> new Stock(c));
			create(ORDERS,orders);
		}
		private Shop(AForkableCursor<Index<Keyword,ACell>> cursor,Shop source) { super(cursor,source); }
		@Override public Shop rebind(AForkableCursor<Index<Keyword,ACell>> cursor) { return new Shop(cursor,this); }

		public UnionSet<Keyword> catalogue() { return registered(CATALOGUE); }
		public Stock stock() { return registered(STOCK); }
		public Orders orders() { return registered(ORDERS); }

		/** Adds an item to the catalogue with its opening stock, both or neither. */
		public void list(Keyword item,long quantity) {
			execute(shop -> {
				shop.catalogue().add(item);
				shop.stock().restock(item,quantity);
			});
		}

		/**
		 * Takes one of a listed item from stock and records the order, both or neither.
		 *
		 * @throws IllegalArgumentException If the item is not in the catalogue
		 * @throws IllegalStateException If the item is out of stock
		 */
		public void order(long id,Keyword item) {
			execute(shop -> {
				if (!shop.catalogue().contains(item)) throw new IllegalArgumentException("Not in the catalogue: "+item);
				shop.stock().take(item);
				shop.orders().record(id,item);
			});
		}
	}

	/** Counts of items in stock. Concurrent changes to a count both apply. */
	public static final class Stock extends IndexComponent<Keyword,CVMLong>
			implements TransactionalComponent<Index<Keyword,CVMLong>,Stock> {
		public Stock(AForkableCursor<Index<Keyword,CVMLong>> cursor) { super(cursor); }
		@Override public Stock rebind(AForkableCursor<Index<Keyword,CVMLong>> cursor) { return new Stock(cursor); }

		/** Returns how many of an item are in stock, zero if it has never been stocked. */
		public long count(Keyword item) { return count(get(),item); }

		/** Adds to an item's count. */
		public void restock(Keyword item,long quantity) {
			cursor().updateAndGet(stock -> adjust(stock,item,quantity));
		}

		/**
		 * Takes one of an item.
		 *
		 * @throws IllegalStateException If none is in stock
		 */
		public void take(Keyword item) {
			cursor().updateAndGet(stock -> {
				if (count(stock,item)<=0) throw new IllegalStateException("Out of stock: "+item);
				return adjust(stock,item,-1);
			});
		}

		/** Applies each side's change to every count, as a difference from the base. */
		@Override protected Index<Keyword,CVMLong> mergePublication(Index<Keyword,CVMLong> base,Index<Keyword,CVMLong> own,Index<Keyword,CVMLong> other) {
			Index<Keyword,CVMLong> merged=other;
			for (Keyword item:own.keySet()) {
				long change=count(own,item)-count(base,item);
				if (change!=0) merged=adjust(merged,item,change);
			}
			return merged;
		}

		private static long count(Index<Keyword,CVMLong> stock,Keyword item) {
			CVMLong n=(stock==null)?null:stock.get(item);
			return (n==null)?0:n.longValue();
		}

		private static Index<Keyword,CVMLong> adjust(Index<Keyword,CVMLong> stock,Keyword item,long change) {
			Index<Keyword,CVMLong> counts=(stock==null)?Index.none():stock;
			return counts.assoc(item,CVMLong.create(count(counts,item)+change));
		}
	}

	/** Orders by identifier. Identifiers are unique, so two books merge by taking every order. */
	public static final class Orders extends MapComponent<CVMLong,Keyword>
			implements TransactionalComponent<AHashMap<CVMLong,Keyword>,Orders> {
		public Orders(AForkableCursor<AHashMap<CVMLong,Keyword>> cursor) { super(cursor); }
		@Override public Orders rebind(AForkableCursor<AHashMap<CVMLong,Keyword>> cursor) { return new Orders(cursor); }
		@Override public Orders fork() { return rebind(storage.fork()); }

		/** Records an order for an item under its identifier. */
		public void record(long id,Keyword item) { put(CVMLong.create(id),item); }

		@Override protected AHashMap<CVMLong,Keyword> mergePublication(AHashMap<CVMLong,Keyword> own,AHashMap<CVMLong,Keyword> other) {
			if (own==null) return other;
			if (other==null) return own;
			AHashMap<CVMLong,Keyword> merged=other;
			for (var order:own.entrySet()) merged=merged.assoc(order.getKey(),order.getValue());
			return merged;
		}
	}
}
