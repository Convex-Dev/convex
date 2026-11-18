package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
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
		// The field is the record's entry, which its first changed write creates
		AForkableCursor<ACell> field=new MapComponent<>(component.cursor()).path(A);
		field.set(null);
		component.sync();
		component.merge(null);
		assertNull(root.get());
		assertEquals(0,calls.get());
		field.set(CVMLong.ONE);
		assertEquals(record(1,1),root.get());
		var stored=root.get();
		field.set(CVMLong.ONE);
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

	@Test void partialDeliveriesAreIgnoredWhetherOrNotARecordIsHeld() {
		// The record's slot is a key its parent does not hold yet, so it is absent
		Root<AHashMap<Keyword,ACell>> parent=Root.create(Maps.empty());
		AForkableCursor<AHashMap<Keyword,ACell>> slot=new MapComponent<>(parent).path(B);
		var component=Timestamped.create(slot,() -> {
			throw new AssertionError("Incoming merges must not read the clock");
		});
		assertFalse(slot.exists());
		assertNull(component.mergeAt(CVMLong.create(100),A));
		assertFalse(slot.exists());
		var held=record(1,1);
		component.merge(held);
		assertSame(held,component.mergeAt(CVMLong.create(100),A));
		assertSame(held,slot.get());
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

	private static List<AInteger> integerRepresentations(BigInteger value) {
		return List.of(AInteger.create(value),CVMBigInteger.wrap(value),CVMBigInteger.wrap(value.toByteArray()));
	}

	@Test void timestampOrderingIsIndependentOfIntegerRepresentation() {
		var rule=Timestamped.lattice();
		long[] times={Long.MIN_VALUE,-1,0,1,Long.MAX_VALUE};
		for (long ownTime:times) {
			for (long otherTime:times) {
				for (var ownTimestamp:integerRepresentations(BigInteger.valueOf(ownTime))) {
					for (var otherTimestamp:integerRepresentations(BigInteger.valueOf(otherTime))) {
						AHashMap<Keyword,ACell> own=Maps.of(TS,ownTimestamp,A,CVMLong.ONE);
						AHashMap<Keyword,ACell> other=Maps.of(TS,otherTimestamp,A,CVMLong.TWO);
						assertSame(ownTimestamp,own.get(TS));
						assertSame(otherTimestamp,other.get(TS));
						assertTrue(rule.checkForeign(own));
						assertTrue(rule.checkForeign(other));
						assertSame(other,rule.merge(null,other));
						assertSame(otherTime>ownTime?other:own,rule.merge(own,other));
					}
				}
			}
		}
	}

	@Test void invalidTimestampsNeverCompeteWithValidRecords() {
		var rule=Timestamped.lattice();
		var valid=record(1,1);
		ACell[] invalid={null,Strings.create("2"),CVMBool.TRUE,CVMDouble.create(2),
			CVMDouble.NaN,Blob.fromHex("02"),Vectors.of(2),Maps.empty(),
			CVMBigInteger.wrap(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
			CVMBigInteger.wrap(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toByteArray()),
			CVMBigInteger.wrap(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)),
			CVMBigInteger.wrap(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE).toByteArray())};
		for (ACell timestamp:invalid) {
			var malformed=record(2,2).assoc(TS,timestamp);
			assertFalse(rule.checkForeign(malformed));
			assertNull(rule.merge(null,malformed));
			assertSame(valid,rule.merge(valid,malformed));
			assertSame(valid,rule.merge(malformed,valid));
			var root=new Root<AHashMap<Keyword,ACell>>();
			var component=Timestamped.create(root.withDefault(Maps.empty()),() -> {
				throw new AssertionError("Incoming merges must not read the clock");
			});
			component.mergeAt(malformed);
			assertNull(root.get());
			root.set(valid);
			assertSame(valid,component.mergeAt(malformed));
			assertSame(valid,root.get());
		}
	}

	@Test void retriesStampFromALaterClockReadAndPreserveConcurrentFields() {
		var root=Root.create(record(1,0));
		var time=new AtomicInteger(1);
		var component=Timestamped.create(root,() -> {
			int now=time.incrementAndGet();
			if (now==2) root.set(root.get().assoc(B,CVMLong.ONE)); // Force the first candidate to lose its CAS.
			return CVMLong.create(now);
		});
		component.cursor().path(A).set(CVMLong.ONE);
		assertEquals(record(3,1).assoc(B,CVMLong.ONE),root.get());
	}

	@Test void customRecordRepresentationSuppliesItsStampAndOrderingRule() {
		var root=new Root<Index<Keyword,ACell>>();
		var ordering=convex.lattice.generic.LWWLattice.<Index<Keyword,ACell>>create(
			value -> ((CVMLong)value.get(TS)).longValue());
		var component=new Timestamped<>(root.withDefault(Index.none()),ordering,
			() -> CVMLong.ONE,(value,time) -> value.assoc(TS,time));
		component.cursor().assoc(A,CVMLong.ONE);
		assertEquals(Index.of(TS,CVMLong.ONE,A,CVMLong.ONE),root.get());
		var remote=Index.<Keyword,ACell>of(TS,CVMLong.TWO,B,CVMLong.ONE);
		component.merge(remote);
		assertSame(remote,root.get());
	}

	@Test void enclosingPublicationUsesTheSameOrderingWithoutRestamping() {
		var root=Root.create(record(1,0));
		var staged=new LatticeComponent<>(Timestamped.lattice(),root.fork(),LatticeContext.EMPTY);
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
		component.cursor().assoc(A,CVMLong.ONE);
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
