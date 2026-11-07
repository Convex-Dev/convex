package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;

class AccessListTest {

	private static final AKeyPair KEY=AKeyPair.createSeeded(8642);
	private static final Keyword SERVICE=Keyword.intern("service");
	private static final Keyword LISTED=Keyword.intern("listed");
	private static final Keyword CUSTOM=Keyword.intern("custom");

	private static final Blob A=Blob.fromHex("a1");
	private static final Blob B=Blob.fromHex("b2");
	private static final Blob C=Blob.fromHex("c3");
	private static final Blob D=Blob.fromHex("d4");
	private static final Blob P=Blob.fromHex("e5");

	private final AtomicLong time=new AtomicLong(10);

	private AccessList<ABlobLike<?>> list(Root<Index<ABlobLike<?>,ACell>> root) {
		return new AccessList<ABlobLike<?>>(root,() -> CVMLong.create(time.get()));
	}

	private static AHashMap<Keyword,ACell> until(long t) {
		return Maps.of(AccessList.UNTIL,CVMLong.create(t));
	}

	/** A bare entry lists a key until it is removed; reads and no-op edits create nothing. */
	@Test void aBareEntryListsAKeyUntilRemoved() {
		var root=new Root<Index<ABlobLike<?>,ACell>>();
		var list=list(root);
		assertFalse(list.contains(A));
		assertNull(list.get(A));
		assertFalse(list.remove(A));
		assertNull(root.get());

		assertTrue(list.add(A));
		assertTrue(list.contains(A));
		assertEquals(Maps.empty(),list.get(A));
		assertFalse(list.add(A),"already listed");
		assertFalse(list.contains(B));

		assertTrue(list.remove(A));
		assertFalse(list.contains(A));
		assertFalse(list.remove(A));
	}

	/** Entries are stored as given, unknown fields included, and replaced whole. */
	@Test void entriesAreKeptAsGiven() {
		var list=list(new Root<>());
		AHashMap<Keyword,ACell> entry=Maps.of(AccessList.REASON,Strings.create("spam"),
			AccessList.SINCE,CVMLong.create(5),CUSTOM,CVMLong.ONE);
		assertTrue(list.add(A,entry));
		assertEquals(entry,list.get(A));
		assertFalse(list.add(A,Maps.of(CUSTOM,CVMLong.TWO)));
		assertEquals(Maps.of(CUSTOM,CVMLong.TWO),list.get(A));
	}

	/** Any stored value lists its key; one that is not a record reads as a bare entry. */
	@Test void aStoredValueThatIsNotARecordStillLists() {
		var root=new Root<Index<ABlobLike<?>,ACell>>(Index.<ABlobLike<?>,ACell>of(A,CVMLong.ONE));
		var list=list(root);
		assertTrue(list.contains(A));
		assertEquals(Maps.empty(),list.get(A));
	}

	/** Keys match by their bytes: a string and a blob with the same bytes are one key. */
	@Test void aStringAndABlobWithTheSameBytesAreOneKey() {
		var list=list(new Root<>());
		list.add(Strings.create("host.example"));
		assertTrue(list.contains(Blob.wrap("host.example".getBytes())));
		assertTrue(list.remove(Blob.wrap("host.example".getBytes())));
		assertFalse(list.contains(Strings.create("host.example")));
	}

	/** An entry reads as absent from its {@code :until}; removing it then reports nothing listed. */
	@Test void anExpiredEntryReadsAsAbsent() {
		var root=new Root<Index<ABlobLike<?>,ACell>>();
		var list=list(root);
		assertTrue(list.add(A,until(20)));
		time.set(19);
		assertTrue(list.contains(A));
		time.set(20);
		assertFalse(list.contains(A));
		assertNull(list.get(A));
		assertTrue(root.get().containsKey(A),"stored until explicitly removed");
		assertTrue(list.add(A,until(30)),"an expired key is listed anew");
		time.set(30);
		assertFalse(list.remove(A),"an expired entry does not count as listed");
		assertFalse(root.get().containsKey(A));
	}

	/** An {@code :until} that is not an integer is ignored: the entry stays live. */
	@Test void anUntilThatIsNotAnIntegerIsIgnored() {
		var list=list(new Root<>());
		list.add(A,Maps.of(AccessList.UNTIL,Strings.create("never")));
		list.add(B,until(50));
		time.set(1000);
		assertTrue(list.contains(A));
		assertFalse(list.contains(B));
	}

