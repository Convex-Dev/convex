package convex.core.lang;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.evalB;
import static convex.core.lang.TestState.evalD;
import static convex.core.lang.TestState.evalL;
import static convex.core.lang.TestState.evalS;
import static convex.core.lang.TestState.step;
import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertArityError;
import static convex.test.Assertions.assertAssertError;
import static convex.test.Assertions.assertBoundsError;
import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertCastError;
import static convex.test.Assertions.assertCompileError;
import static convex.test.Assertions.assertDepthError;
import static convex.test.Assertions.assertError;
import static convex.test.Assertions.assertFundsError;
import static convex.test.Assertions.assertJuiceError;
import static convex.test.Assertions.assertMemoryError;
import static convex.test.Assertions.assertNobodyError;
import static convex.test.Assertions.assertNotError;
import static convex.test.Assertions.assertStateError;
import static convex.test.Assertions.assertTrustError;
import static convex.test.Assertions.assertUndeclaredError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.Block;
import convex.core.BlockResult;
import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.Init;
import convex.core.State;
import convex.core.crypto.Hash;
import convex.core.data.ABlob;
import convex.core.data.ABlobMap;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMByte;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.expanders.AExpander;
import convex.core.lang.impl.CoreFn;
import convex.core.lang.impl.CorePred;
import convex.core.lang.impl.ICoreDef;
import convex.core.lang.ops.Constant;

/**
 * Test class for core functions in the initial environment.
 * 
 * Needs completely deterministic, fully specified behaviour if we want
 * consistent results so we need to do a lot of negative testing here.
 */
public class CoreTest {

	private static final State INITIAL = TestState.INITIAL;
	private static final long INITIAL_JUICE = TestState.INITIAL_JUICE;
	private static final Context<?> INITIAL_CONTEXT= TestState.INITIAL_CONTEXT.fork();

	@Test
	public void testAliases() {
		assertTrue(evalB("(map? *aliases*)"));
		assertEquals(Maps.empty(),eval("*aliases*"));
	}
	
	@Test
	public void testAddress() {
		Address a = TestState.HERO;
		assertEquals(a, eval("(address \"" + a.toHexString() + "\")"));
		assertEquals(a, eval("(address 0x" + a.toHexString() + ")"));
		assertEquals(a, eval("(address (address \"" + a.toHexString() + "\"))"));
		assertEquals(a, eval("(address (blob \"" + a.toHexString() + "\"))"));
		assertEquals(a, eval("(address "+a.longValue()+")"));

		// bad arities
		assertArityError(step("(address 1 2)"));
		assertArityError(step("(address)"));

		// invalid address lengths - not a cast error since argument types (in general) are valid
		assertArgumentError(step("(address \"1234abcd\")"));
		assertArgumentError(step("(address 0x1234abcd)"));

		// invalid conversions
		assertCastError(step("(address :foo)"));
		assertCastError(step("(address nil)"));
	}

	@Test
	public void testBlob() {
		Blob b = Blob.fromHex("cafebabe");
		assertEquals(b, eval("(blob \"Cafebabe\")"));
		assertEquals(b, eval("(blob (blob \"cafebabe\"))"));

		assertEquals("cafebabe", evalS("(str (blob \"Cafebabe\"))"));

		assertEquals(eval("0x"),eval("(blob (str))")); // blob literal
		
		assertEquals(eval("*address*"),eval("(address (blob *address*))"));
		
		// round trip back to Blob
		assertTrue(evalB("(blob? (blob (hash (encoding [1 2 3]))))"));

		assertArityError(step("(blob 1 2)"));
		assertArityError(step("(blob)"));

		assertCastError(step("(blob \"f\")")); // odd length hex string bug #54 special case
		assertCastError(step("(blob :foo)"));
		assertCastError(step("(blob nil)"));
	}

	@Test
	public void testByte() {
		assertSame(CVMByte.create(0x01), eval("(byte 1)"));
		assertSame(CVMByte.create(0xff), eval("(byte 255)"));
		assertSame(CVMByte.create(0xff), eval("(byte -1)"));
		assertSame(CVMByte.create(0xff), eval("(byte (byte -1))"));

		assertCastError(step("(byte nil)"));
		assertCastError(step("(byte :foo)"));

		assertArityError(step("(byte)"));
		assertArityError(step("(byte nil nil)")); // arity before cast
	}
	
	@Test
	public void testLet() {
		
		assertCastError(step("(let [[a b] :foo] b)"));
		
		assertArityError(step("(let [[a b] nil] b)"));
		assertArityError(step("(let [[a b] [1]] b)"));
		assertEquals(2L,evalL("(let [[a b] [1 2]] b)"));
		assertEquals(2L,evalL("(let [[a b] '(1 2)] b)"));

		assertCompileError(step("(let ['(a b) '(1 2)] b)"));
		
		// badly formed lets - Issue #80 related
		assertCompileError(step("(let)"));
		assertCompileError(step("(let :foo)"));
		assertCompileError(step("(let [a])"));


	}

	@Test
	public void testGet() {
		assertEquals(2L, evalL("(get {1 2} 1)"));
		assertEquals(4L, evalL("(get {1 2 3 4} 3)"));
		assertEquals(4L, evalL("(get {1 2 3 4} 3 7)"));
		assertNull(eval("(get {1 2} 2)")); // null if not present
		assertEquals(7L, evalL("(get {1 2 3 4} 5 7)")); // fallback arg

		assertEquals(1L, evalL("(get #{1 2} 1)"));
		assertEquals(2L, evalL("(get #{1 2} 2)"));
		assertNull(eval("(get #{1 2} 3)")); // null if not present
		assertEquals(4L, evalL("(get #{1 2} 3 4)")); // fallback

		assertEquals(2L, evalL("(get [1 2 3] 1)"));
		assertEquals(2L, evalL("(get [1 2 3] 1 7)"));
		assertEquals(7L, evalL("(get [1 2 3] 4 7)"));
		assertEquals(7L, evalL("(get [1 2] nil 7)"));
		assertEquals(7L, evalL("(get [1 2] -5 7)"));
		assertNull(eval("(get [1 2] :foo)"));
		assertNull(eval("(get [1 2] 10)"));
		assertNull(eval("(get [1 2] -1)"));
		assertNull(eval("(get [1 2] 1.0)"));
		
		assertNull(eval("(get [1 2 3] (byte 1))")); // TODO: is this sane?

		assertNull(eval("(get nil nil)"));
		assertNull(eval("(get nil 10)"));
		assertEquals(3L, evalL("(get nil 2 3)"));
		assertEquals(3L, evalL("(get nil nil 3)"));

		assertArityError(step("(get 1)")); // arity > cast
		assertArityError(step("(get)"));
		assertArityError(step("(get 1 2 3 4)"));

		assertCastError(step("(get 1 2 3)")); // 3 arg could work, so cast error on 1st arg
		assertCastError(step("(get 1 1)")); // 2 arg could work, so cast error on 1st arg
	}

	@Test
	public void testGetIn() {
		assertEquals(2L, evalL("(get-in {1 2} [1])"));
		assertEquals(4L, evalL("(get-in {1 {2 4} 3 5} [1 2])"));
		assertEquals(1L, evalL("(get-in #{1 2} [1])"));
		assertEquals(2L, evalL("(get-in [1 2 3] [1])"));
		assertEquals(2L, evalL("(get-in [1 2 3] [1] :foo)"));
		assertEquals(3L, evalL("(get-in [1 2 3] '(2))"));
		assertEquals(3L, evalL("(get-in (list 1 2 3) [2])"));
		assertEquals(4L, evalL("(get-in [1 2 {:foo 4} 3 5] [2 :foo])"));

		// special case: don't coerce to collection if empty sequence of keys
		// so non-collection value may be used safely
		assertEquals(3L, evalL("(get-in 3 [])"));

		assertEquals(Maps.of(1L, 2L), eval("(get-in {1 2} nil)"));
		assertEquals(Maps.of(1L, 2L), eval("(get-in {1 2} [])"));
		assertEquals(Vectors.empty(), eval("(get-in [] [])"));
		assertEquals(Lists.empty(), eval("(get-in (list) nil)"));

		assertEquals(Keywords.FOO, eval("(get-in {1 2} [3] :foo)"));
		assertEquals(Keywords.FOO, eval("(get-in nil [3] :foo)"));

		assertNull(eval("(get-in nil nil)"));
		assertNull(eval("(get-in [1 2 3] [:foo])"));
		assertNull(eval("(get-in nil [])"));
		assertNull(eval("(get-in nil [1 2])"));
		assertNull(eval("(get-in #{} [1 2 3])"));

		assertArityError(step("(get-in 1)")); // arity > cast
		assertArityError(step("(get-in 1 2 3 4)")); // arity > cast
		
		assertCastError(step("(get-in 1 2 3)")); 
		assertCastError(step("(get-in 1 [1])"));
		assertCastError(step("(get-in [1] [0 2])"));
		assertCastError(step("(get-in 1 {1 2})")); // keys not a sequence

		assertCastError(step("(get-in [1] 1)"));
	}

	@Test
	public void testLong() {
		assertCVMEquals(1L, eval("(long 1)"));
		assertEquals(128L, evalL("(long (byte 128))"));
		assertEquals(97L, evalL("(long \\a)"));
		assertEquals(2147483648L, evalL("(long 2147483648)"));
		
		assertEquals(4096L, evalL("(long 0x1000)"));
		assertEquals(255L, evalL("(long 0xff)"));
		assertEquals(4294967295L, evalL("(long 0xffffffff)"));
		assertEquals(-1L, evalL("(long 0xffffffffffffffff)"));
		assertEquals(255L, evalL("(long 0xff00000000000000ff)")); // only taking last 8 bytes
		assertEquals(-1L, evalL("(long 0xcafebabeffffffffffffffff)")); // interpret as big endian big integer


		assertArityError(step("(long)"));
		assertArityError(step("(long 1 2)"));
		assertCastError(step("(long nil)"));
		assertCastError(step("(long [])"));
		assertCastError(step("(long :foo)"));
	}

	@Test
	public void testChar() {
		assertCVMEquals('a', eval("\\a"));
		assertCVMEquals('a', eval("(char 97)"));
		assertCVMEquals('a', eval("(nth \"bar\" 1)"));

		assertCastError(step("(char nil)"));
		assertCastError(step("(char {})"));

		assertArityError(step("(char)"));
		assertArityError(step("(char nil nil)")); // arity before cast

	}

	@Test
	public void testBoolean() {
		// test precise values
		assertSame(CVMBool.TRUE, eval("(boolean 1)"));
		assertSame(CVMBool.FALSE, eval("(boolean nil)"));

		// nil and false should be falsey
		assertFalse(evalB("(boolean false)"));
		assertFalse(evalB("(boolean nil)"));

		// anything else should be truthy
		assertTrue(evalB("(boolean true)"));
		assertTrue(evalB("(boolean [])"));
		assertTrue(evalB("(boolean #{})"));
		assertTrue(evalB("(boolean 1)"));
		assertTrue(evalB("(boolean :foo)"));

		assertArityError(step("(boolean)"));
		assertArityError(step("(boolean 1 2)"));
	}
	
	@Test public void testIf() {
		// basic branching
		assertEquals(1L,evalL("(if true 1 2)"));
		assertEquals(2L,evalL("(if false 1 2)"));

		// expressions
		assertEquals(6L,evalL("(if (= 1 1) (* 2 3) (* 3 4))"));
		assertEquals(12L,evalL("(if (nil? false) (* 2 3) (* 3 4))"));

		
		// null return for missing false branch
		assertNull(eval("(if false 1)"));
		
		// TODO: should these be arity errors?
		assertArityError(step("(if)"));
		assertArityError(step("(if 1)"));
		assertArityError(step("(if 1 2 3 4)"));
	}

	@Test
	public void testEquals() {
		assertTrue(eval("=") instanceof CoreFn);
		assertTrue(evalB("(= \\a)"));
		assertTrue(evalB("(= 1 1)"));
		assertFalse(evalB("(= 1 2)"));
		assertFalse(evalB("(= 1 nil)"));
		assertFalse(evalB("(= 1 1.0)"));
		assertFalse(evalB("(= \\a \\b)"));
		assertFalse(evalB("(= :foo :baz)"));
		assertFalse(evalB("(= :foo 'foo)"));
		assertTrue(evalB("(= :bar :bar :bar)"));
		assertFalse(evalB("(= :bar :bar :bar 2)"));
		assertFalse(evalB("(= *juice* *juice*)"));
		assertTrue(evalB("(=)"));
		assertTrue(evalB("(= nil nil)"));
	}

	@Test
	public void testComparisons() {
		assertTrue(evalB("(== 0 0.0)"));
		assertTrue(evalB("(< 1)"));
		assertTrue(evalB("(> 3 2 1)"));
		assertTrue(evalB("(>= 3 2)"));
		assertTrue(evalB("(<= 1 2.0 2)"));

		assertTrue(evalB("(==)"));
		assertTrue(evalB("(<=)"));
		assertTrue(evalB("(>=)"));
		assertTrue(evalB("(<)"));
		assertTrue(evalB("(>)"));

		assertCastError(step("(== nil nil)"));
		assertCastError(step("(> nil)"));
		assertCastError(step("(< 1 :foo)"));
		assertCastError(step("(<= 1 3 \"hello\")"));
		assertCastError(step("(>= nil 1.0)"));

		// TODO: decide if we want short-circuiting behaviour on casts? Probably not?
		// assertCastError(step("(>= 1 2 3 '*balance*)"));
		assertFalse(evalB("(>= 1 2 3 '*balance*)"));
	}
	
	@Test
	public void testLog() {
		AVector<ACell> v0=Vectors.of(1L, 2L);

		Context<?> c=step("(log 1 2)");
		assertEquals(v0,c.getResult());
		ABlobMap<Address,AVector<AVector<ACell>>> log=c.getLog();
		assertEquals(1,log.count()); // only one address did a log
		assertNotNull(log);
		
		AVector<AVector<ACell>> alog=log.get(c.getAddress());
		assertEquals(1,alog.count()); // one log entry only
		assertEquals(v0,alog.get(0));
		
		// do second log in same context
		AVector<ACell> v1=Vectors.of(3L, 4L);
		c=step(c,"(log 3 4)");
		log=c.getLog();
		
		alog=log.get(c.getAddress());
		assertEquals(2,alog.count()); // should be two entries now
		assertEquals(v0,alog.get(0));
		assertEquals(v1,alog.get(1));
	}
	
	
	@Test
	public void testLogInActor() {
		AVector<ACell> v0=Vectors.of(1L, 2L);

		Context<?> c=step("(deploy '(do (defn event [& args] (apply log args)) (defn non-event [& args] (rollback (apply log args))) (export non-event event)))");
		Address actor=(Address) c.getResult();
		
		assertEquals(0,c.getLog().count()); // Nothing logged so far
	
		// call actor function
		c=step(c,"(call "+actor+" (event 1 2))");
		ABlobMap<Address, AVector<AVector<ACell>>> log = c.getLog();
		
		AVector<AVector<ACell>> alog = log.get(actor);
		assertEquals(1,alog.count()); // should be one entry by the actor
		assertEquals(v0,alog.get(0));
		
		// call actor function which rolls back - should also roll back log
		c=step(c,"(call "+actor+" (non-event 3 4))");
		alog = log.get(actor);
		assertEquals(1,alog.count()); // should be one entry by the actor
		assertEquals(v0,alog.get(0));

	}

	@Test
	public void testVector() {
		assertEquals(Vectors.of(1L, 2L), eval("(vector 1 2)"));
		assertEquals(Vectors.empty(), eval("(vector)"));
	}

	@Test
	public void testIdentity() {
		assertNull(eval("(identity nil)"));
		assertEquals(Vectors.of(1L, 2L), eval("(identity [1 2])"));

		assertArityError(step("(identity)"));
		assertArityError(step("(identity 1 2)"));
	}

	@Test
	public void testConcat() {
		assertNull(eval("(concat)"));
		assertNull(eval("(concat nil nil nil)"));

		// singleton identity preservation
		assertSame(Vectors.empty(), eval("(concat [])"));
		assertSame(Vectors.empty(), eval("(concat nil [])"));
		assertSame(Lists.empty(), eval("(concat () nil)"));
		assertSame(Lists.empty(), eval("(concat nil ())"));
		
		assertCastError(step("(concat 1 2)"));
		assertCastError(step("(concat \"Foo\" \"Bar\")"));

		assertEquals(Vectors.of(1L, 2L, 3L, 4L), eval("(concat [1 2] [3 4])"));
		assertEquals(Vectors.of(1L, 2L, 3L, 4L), eval("(concat nil [1 2] '(3) [] [4])"));
		assertEquals(List.of(1L, 2L, 3L, 4L), eval("(concat nil '(1 2) [3 4] nil)"));
	}

