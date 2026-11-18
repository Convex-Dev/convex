package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.MergeFunction;
import convex.test.Samples;

/**
 * Randomised checks of hash map and hash set merges and subset tests against values built only
 * by assoc and conj. Operands are unions of groups of keys sharing a hash prefix of zero to
 * three hex digits, so trees of every root shift, prefix and child depth meet each other, or
 * ordinary keys. Each operand is built by assoc or conj, by removal from a larger value, or
 * read back from a store.
 *
 * Every result must have the expected keys, equal and hash the same as the value built by assoc
 * or conj, pass validation, and be the receiver itself when it equals it. A merge function is
 * called once for each key the merge passes to it, with that key's values in argument order.
 *
 * Runs with {@code -Dconvex.test.extended=true}, as CI and release builds do. The tree shape
 * tests in {@link MapsTest} and {@link SetsTest} cover the same cases in everyday runs.
 */
@EnabledIfSystemProperty(named="convex.test.extended", matches="true")
@TestInstance(Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
public class HashMergeFuzzTest {

	private static final int POOL=100_000;
	private static final int ITERATIONS=1000;

	/** Keys 0..POOL-1, the first three hex digits of each key's hash, and keys by hash prefix */
	private CVMLong[] keys;
	private int[] prefix3;
	private List<List<List<Integer>>> byPrefix;

	@BeforeAll
	void buildPool() {
		keys=new CVMLong[POOL];
		prefix3=new int[POOL];
		byPrefix=new ArrayList<>();
		for (int len=0; len<4; len++) {
			List<List<Integer>> lists=new ArrayList<>();
			for (int i=0; i<(1<<(4*len)); i++) lists.add(new ArrayList<>());
			byPrefix.add(lists);
		}
		for (int i=0; i<POOL; i++) {
			keys[i]=CVMLong.create(i);
			Hash h=keys[i].getHash();
			int p=(h.getHexDigit(0)<<8)|(h.getHexDigit(1)<<4)|h.getHexDigit(2);
			prefix3[i]=p;
			for (int len=0; len<4; len++) byPrefix.get(len).get(p>>(4*(3-len))).add(i);
		}
	}

	static Stream<Arguments> runs() {
		return Stream.of(
			Arguments.of(1L,false),Arguments.of(2L,false),Arguments.of(3L,false),Arguments.of(4L,false),
			Arguments.of(5L,false),Arguments.of(6L,false),Arguments.of(7L,true),Arguments.of(8L,true));
	}

	/**
	 * @param seed Seed of the run
	 * @param natural True for ordinary keys, false for keys grouped under shared hash prefixes
	 */
	@ParameterizedTest
	@MethodSource("runs")
	void fuzz(long seed, boolean natural) throws Exception {
		Run run=new Run(seed,natural);
		for (int i=0; i<ITERATIONS; i++) {
			Set<Integer> a=run.randomKeys(null);
			Set<Integer> b=run.randomKeys(a);
			String where="seed "+seed+" iteration "+i;
			run.checkSets(a,b,where);
			run.checkMaps(a,b,where);
		}
	}

	/** State of one run: its random source and how it draws keys */
	private final class Run {
		final Random r;
		final boolean natural;

		Run(long seed, boolean natural) {
			this.r=new Random(seed);
			this.natural=natural;
		}

		void addGroup(Set<Integer> s, int len, int prefix) {
			List<Integer> pool=byPrefix.get(len).get(prefix);
			int c=1+r.nextInt((r.nextInt(3)==0)?60:24);
			for (int i=0; i<c; i++) s.add(pool.get(r.nextInt(pool.size())));
		}

		/** A hash prefix of up to three digits, most first digits drawn from a few so groups share them */
		int[] randomPrefix() {
			int len=natural?0:r.nextInt(4);
			int p=0;
			for (int i=0; i<len; i++) p=(p<<4)|((i==0)?new int[] {0,0,1,5,0xf}[r.nextInt(5)]:r.nextInt(16));
			return new int[] {len,p};
		}

		/** Keys of an operand: groups under random prefixes, or for a second operand, some of the first's keys and groups nested in or beside its prefixes */
		Set<Integer> randomKeys(Set<Integer> other) {
			Set<Integer> s=new LinkedHashSet<>();
			if (other==null) {
				int ng=1+r.nextInt(3);
				for (int g=0; g<ng; g++) {
					int[] rp=randomPrefix();
					addGroup(s,rp[0],rp[1]);
				}
				return s;
			}
			int mode=r.nextInt(5);
			if (mode==4) { s.addAll(other); return s; }
			if (mode>0) for (Integer k: other) if (r.nextInt(mode+1)!=0) s.add(k);
			if ((mode==3)&&r.nextBoolean()) return s;
			List<Integer> ol=new ArrayList<>(other);
			int ng=r.nextInt(3);
			for (int g=0; g<ng; g++) {
				if (!ol.isEmpty()&&r.nextBoolean()) {
					int p3=prefix3[ol.get(r.nextInt(ol.size()))];
					int len=natural?0:r.nextInt(4);
					addGroup(s,len,p3>>(4*(3-len)));
				} else {
					int[] rp=randomPrefix();
					addGroup(s,rp[0],rp[1]);
				}
			}
			return s;
		}

		/** Extra keys to add and remove again, none of them in ks */
		List<Integer> extras(Collection<Integer> ks) {
			List<Integer> extra=new ArrayList<>();
			int ne=r.nextInt(40);
			for (int i=0; i<ne; i++) {
				int k=r.nextInt(POOL);
				if (!ks.contains(k)&&!extra.contains(k)) extra.add(k);
			}
			return extra;
		}

		/** Builds a set by conj in random order (mode 0), with extra elements later removed (1), or read back from a store (2) */
		@SuppressWarnings("unchecked")
		AHashSet<CVMLong> buildSet(Collection<Integer> ks, int mode) throws Exception {
			List<Integer> order=new ArrayList<>(ks);
			List<Integer> extra=(mode==1)?extras(ks):List.of();
			order.addAll(extra);
			Collections.shuffle(order,r);
			AHashSet<CVMLong> s=Sets.empty();
			for (Integer k: order) s=(AHashSet<CVMLong>)s.conj(keys[k]);
			for (Integer k: extra) s=(AHashSet<CVMLong>)s.exclude(keys[k]);
			return (mode==2)?reload(s):s;
		}

		/** Builds a map by assoc in random order (mode 0), with extra entries later removed (1), or read back from a store (2) */
		AHashMap<CVMLong,CVMLong> buildMap(Map<Integer,Long> m, int mode) throws Exception {
			List<Integer> order=new ArrayList<>(m.keySet());
			List<Integer> extra=(mode==1)?extras(m.keySet()):List.of();
			order.addAll(extra);
			Collections.shuffle(order,r);
			AHashMap<CVMLong,CVMLong> s=Maps.empty();
			for (Integer k: order) s=s.assoc(keys[k],CVMLong.create(m.containsKey(k)?m.get(k):-1));
			for (Integer k: extra) s=s.dissoc(keys[k]);
			return (mode==2)?reload(s):s;
		}

		@SuppressWarnings("unchecked")
		<T extends ACell> T reload(T c) throws Exception {
			T p=Cells.persist(c,Samples.TEST_STORE);
			return (T) Samples.TEST_STORE.refForHash(p.getHash()).getValue();
		}

		void checkSets(Set<Integer> a, Set<Integer> b, String where) throws Exception {
			AHashSet<CVMLong> sa=buildSet(a,r.nextInt(3)), sb=buildSet(b,r.nextInt(3));
			for (int dir=0; dir<2; dir++) {
				AHashSet<CVMLong> x=(dir==0)?sa:sb, y=(dir==0)?sb:sa;
				Set<Integer> xs=(dir==0)?a:b, ys=(dir==0)?b:a;
				for (int op=0; op<3; op++) {
					Set<Integer> expected=new HashSet<>(xs);
					if (op==0) expected.addAll(ys); else if (op==1) expected.retainAll(ys); else expected.removeAll(ys);
					ASet<CVMLong> result=(op==0)?x.includeAll(y):(op==1)?x.intersectAll(y):x.excludeAll(y);
					String what=where+" "+new String[] {"union","intersection","difference"}[op]+" of "+describe(x)+" with "+describe(y);
					checkResult(buildSet(expected,0),result,x,what);
					HashSet<Integer> got=new HashSet<>();
					for (long i=0; i<result.count(); i++) got.add((int)result.get(i).longValue());
					assertEquals(expected,got,what);
				}
				assertEquals(xs.containsAll(ys),x.containsAll(y),where+" containsAll of "+describe(x)+" with "+describe(y));
				// A random subset too, so true answers are frequent
				Set<Integer> sub=new HashSet<>();
				for (Integer k: xs) if (r.nextInt(3)!=0) sub.add(k);
				AHashSet<CVMLong> z=buildSet(sub,r.nextInt(3));
				assertTrue(x.containsAll(z),where+" containsAll of "+describe(x)+" with subset "+describe(z));
			}
		}

		void checkMaps(Set<Integer> a, Set<Integer> b, String where) throws Exception {
			Map<Integer,Long> ma=new HashMap<>(), mb=new HashMap<>();
			for (Integer k: a) ma.put(k,(long)k);
			// Some shared keys with equal values, some differing
			for (Integer k: b) mb.put(k,(r.nextInt(3)==0)?(long)k:(long)k*2);
			AHashMap<CVMLong,CVMLong> xa=buildMap(ma,r.nextInt(3)), xb=buildMap(mb,r.nextInt(3));
			for (int dir=0; dir<2; dir++) {
				AHashMap<CVMLong,CVMLong> x=(dir==0)?xa:xb, y=(dir==0)?xb:xa;
				Map<Integer,Long> xs=(dir==0)?ma:mb, ys=(dir==0)?mb:ma;
				for (int f=0; f<FUNCTIONS.size(); f++) {
					for (boolean differences: new boolean[] {false,true}) {
						Map<Integer,Long> expected=new HashMap<>();
						Set<Integer> expectedCalls=new HashSet<>();
						Set<Integer> all=new HashSet<>(xs.keySet());
						all.addAll(ys.keySet());
						for (Integer k: all) {
							Long xv=xs.get(k), yv=ys.get(k);
							CVMLong v;
							if (differences&&Objects.equals(xv,yv)) {
								v=CVMLong.create(xv);
							} else {
								v=FUNCTIONS.get(f).merge(cvm(xv),cvm(yv));
								expectedCalls.add(k);
							}
							if (v!=null) expected.put(k,v.longValue());
						}
						Recording rec=new Recording(FUNCTIONS.get(f),xs,ys);
						AHashMap<CVMLong,CVMLong> result=differences?x.mergeDifferences(y,rec):x.mergeWith(y,rec);
						String what=where+" "+(differences?"mergeDifferences":"mergeWith")+" function "+f+" of "+describe(x)+" with "+describe(y);
						checkResult(buildMap(expected,0),result,x,what);
						assertEquals(null,rec.badCall,what);
						assertEquals(expectedCalls,rec.calls,what);
					}
				}
				assertEquals(xs.keySet().containsAll(ys.keySet()),x.containsAllKeys(y),where+" containsAllKeys of "+describe(x)+" with "+describe(y));
			}
		}

		void checkResult(ACell expected, ACell result, ACell receiver, String what) {
			assertEquals(expected,result,what);
			assertEquals(expected.getHash(),result.getHash(),what);
			try {
				result.validate();
			} catch (InvalidDataException e) {
				fail(what+": "+e.getMessage());
			}
			if (expected.equals(receiver)) assertSame(receiver,result,what);
		}
	}

	/** Merge functions: keep the first value, keep keys in both, keep keys only in the first, and sum */
	private static final List<MergeFunction<CVMLong>> FUNCTIONS=List.of(
		(x,y)->(x!=null)?x:y,
		(x,y)->((x!=null)&&(y!=null))?x:null,
		(x,y)->(y==null)?x:null,
		(x,y)->(x==null)?y:((y==null)?x:CVMLong.create(x.longValue()+y.longValue())));

	private static CVMLong cvm(Long v) {
		return (v==null)?null:CVMLong.create(v);
	}

	private static String describe(ACell c) {
		String shape=(c instanceof SetTree<?> t)?"tree at "+t.shift:(c instanceof MapTree)?"map tree":"leaf";
		return shape+" of "+((ACountable<?>)c).count();
	}

	/** A merge function recording the keys it is called for, and the first call with values other than the keys' own */
	private static final class Recording implements MergeFunction<CVMLong> {
		final MergeFunction<CVMLong> f;
		final Map<Integer,Long> xs, ys;
		final Set<Integer> calls=new HashSet<>();
		String badCall=null;

		Recording(MergeFunction<CVMLong> f, Map<Integer,Long> xs, Map<Integer,Long> ys) {
			this.f=f;
			this.xs=xs;
			this.ys=ys;
		}

		@Override
		public CVMLong merge(CVMLong a, CVMLong b) {
			throw new IllegalStateException("Merge function called without its key");
		}

		@Override
		public CVMLong merge(Object key, CVMLong a, CVMLong b) {
			int k=(int)((CVMLong)key).longValue();
			if (!calls.add(k)&&(badCall==null)) badCall="key "+k+" called twice";
			Long av=(a==null)?null:a.longValue(), bv=(b==null)?null:b.longValue();
			if ((!Objects.equals(av,xs.get(k))||!Objects.equals(bv,ys.get(k)))&&(badCall==null)) {
				badCall="key "+k+" called with ("+av+","+bv+")";
			}
			return f.merge(a,b);
		}
	}
}
