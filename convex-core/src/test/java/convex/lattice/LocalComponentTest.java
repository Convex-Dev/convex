package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Blobs;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.cursor.AForkableCursor;

class LocalComponentTest {

	private static final AKeyPair KEY=AKeyPair.createSeeded(7531);
	private static final Keyword ALPHA=Keyword.intern("alpha");
	private static final Keyword BETA=Keyword.intern("beta");
	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final Keyword TS=Timestamped.TIMESTAMP;

	/** A minimal service whose signer counts signatures and whose clock the test sets. */
	private static final class Notes extends ALocalComponent {
		Notes(AForkableCursor<Index<Keyword,ACell>> root,Keyword service,AtomicInteger signatures,AtomicLong time) {
			this(root,service,signatures,time,null);
		}

		Notes(AForkableCursor<Index<Keyword,ACell>> root,Keyword service,AtomicInteger signatures,AtomicLong time,
				Persister<AHashMap<Keyword,ACell>> persister) {
			super(root,service,KEY.getAccountKey(),value -> {
				signatures.incrementAndGet();
				return KEY.signData(value);
			},() -> CVMLong.create(time.get()),persister);
		}

		void put(Keyword key,long value) {
			put(key,CVMLong.create(value));
		}

		void put(Keyword key,ACell value) {
			cursor().path(key).set(value);
		}
	}

	@SuppressWarnings("unchecked")
	private static SignedData<AHashMap<Keyword,ACell>> slot(Index<Keyword,ACell> root) {
		var local=(AHashMap<ACell,SignedData<AHashMap<Keyword,ACell>>>)root.get(LocalLattice.KEY_LOCAL);
		return LocalLattice.getSignedSlot(local,KEY.getAccountKey());
	}

	@Test void editsStayPrivateUntilSync() {
		var host=LatticeHost.create();
		var before=host.cursor().get();
		var signatures=new AtomicInteger();
		var time=new AtomicLong(10);
		var notes=new Notes(host.cursor(),ALPHA,signatures,time);
		notes.sync();
		notes.put(A,1);
		time.set(11);
		notes.put(B,2);
		assertSame(before,host.cursor().get());
		assertEquals(0,signatures.get());
		assertEquals(Maps.of(TS,CVMLong.create(11),A,CVMLong.create(1),B,CVMLong.create(2)),notes.cursor().get());
	}

	@Test void syncSignsOnceAndOnlyWhenChanged() {
		var host=LatticeHost.create();
		var signatures=new AtomicInteger();
		var notes=new Notes(host.cursor(),ALPHA,signatures,new AtomicLong(10));
		notes.put(A,1);
		notes.put(B,2);
		notes.put(A,3);
		notes.sync();
		assertEquals(1,signatures.get());
		var signed=slot(host.cursor().get());
		assertTrue(signed.checkSignature());
		assertEquals(KEY.getAccountKey(),signed.getAccountKey());
		assertEquals(Maps.of(TS,CVMLong.create(10),A,CVMLong.create(3),B,CVMLong.create(2)),signed.getValue().get(ALPHA));

		var root=host.cursor().get();
		notes.sync();
		notes.put(A,3);
		notes.sync();
		assertEquals(1,signatures.get(),"a sync with nothing changed signs nothing");
		assertSame(root,host.cursor().get());
	}

	@Test void restoredRecordWinsOnlyIfNewer() {
		var host=LatticeHost.create();
		var signatures=new AtomicInteger();
		var notes=new Notes(host.cursor(),ALPHA,signatures,new AtomicLong(10));
		notes.put(A,1);
		notes.sync();
		var current=notes.cursor().get();

		notes.merge(Maps.of(TS,CVMLong.create(5),A,CVMLong.create(99)));
		assertEquals(current,notes.cursor().get(),"an older backup does not replace newer state");

		AHashMap<Keyword,ACell> newer=Maps.of(TS,CVMLong.create(20),A,CVMLong.create(2));
		notes.merge(newer);
		assertEquals(newer,notes.cursor().get(),"a newer backup replaces stale state");
		assertEquals(current,slot(host.cursor().get()).getValue().get(ALPHA),"merging does not publish");
		notes.sync();
		assertEquals(2,signatures.get());
		assertEquals(newer,slot(host.cursor().get()).getValue().get(ALPHA));

		notes.mergeAt(CVMLong.ONE);
		notes.mergeAt(newer.assoc(TS,CVMLong.create(30)),A);
		assertEquals(newer,notes.cursor().get(),"only a complete record at this level is taken");
	}

	@Test void servicesShareTheOwnersSlot() {
		var host=LatticeHost.create();
		var signatures=new AtomicInteger();
		var time=new AtomicLong(10);
		var alpha=new Notes(host.cursor(),ALPHA,signatures,time);
		var beta=new Notes(host.cursor(),BETA,signatures,time);
		alpha.put(A,1);
		beta.put(A,2);
		alpha.sync();
		beta.sync();
		assertEquals(2,signatures.get());
		var signed=slot(host.cursor().get());
		assertTrue(signed.checkSignature());
		assertEquals(alpha.cursor().get(),signed.getValue().get(ALPHA));
		assertEquals(beta.cursor().get(),signed.getValue().get(BETA));
	}

	@Test void newerRecordWinsWhicheverCopySyncsFirst() {
		for (boolean newerFirst:new boolean[] {true,false}) {
			var host=LatticeHost.create();
			var signatures=new AtomicInteger();
			var older=new Notes(host.cursor(),ALPHA,signatures,new AtomicLong(1));
			var newer=new Notes(host.cursor(),ALPHA,signatures,new AtomicLong(2));
			older.put(A,1);
			newer.put(A,2);
			if (newerFirst) {
				newer.sync();
				older.sync();
			} else {
				older.sync();
				newer.sync();
			}
			assertEquals(newer.cursor().get(),slot(host.cursor().get()).getValue().get(ALPHA));
			older.sync();
			assertEquals(newer.cursor().get(),older.cursor().get());
		}
	}

	@Test void persistWritesThePrivateCopyToTheHostsStoreWithoutSigning() throws IOException {
		var store=new MemoryStore();
		var host=LatticeHost.create(store);
		var root=host.cursor().get();
		var signatures=new AtomicInteger();
		var notes=new Notes(host.cursor(),ALPHA,signatures,new AtomicLong(10),host::persist);
		ABlob blob=Blobs.createRandom(new Random(7531),1000);
		notes.put(A,blob);

		var persisted=notes.persist();
		assertNotNull(store.refForHash(blob.getHash()));
		assertEquals(0,signatures.get());
		assertSame(root,host.cursor().get());
		assertNull(store.getRootData());

		notes.sync();
		assertEquals(1,signatures.get());
		assertEquals(persisted,slot(host.cursor().get()).getValue().get(ALPHA));
	}

	@Test void persistNeedsAFunction() {
		var notes=new Notes(LatticeHost.create().cursor(),ALPHA,new AtomicInteger(),new AtomicLong(10));
		assertThrows(UnsupportedOperationException.class,notes::persist);
	}
}
