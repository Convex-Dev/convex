package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.test.generators.HashMapGen;
import convex.test.generators.ValueGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestMap {

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Property
	public void assocDissoc(@From(HashMapGen.class) AHashMap baseMap, @From(ValueGen.class) ACell prim) {
		AHashMap m=baseMap;
		AHashMap singletonMap=Maps.of(prim,prim);
		long n = m.count();
		long expectedN = (m.containsKey(prim)) ? n : n + 1;

		// add the key
		m = m.assoc(prim, prim);
		AHashMap fullMap=m;
		assertEquals(prim, m.get(prim));
		assertEquals(expectedN, m.size());
		
		// merging without change
		assertSame(m,m.merge(singletonMap));

		// remove the key
		m = m.dissoc(prim);
		assertNull(m.get(prim));
		assertEquals(expectedN - 1, m.size());

		// recreate full map
		assertEquals(fullMap,singletonMap.merge(m));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Property
	public void mapPRoperties(@From(HashMapGen.class) AHashMap m) {
		long n=m.count();
		// check that the map is unchanged by an identity mapping
		assertSame(m,m.mapEntries(e->e));
		
		//check that a map can be recreated from overlapping slices
		assertEquals(m,m.slice(0,n/2).merge(m.slice(n/3,n)));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Property
	public void merging1(@From(HashMapGen.class) AHashMap m) {
		m = m.filterValues(v -> v != null); // don't want null values, to avoid accidental entry removal

		AMap<ACell, ACell> m1 = m.mergeWith(m, (a, b) -> a);
		assertEquals(m, m1);

		AMap<ACell, ACell> m2 = m.mergeWith(Maps.empty(), (a, b) -> a);
		assertEquals(m, m2);

		AMap<ACell, ACell> m3 = m.mergeWith(Maps.empty(), (a, b) -> b);
		assertSame(Maps.empty(), m3);

	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Property
	public void merging2(@From(HashMapGen.class) AHashMap a,
			@From(HashMapGen.class) AHashMap b) {
		long[] c = new long[] { 0L };
		a.mergeWith(b, (va, vb) -> ((c[0]++ & 1) == 0L) ? va : vb);
		assertTrue(c[0] >= Math.max(a.count(), b.count()));
	}

}
