package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;

/**
 * Tests for {@link Index#mergeDifferences(Index, MergeFunction)}, the structural radix merge.
 *
 * <p>The structural merge must be bit-identical to the original naive double-scan implementation
 * (reproduced here as the {@link #naive} oracle) for all inputs and merge functions, and must
 * return its left argument by reference identity when the merge is a no-op (no spurious allocation).</p>
 */
public class IndexMergeTest {

	// ---- Merge functions under test ----

	/** Left-biased, identity on single side. */
	static final MergeFunction<CVMLong> PICK_A = new MergeFunction<>() {
		public CVMLong merge(CVMLong a, CVMLong b) { return a != null ? a : b; }
	};
	/** Right-biased, identity on single side. */
	static final MergeFunction<CVMLong> PICK_B = new MergeFunction<>() {
		public CVMLong merge(CVMLong a, CVMLong b) { return b != null ? b : a; }
	};
	/** Lattice-style max, identity on single side AND on the winning side. */
	static final MergeFunction<CVMLong> MAX = new MergeFunction<>() {
		public CVMLong merge(CVMLong a, CVMLong b) {
			if (a == null) return b;
			if (b == null) return a;
			return a.longValue() >= b.longValue() ? a : b;
		}
	};
	/** Transforms even on a single side (never returns the input ref). */
	static final MergeFunction<CVMLong> SUM = new MergeFunction<>() {
		public CVMLong merge(CVMLong a, CVMLong b) {
			return CVMLong.create((a == null ? 0 : a.longValue()) + (b == null ? 0 : b.longValue()));
		}
	};
	/** Drops (returns null) when the merged value is even — exercises dissoc/empty-child collapse. */
	static final MergeFunction<CVMLong> DROP_EVEN = new MergeFunction<>() {
		public CVMLong merge(CVMLong a, CVMLong b) {
			long x = (a == null) ? (b == null ? 0 : b.longValue())
					: (b == null ? a.longValue() : Math.max(a.longValue(), b.longValue()));
			return ((x & 1L) == 0L) ? null : CVMLong.create(x);
		}
	};

	static final List<MergeFunction<CVMLong>> FUNCS = List.of(PICK_A, PICK_B, MAX, SUM, DROP_EVEN);

	// ---- Naive reference: the original algorithm we must stay bit-identical to ----

	static Index<ABlob, CVMLong> naive(Index<ABlob, CVMLong> a, Index<ABlob, CVMLong> b, MergeFunction<CVMLong> func) {
		Index<ABlob, CVMLong> result = a;
		long nb = b.count();
		for (long i = 0; i < nb; i++) {
			MapEntry<ABlob, CVMLong> me = b.entryAt(i);
			ABlob k = me.getKey();
			CVMLong v = me.getValue();
			if (a.getEntry(k) != null) continue; // present in both: handled in second loop
			CVMLong nv = func.merge(k, null, v);
			if (nv != null) result = result.assoc(k, nv);
		}
		long na = a.count();
		for (long i = 0; i < na; i++) {
			MapEntry<ABlob, CVMLong> me = a.entryAt(i);
			ABlob k = me.getKey();
			CVMLong v = me.getValue();
			MapEntry<ABlob, CVMLong> meb = b.getEntry(k);
			CVMLong ov = (meb == null) ? null : meb.getValue();
			if (!Utils.equals(v, ov)) {
				CVMLong nv = func.merge(k, v, ov);
				if (nv == null) result = result.dissoc(k);
				else if (!Utils.equals(v, nv)) result = result.assoc(k, nv);
			}
		}
		return result;
	}

	// ---- Key generation: biased prefixes to exercise aligned / disjoint / nested cases ----

	static ABlob randomKey(Random r) {
		int len = 1 + r.nextInt(40); // 1..40 bytes (spans < and > MAX_DEPTH = 64 hex digits)
		byte[] bs = new byte[len];
		r.nextBytes(bs);
		// Squeeze the leading bytes into a small alphabet so keys share prefixes (and some nest).
		bs[0] = (byte) r.nextInt(4);
		if (len > 1) bs[1] = (byte) r.nextInt(4);
		return Blob.wrap(bs);
	}

