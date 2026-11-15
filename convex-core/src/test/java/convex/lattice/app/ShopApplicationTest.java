package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.AKeyedRegions.RegionFactory;
import convex.lattice.IndexComponent;
import convex.lattice.LatticeHost;
import convex.lattice.MapComponent;
import convex.lattice.Regions;
import convex.lattice.TransactionalComponent;
import convex.lattice.UnionSet;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;

/**
 * A shop assembled from three regions that know nothing of the shop or of each
 * other: a library {@link UnionSet} catalogue, a stock of counts and an order book.
 * The shop's operations are transactions across its regions; each region still
 * transacts on its own, and how the order book is built, live or staged, is the
 * caller's choice, with no change to the shop.
 *
 * <p>In memory only: no store, persistence or network.</p>
 */
class ShopApplicationTest {
	private static final Keyword CATALOGUE=Keyword.intern("catalogue");
	private static final Keyword STOCK=Keyword.intern("stock");
	private static final Keyword ORDERS=Keyword.intern("orders");

	private static final Keyword TEA=Keyword.intern("tea");
	private static final Keyword COFFEE=Keyword.intern("coffee");

	/** Counts of items in stock. Concurrent changes to a count both apply. */
	static final class Stock extends IndexComponent<Keyword,CVMLong>
			implements TransactionalComponent<Index<Keyword,CVMLong>,Stock> {
		Stock(AForkableCursor<Index<Keyword,CVMLong>> cursor) { super(cursor); }
		@Override public Stock rebind(AForkableCursor<Index<Keyword,CVMLong>> cursor) { return new Stock(cursor); }

		long count(Keyword item) { return count(get(),item); }

		void restock(Keyword item,long quantity) {
			cursor().updateAndGet(stock -> adjust(stock,item,quantity));
		}

		void take(Keyword item) {
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
	static final class Orders extends MapComponent<CVMLong,Keyword>
			implements TransactionalComponent<AHashMap<CVMLong,Keyword>,Orders> {
		Orders(AForkableCursor<AHashMap<CVMLong,Keyword>> cursor) { super(cursor); }
		@Override public Orders rebind(AForkableCursor<AHashMap<CVMLong,Keyword>> cursor) { return new Orders(cursor); }
		@Override public Orders fork() { return rebind(storage.fork()); }

		void record(long id,Keyword item) { put(CVMLong.create(id),item); }

		@Override protected AHashMap<CVMLong,Keyword> mergePublication(AHashMap<CVMLong,Keyword> own,AHashMap<CVMLong,Keyword> other) {
			if (own==null) return other;
			if (other==null) return own;
			AHashMap<CVMLong,Keyword> merged=other;
			for (var order:own.entrySet()) merged=merged.assoc(order.getKey(),order.getValue());
			return merged;
		}
	}

	/** An order book whose edits land in the shop at once. */
	private static final RegionFactory<AHashMap<CVMLong,Keyword>,Orders> LIVE_ORDERS=Orders::new;

	/** An order book that keeps its own edits until the shop syncs. */
	private static final RegionFactory<AHashMap<CVMLong,Keyword>,Orders> STAGED_ORDERS=cursor -> new Orders(cursor).fork();

	/** The application: three regions, and operations that span them. */
	static final class Shop extends Regions implements TransactionalComponent<Index<Keyword,ACell>,Shop> {
		Shop(AForkableCursor<Index<Keyword,ACell>> cursor,RegionFactory<AHashMap<CVMLong,Keyword>,Orders> orders) {
			super(cursor);
			create(CATALOGUE,(AForkableCursor<ASet<Keyword>> c) -> new UnionSet<>(c));
			create(STOCK,(AForkableCursor<Index<Keyword,CVMLong>> c) -> new Stock(c));
			create(ORDERS,orders);
		}
		private Shop(AForkableCursor<Index<Keyword,ACell>> cursor,Shop source) { super(cursor,source); }
		@Override public Shop rebind(AForkableCursor<Index<Keyword,ACell>> cursor) { return new Shop(cursor,this); }

		UnionSet<Keyword> catalogue() { return registered(CATALOGUE); }
		Stock stock() { return registered(STOCK); }
		Orders orders() { return registered(ORDERS); }

		/** Adds an item to the catalogue with its opening stock, both or neither. */
		void list(Keyword item,long quantity) {
			execute(shop -> {
				shop.catalogue().add(item);
				shop.stock().restock(item,quantity);
			});
		}

		/** Takes one of a listed item from stock and records the order, both or neither. */
		void order(long id,Keyword item) {
			execute(shop -> {
				if (!shop.catalogue().contains(item)) throw new IllegalArgumentException("Not in the catalogue: "+item);
				shop.stock().take(item);
				shop.orders().record(id,item);
			});
		}
	}

	private static Shop open(LatticeHost<Index<Keyword,ACell>> host,RegionFactory<AHashMap<CVMLong,Keyword>,Orders> orders) {
		return host.addApplication(new Shop(host.cursor(),orders));
	}

	private static RegionFactory<AHashMap<CVMLong,Keyword>,Orders> orders(boolean staged) {
		return staged?STAGED_ORDERS:LIVE_ORDERS;
	}

	private static ACell at(LatticeHost<Index<Keyword,ACell>> host,Keyword region) {
		return host.cursor().get().get(region);
	}

	private static Index<Keyword,CVMLong> stock(Keyword item,long count) {
		return Index.of(item,CVMLong.create(count));
	}

	@ParameterizedTest(name="staged orders: {0}")
	@ValueSource(booleans={false,true})
	void anOrderChangesEveryRegionItTouchesTogether(boolean staged) {
		var host=LatticeHost.create();
		var shop=open(host,orders(staged));
		shop.list(TEA,2);
		assertEquals(Sets.of(TEA),at(host,CATALOGUE));
		assertEquals(stock(TEA,2),at(host,STOCK));
		shop.order(1,TEA);
		assertEquals(stock(TEA,1),at(host,STOCK));
		assertEquals(Maps.of(CVMLong.ONE,TEA),at(host,ORDERS),"committed with the stock, however the order book is built");
	}

	@ParameterizedTest(name="staged orders: {0}")
	@ValueSource(booleans={false,true})
	void anOrderThatCannotBeFilledChangesNothing(boolean staged) {
		var host=LatticeHost.create();
		var shop=open(host,orders(staged));
		shop.list(TEA,1);
		shop.order(1,TEA);
		var before=host.cursor().get();
		assertThrows(IllegalStateException.class,() -> shop.order(2,TEA),"out of stock");
		assertThrows(IllegalArgumentException.class,() -> shop.order(3,COFFEE),"not listed");
		assertSame(before,host.cursor().get());
	}

	@ParameterizedTest(name="staged orders: {0}")
	@ValueSource(booleans={false,true})
	void operationsComposeIntoOneTransactionThatCommitsAllOrNothing(boolean staged) {
		var host=LatticeHost.create();
		var shop=open(host,orders(staged));
		shop.list(TEA,1);
		var before=host.cursor().get();
		assertThrows(IllegalStateException.class,() -> shop.execute(s -> {
			s.list(COFFEE,1);
			s.order(1,COFFEE);
			s.order(2,COFFEE);
		}));
		assertSame(before,host.cursor().get(),"no listing and no first order");

		shop.execute(s -> {
			s.list(COFFEE,1);
			s.order(1,COFFEE);
		});
		assertEquals(Sets.of(TEA,COFFEE),at(host,CATALOGUE));
		assertEquals(Index.of(COFFEE,CVMLong.ZERO,TEA,CVMLong.ONE),at(host,STOCK));
		assertEquals(Maps.of(CVMLong.ONE,COFFEE),at(host,ORDERS));
	}

	@Test void aRegionWorksTheSameOnItsOwnAsInTheShop() {
		var alone=new Stock(new Root<>(Index.<Keyword,CVMLong>none()));
		alone.restock(TEA,3);
		alone.take(TEA);
		alone.exclusive().execute(s -> s.restock(TEA,1));

		var host=LatticeHost.create();
		var shop=open(host,LIVE_ORDERS);
		shop.list(COFFEE,1);
		shop.order(1,COFFEE);
		var catalogue=at(host,CATALOGUE);
		var orders=at(host,ORDERS);
		shop.stock().restock(TEA,3);
		shop.stock().take(TEA);
		shop.stock().exclusive().execute(s -> s.restock(TEA,1));

		assertEquals(3,alone.count(TEA));
		assertEquals(3,shop.stock().count(TEA));
		assertSame(catalogue,at(host,CATALOGUE),"the stock's own work leaves the other regions alone");
		assertSame(orders,at(host,ORDERS));
	}

	@Test void orderBooksBuiltLiveOrStagedPublishTheirOwnEditsDifferently() {
		var liveHost=LatticeHost.create();
		var live=open(liveHost,LIVE_ORDERS);
		live.orders().record(7,TEA);
		assertEquals(Maps.of(CVMLong.create(7),TEA),at(liveHost,ORDERS),"in the shop at once");

		var stagedHost=LatticeHost.create();
		var staged=open(stagedHost,STAGED_ORDERS);
		staged.orders().record(7,TEA);
		assertNull(at(stagedHost,ORDERS),"kept until the shop syncs");
		staged.sync();
		assertEquals(Maps.of(CVMLong.create(7),TEA),at(stagedHost,ORDERS));
	}

	@Test void aShopTransactionRetriesOnTopOfAStockCommit() {
		var host=LatticeHost.create();
		var shop=open(host,LIVE_ORDERS);
		shop.list(TEA,1);
		var runs=new AtomicInteger();
		shop.execute(s -> {
			if (runs.incrementAndGet()==1) shop.stock().restock(TEA,5);
			s.order(1,TEA);
		});
		assertEquals(2,runs.get(),"the restock landed during the first run");
		assertEquals(stock(TEA,5),at(host,STOCK),"one plus five, less the order");
	}

	@Test void anExclusiveRestockLeavesTheOtherRegionsFree() throws InterruptedException {
		var host=LatticeHost.create();
		var shop=open(host,LIVE_ORDERS);
		shop.list(TEA,1);
		shop.order(1,TEA);
		shop.stock().exclusive().execute(s -> {
			s.restock(TEA,10);
			Thread elsewhere=Thread.ofPlatform().start(() -> {
				shop.orders().record(2,TEA);
				shop.catalogue().add(COFFEE);
			});
			join(elsewhere);
		});
		assertEquals(stock(TEA,10),at(host,STOCK));
		assertEquals(Maps.of(CVMLong.ONE,TEA,CVMLong.create(2),TEA),at(host,ORDERS));
		assertEquals(Sets.of(TEA,COFFEE),at(host,CATALOGUE));
	}

	@Test void anOrderDuringAnExclusiveRestockIsMergedIntoIt() {
		var host=LatticeHost.create();
		var shop=open(host,LIVE_ORDERS);
		shop.list(TEA,2);
		shop.stock().exclusive().execute(s -> {
			// The shop commits at its root, not through the stock's boundary
			shop.order(1,TEA);
			s.restock(TEA,10);
		});
		assertEquals(stock(TEA,11),at(host,STOCK),"two, less the order, plus the restock");
		assertEquals(Maps.of(CVMLong.ONE,TEA),at(host,ORDERS));
	}

	@Test void anExclusiveShopTransactionHoldsUpRegionWritesUntilItCommits() throws InterruptedException {
		var host=LatticeHost.create();
		var shop=open(host,LIVE_ORDERS);
		shop.list(TEA,1);
		var restock=new AtomicReference<Thread>();
		shop.exclusive().execute(s -> {
			restock.set(Thread.ofPlatform().start(() -> shop.stock().restock(TEA,5)));
			awaitParked(restock.get());
			assertEquals(stock(TEA,1),at(host,STOCK),"the restock waits for the shop");
			s.order(1,TEA);
		});
		join(restock.get());
		assertEquals(stock(TEA,5),at(host,STOCK),"the order, then the restock on top");
	}

	/** Waits until a thread is parked, as one waiting for a claim is, or has finished. */
	private static void awaitParked(Thread thread) {
		while (thread.isAlive() && thread.getState()!=Thread.State.WAITING) Thread.onSpinWait();
	}

	private static void join(Thread thread) {
		try {
			thread.join(10_000);
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
		assertFalse(thread.isAlive(),"finished");
	}
}
