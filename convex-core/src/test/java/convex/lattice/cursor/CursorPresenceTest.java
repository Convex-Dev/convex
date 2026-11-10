package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.MapComponent;
import convex.lattice.PathWriteException;

/** Required reads, explicit absence, and composition over one immutable snapshot. */
class CursorPresenceTest {
	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final ACell FALLBACK=Strings.create("fallback");

	@Test void nilIsPresentAtRootsAndInContainers() {
		Root<ACell> root=Root.create(null);
		assertTrue(root.exists());
		assertNull(root.get());
		assertNull(root.getOrDefault(FALLBACK));
		for (ACell value:new ACell[] {Maps.of(A,null),Index.of(A,null)}) {
			root.set(value);
			var entry=root.path(A);
			assertTrue(entry.exists());
			assertNull(entry.get());
			assertNull(entry.getOrDefault(FALLBACK));
			assertNull(root.getOrDefault(FALLBACK,A));
		}
	}

	@Test void missingReadsAreStrictAndOptionalReadsDoNotThrow() {
		var root=Root.<ACell>create(Maps.empty());
		var missing=root.path(A);
		assertFalse(missing.exists());
		assertThrows(MissingValueException.class,missing::get);
		assertSame(FALLBACK,missing.getOrDefault(FALLBACK));
		assertSame(FALLBACK,root.getOrDefault(FALLBACK,A,B));
		assertFalse(missing.path(B).exists());
		assertThrows(MissingValueException.class,() -> missing.path(B).get());
		assertSame(FALLBACK,missing.path(B).getOrDefault(FALLBACK));
		root.set(Maps.of(A,null));
		assertFalse(missing.path(B).exists());
		assertNull(missing.get());
	}

	@Test void unresolvableTraversalIsAbsent() {
		assertAbsent(CVMLong.ONE,A);
		assertAbsent(Maps.of(A,CVMLong.ONE),A,B);
		assertAbsent(null,A);
		assertAbsent(Index.none(),CVMLong.ONE);
		assertAbsent(Index.none(),(ACell)null);
		for (ACell value:new ACell[] {Vectors.of(null,CVMLong.ONE),Lists.of(null,CVMLong.ONE)}) {
			var root=Root.create(value);
			assertNull(root.get(CVMLong.ZERO));
			assertTrue(root.path(CVMLong.ZERO).exists());
			assertEquals(CVMLong.ONE,root.get(CVMLong.ONE));
			assertEquals(CVMLong.ONE,root.get(CVMBigInteger.wrap(BigInteger.ONE)));
			assertAbsent(value,CVMBigInteger.wrap(BigInteger.ONE.shiftLeft(64)));
			assertAbsent(value,CVMLong.create(2));
			assertAbsent(value,CVMLong.MINUS_ONE);
			assertAbsent(value,A);
			assertAbsent(value,(ACell)null);
		}
	}

	private static void assertAbsent(ACell value,ACell... keys) {
		var root=Root.create(value);
		assertThrows(MissingValueException.class,() -> root.get(keys));
		assertSame(FALLBACK,root.getOrDefault(FALLBACK,keys));
		var direct=root.path(keys);
		AForkableCursor<ACell> chained=root;
		for (ACell key:keys) chained=chained.path(key);
		for (var path:java.util.List.of(direct,chained)) {
			assertThrows(MissingValueException.class,path::get);
			assertFalse(path.exists());
			assertSame(FALLBACK,path.getOrDefault(FALLBACK));
		}
		assertSame(value,root.get());
	}

	@Test void changedWritesStillRejectIncompatibleContainers() {
		assertWriteRefused(CVMLong.ONE,A);
		assertWriteRefused(Maps.of(A,CVMLong.ONE),A,B);
		assertWriteRefused(Index.none(),CVMLong.ONE);
		assertWriteRefused(Vectors.of(CVMLong.ZERO),A);
		assertWriteRefused(Vectors.of(CVMLong.ZERO),CVMLong.ONE);
	}

	private static void assertWriteRefused(ACell value,ACell... keys) {
		var root=Root.create(value);
		assertThrows(PathWriteException.class,() -> root.path(keys).set(FALLBACK));
		assertSame(value,root.get());
	}