	@Test
	public void testFuzzMatchesNaive() {
		for (int seed = 0; seed < 300; seed++) {
			Random r = new Random(seed);
			int np = 1 + r.nextInt(40);
			List<ABlob> keys = new ArrayList<>();
			for (int i = 0; i < np; i++) keys.add(randomKey(r));

			Index<ABlob, CVMLong> a = Index.none();
			Index<ABlob, CVMLong> b = Index.none();
			for (ABlob k : keys) {
				int where = r.nextInt(4); // 0:a-only 1:b-only 2:both-equal 3:both-different
				CVMLong va = CVMLong.create(r.nextInt(100));
				CVMLong vb = CVMLong.create(r.nextInt(100));
				switch (where) {
					case 0 -> a = a.assoc(k, va);
					case 1 -> b = b.assoc(k, vb);
					case 2 -> { a = a.assoc(k, va); b = b.assoc(k, va); }
					default -> { a = a.assoc(k, va); b = b.assoc(k, vb); }
				}
			}

			for (MergeFunction<CVMLong> f : FUNCS) {
				Index<ABlob, CVMLong> got = a.mergeDifferences(b, f);
				Index<ABlob, CVMLong> exp = naive(a, b, f);
				String ctx = "seed=" + seed + " func=" + FUNCS.indexOf(f);
				assertEquals(exp, got, ctx);                       // same content
				assertEquals(exp.getHash(), got.getHash(), ctx);   // therefore bit-identical encoding
				try {
					got.validate();                                // result is canonical
				} catch (InvalidDataException e) {
					fail(ctx + " produced non-canonical Index: " + e.getMessage());
				}
			}
		}
	}

	@Test
	public void testMergeIsCommutativeForLatticeFunc() {
		// MAX is a commutative lattice join: merge(a,b) and merge(b,a) must agree.
		for (int seed = 1000; seed < 1100; seed++) {
			Random r = new Random(seed);
			Index<ABlob, CVMLong> a = Index.none();
			Index<ABlob, CVMLong> b = Index.none();
			int n = 1 + r.nextInt(40);
			for (int i = 0; i < n; i++) {
				a = a.assoc(randomKey(r), CVMLong.create(r.nextInt(50)));
				b = b.assoc(randomKey(r), CVMLong.create(r.nextInt(50)));
			}
			assertEquals(a.mergeDifferences(b, MAX), b.mergeDifferences(a, MAX), "seed=" + seed);
		}
	}

	@Test
	public void testNoOpReturnsIdentity() {
		Random r = new Random(42);
		Index<ABlob, CVMLong> a = Index.none();
		List<ABlob> keys = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			ABlob k = randomKey(r);
			keys.add(k);
			a = a.assoc(k, CVMLong.create(r.nextInt(1000)));
		}

		// Merging with self short-circuits.
		assertSame(a, a.mergeDifferences(a, PICK_A));

		// Merging with empty, identity-on-single-side func: no allocation, returns the same object.
		assertSame(a, a.mergeDifferences(Index.none(), MAX));
		assertSame(a, a.mergeDifferences(Index.none(), PICK_A));

		// Merging with an independently-built subset of itself (equal values), left-biased:
		// every key is either equal-in-both or a-only, so the result is a with zero allocation.
		Index<ABlob, CVMLong> subset = Index.none();
		for (int i = 0; i < keys.size(); i += 2) {
			ABlob k = keys.get(i);
			subset = subset.assoc(k, a.get(k));
		}
		assertSame(a, a.mergeDifferences(subset, PICK_A));
		assertSame(a, a.mergeDifferences(subset, MAX));
	}

	@Test
	public void testDisjointAndNested() {
		// Disjoint prefixes (different first digit).
		Index<ABlob, CVMLong> a = Index.of(Blob.fromHex("10ab"), CVMLong.create(1), Blob.fromHex("12cd"), CVMLong.create(2));
		Index<ABlob, CVMLong> b = Index.of(Blob.fromHex("20ef"), CVMLong.create(3));
		Index<ABlob, CVMLong> m = a.mergeDifferences(b, PICK_A);
		assertEquals(naive(a, b, PICK_A), m);
		assertEquals(3L, m.count());

		// Nested: b's key extends a's key prefix (one key is a strict prefix of the other).
		Index<ABlob, CVMLong> c = Index.of(Blob.fromHex("ab"), CVMLong.create(1));
		Index<ABlob, CVMLong> d = Index.of(Blob.fromHex("abcd"), CVMLong.create(2), Blob.fromHex("abef"), CVMLong.create(3));
		Index<ABlob, CVMLong> mn = c.mergeDifferences(d, PICK_B);
		assertEquals(naive(c, d, PICK_B), mn);
		assertEquals(3L, mn.count());
	}
}
