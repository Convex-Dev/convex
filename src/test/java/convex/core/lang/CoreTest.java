package convex.core.lang;

import static convex.core.lang.TestState.INITIAL_CONTEXT;
import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.evalB;
import static convex.core.lang.TestState.evalL;
import static convex.core.lang.TestState.evalS;
import static convex.core.lang.TestState.step;
import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertArityError;
import static convex.test.Assertions.assertAssertError;
import static convex.test.Assertions.assertBoundsError;
import static convex.test.Assertions.assertCastError;
import static convex.test.Assertions.assertCompileError;
import static convex.test.Assertions.assertDepthError;
import static convex.test.Assertions.assertFundsError;
import static convex.test.Assertions.assertJuiceError;
import static convex.test.Assertions.assertMemoryError;
import static convex.test.Assertions.assertNobodyError;
import static convex.test.Assertions.assertStateError;
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
import convex.core.Init;
import convex.core.State;
import convex.core.crypto.Hash;
import convex.core.data.ABlob;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.Format;
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
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.impl.CoreFn;
import convex.core.lang.impl.CorePred;
import convex.core.lang.impl.ICoreDef;
import convex.core.lang.impl.RecurValue;
import convex.core.lang.impl.ReturnValue;
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

	@Test
	public void testAliases() {
		assertTrue(evalB("(map? *aliases*)"));
		assertEquals(0L,evalL("(count *aliases*)"));
		assertTrue(evalB("(empty? *aliases*)"));
	}
	
	@Test
	public void testAddress() {
		Address a = TestState.HERO;
		assertEquals(a, eval("(address \"" + a.toHexString() + "\")"));
		assertEquals(a, eval("(address 0x" + a.toHexString() + ")"));
		assertEquals(a, eval("(address (address \"" + a.toHexString() + "\"))"));
		assertEquals(a, eval("(address (blob \"" + a.toHexString() + "\"))"));

		// bad arities
		assertArityError(step("(address 1 2)"));
		assertArityError(step("(address)"));

		// invalid address lengths - not a cast error since argument types (in general) are valid
		assertArgumentError(step("(address \"1234abcd\")"));
		assertArgumentError(step("(address 0x1234abcd)"));

		// invalid conversions
		assertCastError(step("(address :foo)"));
		assertCastError(step("(address 1234)"));
		assertCastError(step("(address nil)"));
	}

	@Test
	public void testBlob() {
		Blob b = Blob.fromHex("cafebabe");
		assertEquals(b, eval("(blob \"Cafebabe\")"));
		assertEquals(b, eval("(blob (blob \"cafebabe\"))"));

		assertEquals("cafebabe", evalS("(str (blob \"Cafebabe\"))"));

		assertTrue(evalB("(= *address* (address (blob *address*)))"));
		
		// round trip back to Blob
		assertTrue(evalB("(blob? (blob (hash [1 2 3])))"));

		assertArityError(step("(blob 1 2)"));
		assertArityError(step("(blob)"));

		assertCastError(step("(blob :foo)"));
		assertCastError(step("(blob nil)"));
	}

	@Test
	public void testByte() {
		assertEquals((byte) 0x01, (byte) eval("(byte 1)"));
		assertEquals((byte) 0xff, (byte) eval("(byte 255)"));
		assertEquals((byte) 0xff, (byte) eval("(byte -1)"));
		assertEquals((byte) 0xff, (byte) eval("(byte (byte -1))"));

		assertCastError(step("(byte nil)"));
		assertCastError(step("(byte :foo)"));

		assertArityError(step("(byte)"));
		assertArityError(step("(byte nil nil)")); // arity before cast
	}

	@Test
	public void testInt() {
		assertEquals((int) 0x01, (int) eval("(int 1)"));
		assertEquals((int) 255, (int) eval("(int 255)"));
		assertEquals((int) 97, (int) eval("(int \\a)"));
		assertEquals((int) Integer.MIN_VALUE, (int) eval("(int 2147483648)"));
		assertEquals((int) -1, (int) eval("(int (byte 255))"));

		assertArityError(step("(int)"));
		assertArityError(step("(int 1 2)"));

		assertCastError(step("(int nil)"));
		assertCastError(step("(int [])"));
	}

	@Test
	public void testShort() {
		assertEquals((short) 7, (short) eval("(short 7)"));

		assertCastError(step("(short [1 2])"));

		assertArityError(step("(short 1 2)"));
		assertArityError(step("(short)"));
	}
	
	@Test
	public void testLet() {
		assertCastError(step("(let [[a b] :foo] b)"));
		
		assertArityError(step("(let [[a b] nil] b)"));
		assertArityError(step("(let [[a b] [1]] b)"));
		assertEquals(2L,evalL("(let [[a b] [1 2]] b)"));
		assertEquals(2L,evalL("(let [[a b] '(1 2)] b)"));

		assertCompileError(step("(let ['(a b) '(1 2)] b)"));

	}

	@Test
	public void testGet() {
		assertEquals(2L, (long) eval("(get {1 2} 1)"));
		assertEquals(4L, (long) eval("(get {1 2 3 4} 3)"));
		assertEquals(4L, (long) eval("(get {1 2 3 4} 3 7)"));
		assertNull(eval("(get {1 2} 2)")); // null if not present
		assertEquals(7L, (long) eval("(get {1 2 3 4} 5 7)")); // fallback arg

		assertEquals(1L, (long) eval("(get #{1 2} 1)"));
		assertEquals(2L, (long) eval("(get #{1 2} 2)"));
		assertNull(eval("(get #{1 2} 3)")); // null if not present
		assertEquals(4L, (long) eval("(get #{1 2} 3 4)")); // fallback

		assertEquals(2L, (long) eval("(get [1 2 3] 1)"));
		assertEquals(2L, (long) eval("(get [1 2 3] 1 7)"));
		assertEquals(7L, (long) eval("(get [1 2 3] 4 7)"));
		assertEquals(7L, (long) eval("(get [1 2] nil 7)"));
		assertEquals(7L, (long) eval("(get [1 2] -5 7)"));
		assertNull(eval("(get [1 2] :foo)"));
		assertNull(eval("(get [1 2] 10)"));
		assertNull(eval("(get [1 2] -1)"));
		assertNull(eval("(get [1 2] 1.0)"));

		assertNull(eval("(get nil nil)"));
		assertNull(eval("(get nil 10)"));
		assertEquals(3L, (long) eval("(get nil 2 3)"));
		assertEquals(3L, (long) eval("(get nil nil 3)"));

		assertArityError(step("(get 1)")); // arity > cast
		assertArityError(step("(get)"));
		assertArityError(step("(get 1 2 3 4)"));

		assertCastError(step("(get 1 2 3)")); // 3 arg could work, so cast error on 1st arg
		assertCastError(step("(get 1 1)")); // 2 arg could work, so cast error on 1st arg
	}

	@Test
	public void testGetIn() {
		assertEquals(2L, (long) eval("(get-in {1 2} [1])"));
		assertEquals(4L, (long) eval("(get-in {1 {2 4} 3 5} [1 2])"));
		assertEquals(1L, (long) eval("(get-in #{1 2} [1])"));
		assertEquals(2L, (long) eval("(get-in [1 2 3] [1])"));
		assertEquals(3L, (long) eval("(get-in [1 2 3] '(2))"));
		assertEquals(3L, (long) eval("(get-in (list 1 2 3) [2])"));
		assertEquals(4L, (long) eval("(get-in [1 2 {:foo 4} 3 5] [2 :foo])"));

		// special case: don't coerce to collection if empty sequence of keys
		// so non-collection value may be used safely
		assertEquals(3L, (long) eval("(get-in 3 [])"));

		assertEquals(Maps.of(1L, 2L), eval("(get-in {1 2} nil)"));
		assertEquals(Maps.of(1L, 2L), eval("(get-in {1 2} [])"));
		assertEquals(Vectors.empty(), eval("(get-in [] [])"));
		assertEquals(Lists.empty(), eval("(get-in (list) nil)"));

		assertNull(eval("(get-in nil nil)"));
		assertNull(eval("(get-in [1 2 3] [:foo])"));
		assertNull(eval("(get-in nil [])"));
		assertNull(eval("(get-in nil [1 2])"));
		assertNull(eval("(get-in #{} [1 2 3])"));

		assertArityError(step("(get-in 1)")); // arity > cast
		assertArityError(step("(get-in 1 2 3)")); // arity > cast

		assertCastError(step("(get-in 1 [1])"));
		assertCastError(step("(get-in [1] [0 2])"));

		assertCastError(step("(get-in [1] 1)"));
	}

	@Test
	public void testLong() {
		assertEquals(1L, (long) eval("(long 1)"));
		assertEquals(-128L, (long) eval("(long (byte 128))"));
		assertEquals(97L, (long) eval("(long \\a)"));
		assertEquals(2147483648L, (long) eval("(long 2147483648)"));
		
		assertEquals(4096L, (long) eval("(long 0x1000)"));
		assertEquals(255L, (long) eval("(long 0xff)"));
		assertEquals(4294967295L, (long) eval("(long 0xffffffff)"));
		assertEquals(-1L, (long) eval("(long 0xffffffffffffffff)"));
		assertEquals(255L, (long) eval("(long 0xff00000000000000ff)")); // only taking last 8 bytes
		assertEquals(-1L, (long) eval("(long 0xcafebabeffffffffffffffff)")); // interpret as big endian big integer


		assertArityError(step("(long)"));
		assertArityError(step("(long 1 2)"));
		assertCastError(step("(long nil)"));
		assertCastError(step("(long [])"));
		assertCastError(step("(long :foo)"));
	}

	@Test
	public void testChar() {
		assertEquals('a', (char) eval("\\a"));
		assertEquals('a', (char) eval("(char 97)"));
		assertEquals('a', (char) eval("(nth \"bar\" 1)"));

		assertCastError(step("(char nil)"));
		assertCastError(step("(char {})"));

		assertArityError(step("(char)"));
		assertArityError(step("(char nil nil)")); // arity before cast

	}

	@Test
	public void testBoolean() {
		// test precise values
		assertSame(Boolean.TRUE, eval("(boolean 1)"));
		assertSame(Boolean.FALSE, eval("(boolean nil)"));

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
	public void testStore() {
		assertNull(eval("(let [a {1 2} h (hash a)] (fetch h))"));
		assertEquals(Vectors.of(1L, 2L), eval("(let [a [1 2] h (hash a)] (store a) (fetch h))"));

		// Blob should work with fetch
		assertEquals(Vectors.of(1L, 2L,3L), eval("(let [a [1 2 3] h (hash a)] (store a) (fetch (blob h)))"));

		assertEquals(Keywords.STORE, eval("(let [a :store h (hash a)] (store a) (fetch h))"));
		
		// storing a parent should *not* store child objects
		assertNull(eval("(let [a [1 2] b [a a] h (hash a)] (store b) (fetch h))"));

		assertArityError(step("(store)"));
		assertArityError(step("(store 1 2)"));
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
	public void testKeys() {
		assertEquals(Vectors.empty(), eval("(keys {})"));
		assertEquals(Vectors.of(1L), eval("(keys {1 2})"));
		assertEquals(Sets.of(1L, 3L, 5L), eval("(set (keys {1 2 3 4 5 6}))"));

		assertEquals(Vectors.empty(),RT.keys(BlobMaps.empty()));
		assertEquals(Vectors.of(Init.HERO),RT.keys(BlobMap.create(Init.HERO, 1L)));
		
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
	public void testAbs() {
		// Integer cases
		assertEquals(1L,evalL("(abs 1)"));
		assertEquals(10L,evalL("(abs (byte 10))"));
		assertEquals(17L,evalL("(abs -17)"));
		assertEquals(Long.MAX_VALUE,evalL("(abs 9223372036854775807)"));
		
		// Double cases
		assertEquals(1.0,eval("(abs 1.0)"));
		assertEquals(13.0,eval("(abs (double -13))"));
		assertEquals(Math.pow(10,100),eval("(abs (pow 10 100))"));
		
		// Fun Double cases
		assertEquals(Double.NaN,eval("(abs NaN)"));
		assertEquals(Double.POSITIVE_INFINITY,eval("(abs (/ 1 0))"));
		assertEquals(Double.POSITIVE_INFINITY,eval("(abs (/ -1 0))"));
		
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
		assertEquals(0L,evalL("(signum 0.0)"));
		assertEquals(1L,evalL("(signum 1.0)"));
		assertEquals(-1L,evalL("(signum (double -13))"));
		assertEquals(1L,evalL("(signum (pow 10 100))"));
		
		// Fun Double cases
		assertCastError(step("(signum NaN)"));
		assertEquals(1L,evalL("(signum (/ 1 0))"));
		assertEquals(-1L,evalL("(signum (/ -1 0))"));
		
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
		assertEquals(2L, (long) eval("(nth [1 2] 1)"));
		assertEquals('c', (char) eval("(nth \"abc\" 2)"));

		assertArityError(step("(nth)"));
		assertArityError(step("(nth [])"));
		assertArityError(step("(nth [] 1 2)"));
		assertArityError(step("(nth 1 1 2)")); // arity > cast

		// cast errors for bad indexes
		assertCastError(step("(nth [] :foo)"));
		assertCastError(step("(nth [] nil)"));
		
		// BOUNDS error because nil treated as empty sequence
		assertBoundsError(step("(nth nil 10)"));

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
		assertEquals(Vectors.of(1L, 2L, 3L, 4L), eval("(vec (list 1 2 3 4))"));

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
		// empty index cases
		assertEquals(2L, evalL("(assoc-in {} [] 2)")); // empty indexes returns value
		assertEquals(2L, evalL("(assoc-in nil [] 2)")); // empty indexes returns value
		assertEquals(2L, evalL("(assoc-in :old [] 2)")); // empty indexes returns value
		
		// map cases
		assertEquals(Maps.of(1L,2L), eval("(assoc-in {} [1] 2)"));
		assertEquals(Maps.of(1L,2L,3L,4L), eval("(assoc-in {3 4} [1] 2)"));
		assertEquals(Maps.of(1L,2L), eval("(assoc-in nil [1] 2)"));
		assertEquals(Maps.of(1L,Maps.of(5L,6L),3L,4L), eval("(assoc-in {3 4} [1 5] 6)"));
		
		// vector cases
		assertEquals(Vectors.of(1L, 5L, 3L),eval("(assoc-in [1 2 3] [1] 5)"));
		assertEquals(Vectors.of(1L, 5L),eval("(assoc-in [1] [1] 5)"));
		assertEquals(Vectors.of(1L, 2L, 5L),eval("(assoc-in (first {1 2}) [2] 5)"));
		
		// Cast errors
		assertCastError(step("(assoc-in 1 [2] 3)"));
		assertCastError(step("(assoc-in [1] [:foo] 3)"));
		
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

		assertThrows(IndexOutOfBoundsException.class, () -> eval("(assoc [] 1 7)"));
		assertThrows(IndexOutOfBoundsException.class, () -> eval("(assoc [] -1 7)"));

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
		assertSame(Sets.empty(), eval("(disj nil 1)"));
		assertEquals(Sets.empty(), eval("(disj #{} 1)"));
		assertEquals(Sets.of(1L, 2L, 3L), eval("(disj (set [3 2 1 2 4]) 4)"));

		assertCastError(step("(disj [] 1)"));
		assertArityError(step("(disj)"));
		assertArityError(step("(disj nil 1 2)"));
	}

	@Test
	public void testSet() {
		assertEquals(Sets.of(1L, 2L, 3L), eval("(set [3 2 1 2])"));
		assertEquals(Sets.empty(), eval("(set nil)"));

		assertArityError(step("(set)"));
		assertArityError(step("(set 1 2)"));

		assertCastError(step("(set 1)"));
	}

	@Test
	public void testFirst() {
		assertEquals(1L, (long) eval("(first [1 2])"));
		assertEquals(1L, (long) eval("(first '(1 2 3 4))"));

		assertBoundsError(step("(first [])"));
		assertBoundsError(step("(first nil)"));

		assertArityError(step("(first)"));
		assertArityError(step("(first [1] 2)"));
		assertCastError(step("(first 1)"));
		assertCastError(step("(first :foo)"));
	}

	@Test
	public void testSecond() {
		assertEquals(2L, (long) eval("(second [1 2])"));

		assertBoundsError(step("(second [2])"));
		assertBoundsError(step("(second nil)"));

		assertArityError(step("(second)"));
		assertArityError(step("(second [1] 2)"));
		assertCastError(step("(second 1)"));
	}

	@Test
	public void testLast() {
		assertEquals(2L, (long) eval("(last [1 2])"));
		assertEquals(4L, (long) eval("(last [4])"));

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
		assertEquals(Vectors.of(1L, 2L, 3L), eval("(into [1 2] [3])"));
		assertEquals(Vectors.of(1L, 2L, 3L), eval("(into [1 2 3] nil)"));
		assertEquals(Lists.of(2L, 1L, 3L, 4L), eval("(into '(3 4) '(1 2))"));
		assertEquals(Sets.of(1L, 2L, 3L), eval("(into #{} [1 2 1 2 3])"));
		assertEquals(Maps.of(1L, 2L, 3L, 4L), eval("(into {} [[1 2] [3 4] [1 2]])"));
		assertNull(eval("(into nil nil)"));
		assertEquals(Vectors.empty(), eval("(into nil [])"));
		assertEquals(Vectors.of(1L, 2L, 3L), eval("(into nil [1 2 3])"));
		assertEquals(Maps.empty(), eval("(into {} [])"));
		
		assertEquals(Vectors.of(MapEntry.create(1L, 2L)), eval("(into [] {1 2})"));


		assertCastError(step("(into 1 [2 3])"));
		assertCastError(step("(into nil :foo)"));
		assertCastError(step("(into {} [nil])")); // nil is not a MapEntry
		assertCastError(step("(into {} [[:foo]])")); // length 1 vector shouldn't convert to MapEntry
		assertCastError(step("(into {} [[:foo :bar :baz]])")); // length 1 vector shouldn't convert to MapEntry

		assertArityError(step("(into)"));
		assertArityError(step("(into inc)"));
		assertArityError(step("(into 1 2 3)")); // arity > cast
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
	public void testMapv() {
		assertEquals(Vectors.empty(), eval("(map inc nil)"));
		assertEquals(Vectors.of(2L, 3L), eval("(mapv inc [1 2])"));
		assertEquals(Vectors.of(4L, 6L), eval("(mapv + '(1 2) '(3 4 5))"));

		assertArityError(step("(mapv)"));
		assertArityError(step("(mapv inc)"));
	}

	@Test
	public void testReduce() {
		assertEquals(24L, (long) eval("(reduce * 1 [1 2 3 4])"));
		assertEquals(2L, (long) eval("(reduce + 2 [])"));
		assertEquals(2L, (long) eval("(reduce + 2 nil)"));

		// add values, indexing into map entries as vectors
		assertEquals(10.0, (double) eval("(reduce (fn [acc me] (+ acc (me 1))) 0.0 {:a 1, :b 2, 107 3, nil 4})"));
		// reduce over map, destructuring keys and values
		assertEquals(100.0, (double) eval(
				"(reduce (fn [acc [k v]] (let [x (double (v nil))] (+ acc (* x x)))) 0.0 {true {nil 10}})"));

		assertCastError(step("(reduce 1 2 [])"));
		assertCastError(step("(reduce + 2 :foo)"));

		assertArityError(step("(reduce +)"));
		assertArityError(step("(reduce + 1)"));
		assertArityError(step("(reduce + 1 [2] [3])"));
	}

	@Test
	public void testReturn() {
		// basic return mechanics
		assertTrue(step("(return 1)").getValue() instanceof ReturnValue);

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
	public void testRecur() {
		// test factorial with accumulator
		assertEquals((Object) 120L, eval("(let [f (fn [a x] (if (> x 1) (recur (* a x) (dec x)) a))] (f 1 5))"));

		assertArityError(step("(let [f (fn [x] (recur x x))] (f 1))"));
		assertJuiceError(step("(let [f (fn [x] (recur x))] (f 1))"));

		// should hit depth limits before running out of juice
		// TODO: think about letrec?
		assertDepthError(step("(do   (def f (fn [x] (recur (f x))))   (f 1))"));

		// basic return mechanics
		assertTrue(step("(recur 1)").getValue() instanceof RecurValue);
	}

	@Test
	public void testHalt() {
		assertEquals(1L, (long) eval("(do (halt 1) (assert false))"));
		assertNull(eval("(do (halt) (assert false))"));

		// halt should not roll back state changes
		Context<?> ctx = step("(do (def a 13) (halt 2))");
		assertEquals(2L, ctx.getResult());
		assertEquals(13L, (long) eval(ctx, "a"));

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
	public void testRollback() {
		assertEquals(1L, (long) eval("(do (rollback 1) (assert false))"));
		assertEquals(1L, (long) eval("(do (def a 1) (rollback a) (assert false))"));

		// rollback should roll back state changes
		Context<?> ctx = step("(def a 17)");
		ctx = step(ctx, "(do (def a 13) (rollback 2))");
		assertEquals(17L, (long) eval(ctx, "a"));
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
		Context<?> ctx = step("(def lib (deploy '(do (def foo 100))))");
		
		{ // tests with a typical import
			Context<?> ctx2=step(ctx,"(import ~lib :as mylib)");
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
		assertNull(eval("(lookup 0x1234000000000000000000000000000000000000000000000000000000000000 'count)"));
		assertNull(eval("(do (def foo 1) (lookup 0x1234000000000000000000000000000000000000000000000000000000000000 'foo))"));


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
		
		assertNull(eval("(lookup-syntax 'non-existent-symbol)"));
		
		assertEquals(Syntax.create(1L),eval("(do (def foo 1) (lookup-syntax :foo))"));
		assertEquals(Syntax.create(1L),eval("(do (def foo 1) (lookup-syntax *address* :foo))"));
		assertNull(eval("(do (def foo 1) (lookup-syntax 0x1234000000000000000000000000000000000000000000000000000000000000 :foo))"));

		// invalid name string (too long)
		assertCastError(
				step("(lookup-syntax \"cdiubcidciuecgieufgvuifeviufegviufeviuefbviufegviufevguiefvgfiuevgeufigv\")"));

		assertCastError(step("(lookup-syntax count)"));
		assertCastError(step("(lookup-syntax nil)"));
		assertCastError(step("(lookup-syntax 10)"));
		assertCastError(step("(lookup-syntax [])"));

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
		assertEquals(1L, (long) eval("({2 1 1 2} 2)"));
		assertNull(eval("({2 1 1 2} 3)"));
		assertNull(eval("({} 3)"));

		// fall-through behaviour
		assertEquals(10L, (long) eval("({2 1 1 2} 5 10)"));
		assertNull(eval("({} 1 nil)"));

		// bad arity
		assertArityError(step("({})"));
		assertArityError(step("({} 1 2 3 4)"));
	}

	@Test
	public void testVectorAsFunction() {
		assertEquals(5L, (long) eval("([1 3 5 7] 2)"));

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
		assertEquals(5L, (long) eval("('(1 3 5 7) 2)"));

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

		assertEquals(Vectors.of(1L, 2L, 3L, 4L), eval("(apply vector 1 2 (list 3 4))"));
		assertEquals(List.of(1L, 2L, 3L, 4L), eval("(apply list 1 2 [3 4])"));
		assertEquals(List.of(1L, 2L), eval("(apply list 1 2 nil)"));

		// Insufficient args to apply
		assertArityError(step("(apply)"));
		assertArityError(step("(apply vector)"));
		
		// Insufficient args to applied function
		assertArityError(step("(apply assoc nil)")); 

		// Cast error if not applied to collection
		assertCastError(step("(apply inc 1)"));
		assertCastError(step("(apply inc :foo)"));
	}

	@Test
	public void testBalance() {
		// null address, shouldn't have any balance initially
		String addr=Address.dummy("0").toHexString();
		assertNull(eval("(let [a (address \""+addr+"\")] (balance a))"));

		// hero balance, should reflect cost of initial juice
		String a0 = TestState.HERO.toHexString();
		Long expectedHeroBalance = TestState.HERO_BALANCE;
		assertEquals(expectedHeroBalance, eval("(let [a (address \"" + a0 + "\")] (balance a))"));

		// someone else's balance
		String a1 = TestState.VILLAIN.toHexString();
		Long expectedVillainBalance = TestState.VILLAIN_BALANCE;
		assertEquals(expectedVillainBalance, eval("(let [a (address \"" + a1 + "\")] (balance a))"));

		assertCastError(step("(balance nil)"));
		assertCastError(step("(balance 1)"));
		assertCastError(step("(balance :foo)"));

		assertArityError(step("(balance)"));
		assertArityError(step("(balance 1 2)"));
	}

	@Test
	public void testAccept() {
		assertEquals(0L, (long) eval("(accept 0)"));
		assertEquals(0L, (long) eval("(accept 0.0)"));
		assertEquals(0L, (long) eval("(accept *offer*)")); // offer should be initially zero

		// accepting non-numeric value -> CAST error
		assertCastError(step("(accept :foo)"));
		assertCastError(step("(accept :foo)"));

		// accepting negative -> ARGUMENT error
		assertArgumentError(step("(accept -1)"));

		// accepting more than is offered -> STATE error
		assertStateError(step("(accept 1)"));

		assertArityError(step("(accept)"));
		assertArityError(step("(accept 1 2)"));
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

		assertStateError(step(ctx, "(call 0x1234567812345678123456781234567812345678123456781234567812345678 12 (bad-fn 1 2))")); // bad actor
		assertArgumentError(step(ctx, "(call ctr -12 (bad-fn 1 2))")); // negative offer

		// bad actor takes precedence over bad offer
		assertStateError(step(ctx, "(call 0x1234567812345678123456781234567812345678123456781234567812345678 -12 (bad-fn 1 2))")); 

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
		Address ca = ctx.getValue();
		assertNotNull(ca);
		AccountStatus as = ctx.getAccountStatus(ca);
		assertNotNull(as);
		assertEquals(ca, eval(ctx, "ctr")); // defined address in environment

		// initial deployed state
		assertEquals(0L, as.getBalance().getValue());
		
		// double-deploy should get different addresses
		assertFalse(evalB("(let [cfn '(do 1)] (= (deploy cfn) (deploy cfn)))"));
	}
	
	@Test
	public void testDeployOnce() {
		Context<Address> ctx = step("(def ctr (deploy-once '(fn [] :foo :bar)))");
		Address ca = ctx.getValue();
		assertNotNull(ca);
		AccountStatus as = ctx.getAccountStatus(ca);
		assertNotNull(as);
		assertEquals(ca, eval(ctx, "ctr")); // defined address in environment

		// initial deployed state
		assertEquals(0L, as.getBalance().getValue());
		
		// double-deploy should get same address
		assertTrue(evalB("(let [cfn '(do (def a 1))] (= (deploy-once cfn) (deploy-once cfn)))"));
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
	}
	
	@Test
	public void testAccountQ() {
		// a new Actor is an account
		Context<Address> ctx = step("(def ctr (deploy '(fn [] :foo :bar)))");
		assertTrue(evalB(ctx,"(account? ctr)"));
		
		// standard actors are accounts
		assertTrue(evalB(ctx,"(account? *registry*)"));
		
		// a fake address
		assertFalse(evalB(ctx,"(account? 0x1234567812345678123456781234567812345678123456781234567812345678)"));
		
		// hero address is an account
		assertTrue(evalB(ctx,"(account? *address*)"));
		
		assertCastError(step("(account? :foo)"));
		assertCastError(step("(account? nil)"));
		assertCastError(step("(account? [])"));
		assertCastError(step("(account? 'foo)"));
		
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
		assertNull(eval(ctx,"(account 0x1234567812345678123456781234567812345678123456781234567812345678)"));
		
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
		
		assertEquals(ALL-1337, step("(transfer-memory 0x"+Init.VILLAIN.toHexString()+" 1337)").getAccountStatus(HERO).getAllowance());

		assertEquals(0L, step("(transfer-memory 0x"+Init.VILLAIN.toHexString()+" "+ALL+")").getAccountStatus(HERO).getAllowance());
 
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
		assertStateError(step("(transfer 0x"+CORE.toHexString()+" 1337)"));
		
		{ // transfer to an Actor that accepts everything
			Context<?> ctx=step("(deploy '(do (defn receive-coin [sender amount data] (accept amount)) (export receive-coin)))");
			Address receiver=(Address) ctx.getResult();
			
			ctx=step(ctx,"(transfer 0x"+receiver.toHexString()+" 100)");
			assertEquals(100L,ctx.getResult());
			assertEquals(100L,ctx.getBalance(receiver));
		}
		
		{ // transfer to an Actor that accepts nothing
			Context<?> ctx=step("(deploy '(do (defn receive-coin [sender amount data] (accept 0)) (export receive-coin)))");
			Address receiver=(Address) ctx.getResult();
			
			ctx=step(ctx,"(transfer 0x"+receiver.toHexString()+" 100)");
			assertEquals(0L,ctx.getResult());
			assertEquals(0L,ctx.getBalance(receiver));
		}
		
		{ // transfer to an Actor that accepts half
			Context<?> ctx=step("(deploy '(do (defn receive-coin [sender amount data] (accept (/ amount 2))) (export receive-coin)))");
			Address receiver=(Address) ctx.getResult();
			
			ctx=step(ctx,"(transfer 0x"+receiver.toHexString()+" 100)");
			assertEquals(50L,ctx.getResult());
			assertEquals(50L,ctx.getBalance(receiver));
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

		// String representing a new Address
		String naddr=Address.dummy("123").toHexString();
		
		// transfers to a new address
		{
			Context<?> nc1=step("(transfer (address \""+naddr+"\") 1337)");
			assertEquals(1337L, nc1.getResult());
			assertEquals(BAL - 1337,nc1.getBalance(HERO));
		}
		
		assertEquals(1337L, evalL("(let [a (address \""+naddr+"\")]"
				+ " (transfer a 1337)" + " (balance a))"));

		assertTrue(() -> eval("(let [a (address \""+naddr+"\")]"
				+ "   (not (= *balance* (transfer a 1337))))"));

		// transfer it all!
		assertEquals(0L,step("(transfer (address \""+naddr+"\") *balance*)").getBalance(HERO));

		// Should never be possible to transfer negative amounts
		assertArgumentError(step("(transfer *address* -1000)"));
		assertArgumentError(step("(transfer (address \""+naddr+"\") -1)"));

		// Long.MAX_VALUE is too big for an Amount
		assertArgumentError(step("(transfer *address* 9223372036854775807)")); // Long.MAX_VALUE

		assertFundsError(step("(transfer *address* 9999999999999)"));

		assertCastError(step("(transfer 1 1)"));
		assertCastError(step("(transfer *address* :foo)"));

		assertArityError(step("(transfer)"));
		assertArityError(step("(transfer 1)"));
		assertArityError(step("(transfer 1 2 3)"));
	}
	
	@Test
	public void testStake() {
		Context<Object> ctx=step(TestState.INITIAL_CONTEXT,"(def my-peer \""+Init.FIRST_PEER.toHexString()+"\")");
		Address MY_PEER=Init.FIRST_PEER;
		long PS=ctx.getState().getPeer(Init.FIRST_PEER).getOwnStake();
		
		{
			// simple case of staking 1000000 on first peer of the realm
			Context<Object> rc=step(ctx,"(stake my-peer 1000000)");
			assertEquals(PS+1000000,rc.getState().getPeer(MY_PEER).getTotalStake());
			assertEquals(1000000,rc.getState().getPeer(MY_PEER).getDelegatedStake());
			assertEquals(TestState.TOTAL_FUNDS, rc.getState().computeTotalFunds());
		}
		
		// staking on an account that isn't a peer
		assertStateError(step(ctx,"(stake *address* 1234)"));
		
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
		assertTrue(evalB("(== 0.0 -0.0)"));
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
		assertTrue(evalB("(>= \\b \\a)"));

		// juice should go down in order of evaluation
		assertTrue(evalB("(> *juice* *juice* *juice*)"));

		assertCastError(step("(> :foo)"));
		assertCastError(step("(> :foo :bar)"));
		assertCastError(step("(> [] [1])"));
	}

	@Test
	public void testMin() {
		assertEquals(1L, (long) eval("(min 1 2 3 4)"));
		assertEquals(7L, (long) eval("(min 7)"));
		assertEquals(2L, (long) eval("(min 4 3 2)"));

		assertArityError(step("(min)"));
	}

	@Test
	public void testMax() {
		assertEquals(4L, (long) eval("(max 1 2 3 4)"));
		assertEquals(7L, (long) eval("(max 7)"));
		assertEquals(4L, (long) eval("(max 4 3 2)"));

		assertArityError(step("(max)"));
	}

	@Test
	public void testHash() {
		assertSame(Hash.NULL_HASH, eval("(hash nil)"));
		assertSame(Hash.TRUE_HASH, eval("(hash true)"));
		assertSame(Maps.empty().getHash(), eval("(hash {})"));
		assertTrue(evalB("(= (hash 123) (hash 123))"));

		assertArityError(step("(hash)"));
		assertArityError(step("(hash nil nil)"));
	}

	@Test
	public void testCount() {
		assertEquals(0L, (long) eval("(count nil)"));
		assertEquals(0L, (long) eval("(count [])"));
		assertEquals(0L, (long) eval("(count ())"));
		assertEquals(0L, (long) eval("(count \"\")"));
		assertEquals(2L, (long) eval("(count (list :foo :bar))"));
		assertEquals(2L, (long) eval("(count #{1 2 2})"));
		assertEquals(3L, (long) eval("(count [1 2 3])"));
		
		// Count of a map is the number of entries
		assertEquals(2L, (long) eval("(count {1 2 2 3})")); 

		assertCastError(step("(count 1)"));
		assertCastError(step("(count :foo)"));

		assertArityError(step("(count)"));
		assertArityError(step("(count 1 2)"));
	}

	@Test
	public void testCompile() {
		assertEquals(Constant.create(1L), eval("(compile 1)"));
		
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
		Context<?> C = INITIAL_CONTEXT;
		Object[] a0 = new Object[0];
		Object[] a1 = new Object[1];
		Object[] a2 = new Object[2];
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
			ICoreDef def = syndef.getValue();
			Symbol sym = def.getSymbol();
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
		assertFalse(evalB("(address? 12345)"));
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
		assertFalse(evalB("(blob? (hash *state*))"));
	}

	@Test
	public void testLongPred() {
		assertTrue(evalB("(long? 1)"));
		assertTrue(evalB("(long? (long *balance*))")); // TODO: is this sane?
		assertFalse(evalB("(long? (int 1))"));
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
		assertTrue(evalB("(number? (int 0))"));
		assertTrue(evalB("(number? 0.5)"));
		assertFalse(evalB("(number? nil)"));
		assertFalse(evalB("(number? :foo)"));
		assertFalse(evalB("(number? 0xFF)"));
		assertFalse(evalB("(number? [1 2])"));
	}

	@Test
	public void testZeroPred() {
		assertTrue(evalB("(zero? 0)"));
		assertTrue(evalB("(zero? (int 0))"));
		assertTrue(evalB("(zero? 0.0)"));
		assertFalse(evalB("(zero? 0.00005)"));
		assertFalse(evalB("(zero? 0x00)")); // not numeric!

		assertFalse(0.0 > -0.0); // check we are living in a sane universe
		assertTrue(evalB("(zero? -0.0)"));

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
		assertEquals(10L, (long) eval("(eval '(+ 3 7))"));
		assertEquals(40L, (long) eval("(eval '(* 2 4 5))"));

		assertArityError(step("(eval)"));
		assertArityError(step("(eval 1 2)"));
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
		long expectedTS = INITIAL.getTimeStamp() + 1000;
		Context<?> ctx = step("(schedule (+ *timestamp* 1000) (def a 2))");
		assertEquals(expectedTS, ctx.getResult());
		State s = ctx.getState();
		BlobMap<ABlob, AVector<Object>> sched = s.getSchedule();
		assertEquals(1L, sched.count());
		assertEquals(expectedTS, sched.entryAt(0).getKey().longValue());

		assertTrue(step(ctx, "(do a)").isExceptional());

		Block b = Block.of(expectedTS);
		BlockResult br = s.applyBlock(b);
		State s2 = br.getState();

		Context<?> ctx2 = Context.createInitial(s2, TestState.HERO, INITIAL_JUICE);
		assertEquals(2L, (long) eval(ctx2, "a"));
	}

	@Test
	public void testExpand() {
		assertEquals(Syntax.create(Strings.create("foo")), eval("(expand (name :foo) (fn [x e] x))"));
		assertEquals(Syntax.create(3L), eval("(expand '[1 2 3] (fn [x e] (nth x 2)))"));

		assertArityError(step("(expand)"));
		assertArityError(step("(expand 1 (fn [x e] x) :blah)"));
		assertArityError(step("(expand 1 (fn [x] x))"));
	}

	@Test
	public void testSyntax() {
		assertEquals(Syntax.create(null), eval("(syntax nil)"));
		assertEquals(Syntax.create(10L), eval("(syntax 10)"));

		// TODO: check if this is sensible
		// Syntax should be idempotent and wrap one level only
		assertEquals((Object)eval("(syntax 10)"), eval("(syntax (syntax 10))"));

		// Syntax with null / empty metadata should equal basic syntax
		assertEquals((Object)eval("(syntax 10)"), eval("(syntax 10 nil)"));
		assertEquals((Object)eval("(syntax 10)"), eval("(syntax 10 {})"));
		
		assertCastError(step("(syntax 2 3)"));
		
		assertArityError(step("(syntax)"));
		assertArityError(step("(syntax 2 3 4)"));
	}

	@Test
	public void testUnsyntax() {
		assertNull(eval("(unsyntax (syntax nil))"));
		assertNull(eval("(unsyntax nil)"));
		assertEquals(10L, (long) eval("(unsyntax (syntax 10))"));
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

		assertCastError(step(ctx, "(exports? 1 :foo)"));
		assertCastError(step(ctx, "(exports? nil :foo)"));
		assertCastError(step(ctx, "(exports? caddr nil)"));
		assertCastError(step(ctx, "(exports? caddr 1)"));
	}

	@Test
	public void testDec() {
		assertEquals(0L, (long) eval("(dec 1)"));
		assertEquals(0L, (long) eval("(dec (byte 1))"));
		assertEquals(-10L, (long) eval("(dec -9)"));
		// assertEquals(96L,(long)eval("(dec \\a)")); // TODO: think about this

		assertCastError(step("(dec nil)"));
		assertCastError(step("(dec :foo)"));
		assertCastError(step("(dec [1])"));

		assertArityError(step("(dec)"));
		assertArityError(step("(dec 1 2)"));
	}

	@Test
	public void testInc() {
		assertEquals(2L, (long) eval("(inc 1)"));
		assertEquals(2L, (long) eval("(inc (byte 1))"));
		// assertEquals(98L,(long)eval("(inc \\a)")); // TODO: think about this

		assertCastError(step("(inc nil)"));

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

		assertFalse((Boolean) eval("(or nil nil false)"));
		assertTrue((Boolean) eval("(or nil nil true)"));

		// arity error if fails before first truth value
		assertArityError(step("(or nil (count) true)"));
	}

	@Test
	public void testAnd() {
		assertTrue((Boolean) eval("(and)"));
		assertNull(eval("(and nil)"));
		assertEquals(Keywords.FOO, eval("(and :foo)"));
		assertEquals(Keywords.FOO, eval("(and :bar :foo)"));
		assertNull(eval("(and :foo nil :bar)"));

		// ensure later branches never get executed
		assertNull(eval("(and nil (+ nil :bar))"));

		assertFalse((Boolean) eval("(and 1 false 2)"));
		assertTrue((Boolean) eval("(and 1 :foo true true)"));

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
		Long bal=ctx.getAccountStatus(HERO).getBalance().getValue();
		assertEquals(bal, ctx.getResult());
		
		// throwing it all away....
		assertEquals(0L, evalL("(do (transfer 0x0000000000000000000000000000000000000000000000000000000000000000 *balance*) *balance*)"));
		
		// check balance as single expression
		assertEquals(bal, eval("*balance*"));
		
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
	public void testSpecialJuice() {
		// TODO: semantics of returning juice before lookup complete is OK?
		// seems sensible, represents "juice left at this position".
		assertEquals(INITIAL_JUICE, (long) eval("*juice*"));
		
		// juice gets consumed before returning a value
		assertEquals(INITIAL_JUICE-Juice.DO - Juice.CONSTANT, (long) eval("(do 1 *juice*)"));
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
		
	}
	
	@Test public void testHoldings() {
		Address VILLAIN = TestState.VILLAIN;
		Address HERO = TestState.HERO;
		Context<?> ctx = step("(def VILLAIN (address \""+VILLAIN.toHexString()+"\"))");
		assertTrue(eval(ctx,"VILLAIN") instanceof Address);
		ctx=step(ctx,"(def NOONE 0x"+Address.dummy("0").toHexString()+")");
		
		// initial holding behaviour
		assertNull(eval(ctx,"(get-holding VILLAIN)"));
		assertCastError(step(ctx,"(get-holding :foo)"));
		assertCastError(step(ctx,"(get-holding nil)"));
		assertNobodyError(step(ctx,"(get-holding NOONE)"));
		
		// OK to set holding for a real owner account
	    assertEquals(100L,(long)eval(ctx,"(set-holding VILLAIN 100)"));
	    
		// error to set holding for a non-existent owner account
		assertNobodyError(step(ctx,"(set-holding NOONE 200)"));

		// trying to set holding for the wrong type
		assertCastError(step(ctx,"(set-holding :foo 300)"));
		
		{ // test simple assign
			Context<?> c2 = step(ctx,"(set-holding VILLAIN 123)");
			assertEquals(123L,(long)eval(c2,"(get-holding VILLAIN)"));
			assertTrue(c2.getAccountStatus(VILLAIN).getHoldings().containsKey(HERO));
			assertEquals(123L,c2.getAccountStatus(VILLAIN).getHolding(HERO));
		}
		
		{ // test null assign
			Context<?> c2 = step(ctx,"(set-holding VILLAIN nil)");
			assertFalse(c2.getAccountStatus(VILLAIN).getHoldings().containsKey(HERO));
		}
	}

	@Test
	public void testSymbolFor() {
		assertEquals(Symbols.COUNT, Core.symbolFor(Core.COUNT));
		assertThrows(Throwable.class, () -> Core.symbolFor(0));
	}

	@Test
	public void testCoreFormatRoundTrip() throws BadFormatException {
		{ // a core function
			Object c = eval("count");
			Blob b = Format.encodedBlob(c);
			assertSame(c, Format.read(b));
		}

		{ // a core macro
			Object c = eval("if");
			Blob b = Format.encodedBlob(c);
			assertSame(c, Format.read(b));
		}

		{ // a basic lambda expression
			Object c = eval("(fn [x] x)");
			Blob b = Format.encodedBlob(c);
			assertEquals(c, Format.read(b));
		}

		{ // a basic lambda expression
			Object c = eval("(expander (fn [x e] x))");
			Blob b = Format.encodedBlob(c);
			assertEquals(c, Format.read(b));
		}
	}

}