	/** Adding entries leaves cleanup and capacity policy to the application. */
	@Test void addingEntriesNeverEvictsStoredEntries() {
		var root=new Root<Index<ABlobLike<?>,ACell>>();
		var list=list(root);
		time.set(0);
		list.add(P);
		list.add(A,until(100));
		list.add(B,until(50));
		list.add(C,until(200));
		assertTrue(list.contains(A));
		assertTrue(list.contains(B));
		assertTrue(list.contains(C));
		assertTrue(list.contains(P));

		time.set(150);
		list.add(D,until(300));
		assertTrue(root.get().containsKey(A),"expired entries remain stored");
		assertTrue(root.get().containsKey(B));
		assertFalse(list.contains(A));
		assertTrue(list.contains(C));
		assertTrue(list.contains(D));
		assertTrue(list.contains(P));
		for (int i=0; i<1025; i++) list.add(Strings.create("key"+i),until(Long.MAX_VALUE));
		assertEquals(1030,root.get().count(),"no implicit capacity or maximum-expiry special case");
	}

	/** Replacing an expiring entry, or adding permanent ones, never drops another entry. */
	@Test void replacingOrAddingPermanentEntriesDropsNothing() {
		var root=new Root<Index<ABlobLike<?>,ACell>>();
		var list=list(root);
		list.add(A,until(100));
		list.add(B,until(50));
		list.add(B,until(60));
		for (int i=0; i<10; i++) list.add(Blob.wrap(new byte[] {(byte)i}));
		assertEquals(12,root.get().count());
		assertTrue(list.contains(A));
		assertTrue(list.contains(B));
		var string=Strings.create("host.example");
		list.add(string,until(100));
		assertFalse(list.add(string.toBlob(),until(200)));
		assertEquals(until(200),list.get(string));
		assertEquals(13,root.get().count());
	}

	@Test void cleanupUsesTheCallersPredicate() {
		var root=new Root<Index<ABlobLike<?>,ACell>>();
		var list=list(root);
		list.add(A,until(20));
		list.add(B,until(30));
		list.add(P);
		time.set(20);
		long cutoff=time.get();
		assertEquals(1,list.removeIf((key,value) -> value instanceof AHashMap<?,?> entry
			&& AInteger.isLong(entry.get(AccessList.UNTIL))
			&& ((AInteger)entry.get(AccessList.UNTIL)).longValue()<=cutoff));
		assertFalse(root.get().containsKey(A));
		assertTrue(list.contains(B));
		assertTrue(list.contains(P));
		assertEquals(1,list.removeIf((key,value) -> key.equals(P)));
		assertFalse(root.get().containsKey(P));
	}

	@Test void cleanupSeesRawValuesAndDoesNotReadTheClock() {
		var root=Root.create(Index.<ABlobLike<?>,ACell>of(A,null,B,CVMLong.ONE,C,until(0),P,Maps.empty()));
		var list=new AccessList<>(root,() -> { throw new AssertionError("Cleanup must not read the clock"); });
		assertEquals(2,list.removeIf((key,value) -> value==null || value instanceof CVMLong));
		assertEquals(Index.of(C,until(0),P,Maps.empty()),root.get());
		assertEquals(2,list.removeIf((key,value) -> true));
		assertTrue(root.get().isEmpty());
	}

	@Test void cleanupNoOpsAndFailuresPreserveStorage() {
		var root=new Root<Index<ABlobLike<?>,ACell>>();
		var list=list(root);
		assertEquals(0,list.removeIf((key,value) -> { throw new AssertionError("No entries"); }));
		assertNull(root.get());
		assertThrows(NullPointerException.class,() -> list.removeIf(null));
		list.add(A);
		list.add(B);
		var before=root.get();
		assertEquals(0,list.removeIf((key,value) -> false));
		assertSame(before,root.get());
		assertThrows(IllegalArgumentException.class,() -> list.removeIf((key,value) -> {
			if (key.equals(B)) throw new IllegalArgumentException("Predicate failed");
			return true;
		}));
		assertSame(before,root.get(),"a predicate failure must not commit partial removals");
	}

