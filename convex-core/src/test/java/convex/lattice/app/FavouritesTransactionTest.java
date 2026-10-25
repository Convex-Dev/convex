package convex.lattice.app;

import static convex.lattice.app.FavouritesExample.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeHost;
import convex.lattice.SetComponent;

/** Transactions rerun domain operations; forks reconcile independently edited records. */
class FavouritesTransactionTest {

	@Test void childEditsCommitTogetherWithOneSignatureAndNoExtraStamp() {
		var host=LatticeHost.create();
		var stamps=new AtomicInteger();
		var signatures=new AtomicInteger();
		var app=new FavouritesApp(host,() -> CVMLong.create(stamps.incrementAndGet()));
		var live=new Favourites(app.component().path(ALICE.getAccountKey()),
			app.component().signingFor(ALICE.getAccountKey()),
			value -> { signatures.incrementAndGet(); return ALICE.signData(value); },
			() -> CVMLong.create(stamps.incrementAndGet()));
		var retained=new AtomicReference<SetComponent<ACell>>();
		live.transact(draft -> {
			retained.set(draft.itemSet);
			draft.add(42);
			draft.itemSet.add(CVMLong.create(7));
			assertNull(live.items());
			assertEquals(0,signatures.get());
		});
		assertEquals(Sets.of(42,7),live.items());
		assertEquals(2,stamps.get(),"Commit must not stamp already prepared domain edits again");
		assertEquals(1,signatures.get());
		var signed=app.component().get(ALICE.getAccountKey());
		assertEquals(CVMLong.TWO,signed.getValue().get(TIMESTAMP));
		assertTrue(signed.checkSignature());
		retained.get().add(CVMLong.create(99));
		assertEquals(Sets.of(42,7),live.items(),"Escaped children remain attached to private storage");
		int before=stamps.get();
		live.transact(draft -> draft.add(42));
		assertEquals(before,stamps.get());
		assertEquals(1,signatures.get());
		assertSame(signed,app.component().get(ALICE.getAccountKey()));
		assertTrue(host.cursor().get().isEmpty(),"Local commit does not publish the containing region");
	}

	@Test void callbackOrSigningFailureDiscardsTheWholeAttempt() {
		var app=new FavouritesApp(LatticeHost.create(),() -> CVMLong.ONE);
		app.user(ALICE).add(1);
		var original=app.component().get(ALICE.getAccountKey());
		var signatures=new AtomicInteger();
		var key=new AtomicReference<>(ALICE);
		var live=new Favourites(app.component().path(ALICE.getAccountKey()),
			app.component().signingFor(ALICE.getAccountKey()),value -> {
				signatures.incrementAndGet();
				return key.get()==null?null:key.get().signData(value);
			},() -> CVMLong.TWO);
		assertThrows(IllegalArgumentException.class,() -> live.transact(draft -> {
			draft.remove(1);
			draft.add(2);
			throw new IllegalArgumentException("abort");
		}));
		assertEquals(0,signatures.get());
		assertSame(original,app.component().get(ALICE.getAccountKey()));
		key.set(null);
		assertThrows(IllegalStateException.class,() -> live.transact(draft -> {
			draft.remove(1);
			draft.add(2);
		}));
		assertSame(original,app.component().get(ALICE.getAccountKey()));
		key.set(ALICE);
		live.transact(draft -> { draft.remove(1); draft.add(2); });
		assertEquals(Sets.of(2),live.items());
		assertTrue(app.component().get(ALICE.getAccountKey()).checkSignature());
	}

	@Test void concurrentDomainEditIsPreservedByReexecutingTheTransaction() throws Exception {
		var app=new FavouritesApp(LatticeHost.create(),() -> CVMLong.ONE);
		var live=app.user(ALICE);
		var read=new CountDownLatch(1);
		var changed=new CountDownLatch(1);
		var calls=new AtomicInteger();
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var writer=executor.submit(() -> {
				await(read);
				try { app.user(ALICE).add(99); }
				finally { changed.countDown(); }
			});
			live.transact(draft -> {
				draft.add(42);
				draft.add(7);
				if (calls.incrementAndGet()==1) {
					read.countDown();
					await(changed);
				}
			});
			writer.get(10,TimeUnit.SECONDS);
		}
		assertEquals(2,calls.get());
		assertEquals(Sets.of(99,42,7),live.items(),
			"Local transactions preserve concurrent operations even when replica merge selects a whole record");
	}

	@Test void transactionOnAForkCommitsPrivatelyUntilExplicitSync() {
		var app=new FavouritesApp(LatticeHost.create(),() -> CVMLong.ONE);
		var live=app.user(ALICE);
		var fork=live.fork();
		fork.transact(draft -> {
			draft.add(42);
			draft.transact(nested -> nested.add(7));
			draft.sync(); // The private transaction root has no publication destination.
		});
		assertEquals(Sets.of(42,7),fork.items());
		assertNull(app.component().get(ALICE.getAccountKey()));
		fork.sync();
		assertEquals(fork.items(),live.items());
		assertTrue(app.component().get(ALICE.getAccountKey()).checkSignature());
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(10,TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
