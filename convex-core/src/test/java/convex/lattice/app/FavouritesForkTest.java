package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;
import static convex.lattice.app.FavouritesExample.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeHost;

/** The same favourites operations support shared signed views and private unsigned drafts. */
class FavouritesForkTest {
	private static final Keyword ITEMS=Keyword.intern("items");

	/** Supplies an observable or temporarily unavailable signer without changing domain policy. */
	private static Favourites alice(FavouritesApp app,
			Function<AHashMap<Keyword,ACell>,SignedData<AHashMap<Keyword,ACell>>> signer,
			Supplier<CVMLong> clock) {
		return new Favourites(app.component().path(ALICE.getAccountKey()),
			app.component().signingFor(ALICE.getAccountKey()),signer,clock);
	}

	@Test void domainDraftBatchesSignaturesAndStopsAtTheRegion() {
		var host=LatticeHost.create();
		var clock=new AtomicReference<>(CVMLong.ONE);
		var app=new FavouritesApp(host,clock::get);
		var signatures=new AtomicInteger();
		var live=alice(app,value -> { signatures.incrementAndGet(); return ALICE.signData(value); },clock::get);
		var draft=live.fork();
		draft.sync();
		assertNull(app.component().get(ALICE.getAccountKey()));
		draft.add(42);
		draft.add(7);
		draft.remove(42);
		var nested=draft.fork();
		nested.add(99);
		nested.sync();
		assertEquals(Sets.of(7,99),draft.items());
		assertNull(live.items());
		assertNull(app.items(ALICE.getAccountKey()));
		assertEquals(0,signatures.get());
		app.sync();
		assertTrue(host.cursor().get().isEmpty(),"App sync does not publish outstanding user drafts");
		clock.set(CVMLong.TWO);
		draft.sync();
		assertEquals(1,signatures.get());
		assertEquals(Sets.of(7,99),app.user(ALICE).items());
		var published=app.component().get(ALICE.getAccountKey());
		assertEquals(CVMLong.ONE,published.getValue().get(TIMESTAMP),"Sync must not re-stamp the batch");
		assertTrue(published.checkSignature());
		assertTrue(host.cursor().get().isEmpty());
		draft.add(7); // Unchanged edits and publication do not sign again.
		draft.sync();
		assertEquals(1,signatures.get());
		assertSame(published,app.component().get(ALICE.getAccountKey()));
		app.sync();
		assertSame(published,host.cursor().get(FAVOURITES,ALICE.getAccountKey()));
	}

	@Test void competingDraftsUseWholeRecordOrderingInEitherPublicationOrder() {
		for (boolean newestFirst:new boolean[] {false,true}) {
			var host=LatticeHost.create();
			var clock=new AtomicReference<>(CVMLong.ONE);
			var app=new FavouritesApp(host,clock::get);
			app.user(ALICE).add(99);
			app.user(BOB).add(5);
			var bob=app.component().get(BOB.getAccountKey());
			var first=app.user(ALICE).fork();
			var second=app.user(ALICE).fork();
			clock.set(CVMLong.TWO);
			first.add(1);
			clock.set(CVMLong.create(3));
			second.remove(99);
			second.add(2);
			if (newestFirst) { second.sync(); first.sync(); }
			else { first.sync(); second.sync(); }
			first.sync();
			assertEquals(Sets.of(2),first.items());
			assertEquals(Sets.of(2),second.items());
			assertEquals(Sets.of(2),app.user(ALICE).items(),"The newer record wins, not a union of item sets");
			assertSame(bob,app.component().get(BOB.getAccountKey()));
			assertTrue(host.cursor().get().isEmpty());
		}
	}

	@Test void incomingDraftValuesAreVerifiedWithoutSigningStampingOrPublishing() {
		var app=new FavouritesApp(LatticeHost.create(),() -> { throw new AssertionError("No local edit"); });
		var draft=alice(app,value -> { throw new AssertionError("No publication"); },
			() -> { throw new AssertionError("Do not stamp incoming values"); }).fork();
		AHashMap<Keyword,ACell> payload=Maps.of(TIMESTAMP,CVMLong.ONE,ITEMS,Maps.of("future","items"));
		var envelope=ALICE.signData(payload);
		assertSame(payload,draft.merge(envelope));
		var newer=payload.assoc(TIMESTAMP,CVMLong.TWO);
		draft.merge(BOB.signData(newer));
		draft.merge(SignedData.create(ALICE.getAccountKey(),envelope.getSignature(),Ref.get(newer)));
		draft.mergeAt(ALICE.signData(newer),ITEMS);
		assertThrows(IllegalArgumentException.class,() -> draft.mergeAt(payload));
		assertSame(payload,draft.storage().get());
		assertNull(app.component().get(ALICE.getAccountKey()));
	}

	@Test void unavailableOrWrongSignerLeavesTheBatchPendingAndCanBeRetried() {
		var host=LatticeHost.create();
		var clock=new AtomicReference<>(CVMLong.ONE);
		var app=new FavouritesApp(host,clock::get);
		app.user(ALICE).add(1);
		var signingKey=new AtomicReference<>(BOB);
		var draft=alice(app,value -> signingKey.get()==null?null:signingKey.get().signData(value),clock::get).fork();
		clock.set(CVMLong.create(3));
		draft.remove(1);
		draft.add(3);
		var pending=draft.storage().get();
		clock.set(CVMLong.TWO);
		app.user(ALICE).add(2); // Force reconciliation before the failed signing attempt.
		var before=app.component().get(ALICE.getAccountKey());
		assertThrows(IllegalStateException.class,draft::sync);
		signingKey.set(null);
		assertThrows(IllegalStateException.class,draft::sync);
		assertSame(before,app.component().get(ALICE.getAccountKey()));
		assertSame(pending,draft.storage().get());
		signingKey.set(ALICE);
		draft.sync();
		assertEquals(Sets.of(3),app.user(ALICE).items());
		assertEquals(CVMLong.create(3),app.component().get(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
		assertTrue(app.component().get(ALICE.getAccountKey()).checkSignature());
		assertTrue(host.cursor().get().isEmpty());
	}
}
