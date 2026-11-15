package convex.lattice.app;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.IndexComponent;
import convex.lattice.MapComponent;
import convex.lattice.Regions;
import convex.lattice.TransactionalComponent;
import convex.lattice.UnionSet;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.generic.SetLattice;

/**
 * A shop held in its application region, {@code [:app :shop]}, as
 * <a href="https://docs.convex.world/docs/cad/lattice_apps">CAD045</a> places an
 * application's own state:
 * <pre>{@code
 * {:app {:shop {:catalogue #{item} :stock {item count} :orders {id item}}}}
 * }</pre>
 *
 * <ul>
 *   <li>{@link ShopApp} is the root application. It registers the {@code :app}
 *       region; a root serving other regions, such as files at {@code :fs},
 *       registers those beside it.</li>
 *   <li>{@link AppRegion} is the {@code :app} region, holding the shop under its
 *       application name.</li>
 *   <li>{@link Shop} is the application's domain component. It makes its
 *       {@link Catalogue}, {@link Stock} and {@link Orders} on its own cursor when
 *       they are needed. They are wrappers holding nothing but that cursor, so they
 *       work alike on live storage, a fork or a transaction's draft.</li>
 * </ul>
 *
 * <p>The shop's operations are transactions over its whole value, and compose into
 * larger ones, including a transaction on the root application spanning other
 * regions. The shop merges its value part by part, with each part's rule, so
 * concurrent changes to a fork, or to the shop under an exclusive transaction's
 * claim, are kept. {@link #open} builds the root application over a plain root;
 * a service that stores or replicates it registers it with a host instead,
 * {@code host.addApplication(new ShopApp(host.cursor()))}.</p>
 */
public final class ShopExample {

	public static final Keyword SHOP=Keyword.intern("shop");

	public static final Keyword CATALOGUE=Keyword.intern("catalogue");
	public static final Keyword STOCK=Keyword.intern("stock");
	public static final Keyword ORDERS=Keyword.intern("orders");

	public static final Keyword TEA=Keyword.intern("tea");
	public static final Keyword COFFEE=Keyword.intern("coffee");

	private ShopExample() {}

	/** Lists, orders and restocks in a fresh shop, printing the root after each step. */
	public static void main(String[] args) {
		var app=open();
		var shop=app.shop();
		shop.list(TEA,2);
		System.out.println("Listed:     "+app.value());
		shop.order(1,TEA);
		System.out.println("Ordered:    "+app.value());
		shop.exclusive().execute(s -> {
			s.stock().restock(TEA,10);
			s.order(2,TEA);
		});
		System.out.println("Restocked:  "+app.value());
		var draft=shop.fork();
		draft.order(3,TEA);
		System.out.println("Drafted:    "+app.value());
		draft.sync();
		System.out.println("Synced:     "+app.value());
	}

	/** Builds the root application over a fresh in-memory root. */
	public static ShopApp open() {
		return new ShopApp(new Root<>(Index.<Keyword,ACell>none()));
	}

	/** The root application: the {@code :app} region, with the shop in it. */
	public static final class ShopApp extends Regions implements TransactionalComponent<Index<Keyword,ACell>,ShopApp> {
		public ShopApp(AForkableCursor<Index<Keyword,ACell>> cursor) {
			super(cursor);
			create(Keywords.APP,(AForkableCursor<Index<Keyword,ACell>> c) -> new AppRegion(c));
		}
		private ShopApp(AForkableCursor<Index<Keyword,ACell>> cursor,ShopApp source) { super(cursor,source); }
		@Override public ShopApp rebind(AForkableCursor<Index<Keyword,ACell>> cursor) { return new ShopApp(cursor,this); }

		/** Returns the shop in this root's {@code :app} region. */
		public Shop shop() { return this.<AppRegion>registered(Keywords.APP).shop(); }
	}

	/** The {@code :app} region: applications keyed by name, here only the shop. */
	public static final class AppRegion extends Regions {
		public AppRegion(AForkableCursor<Index<Keyword,ACell>> cursor) {
			super(cursor);
			// A new shop starts empty: its place reads as an empty index until it first changes
			create(SHOP,(AForkableCursor<Index<Keyword,ACell>> c) -> new Shop(c.withDefault(Index.none())));
		}
		private AppRegion(AForkableCursor<Index<Keyword,ACell>> cursor,AppRegion source) { super(cursor,source); }
		@Override public AppRegion rebind(AForkableCursor<Index<Keyword,ACell>> cursor) { return new AppRegion(cursor,this); }

		/** Returns the shop, at {@code :shop}. */
		public Shop shop() { return registered(SHOP); }
	}

	/** The application: a catalogue, stock and orders, and operations that span them. */
	public static final class Shop extends IndexComponent<Keyword,ACell> implements TransactionalComponent<Index<Keyword,ACell>,Shop> {
		public Shop(AForkableCursor<Index<Keyword,ACell>> cursor) { super(cursor); }
		@Override public Shop rebind(AForkableCursor<Index<Keyword,ACell>> cursor) { return new Shop(cursor); }
		@Override public Shop fork() { return rebind(storage.fork()); }

