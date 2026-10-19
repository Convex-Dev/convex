package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.StampedCursor;
import convex.lattice.generic.OwnerKeyLattice;

class TimestampedTest {

	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final Keyword TS=Timestamped.TIMESTAMP;

	private static AHashMap<Keyword,ACell> record(long time,long value) {
		return Maps.of(TS,CVMLong.create(time),A,CVMLong.create(value));
	}

	@Test void constructionReadsSyncAndNoOpEditsAreInert() {
		var root=new Root<AHashMap<Keyword,ACell>>();
		var calls=new AtomicInteger();
		var component=Timestamped.create(root.withDefault(Maps.empty()),
			() -> CVMLong.create(calls.incrementAndGet()));
		assertInstanceOf(StampedCursor.class,component.cursor());
		assertTrue(component.cursor().get().isEmpty());
		component.cursor().set(Maps.empty());
		component.cursor().path(A).set(null);
		component.sync();
		component.merge(null);
		assertNull(root.get());
		assertEquals(0,calls.get());
		component.cursor().path(A).set(CVMLong.ONE);
		assertEquals(record(1,1),root.get());
		var stored=root.get();
		component.cursor().path(A).set(CVMLong.ONE);
		component.sync();
		assertSame(stored,root.get());
		assertEquals(1,calls.get());
	}

	@Test void wholeRecordSelectionIsIdempotentAndPreservesDeletionAndOwnTies() {
		var rule=Timestamped.lattice();
		var older=record(1,1).assoc(B,CVMLong.ONE);
		var newer=record(2,2);
		var tied=record(2,3);
		assertSame(newer,rule.merge(older,newer));
		assertSame(newer,rule.merge(newer,older));
		assertSame(newer,rule.merge(newer,newer));
		assertSame(newer,rule.merge(newer,tied));
		assertSame(tied,rule.merge(tied,newer));
		assertSame(newer,rule.merge(newer,null));
		var root=new Root<AHashMap<Keyword,ACell>>();
		var component=Timestamped.create(root.withDefault(Maps.empty()),() -> {
			throw new AssertionError("Incoming merges must not read the clock");
		});
		for (var incoming:List.of(older,newer,older,newer,tied)) component.merge(incoming);
		assertSame(newer,root.get());
		assertNull(root.get().get(B));
		component.mergeAt(CVMLong.create(100),A);
		assertSame(newer,root.get(),"a whole-record register cannot accept partial deliveries");
		assertSame(rule,component.getLattice());
	}

	@Test void malformedRecordsAreIgnoredAndAllLongTimestampsAreSupported() {
		var root=new Root<AHashMap<Keyword,ACell>>();
		var component=Timestamped.create(root.withDefault(Maps.empty()),() -> CVMLong.ZERO);
		component.merge(Maps.empty());
		component.merge(Maps.of(TS,Strings.create("invalid")));
		assertNull(root.get());
		var oldest=record(Long.MIN_VALUE,1);
		component.merge(oldest);
		assertSame(oldest,root.get(),"an unstamped default does not compete with a valid record");
		component.merge(Maps.of(TS,CVMLong.create(100).toString()));
		assertSame(oldest,root.get());
		var newest=record(Long.MAX_VALUE,2);
		component.merge(newest);
		assertSame(newest,root.get());
	}

	@Test void retriesKeepOneTimestampAndPreserveConcurrentFields() {
		var root=Root.create(record(1,0));
		var calls=new AtomicInteger();
		var component=Timestamped.create(root,() -> {
			calls.incrementAndGet();
			root.set(root.get().assoc(B,CVMLong.ONE)); // Force the first candidate to lose its CAS.
			return CVMLong.create(2);
		});
		component.cursor().path(A).set(CVMLong.ONE);
		assertEquals(record(2,1).assoc(B,CVMLong.ONE),root.get());
		assertEquals(1,calls.get());
	}

	@Test void customRecordRepresentationSuppliesItsStampAndOrderingRule() {
		var root=new Root<Index<Keyword,ACell>>();
		var ordering=convex.lattice.generic.LWWLattice.<Index<Keyword,ACell>>create(
			value -> ((CVMLong)value.get(TS)).longValue());
		var component=new Timestamped<>(root.withDefault(Index.none()),ordering,
			() -> CVMLong.ONE,(value,time) -> value.assoc(TS,time));
		component.cursor().path(A).set(CVMLong.ONE);
		assertEquals(Index.of(TS,CVMLong.ONE,A,CVMLong.ONE),root.get());
		var remote=Index.<Keyword,ACell>of(TS,CVMLong.TWO,B,CVMLong.ONE);
		component.merge(remote);
		assertSame(remote,root.get());
	}

	@Test void enclosingPublicationUsesTheSameOrderingWithoutRestamping() {
		var root=Root.create(record(1,0));
		var staged=new LatticeComponent<>(Timestamped.lattice(),root,LatticeContext.EMPTY);
		var calls=new AtomicInteger();
		var component=Timestamped.create(staged.cursor(),() -> {
			calls.incrementAndGet();
			return CVMLong.TWO;
		});
		component.cursor().path(A).set(CVMLong.ONE);
		component.sync();
		assertEquals(record(1,0),root.get(),"the wrapper does not publish its supplied working cursor");
		var remote=record(3,2);
		root.set(remote);
		staged.sync();
		assertSame(remote,root.get());
		assertSame(remote,component.cursor().get());
		assertSame(staged.getLattice(),component.getLattice());
		assertEquals(1,calls.get());
	}

	@Test void signingCoversTheTimestampAndIncomingEnvelopesAreRetained() {
		var key=AKeyPair.createSeeded(7440);
		var rule=OwnerKeyLattice.create(Timestamped.lattice()).signingFor(key.getAccountKey());
		var root=new Root<SignedData<AHashMap<Keyword,ACell>>>();
		var signatures=new AtomicInteger();
		var signed=new ASignedComponent<>(root,rule,value -> {
			signatures.incrementAndGet();
			return key.signData(value);
		}) {};
		var component=Timestamped.create(signed.cursor().withDefault(Maps.empty()),() -> CVMLong.ONE);
		component.cursor().path(A).set(CVMLong.ONE);
		assertEquals(record(1,1),root.get().getValue());
		assertTrue(root.get().checkSignature());
		var remote=key.signData(record(2,2));
		signed.merge(remote);
		assertSame(remote,root.get());
		assertSame(remote.getValue(),component.cursor().get());
		assertSame(rule.getValueLattice(),component.getLattice());
		assertEquals(1,signatures.get(),"adopting a remote envelope neither stamps nor signs");
		signed.merge(key.signData(Maps.of(A,CVMLong.create(100))));
		assertSame(remote,root.get(),"signed payloads must still have a valid timestamp");
		signed.mergeAt(key.signData(Index.of(TS,CVMLong.create(100),A,CVMLong.ONE)));
		assertSame(remote,root.get(),"the standard map rule rejects a different container representation");
	}
}
