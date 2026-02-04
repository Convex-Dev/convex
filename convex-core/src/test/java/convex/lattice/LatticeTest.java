package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.Root;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.SetLattice;

public class LatticeTest {

	/**
	 * Generic property tests for any lattice
	 * @param v1 First valid lattice value
	 * @param v2 Second valid lattice value
	 * @param lattice Lattice consistent with values
	 */
	public static <V extends ACell> void doLatticeTest(ALattice<V> lattice, V v1, V v2, Object... path) {
		V zero=lattice.zero();
		
		// Merges with zero
		assertEquals(v1,lattice.merge(zero,v1));
		assertSame(v1,lattice.merge(v1,zero));

		// Null merge
		assertSame(v1,lattice.merge(v1,null));
		assertEquals(v1,lattice.merge(null,v1));

		
		assertEquals(v2,lattice.merge(zero,v2));
		assertEquals(v2,lattice.merge(v2,zero));
		
		// Merge of both values should be idempotent (lattice join)
		V merged=lattice.merge(v1, v2);		
		assertEquals(merged,lattice.merge(zero,merged));
		assertEquals(merged,lattice.merge(merged,zero));
		assertEquals(merged,lattice.merge(v1,merged));
		assertEquals(merged,lattice.merge(v2,merged));
		assertEquals(merged,lattice.merge(merged,v1));
		assertEquals(merged,lattice.merge(merged,v2));
		
		// Identity merges
		assertSame(merged,lattice.merge(merged,merged));
		assertSame(v1,lattice.merge(v1,v1));
		assertSame(v2,lattice.merge(v2,v2));
		
		assertSame(lattice,lattice.path());
		
		if (path.length>0) {
			ALattice<ACell> child=lattice.path(path);
			assertNotNull(child);

			ACell c1=RT.getIn(v1,path);
			ACell c2=RT.getIn(v2,path);

			assertEquals(child.merge(c1,c2),RT.getIn(merged, path));

			// Test resolveKey: canonical CVM key round-trips through resolveKey
			Object firstKey=path[0];
			ACell cvmKey=RT.cvm(firstKey);
			ACell resolved=lattice.resolveKey(cvmKey);
			assertNotNull(resolved,"resolveKey should resolve canonical CVM key: "+cvmKey);

			// path() should work with the resolved key
			assertNotNull(lattice.path(resolved),"path() should accept resolved key");

			// toJSONKey round-trip: CVM key → JSON key → resolveKey → same CVM key
			ACell jsonKey=ALattice.toJSONKey(resolved);
			assertNotNull(jsonKey,"toJSONKey should produce a non-null JSON key");
			ACell roundTripped=lattice.resolveKey(jsonKey);
			assertNotNull(roundTripped,"resolveKey should resolve JSON representation back");
			// The round-tripped key should resolve to the same child lattice
			assertNotNull(lattice.path(roundTripped),"path() should accept round-tripped key");
		}
	}
	
	@Test public void testLattice() {
		ACell genesis=Lattice.ROOT.zero();
		var root=Root.create(genesis);
		assertSame(genesis,root.get());
	}

	@Test public void testResolveKey() {
		// ROOT resolveKey: string → keyword
		assertSame(Keywords.KV, Lattice.ROOT.resolveKey(Strings.create("kv")));
		assertSame(Keywords.DATA, Lattice.ROOT.resolveKey(Strings.create("data")));
		assertSame(Keywords.FS, Lattice.ROOT.resolveKey(Strings.create("fs")));

		// ROOT resolveKey: keyword → keyword (identity)
		assertSame(Keywords.KV, Lattice.ROOT.resolveKey(Keywords.KV));

		// ROOT resolveKey: unknown key → null
		assertNull(Lattice.ROOT.resolveKey(Strings.create("nonexistent")));

		// toJSONKey: keyword → string
		assertEquals(Strings.create("kv"), ALattice.toJSONKey(Keywords.KV));
		assertEquals(Strings.create("data"), ALattice.toJSONKey(Keywords.DATA));

		// toJSONKey: string passes through
		{
			ACell s = Strings.create("hello");
			assertSame(s, ALattice.toJSONKey(s));
		}

		// toJSONKey: integer passes through
		assertSame(CVMLong.ONE, ALattice.toJSONKey(CVMLong.ONE));

		// Round-trip: keyword → JSON string → resolveKey → same keyword
		for (Keyword kw : new Keyword[]{Keywords.KV, Keywords.DATA, Keywords.FS}) {
			ACell json = ALattice.toJSONKey(kw);
			ACell resolved = Lattice.ROOT.resolveKey(json);
			assertSame(kw, resolved, "Round-trip should preserve keyword: " + kw);
		}
	}
	
	@Test public void testResolvePath() {
		// Empty path
		ACell[] empty=Lattice.ROOT.resolvePath();
		assertNotNull(empty);
		assertEquals(0,empty.length);

		// Invalid key at root → null
		assertNull(Lattice.ROOT.resolvePath(Strings.create("nonexistent")));

		// ASequence overload: invalid key → null
		@SuppressWarnings("unchecked")
		AVector<ACell> badSeq=(AVector<ACell>)Vectors.of(Strings.create("nonexistent"));
		assertNull(Lattice.ROOT.resolvePath(badSeq));
	}

