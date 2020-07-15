package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.generators.MapGen;
import convex.generators.PrimitiveGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestMap {

	@Property
	public void primitiveAssoc(@From(MapGen.class) AHashMap<Object, Object> m, @From(PrimitiveGen.class) Object prim) {
		long n = m.count();
		long expectedN = (m.containsKey(prim)) ? n : n + 1;

		// add the key
		m = m.assoc(prim, prim);
		assertSame(prim, m.get(prim));
		assertEquals(expectedN, m.size());

		// remove the key
		m = m.dissoc(prim);
		assertNull(m.get(prim));
		assertEquals(expectedN - 1, m.size());

	}

	@Property
	public void testHashCodes(@From(MapGen.class) AMap<Object, Object> m) {
		assertEquals(m.hashCode(), m.getHash().hashCode());
	}

	@Property
	public void mapToIdentity(@From(MapGen.class) AHashMap<Object, Object> m) {
		AHashMap<Object, Object> m2 = m.mapEntries(e -> e);

		// check that the map is unchanged
		assertTrue(m2 == m);
	}

	@Property
	public void merging1(@From(MapGen.class) AHashMap<Object, Object> m) {
		m = m.filterValues(v -> v != null); // don't want null values, to avoid accidental entry removal

		AMap<Object, Object> m1 = m.mergeWith(m, (a, b) -> a);
		assertEquals(m, m1);

		AMap<Object, Object> m2 = m.mergeWith(Maps.empty(), (a, b) -> a);
		assertEquals(m, m2);

		AMap<Object, Object> m3 = m.mergeWith(Maps.empty(), (a, b) -> b);
		assertSame(Maps.empty(), m3);

	}

	@Property
	public void merging2(@From(MapGen.class) AHashMap<Object, Object> a,
			@From(MapGen.class) AHashMap<Object, Object> b) {
		long[] c = new long[] { 0L };
		a.mergeWith(b, (va, vb) -> ((c[0]++ & 1) == 0L) ? va : vb);
		assertTrue(c[0] >= Math.max(a.count(), b.count()));
	}

}
