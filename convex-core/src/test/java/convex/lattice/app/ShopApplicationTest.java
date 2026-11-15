package convex.lattice.app;

import static convex.lattice.app.ShopExample.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.app.ShopExample.Shop;
import convex.lattice.app.ShopExample.Stock;
import convex.lattice.cursor.Root;

/**
 * Transactions across independent regions plugged into one application, the
 * {@link ShopExample}: the shop's operations span its regions, while each region
 * still transacts on its own, and the shop or any region forks to work apart.
 */
class ShopApplicationTest {

	private static ACell at(Shop shop,Keyword region) {
		return shop.value().get(region);
	}

	private static Index<Keyword,CVMLong> stock(Keyword item,long count) {
		return Index.of(item,CVMLong.create(count));
	}

	@Test void anOrderChangesEveryRegionItTouchesTogether() {
		var shop=open();
		shop.list(TEA,2);
		assertEquals(Sets.of(TEA),at(shop,CATALOGUE));
		assertEquals(stock(TEA,2),at(shop,STOCK));
		shop.order(1,TEA);
		assertEquals(stock(TEA,1),at(shop,STOCK));
		assertEquals(Maps.of(CVMLong.ONE,TEA),at(shop,ORDERS),"committed with the stock");
	}

	@Test void anOrderThatCannotBeFilledChangesNothing() {
		var shop=open();
		shop.list(TEA,1);
		shop.order(1,TEA);
		var before=shop.value();
		assertThrows(IllegalStateException.class,() -> shop.order(2,TEA),"out of stock");
		assertThrows(IllegalArgumentException.class,() -> shop.order(3,COFFEE),"not listed");
		assertSame(before,shop.value());
	}

	@Test void operationsComposeIntoOneTransactionThatCommitsAllOrNothing() {
		var shop=open();
		shop.list(TEA,1);
		var before=shop.value();
		assertThrows(IllegalStateException.class,() -> shop.execute(s -> {
			s.list(COFFEE,1);
			s.order(1,COFFEE);
			s.order(2,COFFEE);
		}));
		assertSame(before,shop.value(),"no listing and no first order");

		shop.execute(s -> {
			s.list(COFFEE,1);
			s.order(1,COFFEE);
		});
		assertEquals(Sets.of(TEA,COFFEE),at(shop,CATALOGUE));
		assertEquals(Index.of(COFFEE,CVMLong.ZERO,TEA,CVMLong.ONE),at(shop,STOCK));
		assertEquals(Maps.of(CVMLong.ONE,COFFEE),at(shop,ORDERS));
	}

	@Test void aRegionWorksTheSameOnItsOwnAsInTheShop() {
		var alone=new Stock(new Root<>(Index.<Keyword,CVMLong>none()));
		alone.restock(TEA,3);
		alone.take(TEA);
		alone.exclusive().execute(s -> s.restock(TEA,1));

		var shop=open();
		shop.list(COFFEE,1);
		shop.order(1,COFFEE);
		var catalogue=at(shop,CATALOGUE);
		var orders=at(shop,ORDERS);
		shop.stock().restock(TEA,3);
		shop.stock().take(TEA);
		shop.stock().exclusive().execute(s -> s.restock(TEA,1));

		assertEquals(3,alone.count(TEA));
		assertEquals(3,shop.stock().count(TEA));
		assertSame(catalogue,at(shop,CATALOGUE),"the stock's own work leaves the other regions alone");
		assertSame(orders,at(shop,ORDERS));
	}

	@Test void aForkedRegionKeepsItsEditsUntilItSyncs() {
		var shop=open();
		var draft=shop.orders().fork();
		draft.record(7,TEA);
		shop.orders().record(8,COFFEE);
		assertEquals(Maps.of(CVMLong.create(8),COFFEE),at(shop,ORDERS),"the draft's order is not in the shop yet");
		draft.sync();
		assertEquals(Maps.of(CVMLong.create(7),TEA,CVMLong.create(8),COFFEE),at(shop,ORDERS),"both orders");
	}

	@Test void aForkedShopPublishesItsOperationsWhenItSyncs() {
		var shop=open();
		shop.list(TEA,1);
		var draft=shop.fork();
		draft.order(1,TEA);
		shop.catalogue().add(COFFEE);
		assertEquals(stock(TEA,1),at(shop,STOCK),"the draft's order is not in the shop yet");
		assertNull(at(shop,ORDERS));
		draft.sync();
		assertEquals(stock(TEA,0),at(shop,STOCK));
		assertEquals(Maps.of(CVMLong.ONE,TEA),at(shop,ORDERS));
		assertEquals(Sets.of(TEA,COFFEE),at(shop,CATALOGUE),"the region only the shop changed keeps its change");
	}

	@Test void aShopTransactionRetriesOnTopOfAStockCommit() {
		var shop=open();
		shop.list(TEA,1);
		var runs=new AtomicInteger();
		shop.execute(s -> {
			if (runs.incrementAndGet()==1) shop.stock().restock(TEA,5);
			s.order(1,TEA);
		});
		assertEquals(2,runs.get(),"the restock landed during the first run");
		assertEquals(stock(TEA,5),at(shop,STOCK),"one plus five, less the order");
	}

	@Test void anExclusiveRestockLeavesTheOtherRegionsFree() {
		var shop=open();
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
		assertEquals(stock(TEA,10),at(shop,STOCK));
		assertEquals(Maps.of(CVMLong.ONE,TEA,CVMLong.create(2),TEA),at(shop,ORDERS));
		assertEquals(Sets.of(TEA,COFFEE),at(shop,CATALOGUE));
	}

	@Test void anOrderDuringAnExclusiveRestockIsMergedIntoIt() {
		var shop=open();
		shop.list(TEA,2);
		shop.stock().exclusive().execute(s -> {
			// The shop commits at its root, not through the stock's boundary
			shop.order(1,TEA);
			s.restock(TEA,10);
		});
		assertEquals(stock(TEA,11),at(shop,STOCK),"two, less the order, plus the restock");
		assertEquals(Maps.of(CVMLong.ONE,TEA),at(shop,ORDERS));
	}

	@Test void anExclusiveShopTransactionHoldsUpRegionWritesUntilItCommits() {
		var shop=open();
		shop.list(TEA,1);
		var restock=new AtomicReference<Thread>();
		shop.exclusive().execute(s -> {
			restock.set(Thread.ofPlatform().start(() -> shop.stock().restock(TEA,5)));
			awaitParked(restock.get());
			assertEquals(stock(TEA,1),at(shop,STOCK),"the restock waits for the shop");
			s.order(1,TEA);
		});
		join(restock.get());
		assertEquals(stock(TEA,5),at(shop,STOCK),"the order, then the restock on top");
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
