package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;

class MapComponentTest {
	private static final AString A=Strings.intern("a");
	private static final AString B=Strings.intern("b");

	@Test void unsupportedIndexKeyCannotClearTheCollection() {
		var root=Root.create(Index.<AString,ACell>of(A,CVMLong.ONE,B,CVMLong.TWO));
		var index=new IndexComponent<>(root);
		var before=root.get();
		assertThrows(IllegalArgumentException.class,() -> index.put(null,CVMLong.ONE));
		assertThrows(IllegalArgumentException.class,() -> index.path(null));
		assertNull(index.remove(null));
		assertSame(before,root.get());
		var map=new MapComponent<AString,ACell>(new Root<>());
		map.put(null,CVMLong.ONE);
		assertEquals(CVMLong.ONE,map.get(null),"Hash maps support a nil key");
	}

	private static AMapComponent<AString,ACell,?> component(Root<ACell> root,boolean indexed) {
		return indexed?new IndexComponent<>(root.path()):new MapComponent<>(root.path());
	}

	@ParameterizedTest @ValueSource(booleans={false,true})
	void absentReadsAndNoOpEditsDoNotCreateStorage(boolean indexed) {
		var root=new Root<ACell>();
		var map=component(root,indexed);
		assertTrue(map.isEmpty());
		assertEquals(0,map.size());
		assertNull(map.get(A));
		assertNull(map.remove(A));
		map.clear();
		map.path(A).set(null);
		assertNull(root.get());
		// Nil is a legitimate stored entry, distinct from an absent key.
		map.put(A,null);
		assertTrue(map.containsKey(A));
		assertEquals(1,map.size());
		assertEquals(indexed,root.get() instanceof Index<?,?>);
		map.remove(A);
		assertFalse(map.containsKey(A));
	}

	@ParameterizedTest @ValueSource(booleans={false,true})
	void entryViewsShareEditsAndPreserveSiblingsAndSnapshots(boolean indexed) {
		var root=new Root<ACell>();
		var map=component(root,indexed);
		var child=map.path(A);
		var opaque=Vectors.of("future",Maps.of("version",2));
		map.put(B,opaque);
		child.set(CVMLong.ONE);
		var snapshot=map.get();
		assertEquals(2,map.size());
		assertEquals(CVMLong.ONE,map.put(A,CVMLong.TWO));
		assertEquals(CVMLong.TWO,child.get());
		assertEquals(CVMLong.ONE,snapshot.get(A));
		assertSame(opaque,map.get(B));
		var unchanged=root.get();
		map.put(A,CVMLong.TWO);
		assertSame(unchanged,root.get());
		assertSame(unchanged,map.sync());
		assertEquals(CVMLong.TWO,map.remove(A));
		assertNull(child.get());
		assertSame(opaque,map.get(B));
		map.clear();
		assertTrue(map.isEmpty());
		assertEquals(indexed,root.get() instanceof Index<?,?>);
	}

	@ParameterizedTest @ValueSource(booleans={false,true})
	void incompatibleStorageIsDiagnosedWithoutChangingIt(boolean indexed) {
		var root=Root.<ACell>create(CVMLong.ONE);
		var map=component(root,indexed);
		assertNotNull(map.checkValue(root.get()));
		assertThrows(IllegalStateException.class,map::get);
		assertThrows(IllegalStateException.class,() -> map.put(A,CVMLong.TWO));
		assertThrows(IllegalStateException.class,() -> map.remove(A));
		assertThrows(IllegalStateException.class,map::clear);
		assertThrows(IllegalStateException.class,() -> map.path(A).set(CVMLong.TWO));
		assertSame(CVMLong.ONE,root.get());
		assertNull(map.checkValue(null));
		assertNotNull(map.checkValue(indexed?Maps.empty():Index.none()));
		root.set(indexed?Maps.empty():Index.none());
		assertThrows(IllegalStateException.class,() -> map.path(A).set(CVMLong.TWO));
	}
}
