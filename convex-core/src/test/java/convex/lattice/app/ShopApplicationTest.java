package convex.lattice.app;

import static convex.lattice.app.ShopExample.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.AKeyedRegions.RegionFactory;
import convex.lattice.LatticeHost;
import convex.lattice.app.ShopExample.Orders;
import convex.lattice.app.ShopExample.Stock;
import convex.lattice.cursor.Root;

/**
 * Transactions across independent regions plugged into one application, the
 * {@link ShopExample}: the shop's operations span its regions, while each region
 * still transacts on its own and its order book can be built live or staged with
 * no change to the shop.
 */
class ShopApplicationTest {

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

	@Test void anExclusiveRestockLeavesTheOtherRegionsFree() {
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

	@Test void anExclusiveShopTransactionHoldsUpRegionWritesUntilItCommits() {
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

	@Test void theExampleRuns() {
		ShopExample.main(new String[0]);
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