	@Test
	public void testMapcat() {
		assertNull(eval("(mapcat (fn[x] x) nil)"));
		assertEquals(Vectors.of(2L, 2L), eval("(mapcat (fn [x] [x x]) [2])"));
		assertEquals(Vectors.empty(), eval("(mapcat (fn [x] nil) [1 2 3 4])"));
		assertEquals(Lists.empty(), eval("(mapcat (fn [x] nil) '(1 2 3 4))"));
		assertEquals(Vectors.of(1L, 2L, 3L), eval("(mapcat vector [1 2 3])"));
		assertEquals(Vectors.of(1L, 2L, 2L, 3L, 3L, 4L), eval("(mapcat (fn [a b] [a b]) [1 2 3] [2 3 4])"));

		assertArityError(step("(mapcat identity)"));
		assertCastError(step("(mapcat nil [1 2])"));
	}

	@Test
	public void testHashMap() {
		assertEquals(Maps.empty(), eval("(hash-map)"));
		assertEquals(Maps.of(1L, 2L), eval("(hash-map 1 2)"));
		assertEquals(Maps.of(null, null), eval("(hash-map nil nil)"));
		assertEquals(Maps.of(1L, 2L, 3L, 4L), eval("(hash-map 3 4 1 2)"));
		
		// Check last value of equal keys is used
		assertEquals(Maps.of(1L, 4L), eval("(hash-map 1 2 1 3 1 4)"));
		assertEquals(Maps.of(1L, 2L), eval("(hash-map 1 4 1 3 1 2)"));

		assertArityError(step("(hash-map 1)"));
		assertArityError(step("(hash-map 1 2 3)"));
		assertArityError(step("(hash-map 1 2 3 4 5)"));
	}
	
	@Test
	public void testBlobMap() {
		assertEquals(BlobMaps.empty(), eval("(blob-map)"));
		
		assertCastError(step("(assoc (blob-map) :foo 10)")); // bad key types cause cast errors. See Issue #101
		
		assertArityError(step("(blob-map 1)"));
	}

	@Test
	public void testKeys() {
		assertEquals(Vectors.empty(), eval("(keys {})"));
		assertEquals(Vectors.of(1L), eval("(keys {1 2})"));
		assertEquals(Sets.of(1L, 3L, 5L), eval("(set (keys {1 2 3 4 5 6}))"));

		assertEquals(Vectors.empty(),RT.keys(BlobMaps.empty()));
		assertEquals(Vectors.of(Init.HERO),RT.keys(BlobMap.of(Init.HERO, 1L)));
		
		assertCastError(step("(keys 1)"));
		assertCastError(step("(keys [])"));
		assertCastError(step("(keys nil)")); // TODO: maybe empty set?

		assertArityError(step("(keys)"));
		assertArityError(step("(keys {} {})"));
	}

	@Test
	public void testValues() {
		assertEquals(Vectors.empty(), eval("(values {})"));
		assertEquals(Vectors.of(2L), eval("(values {1 2})"));
		assertEquals(Sets.of(2L, 4L, 6L), eval("(set (values {1 2 3 4 5 6}))"));

		assertCastError(step("(values 1)"));
		assertCastError(step("(values [])"));
		assertCastError(step("(values nil)")); // TODO: maybe empty set?

		assertArityError(step("(values)"));
		assertArityError(step("(values {} {})"));
	}

	@Test
	public void testHashSet() {
		assertEquals(Sets.empty(), eval("(hash-set)"));
		assertEquals(Sets.of(1L, 2L), eval("(hash-set 1 2)"));
		assertEquals(Sets.of((Object) null), eval("(hash-set nil nil)"));
		assertEquals(Sets.of(1L, 2L, 3L, 4L), eval("(hash-set 3 4 1 2)"));
		
		// de-duplication
		assertEquals(Sets.of(1L, 2L, 3L), eval("(hash-set 1 2 3 1)"));
		assertEquals(Sets.of((Long)null), eval("(hash-set nil nil nil)"));
		assertEquals(Sets.of(Sets.empty()), eval("(hash-set (hash-set) (hash-set))"));
		
		assertEquals(Sets.of((Object) null), eval("(hash-set nil)"));
	}

	@Test
	public void testStr() {
		assertEquals("", evalS("(str)"));
		assertEquals("1", evalS("(str 1)"));
		assertEquals("12", evalS("(str 1 2)"));
		assertEquals("baz", evalS("(str \"baz\")"));
		assertEquals("bazbar", evalS("(str \"baz\" \"bar\")"));
		assertEquals("baz", evalS("(str \\b \\a \\z)"));
		assertEquals(":foo", evalS("(str :foo)"));
		assertEquals("nil", evalS("(str nil)"));
		assertEquals("true", evalS("(str true)"));
		assertEquals("cafebabe", evalS("(str (blob \"CAFEBABE\"))"));
	}
	
	@Test
	public void testAssert() {
		assertNull(eval("(assert)"));
		assertNull(eval("(assert true)"));
		assertNull(eval("(assert (= 1 1))"));
		assertNull(eval("(assert '(= 1 2))")); // form itself is truthy, not evaluated
		assertNull(eval("(assert '(assert false))")); // form itself is truthy, not evaluated
		assertNull(eval("(assert 1 2 3)"));
		
		assertAssertError(step("(assert false)"));
		assertAssertError(step("(assert true false)"));
		assertAssertError(step("(assert (= 1 2))"));
	}

	
	@Test
	public void testCeil() {
		// Double cases
		assertEquals(1.0,evalD("(ceil 0.001)"));
		assertEquals(-1.0,evalD("(ceil -1.25)"));
		
		// Integral cases
		assertEquals(-1.0,evalD("(ceil -1)"));
		assertEquals(0.0,evalD("(ceil 0)"));
		assertEquals(1.0,evalD("(ceil 1)"));
		
		// Special cases
		assertEquals(Double.NaN,evalD("(ceil ##NaN)"));
		assertEquals(Double.POSITIVE_INFINITY,evalD("(ceil (/ 1 0))"));
		assertEquals(Double.NEGATIVE_INFINITY,evalD("(ceil (/ -1 0))"));
		
		assertCastError(step("(ceil #3)"));
		assertCastError(step("(ceil :foo)"));
		assertCastError(step("(ceil nil)"));
		assertCastError(step("(ceil [])"));
		
		assertArityError(step("(ceil)"));
		assertArityError(step("(ceil :foo :bar)")); // arity > cast
	}
	
	@Test
	public void testFloor() {
		// Double cases
		assertEquals(0.0,evalD("(floor 0.001)"));
		assertEquals(-2.0,evalD("(floor -1.25)"));
		
		// Integral cases
		assertEquals(-1.0,evalD("(floor -1)"));
		assertEquals(0.0,evalD("(floor 0)"));
		assertEquals(1.0,evalD("(floor 1)"));
		
		// Special cases
		assertEquals(Double.NaN,evalD("(floor ##NaN)"));
		assertEquals(Double.POSITIVE_INFINITY,evalD("(floor (/ 1 0))"));
		assertEquals(Double.NEGATIVE_INFINITY,evalD("(floor (/ -1 0))"));
		
		assertCastError(step("(floor #666)"));
		assertCastError(step("(floor :foo)"));
		assertCastError(step("(floor nil)"));
		assertCastError(step("(floor [])"));
		
		assertArityError(step("(floor)"));
		assertArityError(step("(floor :foo :bar)")); // arity > cast
	}
	
	@Test
	public void testAbs() {
		// Integer cases
		assertEquals(1L,evalL("(abs 1)"));
		assertEquals(10L,evalL("(abs (byte 10))"));
		assertEquals(17L,evalL("(abs -17)"));
		assertEquals(Long.MAX_VALUE,evalL("(abs 9223372036854775807)"));
		
		// Double cases
		assertEquals(1.0,evalD("(abs 1.0)"));
		assertEquals(13.0,evalD("(abs (double -13))"));
		assertEquals(Math.pow(10,100),evalD("(abs (pow 10 100))"));
		
		// Fun Double cases
		assertEquals(Double.NaN,evalD("(abs ##NaN)"));
		assertEquals(Double.POSITIVE_INFINITY,evalD("(abs (/ 1 0))"));
		assertEquals(Double.POSITIVE_INFINITY,evalD("(abs (/ -1 0))"));
		
		// long overflow case
		assertEquals(Long.MIN_VALUE,evalL("(abs -9223372036854775808)"));
		assertEquals(Long.MAX_VALUE,evalL("(abs -9223372036854775807)"));
		
		assertArityError(step("(abs)"));
		assertArityError(step("(abs :foo :bar)")); // arity > cast
		assertCastError(step("(abs :foo)"));
	}
	
	@Test
	public void testSignum() {
		// Integer cases
		assertEquals(1L,evalL("(signum 1)"));
		assertEquals(1L,evalL("(signum (byte 10))"));
		assertEquals(-1L,evalL("(signum -17)"));
		assertEquals(1L,evalL("(signum 9223372036854775807)"));
		assertEquals(-1L,evalL("(signum -9223372036854775808)"));
		
		// Double cases
		assertEquals(0.0,evalD("(signum 0.0)"));
		assertEquals(1.0,evalD("(signum 1.0)"));
		assertEquals(-1.0,evalD("(signum (double -13))"));
		assertEquals(1.0,evalD("(signum (pow 10 100))"));
		
		// Fun Double cases
		assertEquals(Double.NaN,evalD("(signum ##NaN)"));
		assertEquals(1.0,evalD("(signum ##Inf)"));
		assertEquals(-1.0,evalD("(signum ##-Inf)"));
		
		assertArityError(step("(signum)"));
		assertArityError(step("(signum :foo :bar)")); // arity > cast
		assertCastError(step("(signum :foo)"));
	}

	@Test
	public void testNot() {
		assertFalse(evalB("(not 1)"));
		assertTrue(evalB("(not false)"));
		assertTrue(evalB("(not nil)"));
		assertFalse(evalB("(not [])"));

		assertArityError(step("(not)"));
		assertArityError(step("(not true false)"));
	}

	@Test
	public void testNth() {
		assertEquals(2L, evalL("(nth [1 2] 1)"));
		assertEquals(2L, evalL("(nth [1 2] (byte 1))"));
		assertCVMEquals('c', eval("(nth \"abc\" 2)"));

		assertArityError(step("(nth)"));
		assertArityError(step("(nth [])"));
		assertArityError(step("(nth [] 1 2)"));
		assertArityError(step("(nth 1 1 2)")); // arity > cast

		// cast errors for bad indexes
		assertCastError(step("(nth [] :foo)"));
		assertCastError(step("(nth [] nil)"));
		
		// cast errors for non-sequential objects
		assertCastError(step("(nth :foo 0)"));
		assertCastError(step("(nth 12 13)"));
		
		// BOUNDS error because treated as empty sequence
		assertBoundsError(step("(nth nil 10)"));
		assertBoundsError(step("(nth {} 10)"));

		assertBoundsError(step("(nth [1 2] 10)"));
		assertBoundsError(step("(nth [1 2] -1)"));
		assertBoundsError(step("(nth \"abc\" 3)"));
		assertBoundsError(step("(nth \"abc\" -1)"));
	}

	@Test
	public void testList() {
		assertEquals(Lists.of(1L, 2L), eval("(list 1 2)"));
		assertEquals(Lists.of(Symbols.LIST), eval("(list 'list)"));
		assertEquals(Lists.of(Symbols.LIST), eval("'(list)"));
		assertEquals(Lists.empty(), eval("(list)"));
	}

	@Test
	public void testVec() {
		assertSame(Vectors.empty(), eval("(vec nil)"));
		assertSame(Vectors.empty(), eval("(vec [])"));
		assertSame(Vectors.empty(), eval("(vec {})"));
		assertSame(Vectors.empty(), eval("(vec (blob-map))"));
		
		assertEquals(Vectors.of(1,2,3,4), eval("(vec (list 1 2 3 4))"));
		assertEquals(Vectors.of(MapEntry.of(1,2)), eval("(vec {1,2})"));

		assertCastError(step("(vec 1)"));
		assertCastError(step("(vec :foo)"));

		assertArityError(step("(vec)"));
		assertArityError(step("(vec 1 2)"));
	}

	@Test
	public void testAssocNull() {
		assertNull(eval("(assoc nil)")); // null is preserved
		assertEquals(Maps.of(1L, 2L), eval("(assoc nil 1 2)")); // assoc promotes nil to maps
		assertEquals(Maps.of(1L, 2L, 3L, 4L), eval("(assoc nil 1 2 3 4)")); // assoc promotes nil to maps
		
		// No values to assoc, retain initial nil
		assertNull(eval("(assoc nil)"));
	}

	@Test
	public void testAssocMaps() {
		// no key/values is OK
		assertEquals(Maps.empty(), eval("(assoc {})"));
		
		assertEquals(Maps.of(1L, 2L), eval("(assoc {} 1 2)"));
		assertEquals(Maps.of(1L, 2L), eval("(assoc {1 2})"));
		assertEquals(Maps.of(1L, 2L, 3L, 4L), eval("(assoc {} 1 2 3 4)"));
		assertEquals(Maps.of(1L, 2L), eval("(assoc {1 2} 1 2)"));
		assertEquals(Maps.of(1L, 2L, 3L, 4L), eval("(assoc {1 2} 3 4)"));
		assertEquals(Maps.of(1L, 2L, 3L, 4L, 5L, 6L), eval("(assoc {1 2} 3 4 5 6)"));

		assertArityError(step("(assoc {} 1 2 3)"));
		assertArityError(step("(assoc {} 1)"));

	}
	
	@Test
	public void testAssocIn() {
		// empty index cases - type of first arg not checked since no idexing happens
		assertEquals(2L, evalL("(assoc-in {} [] 2)")); // empty indexes returns value
		assertEquals(2L, evalL("(assoc-in nil [] 2)")); // empty indexes returns value
		assertEquals(2L, evalL("(assoc-in :old [] 2)")); // empty indexes returns value
		assertEquals(2L, evalL("(assoc-in 13 nil 2)")); // empty indexes returns value (nil considered empty seq)
		
		// map cases
		assertEquals(Maps.of(1L,2L), eval("(assoc-in {} [1] 2)"));
		assertEquals(Maps.of(1L,2L,3L,4L), eval("(assoc-in {3 4} [1] 2)"));
		assertEquals(Maps.of(1L,2L), eval("(assoc-in nil [1] 2)"));
		assertEquals(Maps.of(1L,Maps.of(5L,6L),3L,4L), eval("(assoc-in {3 4} [1 5] 6)"));
		
		// vector cases
		assertEquals(Vectors.of(1L, 5L, 3L),eval("(assoc-in [1 2 3] [1] 5)"));
		assertEquals(Vectors.of(5L),eval("(assoc-in [1] [0] 5)"));
		assertEquals(MapEntry.of(1L, 5L),eval("(assoc-in (first {1 2}) [1] 5)"));
		
		// Cast error - wrong key types
		assertCastError(step("(assoc-in (blob-map) :foo :bar)"));
		
		// Cast errors - not associative collections
		assertCastError(step("(assoc-in 1 [2] 3)"));
		assertCastError(step("(assoc-in [1] [:foo] 3)"));
		assertCastError(step("(assoc-in #{3} [2] :fail)"));
		
		// cast errors - paths not sequences
		assertCastError(step("(assoc-in {} #{:a :b} 42)")); // See Issue 95
		assertCastError(step("(assoc-in {} :foo 42)")); // See Issue 95
		
		// Arity error
		assertArityError(step("(assoc-in)"));
		assertArityError(step("(assoc-in nil)"));
		assertArityError(step("(assoc-in nil 1)"));
		assertArityError(step("(assoc-in nil 1 2 3)"));
		assertArityError(step("(assoc-in :bad-struct [1] 2 :blah)")); // ARITY before CAST
	}

