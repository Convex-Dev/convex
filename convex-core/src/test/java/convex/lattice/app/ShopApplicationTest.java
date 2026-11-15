package convex.lattice.app;

import static convex.lattice.app.ShopExample.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.app.ShopExample.Shop;
import convex.lattice.app.ShopExample.Stock;
import convex.lattice.cursor.Root;

/**
 * The {@link ShopExample}: an application in its {@code :app} region whose
 * operations are transactions over its parts, made when needed on whatever cursor
 * the shop has, and whose merge keeps concurrent changes to a fork or under a claim.
 */
class ShopApplicationTest {

	private static ACell at(Shop shop,Keyword part) {
		return shop.value().get(part);
	}

	private static Index<Keyword,CVMLong> stock(Keyword item,long count) {
		return Index.of(item,CVMLong.create(count));
	}

	@Test void theShopLivesInItsApplicationRegion() {
		var app=open();
		app.shop().list(TEA,1);
		Index<Keyword,ACell> apps=RT.ensureIndex(app.value().get(Keywords.APP));
		assertEquals(app.shop().value(),apps.get(SHOP));
		assertEquals(1,app.value().count(),"nothing else at the root");
	}

	@Test void anOrderChangesEveryPartItTouchesTogether() {
		var shop=open().shop();
		shop.list(TEA,2);
		assertEquals(Sets.of(TEA),at(shop,CATALOGUE));
		assertEquals(stock(TEA,2),at(shop,STOCK));
		shop.order(1,TEA);
		assertEquals(stock(TEA,1),at(shop,STOCK));
		assertEquals(Maps.of(CVMLong.ONE,TEA),at(shop,ORDERS),"committed with the stock");
	}

	@Test void anOrderThatCannotBeFilledChangesNothing() {
		var shop=open().shop();
		shop.list(TEA,1);
		shop.order(1,TEA);
		var before=shop.value();
		assertThrows(IllegalStateException.class,() -> shop.order(2,TEA),"out of stock");
		assertThrows(IllegalArgumentException.class,() -> shop.order(3,COFFEE),"not listed");
		assertSame(before,shop.value());
	}

	@Test void operationsComposeIntoOneTransactionThatCommitsAllOrNothing() {
		var shop=open().shop();
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

	@Test void aPartWorksTheSameOnItsOwnAsInTheShop() {
		var alone=new Stock(new Root<>(Index.<Keyword,CVMLong>none()));
		alone.restock(TEA,3);
		alone.take(TEA);

		var shop=open().shop();
		shop.stock().restock(TEA,3);
		shop.stock().take(TEA);

		assertEquals(2,alone.count(TEA));
		assertEquals(2,shop.stock().count(TEA));
		assertEquals(alone.value(),at(shop,STOCK));
	}

	@Test void aForkedShopKeepsItsOperationsUntilItSyncs() {
		var shop=open().shop();
		shop.list(TEA,2);
		var draft=shop.fork();
		draft.order(1,TEA);
		shop.order(2,TEA);
		assertEquals(stock(TEA,1),at(shop,STOCK),"only the shop's own order");
		assertEquals(Maps.of(CVMLong.create(2),TEA),at(shop,ORDERS));
		draft.sync();
		assertEquals(stock(TEA,0),at(shop,STOCK),"each order took one");
		assertEquals(Maps.of(CVMLong.ONE,TEA,CVMLong.create(2),TEA),at(shop,ORDERS));
	}

	@Test void aShopTransactionRetriesOnTopOfAConcurrentCommit() {
		var shop=open().shop();
		shop.list(TEA,1);
		var runs=new AtomicInteger();
		shop.execute(s -> {
			if (runs.incrementAndGet()==1) shop.stock().restock(TEA,5);
			s.order(1,TEA);
		});
		assertEquals(2,runs.get(),"the restock landed during the first run");
		assertEquals(stock(TEA,5),at(shop,STOCK),"one plus five, less the order");
	}

	@Test void anExclusiveShopTransactionHoldsUpShopWritesUntilItCommits() {
		var shop=open().shop();
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

	@Test void aRootTransactionDuringAnExclusiveShopTransactionIsMergedIntoIt() {
		var app=open();
		var shop=app.shop();
		shop.list(TEA,2);
		shop.exclusive().execute(s -> {
			// The root commits at the root, not through the shop's claimed boundary
			app.execute(a -> a.shop().order(1,TEA));
			s.stock().restock(TEA,10);
			s.orders().record(2,TEA);
		});
		assertEquals(stock(TEA,11),at(shop,STOCK),"two, less the order, plus the restock");
		assertEquals(Maps.of(CVMLong.ONE,TEA,CVMLong.create(2),TEA),at(shop,ORDERS));
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