	@Test public void testResolvePathRoundTrip() {
		// Build a test lattice: KeyedLattice with :alpha → OwnerLattice(MaxLattice)
		Keyword ALPHA=Keyword.create("alpha");
		Keyword BETA=Keyword.create("beta");
		KeyedLattice lattice=KeyedLattice.create(
			ALPHA, OwnerLattice.create(MaxLattice.create()),
			BETA, MapLattice.create(SetLattice.create())
		);

		// --- Single-level round-trip: JSON "alpha" → :alpha → "alpha" ---
		{
			ACell[] jsonPath={Strings.create("alpha")};
			ACell[] cvmPath=lattice.resolvePath(jsonPath);
			assertNotNull(cvmPath);
			assertSame(ALPHA,cvmPath[0]);

			// Convert back to JSON
			ACell[] jsonBack=toJSONPath(cvmPath);
			assertEquals(Strings.create("alpha"),jsonBack[0]);

			// Round-trip: resolve the JSON-back path again
			ACell[] cvmPath2=lattice.resolvePath(jsonBack);
			assertNotNull(cvmPath2);
			assertArrayEquals(cvmPath,cvmPath2);
		}

		// --- Two-level round-trip through OwnerLattice: ["alpha", "0xabcd"] ---
		{
			Blob ownerKey=Blob.fromHex("abcd");
			ACell[] jsonPath={Strings.create("alpha"),Strings.create("abcd")};
			ACell[] cvmPath=lattice.resolvePath(jsonPath);
			assertNotNull(cvmPath);
			assertSame(ALPHA,cvmPath[0]);
			assertEquals(ownerKey,cvmPath[1]);

			ACell[] jsonBack=toJSONPath(cvmPath);
			assertEquals(Strings.create("alpha"),jsonBack[0]);
			assertEquals(Strings.create("abcd"),jsonBack[1]);

			ACell[] cvmPath2=lattice.resolvePath(jsonBack);
			assertNotNull(cvmPath2);
			assertArrayEquals(cvmPath,cvmPath2);
		}

		// --- Two-level through MapLattice: ["beta", "mykey"] ---
		{
			ACell[] jsonPath={Strings.create("beta"),Strings.create("mykey")};
			ACell[] cvmPath=lattice.resolvePath(jsonPath);
			assertNotNull(cvmPath);
			assertSame(BETA,cvmPath[0]);
			assertEquals(Strings.create("mykey"),cvmPath[1]);

			ACell[] jsonBack=toJSONPath(cvmPath);
			assertArrayEquals(jsonPath,jsonBack);

			ACell[] cvmPath2=lattice.resolvePath(jsonBack);
			assertNotNull(cvmPath2);
			assertArrayEquals(cvmPath,cvmPath2);
		}

		// --- ASequence overload matches varargs ---
		{
			@SuppressWarnings("unchecked")
			AVector<ACell> seqPath=(AVector<ACell>)Vectors.of(Strings.create("alpha"),Strings.create("abcd"));
			ACell[] fromSeq=lattice.resolvePath(seqPath);
			ACell[] fromArgs=lattice.resolvePath(Strings.create("alpha"),Strings.create("abcd"));
			assertNotNull(fromSeq);
			assertArrayEquals(fromArgs,fromSeq);
		}

		// --- CVM keys also work through resolvePath ---
		{
			ACell[] cvmDirect=lattice.resolvePath(ALPHA);
			assertNotNull(cvmDirect);
			assertSame(ALPHA,cvmDirect[0]);
		}

		// --- Invalid deep path → null ---
		assertNull(lattice.resolvePath(Strings.create("nonexistent"),Strings.create("x")));
	}

	/**
	 * Helper: convert each element of a CVM path back to its JSON representation
	 */
	private static ACell[] toJSONPath(ACell[] cvmPath) {
		ACell[] result=new ACell[cvmPath.length];
		for (int i=0; i<cvmPath.length; i++) {
			result[i]=ALattice.toJSONKey(cvmPath[i]);
		}
		return result;
	}

	@Test public void testMaxLattice() {
		MaxLattice lattice=MaxLattice.INSTANCE;
		assertSame(CVMLong.TWO,lattice.merge(RT.cvm(1), RT.cvm(2)));
		
		doLatticeTest(lattice,CVMLong.ONE,CVMLong.ZERO);
		doLatticeTest(lattice,CVMLong.ONE,CVMLong.TWO);
		assertNull(lattice.path(Symbols.FOO));
	}
	
	@Test public void testSetLattice() {
		SetLattice<AInteger> lattice=SetLattice.create();
		ASet<AInteger> FULL=Sets.of(1,2,3);
		assertEquals(FULL,lattice.merge(Sets.of(1,2),Sets.of(2,3)));
		
		doLatticeTest(lattice,Sets.of(1,2),Sets.of(2,3));
		assertNull(lattice.path(Symbols.FOO));
	}
}