	@Test
	public void testAssocFailures() {
		assertCastError(step("(assoc 1 1 2)"));
		assertCastError(step("(assoc :foo)"));
		assertCastError(step("(assoc #{} :foo true)"));
		
		assertCastError(step("(assoc [1 2 3] 1.4 :foo)"));
		assertCastError(step("(assoc [1 2 3] nil :foo)"));
		
		assertCastError(step("(assoc [] 2 :foo)"));
		assertCastError(step("(assoc (list) 2 :fail)"));

		// Arity error
		assertArityError(step("(assoc)"));
		assertArityError(step("(assoc nil 1)"));
		assertArityError(step("(assoc nil 1 2 3)"));
		assertArityError(step("(assoc 1 1)")); // ARITY before CAST
	}

	@Test
	public void testAssocVectors() {
		assertEquals(Vectors.empty(), eval("(assoc [])"));
		assertEquals(Vectors.of(2L, 1L), eval("(assoc [1 2] 0 2 1 1)"));

		assertCastError(step("(assoc [] 1 7)"));
		assertCastError(step("(assoc [] -1 7)"));

		assertCastError(step("(assoc [1 2] :a 2)"));

		assertArityError(step("(assoc [] 1 2 3)"));
		assertArityError(step("(assoc [] 1)"));
	}

	@Test
	public void testDissoc() {
		assertEquals(Maps.empty(), eval("(dissoc {1 2} 1)"));
		assertEquals(Maps.of(1L, 2L), eval("(dissoc {1 2 3 4} 3)"));
		assertEquals(Maps.of(1L, 2L), eval("(dissoc {1 2} 3)"));
		assertEquals(Maps.of(1L, 2L), eval("(dissoc {1 2})"));
		assertEquals(Maps.empty(), eval("(dissoc nil 1)"));
		assertEquals(Maps.empty(), eval("(dissoc {1 2 3 4} 1 3)"));
		assertEquals(Maps.of(3L, 4L), eval("(dissoc {1 2 3 4} 1 2)"));

		assertCastError(step("(dissoc 1 1 2)"));
		assertCastError(step("(dissoc #{})"));
		assertCastError(step("(dissoc [])"));

		assertArityError(step("(dissoc)"));
	}

	@Test
	public void testContainsKey() {
		assertFalse(evalB("(contains-key? {} 1)"));
		assertFalse(evalB("(contains-key? {} nil)"));
		assertTrue(evalB("(contains-key? {1 2} 1)"));

		assertFalse(evalB("(contains-key? #{} 1)"));
		assertFalse(evalB("(contains-key? #{1 2 3} nil)"));
		assertFalse(evalB("(contains-key? #{false} true)"));
		assertTrue(evalB("(contains-key? #{1 2} 1)"));
		assertTrue(evalB("(contains-key? #{nil 2 3} nil)"));

		assertFalse(evalB("(contains-key? [] 1)"));
		assertFalse(evalB("(contains-key? [0 1 2] :foo)"));
		assertTrue(evalB("(contains-key? [3 4] 1)"));

		assertFalse(evalB("(contains-key? nil 1)"));

		assertArityError(step("(contains-key? 3)"));
		assertArityError(step("(contains-key? {} 1 2)"));
		assertCastError(step("(contains-key? 3 4)"));
	}

	@Test
	public void testDisj() {
		assertEquals(Sets.of(2L), eval("(disj #{1 2} 1)"));
		assertEquals(Sets.of(1L, 2L), eval("(disj #{1 2} 1.0)"));
		assertSame(Sets.empty(), eval("(disj #{1} 1)"));
		assertSame(Sets.empty(), eval("(reduce disj #{1 2} [1 2])"));
		assertEquals(Sets.empty(), eval("(disj #{} 1)"));
		assertEquals(Sets.of(1L, 2L, 3L), eval("(disj (set [3 2 1 2 4]) 4)"));
		
		// nil is treated as empty set
		assertSame(Sets.empty(), eval("(disj nil 1)"));
		assertSame(Sets.empty(), eval("(disj nil nil)"));

		assertCastError(step("(disj [] 1)"));
		assertArityError(step("(disj)"));
		assertArityError(step("(disj nil 1 2)"));
	}

	@Test
	public void testSet() {
		assertEquals(Sets.of(1L, 2L, 3L), eval("(set [3 2 1 2])"));
		assertEquals(Sets.of(1L, 2L, 3L), eval("(set #{1 2 3})"));
		
		assertEquals(Sets.empty(), eval("(set nil)")); // nil treated as empty set of elements

		assertArityError(step("(set)"));
		assertArityError(step("(set 1 2)"));

		assertCastError(step("(set 1)"));
	}
	
	@Test
	public void testSubsetQ() {
		assertTrue(evalB("(subset? #{} #{})"));
		assertTrue(evalB("(subset? #{} #{1 2 3})"));
		assertTrue(evalB("(subset? #{2 3} #{1 2 3 4})"));
		
		// check nil is handled as empty set
		assertTrue(evalB("(subset? nil #{})"));
		assertTrue(evalB("(subset? #{} nil)"));
		assertTrue(evalB("(subset? nil #{1 2 3})"));
		assertFalse(evalB("(subset? #{1 2 3} nil)"));

		
		assertFalse(evalB("(subset? #{2 3} #{1 2})"));
		assertFalse(evalB("(subset? #{1 2 3} #{0})"));
		assertFalse(evalB("(subset? #{#{}} #{#{1}})"));
		
		assertArityError(step("(subset?)"));
		assertArityError(step("(subset? 1)"));
		assertArityError(step("(subset? 1 2 3)"));

		assertCastError(step("(subset? 1 2)"));
		assertCastError(step("(subset? #{} [2])"));
	}
	
	@Test
	public void testSetUnion() {
		assertEquals(Sets.empty(),eval("(union)"));

		assertEquals(Sets.empty(),eval("(union nil)"));
		assertEquals(Sets.empty(),eval("(union #{})"));
		assertEquals(Sets.of(1L,2L),eval("(union #{1 2})"));
		
		// nil treated as empty set in all cases
		assertEquals(Sets.of(1L,2L),eval("(union nil #{1 2})"));
		assertEquals(Sets.of(1L,2L),eval("(union #{1 2} nil)"));

		assertEquals(Sets.of(1L,2L,3L),eval("(union #{1 2} #{3})"));
		
		assertEquals(Sets.of(1L,2L,3L,4L,5L),eval("(union #{1 2} #{3} #{4 5})"));
		
		assertCastError(step("(union :foo)"));
		assertCastError(step("(union [1] [2 3])"));
	}
	
	@Test
	public void testSetIntersection() {
		assertEquals(Sets.empty(),eval("(intersection nil)"));
		assertEquals(Sets.empty(),eval("(intersection #{})"));
		assertEquals(Sets.of(1L,2L),eval("(intersection #{1 2})"));

		assertEquals(Sets.empty(),eval("(intersection #{1 2} #{3})"));
		assertEquals(Sets.empty(),eval("(intersection #{1 2 3} nil)"));
		
		assertEquals(Sets.of(2L,3L),eval("(intersection #{1 2 3} #{2 3 4})"));
		
		assertEquals(Sets.of(3L),eval("(intersection #{1 2 3} #{2 3 4} #{3 4 5})"));

		assertArityError(step("(intersection)"));
		
		assertCastError(step("(intersection :foo)"));
		assertCastError(step("(intersection [1] [2 3])"));
	}
	
	@Test
	public void testSetDifference() {
		assertEquals(Sets.empty(),eval("(difference nil)"));
		assertEquals(Sets.empty(),eval("(difference #{})"));
		assertEquals(Sets.of(1L,2L),eval("(difference #{1 2})"));

		assertEquals(Sets.of(1L,2L),eval("(difference #{1 2} #{3})"));
		
		assertEquals(Sets.of(2L,3L),eval("(difference #{1 2 3} #{1 4})"));
		
		assertEquals(Sets.of(3L),eval("(difference #{1 2 3} #{2 4} #{1 5})"));

		assertArityError(step("(difference)"));
		
		assertCastError(step("(difference :foo)"));
		assertCastError(step("(difference [1] [2 3])"));
	}


	@Test
	public void testFirst() {
		assertEquals(1L, evalL("(first [1 2])"));
		assertEquals(1L, evalL("(first '(1 2 3 4))"));

		assertBoundsError(step("(first [])"));
		assertBoundsError(step("(first nil)"));

		assertArityError(step("(first)"));
		assertArityError(step("(first [1] 2)"));
		assertCastError(step("(first 1)"));
		assertCastError(step("(first :foo)"));
	}

	@Test
	public void testSecond() {
		assertEquals(2L, evalL("(second [1 2])"));

		assertBoundsError(step("(second [2])"));
		assertBoundsError(step("(second nil)"));

		assertArityError(step("(second)"));
		assertArityError(step("(second [1] 2)"));
		assertCastError(step("(second 1)"));
	}

	@Test
	public void testLast() {
		assertEquals(2L, evalL("(last [1 2])"));
		assertEquals(4L, evalL("(last [4])"));

		assertBoundsError(step("(last [])"));
		assertBoundsError(step("(last nil)"));

		assertArityError(step("(last)"));
		assertArityError(step("(last [1] 2)"));
		assertCastError(step("(last 1)"));
	}

	@Test
	public void testNext() {
		assertEquals(Vectors.of(2L, 3L), eval("(next [1 2 3])"));
		assertEquals(Lists.of(2L, 3L), eval("(next '(1 2 3))"));
		
		assertNull(eval("(next nil)"));
		assertNull(eval("(next [1])"));
		assertNull(eval("(next {1 2})"));

		assertArityError(step("(next)"));
		assertArityError(step("(next [1] [2 3])"));

		assertCastError(step("(next 1)"));
		assertCastError(step("(next :foo)"));
	}

	@Test
	public void testConj() {
		assertEquals(Vectors.of(1L, 2L, 3L), eval("(conj [1 2] 3)"));
		assertEquals(Lists.of(3L, 1L, 2L), eval("(conj (list 1 2) 3)"));
		assertEquals(Vectors.of(3L), eval("(conj nil 3)"));
		assertEquals(Sets.of(3L), eval("(conj #{} 3)"));
		assertEquals(Sets.of(3L), eval("(conj #{3} 3)"));
		assertEquals(Maps.of(1L, 2L), eval("(conj {} [1 2])"));
		assertEquals(Maps.of(1L, 2L, 5L, 6L), eval("(conj {1 3 5 6} [1 2])"));
		assertEquals(Vectors.of(1L), eval("(conj nil 1)"));
		assertEquals(Lists.of(1L), eval("(conj (list) 1)"));
		assertEquals(Lists.of(1L, 2L), eval("(conj (list 2) 1)"));
		assertEquals(Sets.of(1L, 2L, 3L), eval("(conj #{2 3} 1)"));
		assertEquals(Sets.of(1L, 2L, 3L), eval("(conj #{2 3 1} 1)"));

		assertCastError(step("(conj {} 2)")); // can't cast long to a map entry
		assertCastError(step("(conj {} [1 2 3])")); // wrong size vector for a map entry

		assertCastError(step("(conj 1 2)"));
		assertCastError(step("(conj (str :foo) 2)"));

		assertArityError(step("(conj)"));
	}

	@Test
	public void testCons() {
		assertEquals(Lists.of(3L, 1L, 2L), eval("(cons 3 (list 1 2))"));
		assertEquals(Lists.of(3L, 1L, 2L), eval("(cons 3 [1 2])"));
		assertEquals(Lists.of(3L, 1L, 2L), eval("(cons 3 1 [2])"));

		assertEquals(Lists.of(3L), eval("(cons 3 nil)"));
		assertEquals(Lists.of(1L, 3L), eval("(cons 1 #{3})"));
		assertEquals(Lists.of(1L), eval("(cons 1 [])"));

		assertCastError(step("(cons 1 2)"));

		assertArityError(step("(cons [])"));
		assertArityError(step("(cons 1)"));
		assertArityError(step("(cons)"));

		assertCastError(step("(cons 1 2 3 4 5)"));
	}

	@Test
	public void testInto() {
		// nil as data structure
		assertNull(eval("(into nil nil)"));
		assertEquals(Maps.of(1L,2L),eval("(into nil {1 2})"));
		assertEquals(Vectors.empty(), eval("(into nil [])"));
		assertEquals(Vectors.of(1L, 2L, 3L), eval("(into nil [1 2 3])"));
		
		assertEquals(Vectors.of(1L, 2L, 3L), eval("(into [1 2] [3])"));
		assertEquals(Vectors.of(1L, 2L, 3L), eval("(into [1 2 3] nil)"));
		assertEquals(Vectors.of(1L, 2L, 3L), eval("(into nil [1 2 3])"));

		assertEquals(Lists.of(2L, 1L, 3L, 4L), eval("(into '(3 4) '(1 2))"));
		
		assertEquals(Sets.of(1L, 2L, 3L), eval("(into #{} [1 2 1 2 3])"));
		
		// map as data structure
		assertEquals(Maps.empty(), eval("(into {} [])"));
		assertEquals(Maps.of(1L, 2L, 3L, 4L), eval("(into {} [[1 2] [3 4] [1 2]])"));
		
		assertEquals(Vectors.of(MapEntry.of(1L, 2L)), eval("(into [] {1 2})"));

		assertCastError(step("(into 1 [2 3])")); // long is not a conjable data structure
		assertCastError(step("(into nil :foo)")); // keyword is not a sequence of elements
		
		assertCastError(step("(into {} [nil])")); // nil is not a MapEntry
		assertCastError(step("(into {} [[:foo]])")); // length 1 vector shouldn't convert to MapEntry
		assertCastError(step("(into {} [[:foo :bar :baz]])")); // length 1 vector shouldn't convert to MapEntry
		assertCastError(step("(into {1 2} [2 3])")); // longs are not map entries
		assertCastError(step("(into {1 2} [[] []])")); // empty vectors are not map entries

		assertArityError(step("(into)"));
		assertArityError(step("(into inc)"));
		assertArityError(step("(into 1 2 3)")); // arity > cast
	}
	
	@Test
	public void testMerge() {
		assertEquals(Maps.empty(),eval("(merge)"));
		assertEquals(Maps.empty(),eval("(merge nil)"));
		assertEquals(Maps.empty(),eval("(merge nil nil)"));
		
		assertEquals(Maps.of(1L,2L,3L,4L),eval("(merge {1 2} {3 4})"));
		assertEquals(Maps.of(1L,2L,3L,4L),eval("(merge {1 2 3 4} {})"));
		assertEquals(Maps.of(1L,2L,3L,4L),eval("(merge nil {1 2 3 4})"));

		assertEquals(Maps.of(1L,3L),eval("(merge {1 2} {1 3})"));
		assertEquals(Maps.of(1L,3L),eval("(merge nil {1 2} nil {1 3} nil)"));

		assertCastError(step("(merge [])"));
		assertCastError(step("(merge {} [1 2 3])"));
		assertCastError(step("(merge nil :foo)"));
	}
	
	@Test
	public void testDotimes() {
		assertEquals(Vectors.of(0L, 1L, 2L), eval("(do (def a []) (dotimes [i 3] (def a (conj a i))) a)"));
		assertEquals(Vectors.empty(), eval("(do (def a []) (dotimes [i 0] (def a (conj a i))) a)"));
		assertEquals(Vectors.empty(), eval("(do (def a []) (dotimes [i -1.5] (def a (conj a i))) a)"));
		assertEquals(Vectors.empty(), eval("(do (def a []) (dotimes [i -1.5]) a)"));
		
		assertCastError(step("(dotimes [1 10])"));
		assertCastError(step("(dotimes [i :foo])"));
		
		assertCastError(step("(dotimes :foo)"));
		
		assertArityError(step("(dotimes)"));
		assertArityError(step("(dotimes [i])"));
		assertArityError(step("(dotimes [i 2 3])"));

	}
	
	@Test
	public void testDoouble() {
		assertEquals(-13.0,evalD("(double -13)")); 
		assertEquals(1.0,evalD("(double true)")); // ?? cast OK?
		
		assertEquals(255.0,evalD("(double (byte -1))")); // byte should be 0-255

		
		assertCastError(step("(double :foo)"));
		
		assertArityError(step("(double)"));
		assertArityError(step("(double :foo :bar)"));
	}
	
	@Test
	public void testMacro() {
		assertTrue(eval("(macro [x] x)") instanceof AExpander);
		assertCastError(step("((macro [x] x) 42)"));
		
		// TODO: is this sane?
		assertCastError(step("(let [m (macro [x] x)] (m 42))"));
	}