		public Catalogue catalogue() { return new Catalogue(path(CATALOGUE)); }
		public Stock stock() { return new Stock(path(STOCK)); }
		public Orders orders() { return new Orders(path(ORDERS)); }

		/** Lists an item with its opening stock, both or neither. */
		public void list(Keyword item,long quantity) {
			execute(shop -> {
				shop.catalogue().list(item);
				shop.stock().restock(item,quantity);
			});
		}

		/**
		 * Takes one of a listed item from stock and records the order, all or nothing.
		 *
		 * @throws IllegalArgumentException If the item is not listed
		 * @throws IllegalStateException If the item is out of stock
		 */
		public void order(long id,Keyword item) {
			execute(shop -> {
				if (!shop.catalogue().isListed(item)) throw new IllegalArgumentException("Not listed: "+item);
				shop.stock().take(item);
				shop.orders().record(id,item);
			});
		}

		/** Merges each part that both sides changed with that part's own rule. */
		@Override protected Index<Keyword,ACell> reconcile(Index<Keyword,ACell> base,Index<Keyword,ACell> own,Index<Keyword,ACell> other) {
			Index<Keyword,ACell> merged=(other==null)?Index.none():other;
			merged=mergePart(merged,CATALOGUE,base,own,other,(ASet<Keyword> b,ASet<Keyword> o,ASet<Keyword> t) -> Catalogue.merge(o,t));
			merged=mergePart(merged,STOCK,base,own,other,(Index<Keyword,CVMLong> b,Index<Keyword,CVMLong> o,Index<Keyword,CVMLong> t) -> Stock.merge(b,o,t));
			merged=mergePart(merged,ORDERS,base,own,other,(AHashMap<CVMLong,Keyword> b,AHashMap<CVMLong,Keyword> o,AHashMap<CVMLong,Keyword> t) -> Orders.merge(o,t));
			return merged;
		}

		private interface PartMerge<V extends ACell> { V merge(V base,V own,V other); }

		@SuppressWarnings("unchecked")
		private static <V extends ACell> Index<Keyword,ACell> mergePart(Index<Keyword,ACell> merged,Keyword part,
				Index<Keyword,ACell> base,Index<Keyword,ACell> own,Index<Keyword,ACell> other,PartMerge<V> rule) {
			V b=(V)part(base,part),o=(V)part(own,part),t=(V)part(other,part);
			if (Utils.equals(o,b)) return merged; // this side left it alone, so the other side's value stands
			V result=Utils.equals(t,b)?o:rule.merge(b,o,t);
			return merged.assoc(part,result);
		}

		private static ACell part(Index<Keyword,ACell> shop,Keyword part) {
			return (shop==null)?null:shop.get(part);
		}
	}

	/** The items the shop lists. Listings from two sides are all kept. */
	public static final class Catalogue extends UnionSet<Keyword> {
		public Catalogue(AForkableCursor<ASet<Keyword>> cursor) { super(cursor); }

		/** Lists an item. */
		public void list(Keyword item) { add(item); }

		/** Returns whether an item is listed. */
		public boolean isListed(Keyword item) { return contains(item); }

		static ASet<Keyword> merge(ASet<Keyword> own,ASet<Keyword> other) {
			return SetLattice.<Keyword>create().merge(own,other);
		}
	}

	/** Counts of items in stock. Concurrent changes to a count both apply. */
	public static final class Stock extends IndexComponent<Keyword,CVMLong> {
		public Stock(AForkableCursor<Index<Keyword,CVMLong>> cursor) { super(cursor); }

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
		static Index<Keyword,CVMLong> merge(Index<Keyword,CVMLong> base,Index<Keyword,CVMLong> own,Index<Keyword,CVMLong> other) {
			Index<Keyword,CVMLong> merged=other;
			if (own!=null) for (Keyword item:own.keySet()) {
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

	/** Orders by identifier. Identifiers are unique, so orders from two sides are all kept. */
	public static final class Orders extends MapComponent<CVMLong,Keyword> {
		public Orders(AForkableCursor<AHashMap<CVMLong,Keyword>> cursor) { super(cursor); }

		/** Records an order for an item under its identifier. */
		public void record(long id,Keyword item) { put(CVMLong.create(id),item); }

		static AHashMap<CVMLong,Keyword> merge(AHashMap<CVMLong,Keyword> own,AHashMap<CVMLong,Keyword> other) {
			if (own==null) return other;
			if (other==null) return own;
			AHashMap<CVMLong,Keyword> merged=other;
			for (var order:own.entrySet()) merged=merged.assoc(order.getKey(),order.getValue());
			return merged;
		}
	}
}
