package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.lattice.MapComponent;
import convex.lattice.PathWriteException;

class DefaultCursorTest {

	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");

	@Test void defaultsDoNotMaterialiseUntilADescendantChanges() {
		var root=new Root<AHashMap<Keyword,ACell>>();
		var cursor=root.withDefault(Maps.empty());
		assertEquals(Maps.empty(),cursor.get());
		cursor.set(Maps.empty());
		cursor.updateAndGet(value -> {
			assertNotNull(value);
			return value;
		});
		// A plain path below the default has no entry to write, so it materialises nothing
		assertThrows(PathWriteException.class,() -> cursor.path(A).set(CVMLong.ONE));
		assertNull(root.get());
		// A component's entry view creates its entry, and with it the default's container
		var entries=new MapComponent<>(cursor);
		entries.path(A).set(null);
		assertNull(root.get());
		entries.path(A).set(CVMLong.ONE);
		entries.path(B).set(CVMLong.TWO);
		assertEquals(Maps.of(A,CVMLong.ONE,B,CVMLong.TWO),root.get());
		assertEquals(root.get(),cursor.get());
	}

	@Test void atomicOperationsCompareAndReturnTheDefaultView() {
		var root=new Root<CVMLong>();
		var cursor=root.withDefault(CVMLong.ZERO);
		assertFalse(cursor.compareAndSet(null,CVMLong.ONE));
		assertTrue(cursor.compareAndSet(CVMLong.ZERO,CVMLong.ZERO));
		assertNull(root.get());
		assertTrue(cursor.compareAndSet(CVMLong.ZERO,CVMLong.ONE));
		assertFalse(cursor.compareAndSet(CVMLong.ZERO,CVMLong.TWO));
		assertEquals(CVMLong.ONE,cursor.getAndSet(null));
		assertNull(root.get());
		assertEquals(CVMLong.ZERO,cursor.get());
		assertEquals(CVMLong.ZERO,cursor.getAndUpdate(value -> CVMLong.create(value.longValue()+1)));
		assertEquals(CVMLong.ONE,root.get());
	}
}