	@Test
	public void testMap() {
		assertEquals(Vectors.of(2L, 3L), eval("(map inc [1 2])"));
		assertEquals(Vectors.of(2L, 3L), eval("(map inc '(1 2))")); // TODO is this right?
		assertEquals(Vectors.empty(), eval("(map inc nil)")); // TODO is this right?
		assertEquals(Vectors.of(4L, 6L), eval("(map + [1 2] [3 4 5])"));
		assertEquals(Vectors.of(3L), eval("(map + [1 2 3] [2])"));
		assertEquals(Vectors.of(1L, 2L, 3L), eval("(map identity [1 2 3])"));

		assertCastError(step("(map 1 [1])"));
		assertCastError(step("(map 1 [] [] [])"));
		assertCastError(step("(map inc 1)"));

		assertArityError(step("(map)"));
		assertArityError(step("(map inc)"));
		assertArityError(step("(map 1)")); // arity > cast
	}
	
	@Test
	public void testFor() {
		assertEquals(Vectors.empty(), eval("(for [x nil] (inc x))"));
		assertEquals(Vectors.empty(), eval("(for [x []] (inc x))"));
		assertEquals(Vectors.of(2L,3L), eval("(for [x '(1 2)] (inc x))"));
		assertEquals(Vectors.of(2L,3L), eval("(for [x [1 2]] (inc x))"));
		
		// TODO: maybe dubious error types?
		
		assertCastError(step("(for 1 1)")); // bad binding form
		assertArityError(step("(for [x] 1)")); // bad binding form
		assertArityError(step("(for [x [1 2] [2 3]] 1)")); // bad binding form length
		assertCastError(step("(for [x :foo] 1)")); // bad sequence

		assertArityError(step("(for)"));
		assertArityError(step("(for [] nil nil)"));
		assertCastError(step("(for 1)")); // arity > cast
	}

	@Test
	public void testMapv() {
		assertEquals(Vectors.empty(), eval("(map inc nil)"));
		assertEquals(Vectors.of(2L, 3L), eval("(mapv inc [1 2])"));
		assertEquals(Vectors.of(4L, 6L), eval("(mapv + '(1 2) '(3 4 5))"));

		assertArityError(step("(mapv)"));
		assertArityError(step("(mapv inc)"));
	}

	@Test
	public void testReduce() {
		assertEquals(24L, evalL("(reduce * 1 [1 2 3 4])"));
		assertEquals(2L, evalL("(reduce + 2 [])"));
		assertEquals(2L, evalL("(reduce + 2 nil)"));

		// add values, indexing into map entries as vectors
		assertEquals(10.0, evalD("(reduce (fn [acc me] (+ acc (me 1))) 0.0 {:a 1, :b 2, 107 3, nil 4})"));
		// reduce over map, destructuring keys and values
		assertEquals(100.0, evalD(
				"(reduce (fn [acc [k v]] (let [x (double (v nil))] (+ acc (* x x)))) 0.0 {true {nil 10}})"));

		assertEquals(Lists.of(3,2,1), eval("(reduce conj '() '(1 2 3))"));

		
		assertCastError(step("(reduce 1 2 [])"));
		assertCastError(step("(reduce + 2 :foo)"));

		assertArityError(step("(reduce +)"));
		assertArityError(step("(reduce + 1)"));
		assertArityError(step("(reduce + 1 [2] [3])"));
	}
	
	@Test public void testReduceFail() {
		// shouldn't fail because function never called
		assertEquals(2L,evalL("(reduce address 2 [])"));
		
		assertArityError(step("(reduce address 2 [:foo :bar])"));
		assertCastError(step("(reduce (fn [a x] (address x)) 2 [:foo :bar])"));
	}
	
	@Test
	public void testReduced() {
		assertEquals(Vectors.of(2L,3L), eval("(reduce (fn [i v] (if (== v 3) (reduced [i v]) v)) 1 [1 2 3 4 5])"));
	
		assertArityError(step("(reduced)"));
		assertArityError(step("(reduced 1 2)"));
		
		// reduced on its own is an exceptional result
		assertError(ErrorCodes.UNEXPECTED,step("(reduced 1)"));
	}

	@Test
	public void testReturn() {
		// basic return mechanics
		assertEquals(1L,evalL("(return 1)"));

		assertEquals(Vectors.empty(), eval("(let [f (fn [x] (+ 1 (return x)))] (f []))")); // return in function body
		assertEquals(Vectors.empty(), eval("(let [f (fn [x] (let [a (return x)] 2))] (f []))")); // return in let
																									// binding
		assertEquals(Vectors.empty(), eval("(let [f (fn [x] (let [a 2] (return x) a))] (f []))")); // return in let body
		assertEquals(Vectors.empty(), eval("(let [f (fn [x] (do (return x) 2))] (f []))")); // return in do
		assertEquals(Vectors.empty(), eval("(let [f (fn [x] (return (return x)))] (f []))")); // nested returns
		assertEquals(Vectors.empty(), eval("(let [f (fn [x] ((return x) 2 3))] (f []))")); // return in function call
																							// position
		assertEquals(Vectors.empty(), eval("(let [f (fn [x] (= 2 (return x) 3))] (f []))")); // return in function arg
		assertEquals(Vectors.empty(), eval("(let [f (fn [x] (if (return x) 2 3))] (f []))")); // return in cond test
		assertEquals(Vectors.empty(), eval("(let [f (fn [x] (if true (return x) 3))] (f []))")); // return in cond
																									// result
		assertEquals(Vectors.empty(), eval("(let [f (fn [x] (if true [] (return x)))] (f 3))")); // return in cond
																									// default
		assertArityError(step("(return)"));
		assertArityError(step("(return 1 2)"));
	}

	@Test
	public void testLoop() {
		assertNull(eval("(loop [])"));
		assertEquals(Keywords.FOO,eval("(loop [] :foo)"));
		assertEquals(Keywords.FOO,eval("(loop [] 1 2 3 :foo)"));
		assertEquals(Keywords.FOO,eval("(loop [a :foo] :bar a)"));
		
		assertCompileError(step("(loop [a])")); 
		assertCompileError(step("(loop)")); // Issue #80
		assertCompileError(step("(loop :foo)")); // Not a binting vector
	}
	
	@Test
	public void testRecordLookup() {
		assertEquals(INITIAL.getAccounts(),eval("(*state* :accounts)"));
		assertEquals(Keywords.FOO,eval("(*state* [ 982788 ] :foo )"));
		assertNull(eval("(*state* [1 2 3])")); // Issue #85
		
		assertArityError(step("(*state* :accounts :foo :bar)"));
	}
	
	@Test
	public void testRecur() {
		// test factorial with accumulator
		assertEquals(120L, evalL("(let [f (fn [a x] (if (> x 1) (recur (* a x) (dec x)) a))] (f 1 5))"));

		assertArityError(step("(let [f (fn [x] (recur x x))] (f 1))"));
		assertJuiceError(step("(let [f (fn [x] (recur x))] (f 1))"));

		// should hit depth limits before running out of juice
		// TODO: think about letrec?
		assertDepthError(step("(do   (def f (fn [x] (recur (f x))))   (f 1))"));

		// basic return mechanics
		assertError(ErrorCodes.UNEXPECTED,step("(recur 1)"));
	}
	
	@Test
	public void testRecurMultiFn() {
		// test function that should exit on recur with value 13
		Context<?> ctx=step("(defn f ([] 13) ([a] (inc (recur))))");

		assertEquals(13L,evalL(ctx,"(f)"));
		assertEquals(13L,evalL(ctx,"(f :foo)"));
		assertArityError(step(ctx,"(f 1 2)"));
	}

	@Test
	public void testHalt() {
		assertEquals(1L, evalL("(do (halt 1) (assert false))"));
		assertNull(eval("(do (halt) (assert false))"));

		// halt should not roll back state changes
		{
			Context<?> ctx = step("(do (def a 13) (halt 2))");
			assertCVMEquals(2L, ctx.getResult());
			assertEquals(13L, evalL(ctx, "a"));
		}
		
		// Halt should return from a smart contract call but still have state changes
		{
			Context<?> ctx=step("(def act (deploy '(do (def g :foo) (defn f [] (def g 3) (halt 2) 1) (export f))))");
			assertTrue(ctx.getResult() instanceof Address);
			assertEquals(Keywords.FOO, eval(ctx,"(lookup act 'g)")); // initial value of g
			ctx=step(ctx,"(call act (f))");
			assertCVMEquals(2L, ctx.getResult()); // halt value returned
			assertCVMEquals(3L, eval(ctx,"(lookup act 'g)")); // g has been updated
		}

		assertArityError(step("(halt 1 2)"));
	}
	
	@Test
	public void testFail() {
		assertAssertError(step("(fail)"));
		assertAssertError(step("(fail \"Foo\")"));
		assertAssertError(step("(fail :ASSERT \"Foo\")"));
		assertAssertError(step("(fail :foo)"));
		
		assertAssertError(step("(fail)"));
		
		{ // need to double-step this: can't define macro and use it in the same expression?
			Context<?> ctx=step("(defmacro check [condition reaction] '(if (not ~condition) ~reaction))");
			assertAssertError(step(ctx,"(check (= (+ 2 2) 5) (fail \"Laws of arithmetic violated\"))"));
		}
		
		// cannot have null error code
		assertArgumentError(step("(fail nil \"Hello\")"));
		
		assertArityError(step("(fail 1 \"Message\" 3)"));
	}
	
	@Test
	public void testFailContract() {
		Context<?> ctx=step("(def act (deploy '(do (defn set-and-fail [x] (def foo x) (fail :NOPE (str x))) (export set-and-fail))))");
		Address act=(Address) ctx.getResult();
		assertNotNull(act);
		
		ctx=step(ctx,"(call act (set-and-fail 100))");
		assertError(Keyword.create("NOPE"),ctx);
		
		// Foo shouldn't be defined
		assertNull(ctx.getAccountStatus(act).getEnvironmentValue(Symbols.FOO));
	}

	@Test
	public void testRollback() {
		assertEquals(1L, evalL("(do (rollback 1) (assert false))"));
		assertEquals(1L, evalL("(do (def a 1) (rollback a) (assert false))"));

		// rollback should roll back state changes
		Context<?> ctx = step("(def a 17)");
		ctx = step(ctx, "(do (def a 13) (rollback 2))");
		assertEquals(17L, evalL(ctx, "a"));
	}

	@Test
	public void testWhen() {
		assertNull(eval("(when false 2)"));
		assertNull(eval("(when true)"));
		assertEquals(Vectors.empty(), eval("(when 2 3 4 5 [])"));

		// TODO: needs to fix / check?
		assertArityError(step("(when)"));
	}
	
	@Test
	public void testIfLet() {
		assertEquals(1L,evalL("(if-let [a 1] a)"));
		assertEquals(2L,evalL("(if-let [a true] 2)"));
		assertEquals(3L,evalL("(if-let [a []] 3 4)"));
		assertEquals(4L,evalL("(if-let [a nil] 3 4)"));
		assertEquals(5L,evalL("(if-let [a false] 3 5)"));

		assertNull(eval("(if-let [a false] 1)")); // null on false branch

		assertArityError(step("(if-let [:foo 1])"));

		// TODO: needs to fix / check?
		assertArityError(step("(if-let [a true])")); // no branches
		assertArityError(step("(if-let [a true] 1 2 3)")); // too many branches
		assertArityError(step("(if-let)"));
		assertArityError(step("(if-let [])"));
		assertArityError(step("(if-let [foo] 1)"));
	}
	
	@Test
	public void testWhenLet() {
		assertEquals(1L,evalL("(when-let [a 1] a)"));
		assertEquals(2L,evalL("(when-let [a true] 2)"));
		assertEquals(3L,evalL("(when-let [a 1] 2 3)"));
		
		assertNull(eval("(when-let [a true])")); // empty trye branch
		assertNull(eval("(when-let [a false] 1)")); // null on false branch
		assertNull(eval("(when-let [a false])")); // null on false branch

		assertCompileError(step("(when-let [:foo 1])"));

		// TODO: needs to fix / check?
		assertArityError(step("(when-let)"));
		assertArityError(step("(when-let [])"));
		assertArityError(step("(when-let [foo] 1)"));
	}

	@Test
	public void testKeyword() {
		assertEquals(Keywords.STATE, eval("(keyword 'state)"));
		assertEquals(Keywords.STATE, eval("(keyword (name :state))"));
		assertEquals(Keywords.STATE, eval("(keyword (str 'state))"));
		
		// keyword lookups
		assertNull(eval("((keyword :foo) nil)"));

		assertArgumentError(step("(keyword (str))")); // too short

		assertCastError(step("(keyword nil)"));
		assertCastError(step("(keyword 1)"));
		assertCastError(step("(keyword [])"));

		assertArityError(step("(keyword)"));
		assertArityError(step("(keyword 1 3)"));
	}
	
	@Test
	public void testKeywordAsFunction() {
		// lookups in maps
		assertNull(eval("(:foo {})"));
		assertNull(eval("(:foo {:bar 1} nil)"));
		assertEquals(1L,evalL("(:foo {} 1)"));
		assertEquals(1L,evalL("(:foo {:foo 1} 2)"));
		
		// lookups in sets
		assertNull(eval("(:foo #{})"));
		assertNull(eval("(:foo #{:bar} nil)"));
		assertEquals(1L,evalL("(:foo #{} 1)"));
		assertEquals(Keywords.FOO,eval("(:foo #{:foo} 2)"));

		// lookups in vectors
		assertNull(eval("(:foo [])"));
		assertNull(eval("(:foo [] nil)"));
		assertEquals(1L,evalL("(:foo [1 2 3] 1)"));
		
		// lookups on nil
		assertNull(eval("((keyword :foo) nil)"));
		assertNull(eval("(:foo nil)"));
		assertEquals(1L,evalL("(:foo nil 1)"));
	}

	@Test
	public void testName() {
		assertEquals("count", evalS("(name :count)"));
		assertEquals("count", evalS("(name 'count)"));
		assertEquals("foo", evalS("(name \"foo\")"));
		
		// should extract symbol name, exluding namespace alias
		assertEquals("bar", evalS("(name 'foo/bar)"));
		
		// longer strings OK for name
		assertEquals("duicgidvgefiucefiuvfeiuvefiuvgifegvfuievgiuefgviuefgviufegvieufgviuefvgevevgi", evalS("(name \"duicgidvgefiucefiuvfeiuvefiuvgifegvfuievgiuefgviuefgviufegvieufgviuefvgevevgi\")"));

		assertCastError(step("(name nil)"));
		assertCastError(step("(name [])"));
		assertCastError(step("(name 12)"));

		assertArityError(step("(name)"));
		assertArityError(step("(name 1 3)"));
	}

	@Test
	public void testSymbol() {
		assertEquals(Symbols.COUNT, eval("(symbol :count)"));
		assertEquals(Symbols.COUNT, eval("(symbol (name 'count))"));
		assertEquals(Symbols.COUNT, eval("(symbol (str 'count))"));
		assertEquals(Symbols.COUNT, eval("(symbol (name :count))"));
		assertEquals(Symbols.COUNT, eval("(symbol (name \"count\"))"));
		
		assertEquals(Symbol.create(Address.create(8),Strings.create("foo")),eval("'#8/foo"));

		// too short or too long results in CAST error
		assertCastError(step("(symbol (str))"));
		assertCastError(
				step("(symbol \"duicgidvgefiucefiuvfeiuvefiuvgifegvfuievgiuefgviuefgviufegvieufgviuefvgevevgi\")"));

		assertCastError(step("(symbol nil)"));
		assertCastError(step("(symbol [])"));

		assertArityError(step("(symbol)"));
		assertArityError(step("(symbol 1 3)"));
	}
	
	@Test
	public void testImport() {
		Context<Address> ctx = step("(def lib (deploy '(do (def foo 100))))");
		Address libAddress=ctx.getResult();
		
		{ // tests with a typical import
			Context<?> ctx2=step(ctx,"(import ~lib :as mylib)");
			assertEquals(libAddress,ctx2.getResult());
			
			assertEquals(100L, evalL(ctx2, "mylib/foo"));
			assertUndeclaredError(step(ctx2, "mylib/bar"));
			assertTrue(evalB(ctx2,"(syntax? (lookup-syntax 'mylib/foo))"));
			assertTrue(evalB(ctx2,"(defined? mylib/foo)"));
			assertFalse(evalB(ctx2,"(defined? mylib/bar)"));
		}
		
		assertArityError(step(ctx,"(import)"));
		assertArityError(step(ctx,"(import ~lib)"));	
		assertArityError(step(ctx,"(import ~lib :as)"));	
		assertArityError(step(ctx,"(import ~lib :as mylib :blah)"));	
	}
	