	@Test void cleanupRetriesPreserveConcurrentChangesAndReturnCommittedCount() throws Exception {
		var root=Root.create(Index.<ABlobLike<?>,ACell>of(A,Maps.empty(),B,Maps.empty()));
		var list=list(root);
		var entered=new CountDownLatch(1);
		var resume=new CountDownLatch(1);
		var first=new AtomicBoolean(true);
		try (var executor=Executors.newSingleThreadExecutor()) {
			var result=executor.submit(() -> list.removeIf((key,value) -> {
				if (first.getAndSet(false)) {
					entered.countDown();
					try { assertTrue(resume.await(10,TimeUnit.SECONDS)); }
					catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
				}
				return key.equals(A) || key.equals(B);
			}));
			try {
				assertTrue(entered.await(10,TimeUnit.SECONDS));
				list.remove(B);
				list.add(C);
			} finally { resume.countDown(); }
			assertEquals(1L,result.get(10,TimeUnit.SECONDS));
		}
		assertEquals(Index.of(C,Maps.empty()),root.get());
	}

	/** Keys longer than an Index matches exactly are refused, so they never alias a stored key. */
	@Test void keysTooLongToMatchExactlyAreRefused() {
		var list=list(new Root<>());
		byte[] bytes=new byte[256];
		Arrays.fill(bytes,(byte)7);
		Blob longest=Blob.wrap(bytes,0,255);
		Blob tooLong=Blob.wrap(bytes);
		assertTrue(list.add(longest));
		assertTrue(list.contains(longest));
		assertFalse(list.contains(tooLong),"shares the first 255 bytes but is not the same key");
		assertThrows(IllegalArgumentException.class,() -> list.add(tooLong));
		assertFalse(list.remove(tooLong));
		assertTrue(list.contains(longest));
	}

	/** Storage of another shape is an error, never an empty list, and is left unchanged. */
	@Test void storageThatIsNotAnIndexIsRefused() {
		@SuppressWarnings({"unchecked","rawtypes"})
		var root=(Root<Index<ABlobLike<?>,ACell>>)(Root)Root.create(CVMLong.ONE);
		var list=list(root);
		assertThrows(IllegalStateException.class,() -> list.contains(A));
		assertThrows(IllegalStateException.class,() -> list.add(A));
		assertThrows(IllegalStateException.class,() -> list.remove(A));
		assertThrows(IllegalStateException.class,() -> list.removeIf((key,value) -> true));
		assertEquals(CVMLong.ONE,root.get());
	}

	/** A fork edits independently and publishes on sync. */
	@Test void aForkPublishesOnSync() {
		var root=new Root<Index<ABlobLike<?>,ACell>>();
		var list=list(root);
		var fork=list.fork();
		fork.add(A);
		assertTrue(fork.contains(A));
		assertFalse(list.contains(A));
		fork.sync();
		assertTrue(list.contains(A));
		assertEquals(1,fork.removeIf((key,value) -> true));
		assertTrue(list.contains(A),"cleanup remains private until sync");
		fork.sync();
		assertFalse(list.contains(A));
	}

	/** A minimal service record at {@code [:local owner :service]}, with an access list beside other state. */
	private final class Service extends ALocalComponent {
		final AccessList<ABlobLike<?>> listed;

		Service(AForkableCursor<Index<Keyword,ACell>> root) {
			super(root,SERVICE,KEY.getAccountKey(),KEY::signData,() -> CVMLong.create(time.get()),null);
			listed=new AccessList<ABlobLike<?>>(new MapComponent<Keyword,ACell>(cursor())
				.<Index<ABlobLike<?>,ACell>>path(LISTED),() -> CVMLong.create(time.get()));
		}
	}

	/**
	 * Within a {@code :local} record the list is replaced whole with the record: a
	 * removal survives a restart, and an older copy of the record cannot bring it back.
	 */
	@Test void aRemovalCarriesOverInALocalRecord() {
		var host=LatticeHost.create();
		var service=new Service(host.cursor());
		service.listed.add(A);
		service.listed.add(B);
		service.sync();
		AHashMap<Keyword,ACell> older=service.cursor().get();

		time.set(20);
		var restarted=new Service(host.cursor());
		assertTrue(restarted.listed.contains(A));
		assertTrue(restarted.listed.remove(A));
		restarted.sync();

		time.set(30);
		var again=new Service(host.cursor());
		assertFalse(again.listed.contains(A));
		assertTrue(again.listed.contains(B));
		again.merge(older);
		assertFalse(again.listed.contains(A),"the older record does not revive a removed key");
	}
}
