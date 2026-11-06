package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMLong;

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

	@Test void invalidTraversalIsNeverHiddenByFallbacks() {
		var scalar=Root.<ACell>create(CVMLong.ONE).path(A);
		assertThrows(IllegalArgumentException.class,scalar::get);
		assertThrows(IllegalArgumentException.class,scalar::exists);
		assertThrows(IllegalArgumentException.class,() -> scalar.getOrDefault(FALLBACK));
		var index=Root.create(Index.none());
		assertThrows(IllegalArgumentException.class,() -> index.getOrDefault(null,CVMLong.ONE));
		for (ACell value:new ACell[] {Vectors.of(null,CVMLong.ONE),Lists.of(null,CVMLong.ONE)}) {
			var root=Root.create(value);
			assertNull(root.get(CVMLong.ZERO));
			assertTrue(root.path(CVMLong.ZERO).exists());
			assertEquals(CVMLong.ONE,root.get(CVMLong.ONE));
			assertEquals(CVMLong.ONE,root.get(CVMBigInteger.wrap(BigInteger.ONE)));
			assertThrows(IndexOutOfBoundsException.class,() -> root.get(CVMBigInteger.wrap(BigInteger.ONE.shiftLeft(64))));
			assertThrows(IndexOutOfBoundsException.class,() -> root.get(CVMLong.create(2)));
			assertThrows(IndexOutOfBoundsException.class,() -> root.getOrDefault(FALLBACK,CVMLong.MINUS_ONE));
			assertThrows(IllegalArgumentException.class,() -> root.getOrDefault(FALLBACK,A));
		}
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

	@Test void explicitDefaultsAndWritesCanCreateMissingEntries() {
		var root=Root.<ACell>create(Maps.empty());
		var path=root.<ACell>path(A);
		var defaulted=path.withDefault(CVMLong.ZERO);
		assertTrue(defaulted.exists());
		assertEquals(CVMLong.ZERO,defaulted.get());
		defaulted.set(CVMLong.ZERO);
		assertFalse(path.exists());
		assertTrue(defaulted.compareAndSet(CVMLong.ZERO,CVMLong.ONE));
		assertEquals(CVMLong.ONE,path.get());
		root.set(Maps.empty());
		path.set(CVMLong.TWO);
		assertEquals(CVMLong.TWO,path.get());
		root.set(Maps.empty());
		assertNull(path.getAndUpdate(value -> { assertNull(value); return CVMLong.ONE; }));
		assertEquals(CVMLong.ONE,path.get());
		root.set(Maps.empty());
		assertThrows(IllegalStateException.class,() -> path.path(B).set(CVMLong.ONE));
		assertEquals(Maps.empty(),root.get());
	}
}