	@Test
	public void testImportCore() {
		Context<?> ctx = step("(import convex.core :as cc)");
		assertFalse(ctx.isExceptional());
		assertEquals((Object)eval(ctx,"count"),eval(ctx,"cc/count"));
	}
	

	@Test
	public void testLookup() {
		assertSame(Core.COUNT, eval("(lookup :count)"));
		assertSame(Core.COUNT, eval("(lookup 'count)"));
		assertSame(Core.COUNT, eval("(lookup \"count\")"));
		
		assertSame(Core.COUNT, eval("(lookup *address* 'count)"));

		assertNull(eval("(lookup 'non-existent-symbol)"));
		assertNull(eval("(lookup :non-existent-symbol)"));
		
		// Lookups after def
		assertEquals(1L,evalL("(do (def foo 1) (lookup :foo))"));
		assertEquals(1L,evalL("(do (def foo 1) (lookup *address* :foo))"));
		
		// Lookups in non-existent environment
		assertNull(eval("(lookup #77777777 'count)"));
		assertNull(eval("(do (def foo 1) (lookup #66666666 'foo))"));


		// invalid name string
		assertCastError(
				step("(lookup \"cdiubcidciuecgieufgvuifeviufegviufeviuefbviufegviufevguiefvgfiuevgeufigv\")"));

		assertCastError(step("(lookup count)"));
		assertCastError(step("(lookup nil)"));
		assertCastError(step("(lookup 10)"));
		assertCastError(step("(lookup [])"));

		assertArityError(step("(lookup)"));
		assertArityError(step("(lookup 1 2 3)"));
	}
	
	@Test
	public void testLookupSyntax() {
		assertSame(Core.COUNT, ((Syntax)eval("(lookup-syntax :count)")).getValue());
		assertSame(Core.COUNT, ((Syntax)eval("(lookup-syntax "+Init.CORE_ADDRESS+ " :count)")).getValue());
		
		assertNull(eval("(lookup-syntax 'non-existent-symbol)"));
		
		assertEquals(Syntax.of(1L),eval("(do (def foo 1) (lookup-syntax :foo))"));
		assertEquals(Syntax.of(1L),eval("(do (def foo 1) (lookup-syntax *address* :foo))"));
		assertNull(eval("(do (def foo 1) (lookup-syntax #0 :foo))"));

		// invalid name string (too long)
		assertCastError(
				step("(lookup-syntax \"cdiubcidciuecgieufgvuifeviufegviufeviuefbviufegviufevguiefvgfiuevgeufigv\")"));

		// bad symbols
		assertCastError(step("(lookup-syntax count)"));
		assertCastError(step("(lookup-syntax nil)"));
		assertCastError(step("(lookup-syntax 10)"));
		assertCastError(step("(lookup-syntax [])"));
		
		// Bad addresses
		assertCastError(step("(lookup-syntax :foo 'bar)"));
		assertCastError(step("(lookup-syntax 8 'count)"));

		assertArityError(step("(lookup-syntax)"));
		assertArityError(step("(lookup-syntax 1 2 3)"));
	}

	@Test
	public void testEmpty() {
		assertNull(eval("(empty nil)"));
		assertSame(Lists.empty(), eval("(empty (list 1 2))"));
		assertSame(Maps.empty(), eval("(empty {1 2 3 4})"));
		assertSame(Vectors.empty(), eval("(empty [1 2 3])"));
		assertSame(Sets.empty(), eval("(empty #{1 2})"));

		assertCastError(step("(empty 1)"));
		assertCastError(step("(empty :foo)"));
		assertArityError(step("(empty)"));
		assertArityError(step("(empty [1] [2])"));
	}

	@Test
	public void testMapAsFunction() {
		assertEquals(1L, evalL("({2 1 1 2} 2)"));
		assertNull(eval("({2 1 1 2} 3)"));
		assertNull(eval("({} 3)"));

		// fall-through behaviour
		assertEquals(10L, evalL("({2 1 1 2} 5 10)"));
		assertNull(eval("({} 1 nil)"));

		// bad arity
		assertArityError(step("({})"));
		assertArityError(step("({} 1 2 3 4)"));
	}

	@Test
	public void testVectorAsFunction() {
		assertEquals(5L, evalL("([1 3 5 7] 2)"));

		assertEquals(5L, evalL("([1 3 5 7] (byte 2))")); // TODO: is this sane? Implicit cast to Long is OK?

		// bounds checks get applied
		assertBoundsError(step("([] 0)"));
		assertBoundsError(step("([1 2 3] -1)"));
		assertBoundsError(step("([1 2 3] 3)"));

		// Bad index types
		assertCastError(step("([] nil)"));
		assertCastError(step("([] :foo)"));
		
		// bad arity
		assertArityError(step("([])"));
		assertArityError(step("([] 1 2 3 4)"));
	}

	@Test
	public void testListAsFunction() {
		assertEquals(5L, evalL("('(1 3 5 7) 2)"));

		// bounds checks get applied
		assertBoundsError(step("(() 0)"));
		assertBoundsError(step("('(1 2 3) -1)"));
		assertBoundsError(step("('(1 2 3) 3)"));

		// cast error
		assertCastError(step("(() nil)"));
		assertCastError(step("(() :foo)"));
		assertCastError(step("(() {})"));

		
		// bad arity
		assertArityError(step("(())"));
		assertArityError(step("(() 1 2 3 4)"));
	}

	@Test
	public void testApply() {
		assertNull(eval("(apply assoc [nil])"));
		assertEquals(Vectors.empty(), eval("(apply vector ())"));
		assertEquals(Lists.empty(), eval("(apply list [])"));
		assertEquals("foo", evalS("(apply str [\\f \\o \\o])"));
		
		assertEquals(10L,evalL("(apply + 1 2 [3 4])"));
		assertEquals(3L,evalL("(apply + 1 2 nil)"));

		assertEquals(Vectors.of(1L, 2L, 3L, 4L), eval("(apply vector 1 2 (list 3 4))"));
		assertEquals(List.of(1L, 2L, 3L, 4L), eval("(apply list 1 2 [3 4])"));
		assertEquals(List.of(1L, 2L), eval("(apply list 1 2 nil)"));
		
		// Bad function type
		assertCastError(step("(apply 666 1 2 [3 4])"));

		// Keyword works as a function lookup wrong arity (#79)
		assertArityError(step("(apply :n 1 2 [3 4])"));

		// Insufficient args to apply itself
		assertArityError(step("(apply)"));
		assertArityError(step("(apply vector)"));
		
		// Arity failure before cast
		assertArityError(step("(apply 666)"));
		
		// Insufficient args to applied function
		assertArityError(step("(apply assoc nil)")); 

		// Cast error if not applied to collection
		assertCastError(step("(apply inc 1)"));
		assertCastError(step("(apply inc :foo)"));
		
		// not a sequential collection
		assertCastError(step("(apply + 1 2 {})"));
	}


	@Test
	public void testNonExistentAccountBalance() {
		// Address that doesn't exist address, shouldn't have any balance initially
		long addr=7777777777L;
		assertNull(eval("(let [a (address "+addr+")] (balance a))"));
	}
	
	@Test
	public void testBalance() {

		// hero balance, should reflect cost of initial juice
		String a0 = TestState.HERO.toHexString();
		Long expectedHeroBalance = TestState.HERO_BALANCE;
		assertEquals(expectedHeroBalance, evalL("(let [a (address \"" + a0 + "\")] (balance a))"));

		// someone else's balance
		String a1 = TestState.VILLAIN.toHexString();
		Long expectedVillainBalance = TestState.VILLAIN_BALANCE;
		assertEquals(expectedVillainBalance, evalL("(let [a (address \"" + a1 + "\")] (balance a))"));

		assertCastError(step("(balance nil)"));
		assertCastError(step("(balance 0x00)"));
		assertCastError(step("(balance :foo)"));

		assertArityError(step("(balance)"));
		assertArityError(step("(balance 1 2)"));
	}

	@Test
	public void testAccept() {
		assertEquals(0L, evalL("(accept 0)"));
		assertEquals(0L, evalL("(accept *offer*)"));  // offer should be initially zero
		assertEquals(0L, evalL("(accept (byte 0))")); // byte should widen to Long

		// accepting non-integer value -> CAST error
		assertCastError(step("(accept :foo)"));
		assertCastError(step("(accept :foo)"));
		assertCastError(step("(accept 0.3)"));

		// accepting negative -> ARGUMENT error
		assertArgumentError(step("(accept -1)"));

		// accepting more than is offered -> STATE error
		assertStateError(step("(accept 1)"));

		assertArityError(step("(accept)"));
		assertArityError(step("(accept 1 2)"));
	}
	
	@Test
	public void testAcceptInActor() {
		Context<?> ctx=INITIAL_CONTEXT.fork();
		ctx=step(ctx,"(def act (deploy '(do (defn receive-coin [sender amount data] (accept amount))  (defn echo-offer [] *offer*) (export echo-offer receive-coin))))");
		
		ctx=step(ctx,"(transfer act 100)");
		assertEquals(100L, (long)RT.jvm(ctx.getResult()));
		assertEquals(100L,evalL(ctx,"(balance act)"));
		assertEquals(999L,evalL(ctx,"(call act 999 (echo-offer))"));
		
		// send via contract call
		ctx=step(ctx,"(call act 666 (receive-coin *address* 350 nil))");
		assertEquals(350L, (long)RT.jvm(ctx.getResult()));
		assertEquals(450L,evalL(ctx,"(balance act)"));
		
	}

	@Test
	public void testCall() {
		Context<Address> ctx = step("(def ctr (deploy '(do (defn foo [] :bar) (export foo))))");

		assertEquals(Keywords.BAR,eval(ctx,"(call ctr (foo))")); // regular call
		assertEquals(Keywords.BAR,eval(ctx,"(call ctr 100 (foo))")); // call with offer
		
		assertArityError(step(ctx, "(call)"));
		assertArityError(step(ctx, "(call 12)"));

		assertCastError(step(ctx, "(call ctr :foo (bad-fn 1 2))")); // cast fail on offered value
		assertStateError(step(ctx, "(call ctr 12 (bad-fn 1 2))")); // bad function
		
		// bad format for call
		assertCompileError(step(ctx,"(call ctr foo)"));

		assertStateError(step(ctx, "(call #666666 12 (bad-fn 1 2))")); // bad actor
		assertArgumentError(step(ctx, "(call ctr -12 (bad-fn 1 2))")); // negative offer

		// bad actor takes precedence over bad offer
		assertStateError(step(ctx, "(call #666666 -12 (bad-fn 1 2))")); 

	}
	
	@Test
	public void testCallSelf() {
		Context<Address> ctx = step("(def ctr (deploy '(do (defn foo [] (call *address* (bar))) (defn bar [] (= *address* *caller*)) (export foo bar))))");

		assertTrue(evalB(ctx, "(call ctr (foo))")); // nested call to same actor
		assertFalse(evalB(ctx, "(call ctr (bar))")); // call from hero only

	}


	@Test
	public void testCallStar() {
		Context<Address> ctx = step("(def ctr (deploy '(do :foo (defn f [x] (inc x)) (export f g) )))");

		assertEquals(9L,evalL(ctx, "(call* ctr 0 :f 8)"));
		
		assertArityError(step(ctx, "(call*)"));
		assertArityError(step(ctx, "(call* 12)"));
		assertArityError(step(ctx, "(call* 1 2)")); // no function

		assertCastError(step(ctx, "(call* ctr :foo 'bad-fn 1 2)")); // cast fail on offered value
		assertStateError(step(ctx, "(call* ctr 12 'bad-fn 1 2)")); // bad function
	}

	@Test
	public void testDeploy() {
		Context<Address> ctx = step("(def ctr (deploy '(fn [] :foo :bar)))");
		Address ca = ctx.getResult();
		assertNotNull(ca);
		AccountStatus as = ctx.getAccountStatus(ca);
		assertNotNull(as);
		assertEquals(ca, eval(ctx, "ctr")); // defined address in environment

		// initial deployed state
		assertEquals(0L, as.getBalance());
		
		// double-deploy should get different addresses
		assertFalse(evalB("(let [cfn '(do 1)] (= (deploy cfn) (deploy cfn)))"));
	}
	
	@Test
	public void testActorQ() {
		Context<Address> ctx = step("(def ctr (deploy '(fn [] :foo :bar)))");
		Address ctr=ctx.getResult();
		
		assertTrue(evalB(ctx,"(actor? ctr)"));
		assertTrue(evalB(ctx,"(actor? \""+ctr.toHexString()+"\")"));
		assertTrue(evalB(ctx,"(actor? (address ctr))"));
		
		// hero address is not an Actor
		assertFalse(evalB(ctx,"(actor? *address*)"));
		
		assertFalse(evalB(ctx,"(actor? :foo)"));
		assertFalse(evalB(ctx,"(actor? nil)"));
		assertFalse(evalB(ctx,"(actor? [ctr])"));
		assertFalse(evalB(ctx,"(actor? 'ctr)"));

		// non-existant account is not an actor
		assertFalse(evalB(ctx,"(actor? 99999999)"));
		assertFalse(evalB(ctx,"(actor? #4512)"));
		assertFalse(evalB(ctx,"(actor? -1234)"));

		assertArityError(step("(actor?)"));
		assertArityError(step("(actor? :foo :bar)")); // ARITY before CAST

	}
	
	@Test
	public void testAccountQ() {
		// a new Actor is an account
		Context<Address> ctx = step("(def ctr (deploy '(fn [] :foo :bar)))");
		assertTrue(evalB(ctx,"(account? ctr)"));
		
		// standard actors are accounts
		assertTrue(evalB(ctx,"(account? *registry*)"));
		
		// standard actors are accounts
		assertTrue(evalB(ctx,"(account? "+Init.HERO+")"));
		
		// a fake address
		assertFalse(evalB(ctx,"(account? 77777777)"));
		
		// String with and without hex. See Issue #90
		assertFalse(evalB(ctx,"(account? \"deadbeef\")"));
		assertFalse(evalB(ctx,"(account? \"zzz\")"));

		// a blob that is wrong length for an address. See Issue #90
		assertFalse(evalB(ctx,"(account? 0x1234)"));
		
		// a blob that actually refers to a valid account. But it isn't an Address...
		assertFalse(evalB(ctx,"(account? 0x0000000000000008)"));
		
		// current hero address is an account
		assertTrue(evalB(ctx,"(account? *address*)"));
		
		assertFalse(evalB("(account? :foo)"));
		assertFalse(evalB("(account? nil)"));
		assertFalse(evalB("(account? [])"));
		assertFalse(evalB("(account? 'foo)"));
		
		assertArityError(step("(account?)"));
		assertArityError(step("(account? 1 2)")); // ARITY before CAST
	}
	
	@Test
	public void testAccount() {
		// a new Actor is an account
		Context<Address> ctx = step("(def ctr (deploy '(fn [] :foo :bar)))");
		AccountStatus as=eval(ctx,"(account ctr)");
		assertNotNull(as);
		
		// standard actors are accounts
		assertTrue(eval("(account *registry*)") instanceof AccountStatus);
		
		// a fake address
		assertNull(eval(ctx,"(account 77777777)"));
		
		// current address is an account, and its balance is correct
		assertTrue(evalB("(= *balance* (:balance (account *address*)))"));
		
		// invalid addresses
		assertCastError(step("(account nil)"));
		assertCastError(step("(account :foo)"));
		assertCastError(step("(account [])"));
		assertCastError(step("(account 'foo)"));
		
		assertArityError(step("(account)"));
		assertArityError(step("(account 1 2)")); // ARITY before CAST
	}
	
	@Test
	public void testSetKey() {
		Context<?> ctx=INITIAL_CONTEXT;
		
		ctx=step(ctx,"(set-key 0x0000000000000000000000000000000000000000000000000000000000000000)");
		assertEquals(AccountKey.ZERO,ctx.getResult());
		assertEquals(AccountKey.ZERO,eval(ctx,"*key*"));
		
		ctx=step(ctx,"(set-key nil)");
		assertNull(ctx.getResult());
		assertNull(eval(ctx,"*key*"));
		
		ctx=step(ctx,"(set-key "+Init.HERO_KP.getAccountKey()+")");
		assertEquals(Init.HERO_KP.getAccountKey(),ctx.getResult());
		assertEquals(Init.HERO_KP.getAccountKey(),eval(ctx,"*key*"));
	}
	