	@Test void setMembershipIsNotInferredFromFalseyValues() {
		var root=Root.create(Sets.of(A));
		assertTrue(root.path(A).exists());
		assertFalse(root.path(B).exists());
		assertThrows(MissingValueException.class,() -> root.get(B));
	}

	@Test void readsUseOneSnapshotAndDoNotCallTheStrictSourceReadForAbsence() {
		AtomicInteger reads=new AtomicInteger();
		Root<ACell> root=new Root<>(Maps.of(A,null)) {
			@Override public ACell get() {
				return reads.getAndIncrement()==0?super.get():Maps.empty();
			}
		};
		assertNull(root.path(A).getOrDefault(FALLBACK));
		assertEquals(1,reads.get());
		assertSame(FALLBACK,root.path(A).getOrDefault(FALLBACK));
		assertEquals(2,reads.get());
	}

	@Test void absencePropagatesThroughTransformationsAndCaches() {
		var root=Root.<ACell>create(Maps.empty());
		var path=root.path(A);
		AtomicInteger transforms=new AtomicInteger();
		var transformed=Transformer.create(path,value -> { transforms.incrementAndGet(); return value; });
		var projected=ProjectionCursor.create(path,value -> value,(old,value) -> value);
		var cached=new TimeCache<ACell>(transformed,Long.MAX_VALUE);
		for (ACursor<ACell> cursor:java.util.List.of(transformed,projected,cached)) {
			assertFalse(cursor.exists());
			assertSame(FALLBACK,cursor.getOrDefault(FALLBACK));
			assertThrows(MissingValueException.class,cursor::get);
		}
		assertEquals(0,transforms.get());
		root.set(Maps.of(A,null));
		assertTrue(transformed.exists());
		assertNull(transformed.get());
		assertFalse(cached.exists());
		cached.invalidate();
		assertTrue(cached.exists());
		assertNull(cached.get());
	}

	@Test void defaultsAndPathsWriteOnlyWhereTheirEntryExists() {
		Root<AHashMap<Keyword,ACell>> root=Root.create(Maps.empty());
		var path=root.<ACell>path(A);
		var defaulted=path.withDefault(CVMLong.ZERO);
		assertTrue(defaulted.exists());
		assertEquals(CVMLong.ZERO,defaulted.get());
		// A default supplies a value to read, not a place to write
		assertThrows(PathWriteException.class,() -> defaulted.set(CVMLong.ZERO));
		assertThrows(PathWriteException.class,() -> defaulted.compareAndSet(CVMLong.ZERO,CVMLong.ONE));
		assertThrows(PathWriteException.class,() -> path.set(CVMLong.TWO));
		assertThrows(PathWriteException.class,() -> path.getAndUpdate(value -> CVMLong.ONE));
		assertFalse(path.exists());
		assertEquals(Maps.empty(),root.get());
		// Writing the container creates the entry, which both views then write
		root.assoc(A,CVMLong.TWO);
		assertEquals(CVMLong.TWO,path.get());
		assertTrue(defaulted.compareAndSet(CVMLong.TWO,CVMLong.ONE));
		assertEquals(CVMLong.ONE,path.getAndUpdate(value -> CVMLong.TWO));
		assertEquals(CVMLong.TWO,path.get());
		// A component's entry view creates its entry when a write changes it
		root.set(Maps.empty());
		AForkableCursor<ACell> entry=new MapComponent<>(root).path(A);
		var entryDefault=entry.withDefault(CVMLong.ZERO);
		entryDefault.set(CVMLong.ZERO);
		assertFalse(path.exists());
		assertTrue(entryDefault.compareAndSet(CVMLong.ZERO,CVMLong.ONE));
		assertEquals(CVMLong.ONE,path.get());
		root.set(Maps.empty());
		assertNull(entry.getAndUpdate(value -> { assertNull(value); return CVMLong.ONE; }));
		assertEquals(CVMLong.ONE,path.get());
		root.set(Maps.empty());
		assertThrows(IllegalStateException.class,() -> path.path(B).set(CVMLong.ONE));
		assertEquals(Maps.empty(),root.get());
	}
}
