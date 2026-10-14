package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeContext;
import convex.lattice.RootComponent;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.SetLattice;

class CursorPersistenceTest {
	private static class CountingStore extends MemoryStore {
		int writes;
		int flushes;
		@Override public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref,int status,
				Consumer<Ref<ACell>> novelty) {
			writes++;
			return super.storeTopRef(ref,status,novelty);
		}
		@Override public void flush() { flushes++; }
	}

	@Test void signedPathsAndForksPersistWithoutSigningOrPublishing() throws Exception {
		try (var store=new CountingStore()) {
			var host=RootComponent.create(OwnerKeyLattice.create(SetLattice.<ACell>create()),store);
			var key=AKeyPair.createSeeded(1301);
			// No signing capability: persistence must not attempt to sign anything.
			var live=host.cursor().view().<ASet<ACell>>path(key.getAccountKey(),Keywords.VALUE);
			var before=host.cursor().get();
			var fork=live.fork();
			fork.set(Sets.of(1));
			var pending=fork.get();
			assertEquals(pending,fork.persist(pending));
			assertEquals(1,store.writes);
			assertSame(before,host.cursor().get());
			assertSame(pending,fork.get());
			assertNull(store.getRootData());

			var plainPath=PathCursor.<ASet<ACell>,ASet<ACell>>create(fork,new ACell[0]);
			assertEquals(pending,plainPath.checkpoint(pending));
			plainPath.flush();
			assertEquals(2,store.writes);
			assertEquals(1,store.flushes);
			assertNull(store.getRootData());
			assertSame(before,host.cursor().get());

			// Restoring signing enables merge-back; it does not persist or publish.
			host.cursor().setContext(LatticeContext.create(null,key));
			fork.sync();
			assertEquals(pending,live.get());
			assertEquals(2,store.writes);
			assertNull(store.getRootData());
		}
	}

	@Test void transactionPersistenceUsesHostAndCannotEscapeCallback() throws Exception {
		try (var store=new CountingStore();var executor=Executors.newSingleThreadExecutor()) {
			var host=RootComponent.create(SetLattice.<ACell>create(),store);
			var before=host.cursor().get();
			AtomicReference<ALatticeCursor<ASet<ACell>>> escaped=new AtomicReference<>();
			var failure=new IllegalArgumentException("abort");
			assertSame(failure,assertThrows(IllegalArgumentException.class,() -> host.cursor().transact(draft -> {
				escaped.set(draft);
				draft.set(Sets.of(2));
				try {
					assertEquals(draft.get(),draft.persist(draft.get()));
					assertTrue(executor.submit(() -> {
						assertThrows(IllegalStateException.class,() -> draft.persist(CVMLong.ONE));
						return true;
					}).get(5,TimeUnit.SECONDS));
				} catch (Exception e) {
					throw new AssertionError(e);
				}
				throw failure;
			})));
			assertEquals(1,store.writes,"Rollback leaves already-checkpointed immutable cells");
			assertSame(before,host.cursor().get());
			assertNull(store.getRootData());
			assertThrows(IllegalStateException.class,() -> escaped.get().persist(CVMLong.ONE));
			assertThrows(IllegalStateException.class,() -> escaped.get().checkpoint(CVMLong.ONE));
			assertThrows(IllegalStateException.class,() -> escaped.get().flush());
		}
	}

	@Test void unhostedCursorCheckpointsAreOptionalButExplicitPersistenceFails() throws Exception {
		var cursor=Cursors.createLattice(SetLattice.create());
		var data=Blob.fromHex("1234");
		assertSame(data,cursor.checkpoint(data));
		assertThrows(IllegalStateException.class,() -> cursor.persist(data));
		assertThrows(IllegalStateException.class,cursor::flush);
	}
}