	@Test
	public void testSetAllowance() {
		
		// zero price for unchanged allowance
		assertEquals(0L, evalL("(set-memory *memory*)"));
		
		// sell whole allowance
		assertEquals(0L, evalL("(do (set-memory 0) *memory*)"));
		
		// buy allowance reduces balance
		assertTrue(evalL("(let [b *balance*] (set-memory (inc *memory*)) (- *balance* b))")<0);
		
		// sell allowance increases balance
		assertTrue(evalL("(let [b *balance*] (set-memory (dec *memory*)) (- *balance* b))")>0);
		
		// trying to buy too much is a funds error
		assertFundsError(step("(set-memory 1000000000000000000)"));

		
		assertCastError(step("(set-memory :foo)"));
		assertCastError(step("(set-memory nil)"));
		
		assertArityError(step("(set-memory)"));
		assertArityError(step("(set-memory 1 2)"));

	}
	
	@Test
	public void testTransferAllowance() {
		long ALL=Constants.INITIAL_ACCOUNT_ALLOWANCE;
		Address HERO = TestState.HERO;
		assertEquals(ALL, evalL(Symbols.STAR_ALLOWANCE.toString()));

		assertEquals(ALL, step("(transfer-memory *address* 1337)").getAccountStatus(HERO).getAllowance());
		
		assertEquals(ALL-1337, step("(transfer-memory "+Init.VILLAIN+" 1337)").getAccountStatus(HERO).getAllowance());

		assertEquals(0L, step("(transfer-memory "+Init.VILLAIN+" "+ALL+")").getAccountStatus(HERO).getAllowance());
 
		assertArgumentError(step("(transfer-memory *address* -1000)"));	
		assertMemoryError(step("(transfer-memory *address* (+ 1 "+ALL+"))"));

		// check bad arg types
		assertCastError(step("(transfer-memory -1000 1000)"));
		assertCastError(step("(transfer-memory *address* :foo)"));
		
		// check bad arities
		assertArityError(step("(transfer-memory -1000)"));
		assertArityError(step("(transfer-memory)"));
		assertArityError(step("(transfer-memory *address* 100 100)"));

	}
	
	@Test
	public void testTransferToActor() {
		// SECURITY: be careful with these tests
		Address CORE=Init.CORE_ADDRESS;
		
		// should fail transferring to an account with no receive-coins export
		assertStateError(step("(transfer "+CORE+" 1337)"));
		
		{ // transfer to an Actor that accepts everything
			Context<?> ctx=step("(deploy '(do (defn receive-coin [sender amount data] (accept amount)) (export receive-coin)))");
			Address receiver=(Address) ctx.getResult();
			
			ctx=step(ctx,"(transfer "+receiver.toString()+" 100)");
			assertCVMEquals(100L,ctx.getResult());
			assertCVMEquals(100L,ctx.getBalance(receiver));
		}
		
		{ // transfer to an Actor that accepts nothing
			Context<?> ctx=step("(deploy '(do (defn receive-coin [sender amount data] (accept 0)) (export receive-coin)))");
			Address receiver=(Address) ctx.getResult();
			
			ctx=step(ctx,"(transfer "+receiver.toString()+" 100)");
			assertCVMEquals(0L,ctx.getResult());
			assertCVMEquals(0L,ctx.getBalance(receiver));
		}
		
		{ // transfer to an Actor that accepts half
			Context<?> ctx=step("(deploy '(do (defn receive-coin [sender amount data] (accept (long (/ amount 2)))) (export receive-coin)))");
			Address receiver=(Address) ctx.getResult();
			
			// should be OK with a Blob Address
			ctx=step(ctx,"(transfer "+receiver+" 100)");
			assertCVMEquals(50L,ctx.getResult());
			assertCVMEquals(50L,ctx.getBalance(receiver));
		}


	}

	@Test
	public void testTransfer() {
		// SECURITY: don't mess with these tests

		// balance at start of transaction
		long BAL = TestState.HERO_BALANCE;

		Address HERO = TestState.HERO;

		// transfer to self. Note juice already accounted for in context.
		assertEquals(1337L, evalL("(transfer *address* 1337)")); // should return transfer amount
		assertEquals(BAL, step("(transfer *address* 1337)").getBalance(HERO));
		
		// transfers to an address that doesn't exist
		{
			Context<?> nc1=step("(transfer (address 666666) 1337)");
			assertNobodyError(nc1);
		}
		
		
		// String representing a new User Address
		Context<Address> ctx=step("(create-account "+Init.HERO_KP.getAccountKey()+")");
		Address naddr=ctx.getResult();

		// transfers to a new address
		{
			Context<?> nc1=step(ctx,"(transfer "+naddr+" 1337)");
			assertCVMEquals(1337L, nc1.getResult());
			assertEquals(BAL - 1337,nc1.getBalance(HERO));
			assertEquals(1337L, evalL(nc1,"(balance "+naddr+")"));
		}
		
		assertTrue(() -> evalB(ctx,"(let [a "+naddr+"]"
				+ "   (not (= *balance* (transfer a 1337))))"));

		// transfer it all!
		assertEquals(0L,step(ctx,"(transfer "+naddr+" *balance*)").getBalance(HERO));

		// Should never be possible to transfer negative amounts
		assertArgumentError(step("(transfer *address* -1000)"));
		assertArgumentError(step("(transfer "+naddr+" -1)"));

		// Long.MAX_VALUE is too big for an Amount
		assertArgumentError(step("(transfer *address* 9223372036854775807)")); // Long.MAX_VALUE

		assertFundsError(step("(transfer *address* 999999999999999999)"));

		assertCastError(step("(transfer :foo 1)"));
		assertCastError(step("(transfer *address* :foo)"));

		assertArityError(step("(transfer)"));
		assertArityError(step("(transfer 1)"));
		assertArityError(step("(transfer 1 2 3)"));
	}
	
	@Test
	public void testStake() {
		Context<ACell> ctx=step(INITIAL_CONTEXT,"(def my-peer \""+Init.FIRST_PEER_KEY.toHexString()+"\")");
		AccountKey MY_PEER=Init.FIRST_PEER_KEY;
		long PS=ctx.getState().getPeer(Init.FIRST_PEER_KEY).getOwnStake();
		
		{
			// simple case of staking 1000000 on first peer of the realm
			Context<ACell> rc=step(ctx,"(stake my-peer 1000000)");
			assertNotError(rc);
			assertEquals(PS+1000000,rc.getState().getPeer(MY_PEER).getTotalStake());
			assertEquals(1000000,rc.getState().getPeer(MY_PEER).getDelegatedStake());
			assertEquals(TestState.TOTAL_FUNDS, rc.getState().computeTotalFunds());
		}
		
		// staking on an account key that isn't a peer
		assertStateError(step(ctx,"(stake 0x1234567812345678123456781234567812345678123456781234567812345678 1234)"));

		// staking on an address
		assertCastError(step(ctx,"(stake *address* 1234)"));
		
		// bad arg types
		assertCastError(step(ctx,"(stake :foo 1234)"));
		assertCastError(step(ctx,"(stake my-peer :foo)"));
		assertCastError(step(ctx,"(stake my-peer nil)"));
		
		assertArityError(step(ctx,"(stake my-peer)"));
		assertArityError(step(ctx,"(stake my-peer 1000 :foo)"));
	}

	@Test
	public void testNumericComparisons() {
		assertFalse(evalB("(== 1 2)"));
		assertFalse(evalB("(== 1.0 2.0)"));

		assertTrue(evalB("(== 3 3)"));
		assertTrue(evalB("(== 0.0 -0.0)")); // IEE754 defines as equals
		assertFalse(evalB("(= 0.0 -0.0)")); // Not identical values
		assertTrue(evalB("(== 7.0000 7.0)"));
		assertTrue(evalB("(== -1.00E0 -1.0)"));

		assertTrue(evalB("(<)"));
		assertTrue(evalB("(>)"));
		assertTrue(evalB("(< 1 2)"));
		assertTrue(evalB("(<= 1 2)"));
		assertTrue(evalB("(<= 1 2 6)"));
		assertFalse(evalB("(< 2 2)"));
		assertFalse(evalB("(< 3 2)"));
		assertTrue(evalB("(<= 2.0 2.0)"));
		assertTrue(evalB("(>= 2.0 2.0)"));
		assertFalse(evalB("(> 1 2)"));
		assertFalse(evalB("(> 3.0 3.0)"));
		assertFalse(evalB("(> 3.0 1.0 7.0)"));
		assertTrue(evalB("(>= 3.0 3.0)"));
		
		// assertTrue(evalB("(>= \\b \\a)")); // TODO: do we want this to work?

		// juice should go down in order of evaluation
		assertTrue(evalB("(> *juice* *juice* *juice*)"));

		assertCastError(step("(> :foo)"));
		assertCastError(step("(> :foo :bar)"));
		assertCastError(step("(> [] [1])"));
	}

	@Test
	public void testMin() {
		assertEquals(1L, evalL("(min 1 2 3 4)"));
		assertEquals(7L, evalL("(min 7)"));
		assertEquals(2L, evalL("(min 4 3 2)"));
		
		assertEquals(1.0, evalD("(min 2.0 1.0 3.0)"));
		assertEquals(-0.0, evalD("(min 2.0 ##NaN -0.0 ##Inf)"));
		assertEquals(CVMDouble.NaN, eval("(min ##NaN)"));

		// TODO: Figure out how this should behave. See issue https://github.com/Convex-Dev/convex/issues/99
		// assertEquals(CVMLong.ONE, eval("(min ##NaN 1 ##NaN)"));

		assertCastError(step("(min true)"));
		assertCastError(step("(min \\c)"));

		// assertArityError(step("(min)")); // TODO: consider this?
		assertEquals(CVMDouble.NaN, eval("(min)"));

	}

	@Test
	public void testMax() {
		assertEquals(4L, evalL("(max 1 2 3 4)"));
		assertEquals(4L, evalL("(max 1 ##-Inf 3 ##NaN 4)"));
		assertEquals(7L, evalL("(max 7)"));
		assertEquals(4.0, evalD("(max 4.0 3 2)"));

		// assertArityError(step("(max)")); // TODO: consider this?
		assertEquals(CVMDouble.NaN, eval("(max)"));
	}
	
	@Test
	public void testPow() {
		assertEquals(4.0, evalD("(pow 2 2)"));
		
		assertCastError(step("(pow :a 7)"));
		assertCastError(step("(pow 7 :a)"));
		
		assertArityError(step("(pow)"));
		assertArityError(step("(pow 1)"));	
		assertArityError(step("(pow 1 2 3)"));	
	}
	
	@Test
	public void testQuot() {
		assertEquals(0L, evalL("(quot 4 10)"));
		assertEquals(2L, evalL("(quot 10 4)"));
		assertEquals(-2L, evalL("(quot -10 4)"));
		
		assertCastError(step("(quot :a 7)"));
		assertCastError(step("(quot 7 nil)"));
		
		assertArityError(step("(quot)"));
		assertArityError(step("(quot 1)"));	
		assertArityError(step("(quot 1 2 3)"));	
	}
	
	@Test
	public void testMod() {
		assertEquals(4L, evalL("(mod 4 10)"));
		assertEquals(4L, evalL("(mod 14 10)"));
		assertEquals(6L, evalL("(mod -1 7)"));
		assertEquals(0L, evalL("(mod 7 7)"));
		assertEquals(0L, evalL("(mod 0 -1)"));
		
		assertEquals(6L, evalL("(mod -1 -7)"));
		
		assertArgumentError(step("(mod 10 0)"));
		
		assertCastError(step("(mod :a 7)"));
		assertCastError(step("(mod 7 nil)"));
		
		assertArityError(step("(mod)"));
		assertArityError(step("(mod 1)"));	
		assertArityError(step("(mod 1 2 3)"));	
	}
	
	@Test
	public void testRem() {
		assertEquals(4L, evalL("(rem 4 10)"));
		assertEquals(4L, evalL("(rem 14 10)"));
		assertEquals(-1L, evalL("(rem -1 7)"));
		assertEquals(0L, evalL("(rem 7 7)"));
		assertEquals(0L, evalL("(rem 0 -1)"));
		
		assertEquals(-1L, evalL("(rem -1 -7)"));
		
		assertArgumentError(step("(rem 10 0)"));
		
		assertCastError(step("(rem :a 7)"));
		assertCastError(step("(rem 7 nil)"));
		
		assertArityError(step("(rem)"));
		assertArityError(step("(rem 1)"));	
		assertArityError(step("(rem 1 2 3)"));	
	}
	
	@Test
	public void testExp() {
		assertEquals(1.0, evalD("(exp 0)"));
		assertEquals(1.0, evalD("(exp -0)"));
		assertEquals(StrictMath.exp(1.0), evalD("(exp 1)"));
		assertEquals(0.0, evalD("(exp (/ -1 0))"));
		assertEquals(Double.POSITIVE_INFINITY, evalD("(exp (/ 1 0))"));
		
		assertCastError(step("(exp :a)"));
		assertCastError(step("(exp #3)"));
		assertCastError(step("(exp nil)"));
		
		assertArityError(step("(exp)"));
		assertArityError(step("(exp 1 2)"));	
	}

	@Test
	public void testHash() {
		assertEquals(Hash.fromHex("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a"),eval("(hash 0x)"));
		
		assertEquals(Hash.NULL_HASH, eval("(hash (encoding nil))"));
		assertEquals(Hash.TRUE_HASH, eval("(hash (encoding true))"));
		assertEquals(Maps.empty().getHash(), eval("(hash (encoding {}))"));
		
		assertTrue(evalB("(= (hash 0x12) (hash 0x12))"));

		assertArityError(step("(hash)"));
		assertArityError(step("(hash nil nil)"));
	}

	@Test
	public void testCount() {
		assertEquals(0L, evalL("(count nil)"));
		assertEquals(0L, evalL("(count [])"));
		assertEquals(0L, evalL("(count ())"));
		assertEquals(0L, evalL("(count \"\")"));
		assertEquals(2L, evalL("(count (list :foo :bar))"));
		assertEquals(2L, evalL("(count #{1 2 2})"));
		assertEquals(3L, evalL("(count [1 2 3])"));
		
		// Count of a map is the number of entries
		assertEquals(2L, evalL("(count {1 2 2 3})")); 

		assertCastError(step("(count 1)"));
		assertCastError(step("(count :foo)"));

		assertArityError(step("(count)"));
		assertArityError(step("(count 1 2)"));
	}

	@Test
	public void testCompile() {
		assertEquals(Constant.of(1L), eval("(compile 1)"));
		
		assertEquals(Constant.class, eval("(compile 1)").getClass());
		assertEquals(Constant.class, eval("(compile nil)").getClass());
		assertEquals(Constant.class, eval("(compile (+ 1 2))").getClass());

		assertArityError(step("(compile)"));
		assertArityError(step("(compile 1 2)"));
		assertArityError(step("(if 1)"));
	}

	private AVector<Syntax> ALL_PREDICATES = Vectors
			.create(Core.CORE_NAMESPACE.filterValues(e -> e.getValue() instanceof CorePred).values());
	private AVector<Syntax> ALL_CORE_DEFS = Vectors
			.create(Core.CORE_NAMESPACE.filterValues(e -> e.getValue() instanceof ICoreDef).values());

	@Test
	public void testPredArity() {
		AVector<Syntax> pvals = ALL_PREDICATES;
		assertFalse(pvals.isEmpty());
		Context<?> C = INITIAL_CONTEXT.fork();
		ACell[] a0 = new ACell[0];
		ACell[] a1 = new ACell[1];
		ACell[] a2 = new ACell[2];
		for (Syntax psyntax : pvals) {
			CorePred pred = psyntax.getValue();
			assertTrue(RT.isBoolean(pred.invoke(C, a1).getResult()), "Predicate: " + pred);
			assertArityError(pred.invoke(C, a0));
			assertArityError(pred.invoke(C, a2));
		}
	}

	@Test
	public void testCoreDefSymbols() throws BadFormatException {
		AVector<Syntax> vals = ALL_CORE_DEFS;
		assertFalse(vals.isEmpty());
		for (Syntax syndef : vals) {
			ACell def = syndef.getValue();
			Symbol sym = ((ICoreDef)def).getSymbol();
			assertSame(def, Core.CORE_NAMESPACE.get(sym).getValue());

			Blob b = Format.encodedBlob(def);
			assertSame(def, Format.read(b));

			assertEquals(def.ednString(), sym.toString());
		}
	}

	@Test
	public void testNilPred() {
		assertTrue(evalB("(nil? nil)"));
		assertFalse(evalB("(nil? 1)"));
		assertFalse(evalB("(nil? [])"));
	}

	@Test
	public void testListPred() {
		assertFalse(evalB("(list? nil)"));
		assertFalse(evalB("(list? 1)"));
		assertTrue(evalB("(list? '())"));
		assertTrue(evalB("(list? '(3 4 5))"));
		assertFalse(evalB("(list? [1 2 3])"));
		assertFalse(evalB("(list? {1 2})"));
	}

	@Test
	public void testVectorPred() {
		assertFalse(evalB("(vector? nil)"));
		assertFalse(evalB("(vector? 1)"));
		assertTrue(evalB("(vector? [])"));
		assertFalse(evalB("(vector? '(3 4 5))"));
		assertTrue(evalB("(vector? [1 2 3])"));
		assertTrue(evalB("(vector? (first {1 2 3 4}))"));
		assertFalse(evalB("(vector? {1 2})"));
	}

	@Test
	public void testSetPred() {
		assertFalse(evalB("(set? nil)"));
		assertFalse(evalB("(set? 1)"));
		assertTrue(evalB("(set? #{})"));
		assertFalse(evalB("(set? '(3 4 5))"));
		assertTrue(evalB("(set? #{1 2 3})"));
		assertFalse(evalB("(set? {1 2})"));
	}

	@Test
	public void testMapPred() {
		assertFalse(evalB("(map? nil)"));
		assertFalse(evalB("(map? 1)"));
		assertTrue(evalB("(map? {})"));
		assertFalse(evalB("(map? '(3 4 5))"));
		assertTrue(evalB("(map? {1 2 3 4})"));
		assertFalse(evalB("(map? #{1 2})"));
	}

	@Test
	public void testCollPred() {
		assertFalse(evalB("(coll? nil)"));
		assertFalse(evalB("(coll? 1)"));
		assertFalse(evalB("(coll? :foo)"));
		assertTrue(evalB("(coll? {})"));
		assertTrue(evalB("(coll? [])"));
		assertTrue(evalB("(coll? ())"));
		assertTrue(evalB("(coll? '())"));
		assertTrue(evalB("(coll? #{})"));
		assertTrue(evalB("(coll? '(3 4 5))"));
		assertTrue(evalB("(coll? [:foo :bar])"));
		assertTrue(evalB("(coll? {1 2 3 4})"));
		assertTrue(evalB("(coll? #{1 2})"));
	}

	@Test
	public void testEmptyPred() {
		assertTrue(evalB("(empty? nil)"));
		assertTrue(evalB("(empty? {})"));
		assertTrue(evalB("(empty? [])"));
		assertTrue(evalB("(empty? ())"));
		assertTrue(evalB("(empty? #{})"));
		assertFalse(evalB("(empty? {1 2})"));
		assertFalse(evalB("(empty? [ 3])"));
		assertFalse(evalB("(empty? '(foo))"));
		assertFalse(evalB("(empty? #{[]})"));
	}

	@Test
	public void testSymbolPred() {
		assertTrue(evalB("(symbol? 'foo)"));
		assertTrue(evalB("(symbol? (symbol :bar))"));
		assertFalse(evalB("(symbol? nil)"));
		assertFalse(evalB("(symbol? 1)"));
		assertFalse(evalB("(symbol? ['foo])"));
	}

	@Test
	public void testKeywordPred() {
		assertTrue(evalB("(keyword? :foo)"));
		assertTrue(evalB("(keyword? (keyword 'bar))"));
		assertFalse(evalB("(keyword? nil)"));
		assertFalse(evalB("(keyword? 1)"));
		assertFalse(evalB("(keyword? [:foo])"));
	}

	@Test
	public void testAddressPred() {
		assertTrue(evalB("(address? *origin*)"));
		assertFalse(evalB("(address? nil)"));
		assertFalse(evalB("(address? 1)"));
		assertFalse(evalB("(address? \"0a1b2c3d\")"));
		assertFalse(evalB("(address? (blob *origin*))"));
	}

	@Test
	public void testBlobPred() {
		assertTrue(evalB("(blob? (blob *origin*))"));
		assertTrue(evalB("(blob? 0xFF)"));
		assertTrue(evalB("(blob? (blob 0x17))"));
		
		assertFalse(evalB("(blob? 17)"));
		assertFalse(evalB("(blob? nil)"));
		assertFalse(evalB("(blob? *address*)"));
		assertFalse(evalB("(blob? (hash (encoding *state*)))"));
	}

	@Test
	public void testLongPred() {
		assertTrue(evalB("(long? 1)"));
		assertTrue(evalB("(long? (long *balance*))")); // TODO: is this sane?
		assertFalse(evalB("(long? (byte 1))"));
		assertFalse(evalB("(long? nil)"));
		assertFalse(evalB("(long? 0xFF)"));
		assertFalse(evalB("(long? [1 2])"));
	}

	@Test
	public void testStrPred() {
		assertTrue(evalB("(str? (name :foo))"));
		assertTrue(evalB("(str? (str :foo))"));
		assertTrue(evalB("(str? (str nil))"));
		assertFalse(evalB("(str? 1)"));
		assertFalse(evalB("(str? nil)"));
	}

	@Test
	public void testNumberPred() {
		assertTrue(evalB("(number? 0)"));
		assertTrue(evalB("(number? (byte 0))"));
		assertTrue(evalB("(number? 0.5)"));
		assertTrue(evalB("(number? ##NaN)")); // Sane? Is numeric double type....
		
		assertFalse(evalB("(number? nil)"));
		assertFalse(evalB("(number? :foo)"));
		assertFalse(evalB("(number? 0xFF)"));
		assertFalse(evalB("(number? [1 2])"));
		
		assertFalse(evalB("(number? true)"));

	}

	@Test
	public void testZeroPred() {
		assertTrue(evalB("(zero? 0)"));
		assertTrue(evalB("(zero? (byte 0))"));
		assertTrue(evalB("(zero? 0.0)"));
		assertFalse(evalB("(zero? 0.00005)"));
		assertFalse(evalB("(zero? 0x00)")); // not numeric!

		assertFalse(0.0 > -0.0); // check we are living in a sane universe
		assertTrue(evalB("(zero? -0.0)"));
		
		assertFalse(evalB("(zero? \\c)"));

		assertFalse(evalB("(zero? nil)"));
		assertFalse(evalB("(zero? :foo)"));
		assertFalse(evalB("(zero? [1 2])"));
	}
	
	@Test
	public void testFn() {
		assertEquals(1L,evalL("((fn [] 1))"));
		assertEquals(2L,evalL("((fn [x] 2) 1)"));
		// TODO: more cases!
	}
	
	@Test
	public void testFnMulti() {
		assertEquals(1L,evalL("((fn ([] 1)))"));
		assertEquals(2L,evalL("((fn ([x] 2)) 1)"));
		
		// dispatch by arity
		assertEquals(1L,evalL("((fn ([x] 1) ([x y] 2)) 3)"));
		assertEquals(2L,evalL("((fn ([x] 1) ([x y] 2)) 3 4)"));
		
		// first matching impl chosen
		assertEquals(1L,evalL("((fn ([x] 1) ([x] 2)) 3)"));

		// variadic match
		assertEquals(2L,evalL("((fn ([x] 1) ([x & more] 2)) 3 4 5 6)"));
		assertEquals(2L,evalL("((fn ([x] 1) ([x y & more] 2)) 3 4)"));
		
		// MultiFn printing
		assertCVMEquals("(fn ([] 0) ([x] 1))",eval("(str (fn ([]0) ([x] 1) ))"));

		// arity errors
		assertArityError(step("((fn ([x] 1) ([x & more] 2)))")); 
		assertArityError(step("((fn ([x] 1) ([x y] 2)))")); 
		assertArityError(step("((fn ([x] 1) ([x y z] 2)) 2 3)")); 
		assertArityError(step("((fn ([x] 1) ([x y z & more] 2)) 2 3)")); 
	}
	
	@Test
	public void testFnMultiRecur() {
		assertEquals(7L,evalL("((fn ([x] x) ([x y] (recur 7))) 1 2)"));
		
		assertArityError(step("((fn ([x] x) ([x y] (recur))) 1 2)")); 
		
		assertJuiceError(step("((fn ([x] (recur 3 4)) ([x y] (recur 5))) 1 2)")); 
	}

	@Test
	public void testFnPred() {
		assertFalse(evalB("(fn? 0)"));
		assertTrue(evalB("(fn? (fn[x] 0))"));
		assertFalse(evalB("(fn? {})"));
		assertTrue(evalB("(fn? count)"));
		assertTrue(evalB("(fn? fn?)"));
		assertFalse(evalB("(fn? if)"));
	}
	
	@Test
	public void testDef() {
		assertEquals(Vectors.of(2L, 3L), eval("(do (def v [2 3]) v)"));
		
		// TODO: are these error types logical?
		assertCompileError(step("(def)"));
		assertCompileError(step("(def a b c)"));
		
		assertUndeclaredError(step("(def a b)"));
		
		assertUndeclaredError(step("(def a a)"));
	}
	
	@Test
	public void testDefined() {
		assertFalse(evalB("(defined? foobar)"));
		
		assertTrue(evalB("(do (def foobar [2 3]) (defined? foobar))"));
		assertTrue(evalB("(defined? count)"));
		assertTrue(evalB("(defined? :count)"));
		assertTrue(evalB("(defined? \"count\")"));
		
		// invalid names
		assertCastError(step("(defined? nil)"));
		assertCastError(step("(defined? 1)"));
		assertCastError(step("(defined? 0x)"));
		
		assertArityError(step("(defined?)"));
		assertArityError(step("(defined? foo bar)"));
	}
	
	@Test
	public void testUndef() {
		assertNull(eval("(undef count)"));
		assertNull(eval("(undef foo)"));
		assertNull(eval("(undef *balance*)"));
		assertNull(eval("(undef foo/bar)"));
		
		assertEquals(Vectors.of(1L, 2L), eval("(do (def a 1) (def v [a 2]) (undef a) v)"));
		
		assertFalse(evalB("(do (def a 1) (undef a) (defined? a))"));
		
		assertUndeclaredError(step("(do (def a 1) (undef a) a)"));
		
		assertArityError(step("(undef a b)"));
		assertArityError(step("(undef)"));
	}
	

	@Test
	public void testDefn() {
		assertTrue(evalB("(do (defn f [a] a) (fn? f))"));
		assertEquals(Vectors.of(2L, 3L), eval("(do (defn f [a & more] more) (f 1 2 3))"));
	
		// multiple expressions in body
		assertEquals(2L,evalL("(do (defn f [a] 1 2) (f 3))"));

		// arity problems
		assertArityError(step("(defn)"));
		assertArityError(step("(defn f)"));
		
		// bad function construction
		assertCompileError(step("(defn f b)"));
		
	}
	
	@Test
	public void testDefnMulti() {
		assertEquals(2L,evalL("(do (defn f ([a] 1 2)) (f 3))"));
		assertEquals(2L,evalL("(do (defn f ([] 4) ([a] 1 2)) (f 3))"));
		
		assertArityError(step("(do (defn f ([] nil)) (f 3))"));
	}
	
	@Test
	public void testDefactor() {
		Context<?> ctx=step("(let [agf (defactor multiply-actor [x] (defn calc [y] (* x y)) (export calc))] (def ma (deploy (agf 13))))");
		
		Address ma=(Address) ctx.getResult();
		assertNotNull(ma);
		
		assertEquals(130L,evalL(ctx,"(call ma (calc 10))"));
	}
	
	
	
	@Test
	public void testSetStar() {
		assertEquals(13L,evalL("(set* 'a 13)"));
		assertEquals(13L,evalL("(do (set* \"a\" 13) a)"));
		assertEquals(10L,evalL("(let [a 10] (let [] (set* 'a 13)) a)"));
		assertUndeclaredError(step("(do (let [a 10] (set* 'a 20)) a)"));
		
		assertArgumentError(step("(set* 'a/b 10)"));
	}
	
	@Test
	public void testSetBang() {
		// set returns its value
		assertEquals(13L,evalL("(set! a 13)"));
		
		// set! works without a binding expression
		assertEquals(13L,evalL("(do (set! a 13) a)"));
		
		// set! works in a function body
		assertEquals(35L,evalL("(let [a 13 f (fn [x] (set! a 25) (+ x a))] (f 10))"));
		
		// set! only works in the scope of the immediate surrounding binding expression
		assertEquals(10L,evalL("(let [a 10] (let [] (set! a 13)) a)"));
		
		// set! binding does not escape current form, still undeclared in enclosing local context
		assertUndeclaredError(step("(do (let [a 10] (set! a 20)) a)"));
		
		// set! fails if trying to set a qualified argument name
		assertArgumentError(step("(set! a/b 10)"));
	}

	@Test
	public void testEval() {
		assertEquals("foo", evalS("(eval (list 'str \\f \\o \\o))"));
		assertNull(eval("(eval 'nil)"));
		assertEquals(10L, evalL("(eval '(+ 3 7))"));
		assertEquals(40L, evalL("(eval '(* 2 4 5))"));

		assertArityError(step("(eval)"));
		assertArityError(step("(eval 1 2)"));
	}
	
	@Test
	public void testEvalAs() {
		assertEquals("foo", evalS("(eval-as *address* (list 'str \\f \\o \\o))"));
		
		assertTrustError(step("(eval-as *registry* '1)"));
		
		assertCastError(step("(eval-as :foo 2)"));
		assertArityError(step("(eval-as 1)")); // arity > cast
		assertArityError(step("(eval-as 1 2 3)"));
	}
	
	@Test
	public void testEvalAsTrustedUser() {
		Context<ACell> ctx=step("(set-controller "+TestState.VILLAIN+")");
		ctx=ctx.forkWithAddress(TestState.VILLAIN);
		ctx=step(ctx,"(def hero "+TestState.HERO+")");
		
		assertEquals(3L, evalL(ctx,"(eval-as hero '(+ 1 2))"));
		assertEquals(TestState.HERO, eval(ctx,"(eval-as hero '*address*)"));
		assertEquals(TestState.VILLAIN, eval(ctx,"(eval-as hero '*caller*)"));
		assertEquals(Keywords.FOO, eval(ctx,"(eval-as hero '(return :foo))"));
		assertEquals(Keywords.FOO, eval(ctx,"(eval-as hero '(halt :foo))"));
		assertEquals(Keywords.FOO, eval(ctx,"(eval-as hero '(rollback :foo))"));
		
		assertAssertError(step(ctx,"(eval-as hero '(assert false))"));
	}
	
	@Test
	public void testEvalAsUntrustedUser() {
		Context<?> ctx=step("(set-controller nil)");
		ctx=ctx.forkWithAddress(TestState.VILLAIN);
		ctx=step(ctx,"(def hero "+TestState.HERO+")");
		
		assertTrustError(step(ctx,"(eval-as hero '(+ 1 2))"));
		assertTrustError(step(ctx,"(eval-as (address hero) '(+ 1 2))"));
	}
	
	@Test
	public void testEvalAsWhitelistedUser() {
		// create trust monitor that allows VILLAIN
		Context<?> ctx=step("(deploy '(do (defn check-trusted? [s a o] (= s (address "+TestState.VILLAIN+"))) (export check-trusted?)))");
		Address monitor = (Address) ctx.getResult();
		ctx=step(ctx,"(set-controller "+monitor+")");
		
		ctx=ctx.forkWithAddress(TestState.VILLAIN);
		ctx=step(ctx,"(def hero "+TestState.HERO+")");
		
		assertEquals(3L, evalL(ctx,"(eval-as hero '(+ 1 2))"));
	}
	
	@Test
	public void testQuery() {
		Context<AVector<ACell>> ctx=step("(query (def a 10) [*address* *origin* *caller* 10])");
		assertEquals(Vectors.of(Init.HERO,Init.HERO,null,10L), ctx.getResult());
		
		// shouldn't be any def in the environment
		assertSame(INITIAL,ctx.getState());
		
		// some juice should be consumed
		assertTrue(INITIAL_CONTEXT.getJuice()>ctx.getJuice());
	}
	
	@Test
	public void testQueryError() {
		Context<CVMLong> ctx=step("(query (fail :FOO))");
		assertAssertError(ctx);
		
		// some juice should be consumed
		assertTrue(INITIAL_CONTEXT.getJuice()>ctx.getJuice());
	}
	
// TODO: probably needs Op level support?
//	@Test
//	public void testQueryAs() {
//		Context<AVector<ACell>> ctx=step("(query-as "+Init.VILLAIN+" '(do (def a 10) [*address* *origin* *caller* 10]))");
//		assertEquals(Vectors.of(Init.VILLAIN,Init.VILLAIN,null,10L), ctx.getResult());
//		
//		// shouldn't be any def in the environment
//		assertSame(INITIAL,ctx.getState());
//		assertSame(INITIAL_CONTEXT.getLocalBindings(),ctx.getLocalBindings());
//		
//		// some juice should be consumed
//		assertTrue(INITIAL_CONTEXT.getJuice()>ctx.getJuice());
//	}
	
	@Test
	public void testEvalAsNotWhitelistedUser() {
		// create trust monitor that allows HERO only
		Context<?> ctx=step("(deploy '(do (defn check-trusted? [s a o] (= s (address "+TestState.HERO+"))) (export check-trusted?)))");
		Address monitor = (Address) ctx.getResult();
		ctx=step(ctx,"(set-controller "+monitor+")");
		
		ctx=ctx.forkWithAddress(TestState.VILLAIN);
		ctx=step(ctx,"(def hero "+TestState.HERO+")");
		
		assertTrustError(step(ctx,"(eval-as hero '(+ 1 2))"));
	}
	
	@Test
	public void testSetController() {
		// set-controller returns new controller
		assertEquals(TestState.VILLAIN, eval("(set-controller "+TestState.VILLAIN+")"));
		assertEquals(TestState.VILLAIN, eval("(set-controller (address "+TestState.VILLAIN+"))"));
		assertEquals(null, (Address)eval("(set-controller nil)"));
		
		assertCastError(step("(set-controller :foo)"));
		assertCastError(step("(set-controller (address nil))"));
		
		assertArityError(step("(set-controller)")); 
		assertArityError(step("(set-controller 1 2)")); // arity > cast
	}
	
	@Test
	public void testScheduleFailures() {
		assertArityError(step("(schedule)"));
		assertArityError(step("(schedule 1)"));
		assertArityError(step("(schedule 1 2 3)"));
		assertArityError(step("(schedule :foo 2 3)")); // ARITY error before CAST
		
		assertCastError(step("(schedule :foo (def a 2))"));
		assertCastError(step("(schedule nil (def a 2))"));
	}

	@Test
	public void testScheduleExecution() throws BadSignatureException {
		long expectedTS = INITIAL.getTimeStamp().longValue() + 1000;
		Context<?> ctx = step("(schedule (+ *timestamp* 1000) (def a 2))");
		assertCVMEquals(expectedTS, ctx.getResult());
		State s = ctx.getState();
		BlobMap<ABlob, AVector<ACell>> sched = s.getSchedule();
		assertEquals(1L, sched.count());
		assertEquals(expectedTS, sched.entryAt(0).getKey().longValue());

		assertTrue(step(ctx, "(do a)").isExceptional());

		Block b = Block.of(expectedTS,Init.FIRST_PEER_KEY);
		BlockResult br = s.applyBlock(b);
		State s2 = br.getState();

		Context<?> ctx2 = Context.createInitial(s2, TestState.HERO, INITIAL_JUICE);
		assertEquals(2L, evalL(ctx2, "a"));
	}

	@Test
	public void testExpand() {
		assertEquals(Syntax.of(Strings.create("foo")), eval("(expand (name :foo) (fn [x e] x))"));
		assertEquals(Syntax.of(3L), eval("(expand '[1 2 3] (fn [x e] (nth x 2)))"));
		
		assertNull(Syntax.unwrap(eval("(expand nil)")));

		assertCastError(step("(expand 1 :foo)"));
		assertCastError(step("(expand { 888 227 723 560} [75 561 258 833])"));
		assertCastError(step("(expand { :CIWh 155578 } :nth )"));
		
		assertArityError(step("(expand)"));
		assertArityError(step("(expand 1 (fn [x e] x) :blah)"));
		
		// arity error calling expander function
		assertArityError(step("(expand 1 (fn [x] x))"));

		// arity error in expansion execution
		assertArityError(step("(expand 1 (fn [x e] (count)))"));
	}
	
	@Test
	public void testExpander() {
		assertCastError(step("(expander `(export test))")); // Issue #88
		assertCastError(step("(expander :foo)")); // Issue #88
		assertCastError(step("(expander *offer*)")); // Issue #83
		
		assertArityError(step("(expander)"));
		assertArityError(step("(expander (fn[]) (fn[]))"));
	}

	@Test
	public void testSyntax() {
		assertEquals(Syntax.of(null), eval("(syntax nil)"));
		assertEquals(Syntax.of(10L), eval("(syntax 10)"));

		// TODO: check if this is sensible
		// Syntax should be idempotent and wrap one level only
		assertCVMEquals(eval("(syntax 10)"), eval("(syntax (syntax 10))"));

		// Syntax with null / empty metadata should equal basic syntax
		assertCVMEquals(eval("(syntax 10)"), eval("(syntax 10 nil)"));
		assertCVMEquals(eval("(syntax 10)"), eval("(syntax 10 {})"));
		
		assertCastError(step("(syntax 2 3)"));
		
		assertArityError(step("(syntax)"));
		assertArityError(step("(syntax 2 3 4)"));
	}

	@Test
	public void testUnsyntax() {
		assertNull(eval("(unsyntax (syntax nil))"));
		assertNull(eval("(unsyntax nil)"));
		assertEquals(10L, evalL("(unsyntax (syntax 10))"));
		assertEquals(Keywords.FOO, eval("(unsyntax (expand :foo))"));

		assertArityError(step("(unsyntax)"));
		assertArityError(step("(unsyntax 2 3)"));
	}
	
	@Test
	public void testMeta() {
		assertEquals(Maps.empty(),eval("(meta (syntax nil))"));
		assertNull(eval("(meta nil)"));
		assertNull(eval("(meta 10)"));
		assertEquals(Maps.of(1L,2L), eval("(meta (syntax 10 {1 2}))"));

		assertArityError(step("(meta)"));
		assertArityError(step("(meta 2 3)"));
	}

	@Test
	public void testSyntaxQ() {
		assertFalse(evalB("(syntax? nil)"));
		assertTrue(evalB("(syntax? (syntax 10))"));

		assertArityError(step("(syntax?)"));
		assertArityError(step("(syntax? 2 3)"));
	}

	@Test
	public void testExports() {
		assertEquals(Sets.empty(), eval("*exports*"));
		assertEquals(Sets.of(Symbols.FOO, Symbols.BAR), eval("(do (export foo bar) *exports*)"));
	}

	@Test
	public void testExportsQ() {
		Context<?> ctx = step("(def caddr (deploy '(do " + "(defn private [] :priv) " + "(defn public [] :pub)"
				+ "(export public count))))");

		Address caddr = (Address) ctx.getResult();
		assertNotNull(caddr);

		assertTrue(evalB(ctx, "(exports? caddr :public)"));
		assertFalse(evalB(ctx, "(exports? caddr :random-name)"));
		assertFalse(evalB(ctx, "(exports? caddr :private)"));

		assertArityError(step(ctx, "(exports? 1)"));
		assertArityError(step(ctx, "(exports? 1 2 3)"));

		assertCastError(step(ctx, "(exports? :foo :foo)"));
		assertCastError(step(ctx, "(exports? nil :foo)"));
		assertCastError(step(ctx, "(exports? caddr nil)"));
		assertCastError(step(ctx, "(exports? caddr 1)"));
	}

	@Test
	public void testDec() {
		assertEquals(0L, evalL("(dec 1)"));
		assertEquals(0L, evalL("(dec (byte 1))"));
		assertEquals(-10L, evalL("(dec -9)"));
		// assertEquals(96L,(long)eval("(dec \\a)")); // TODO: think about this

		assertCastError(step("(dec nil)"));
		assertCastError(step("(dec :foo)"));
		assertCastError(step("(dec [1])"));
		assertCastError(step("(dec #666)"));
		assertCastError(step("(dec 3.0)"));

		assertArityError(step("(dec)"));
		assertArityError(step("(dec 1 2)"));
	}

	@Test
	public void testInc() {
		assertEquals(2L, evalL("(inc 1)"));
		assertEquals(2L, evalL("(inc (byte 1))"));
		// assertEquals(98L,(long)eval("(inc \\a)")); // TODO: think about this

		assertCastError(step("(inc #42)")); // Issue #89
		assertCastError(step("(inc nil)"));
		assertCastError(step("(inc \\c)")); // Issue #89
		assertCastError(step("(inc true)")); // Issue #89

		assertArityError(step("(inc)"));
		assertArityError(step("(inc 1 2)"));
	}

	@Test
	public void testOr() {
		assertNull(eval("(or)"));
		assertNull(eval("(or nil)"));
		assertEquals(Keywords.FOO, eval("(or :foo)"));
		assertEquals(Keywords.FOO, eval("(or nil :foo :bar)"));
		assertEquals(Keywords.FOO, eval("(or :foo nil :bar)"));

		// ensure later branches never get executed
		assertEquals(Keywords.FOO, eval("(or :foo (+ nil :bar))"));

		assertFalse(evalB("(or nil nil false)"));
		assertTrue(evalB("(or nil nil true)"));

		// arity error if fails before first truth value
		assertArityError(step("(or nil (count) true)"));
	}

	@Test
	public void testAnd() {
		assertTrue(evalB("(and)"));
		assertNull(eval("(and nil)"));
		assertEquals(Keywords.FOO, eval("(and :foo)"));
		assertEquals(Keywords.FOO, eval("(and :bar :foo)"));
		assertNull(eval("(and :foo nil :bar)"));

		// ensure later branches never get executed
		assertNull(eval("(and nil (+ nil :bar))"));

		assertFalse(evalB("(and 1 false 2)"));
		assertTrue(evalB("(and 1 :foo true true)"));

		// arity error if fails before first falsey value
		assertArityError(step("(and true (count) nil)"));
	}
	
	
	
	@Test
	public void testSpecialAddress() {
		Address HERO = TestState.HERO;
		
		// Hero should be address and origin in initial context
		assertEquals(HERO, eval("*address*"));
		assertEquals(HERO, eval("*origin*"));
	}
	
	@Test
	public void testSpecialAllowance() {
		assertEquals(Constants.INITIAL_ACCOUNT_ALLOWANCE, evalL("*memory*"));
	}
	

	@Test
	public void testSpecialBalance() {
		// balance should return exact balance of account after execution
		Address HERO = TestState.HERO;
		Context<?> ctx = step("(long *balance*)");
		Long bal=ctx.getAccountStatus(HERO).getBalance();
		assertCVMEquals(bal, ctx.getResult());
		
		// throwing it all away....
		assertEquals(0L, evalL("(do (transfer "+Init.VILLAIN+" *balance*) *balance*)"));
		
		// check balance as single expression
		assertEquals(bal, evalL("*balance*"));
		
		// test overrides in local any dynamic context
		assertNull(eval("(let [*balance* nil] *balance*)"));
		assertNull(eval("(do (def *balance* nil) *balance*)"));
	}
	
	@Test
	public void testSpecialCaller() {
		Address HERO = TestState.HERO;
		assertNull(eval("*caller*"));
		assertEquals(HERO, eval("(do (def c (deploy '(do (defn f [] *caller*) (export f)))) (call c (f)))"));
	}
	
	@Test
	public void testSpecialResult() {
		// initial context result should be null
		assertNull(eval("*result*"));
		
		// Result should get value of last completed expression
		assertEquals(Keywords.FOO, eval("(do :foo *result*)"));
		assertNull(eval("(do (do) *result*)"));
		
		// *result* should be cleared to nil in an Actor call.
		assertNull(eval("(do (def c (deploy '(do (defn f [] *result*) (export f)))) (call c (f)))"));

	}
	
	@Test
	public void testSpecialState() {
		assertSame(INITIAL, eval("*state*"));
		assertSame(INITIAL.getAccounts(), eval("(:accounts *state*)"));
	}
	
	@Test
	public void testSpecialKey() {
		assertEquals(Init.HERO_KP.getAccountKey(), eval("*key*"));
	}
	
	@Test
	public void testSpecialJuice() {
		// TODO: semantics of returning juice before lookup complete is OK?
		// seems sensible, represents "juice left at this position".
		assertEquals(INITIAL_JUICE, evalL("*juice*"));
		
		// juice gets consumed before returning a value
		assertEquals(INITIAL_JUICE-Juice.DO - Juice.CONSTANT, evalL("(do 1 *juice*)"));
	}


	@Test
	public void testSpecialEdgeCases() {

		// TODO: are we happy about letting people trash special symbols?
		assertEquals(Keywords.FOO, eval("(let [*balance* :foo] *balance*)"));
		assertEquals(Keywords.FOO, eval("(do (def *balance* :foo) *balance*)"));

	}
	
	@Test public void testSpecialHoldings() {
		assertSame(BlobMaps.empty(),eval("*holdings*"));
		
		// Test set-holding modifies *holdings* as expected
		Address HERO = TestState.HERO;
		assertNull(eval("(get-holding *address*)"));
		assertEquals(BlobMaps.of(HERO,1L),eval("(do (set-holding *address* 1) *holdings*)"));
		
		assertNull(eval("(*holdings* { :PuSg 650989 })"));
		assertEquals(Keywords.FOO,eval("(*holdings* { :PuSg 650989 } :foo )"));
	}
	
	@Test public void testHoldings() {
		Address VILLAIN = TestState.VILLAIN;
		Address HERO = TestState.HERO;
		Context<?> ctx = step("(def VILLAIN (address \""+VILLAIN.toHexString()+"\"))");
		assertTrue(eval(ctx,"VILLAIN") instanceof Address);
		ctx=step(ctx,"(def NOONE (address 7777777))");
		
		// initial holding behaviour
		assertNull(eval(ctx,"(get-holding VILLAIN)"));
		assertCastError(step(ctx,"(get-holding :foo)"));
		assertCastError(step(ctx,"(get-holding nil)"));
		assertNobodyError(step(ctx,"(get-holding NOONE)"));
		
		// OK to set holding for a real owner account
	    assertEquals(100L,evalL(ctx,"(set-holding VILLAIN 100)"));
	    
		// error to set holding for a non-existent owner account
		assertNobodyError(step(ctx,"(set-holding NOONE 200)"));

		// trying to set holding for the wrong type
		assertCastError(step(ctx,"(set-holding :foo 300)"));
		
		{ // test simple assign
			Context<?> c2 = step(ctx,"(set-holding VILLAIN 123)");
			assertEquals(123L,evalL(c2,"(get-holding VILLAIN)"));
			assertTrue(c2.getAccountStatus(VILLAIN).getHoldings().containsKey(HERO));
			assertCVMEquals(123L,c2.getAccountStatus(VILLAIN).getHolding(HERO));
		}
		
		{ // test null assign
			Context<?> c2 = step(ctx,"(set-holding VILLAIN nil)");
			assertFalse(c2.getAccountStatus(VILLAIN).getHoldings().containsKey(HERO));
		}
	}

	@Test
	public void testSymbolFor() {
		assertEquals(Symbols.COUNT, Core.symbolFor(Core.COUNT));
		assertThrows(Throwable.class, () -> Core.symbolFor(null));
	}

	@Test
	public void testCoreFormatRoundTrip() throws BadFormatException {
		{ // a core function
			ACell c = eval("count");
			Blob b = Format.encodedBlob(c);
			assertSame(c, Format.read(b));
		}

		{ // a core macro
			ACell c = eval("if");
			Blob b = Format.encodedBlob(c);
			assertSame(c, Format.read(b));
		}

		{ // a basic lambda expression
			ACell c = eval("(fn [x] x)");
			Blob b = Format.encodedBlob(c);
			assertEquals(c, Format.read(b));
		}

		{ // a basic lambda expression
			ACell c = eval("(expander (fn [x e] x))");
			Blob b = Format.encodedBlob(c);
			assertEquals(c, Format.read(b));
		}
	}

}
