package convex.core.lang;

import static convex.test.Assertions.assertJuiceError;
import static convex.test.Assertions.assertUndeclaredError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.AOp;
import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Symbols;
import convex.core.cvm.Syntax;
import convex.core.cvm.ops.Cond;
import convex.core.cvm.ops.Constant;
import convex.core.cvm.ops.Def;
import convex.core.cvm.ops.Do;
import convex.core.cvm.ops.Invoke;
import convex.core.cvm.ops.Lambda;
import convex.core.cvm.ops.Let;
import convex.core.cvm.ops.Local;
import convex.core.cvm.ops.Lookup;
import convex.core.cvm.ops.Query;
import convex.core.cvm.ops.Set;
import convex.core.cvm.ops.Special;
import convex.core.cvm.ops.Try;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.CodedValue;
import convex.core.data.DenseRecord;
import convex.core.data.ExtensionValue;
import convex.core.data.Format;
import convex.core.data.List;
import convex.core.data.ObjectsTest;
import convex.core.data.Symbol;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.BaseTest;
import convex.core.init.Init;
import convex.core.lang.impl.AClosure;
import convex.core.lang.impl.Fn;
import convex.core.util.Utils;

/**
 * Tests for ops functionality.
 *
 * In general, focused on unit testing special op capabilities. General on-chain
 * behaviour should be covered elsewhere.
 */
public class OpsTest extends ACVMTest {

	protected OpsTest() {
		super(BaseTest.STATE);
	}

	private final long INITIAL_JUICE = context().getJuiceAvailable();

	@Test
	public void testConstant() {
		Context c = context();

		{// simple long constant
			AOp<CVMLong> op = Constant.of(10L);
			Context c2 = c.fork().execute(op);

			assertEquals(INITIAL_JUICE - Juice.CONSTANT, c2.getJuiceAvailable());
			assertEquals(CVMLong.create(10L), c2.getResult());
			doOpTest(op);
		}

		{// null constant
			AOp<ACell> op = Constant.nil();
			Context c2 = c.fork().execute(op);

			assertEquals(INITIAL_JUICE - Juice.CONSTANT, c2.getJuiceAvailable());
			assertNull(c2.getResult());
			doOpTest(op);
			
			assertEquals(Blob.wrap(new byte[] {CVMTag.OP_CODED,CVMTag.OPCODE_CONSTANT,Tag.NULL}),op.getEncoding());
		}
		
		{// nested constant
			AOp<ACell> op = Constant.of(Constant.nil());
			Context c2 = c.fork().execute(op);

			assertEquals(INITIAL_JUICE - Juice.CONSTANT, c2.getJuiceAvailable());
			assertEquals(Constant.nil(),c2.getResult());
			doOpTest(op);
			
			assertEquals(Blob.wrap(new byte[] {CVMTag.OP_CODED,CVMTag.OPCODE_CONSTANT,CVMTag.OP_CODED,CVMTag.OPCODE_CONSTANT,Tag.NULL}),op.getEncoding());
		}
	}

	@Test
	public void testOutOfJuice() {
		long JUICE = Juice.CONSTANT - 1; // insufficient juice to run operation
		Context c = Context.create(INITIAL, HERO, JUICE);

		AOp<CVMLong> op = Constant.of(10L);
		assertJuiceError(c.execute(op));

		doOpTest(op);
	}

	@Test
	public void testDef() {
		Context c1 = context();

		Symbol fooSym = Symbol.create("foo");
		AOp<AString> op = Def.create(Syntax.create(fooSym), Constant.createString("bar"));

		AMap<Symbol, ACell> env1 = c1.getEnvironment();
		Context c2 = c1.execute(op);
		AMap<Symbol, ACell> env2 = c2.getEnvironment();

		assertNotEquals(env1, env2);

		assertNull(env1.get(fooSym)); // initially no entry
		assertEquals("bar", env2.get(fooSym).toString());

		long expectedJuice = INITIAL_JUICE - Juice.CONSTANT - Juice.DEF;
		assertEquals(expectedJuice, c2.getJuiceAvailable());
		assertEquals("bar", c2.getResult().toString());

		AOp<AString> lookupOp = Lookup.create(Symbol.create("foo"));
		Context c3 = c2.execute(lookupOp);
		expectedJuice -= Juice.LOOKUP_DYNAMIC;
		assertEquals(expectedJuice, c3.getJuiceAvailable());
		assertEquals("bar", c3.getResult().toString());

		doOpTest(op);
		doOpTest(lookupOp);
	}

	@Test
	public void testUndeclaredLookup() {
		Context c = context();
		AOp<AString> op = Lookup.create("missing-symbol");
		assertUndeclaredError(c.execute(op));

		doOpTest(op);
	}

	@Test
	public void testDo() throws BadFormatException {
		Context c = context();

		AOp<AString> op = Do.create(Def.create("foo", Constant.createString("bar")), Lookup.create("foo"));

		Context c2 = c.execute(op);
		long expectedJuice = INITIAL_JUICE - (Juice.CONSTANT + Juice.DEF + Juice.LOOKUP_DYNAMIC + Juice.DO);
		assertEquals(expectedJuice, c2.getJuiceAvailable());
		assertEquals("bar", c2.getResult().toString());
		
		Blob enc=op.getEncoding();
		
		assertEquals(CVMTag.OP_DO,op.getTag());
		assertEquals(op,DenseRecord.read(CVMTag.OP_DO, enc,0));

		ObjectsTest.doCAD3Tests(op);
		
		doOpTest(op);
	}
	
	@Test
	public void testTry() throws BadFormatException {
		AOp<CVMLong> op = Try.create(Invoke.create(Constant.of(CVMLong.ZERO)), Constant.of(CVMLong.ONE));

		Context c = context();
		Context c2 = c.execute(op);
		assertFalse(c2.isExceptional());
		assertEquals(CVMLong.ONE,c2.getResult());

		doOpTest(op);
	}
	
	@Test
	public void testQuery() throws BadFormatException {
		Query<CVMLong> op = Query.create(Def.create(Symbols.FOO, Constant.of(CVMLong.ONE)));

		Context c = context();
		Context c2 = c.execute(op);
		assertFalse(c2.isExceptional());
		assertEquals(CVMLong.ONE,c2.getResult());

		assertFalse(c2.getEnvironment().containsKey(Symbols.FOO));
		
		Blob enc=op.getEncoding();
		CodedValue cv=CodedValue.read(op.getTag(), enc, 0);
		assertEquals(op,cv);
		assertEquals(op.getRefCount(),cv.getRefCount());
		
		doOpTest(op);
	}
	
	@Test
	public void testBadQuery() {
		// query with a null op
		Query<CVMLong> op=Reader.read("#[c0bb800100]");
		Context c = context();
		c=step(c,op);
	}

	@Test
	public void testSpecial() {
		Context c = context();

		AOp<Address> op = Special.forSymbol(Symbols.STAR_ADDRESS);
		AOp<Address> op2 = Special.forSymbol(Symbol.create("*address*"));
		assertEquals(op,op2); // double check lookup in hash map

		Context c2 = c.execute(op);
		assertEquals(c2.getAddress(), c2.getResult());

		doOpTest(op);
	}
	
	@Test public void testAllSpecials() {
		Symbol[] syms=Special.SYMBOLS;
		Context ctx=context();
		
		for (Symbol s: syms) {
			ACell result=eval(s);
			
			// should be same result on same state
			assertEquals(result,exec(ctx,s.toString()).getResult());
		}
	}
	
	@Test
	public void testSet() throws BadFormatException {
		AOp<Address> op = Set.create(45, Constant.nil());
		Blob expectedEncoding=Blob.fromHex("c0112dc0b000");
		assertEquals(expectedEncoding,op.getEncoding());
		assertEquals(op,Format.read(expectedEncoding));
		doOpTest(op);
	}

	@Test
	public void testLet() {
		Context c = context();
		AOp<AString> op = Let.create(Vectors.of(Symbols.FOO),
				Vectors.of(Constant.createString("bar"), Local.create(0)), false);
		Context c2 = c.execute(op);
		assertEquals("bar", c2.getResult().toString());

		doOpTest(op);
	}

	@Test
	public void testCondTrue() {
		Context c = context();

		AOp<AString> op = Cond.create(Constant.of(true), Constant.createString("trueResult"),
				Constant.createString("falseResult"));

		Context c2 = c.execute(op);

		assertEquals("trueResult", c2.getResult().toString());
		long expectedJuice = INITIAL_JUICE - (Juice.COND_OP + Juice.CONSTANT + Juice.CONSTANT);
		assertEquals(expectedJuice, c2.getJuiceAvailable());

		doOpTest(op);
	}

	@Test
	public void testCondFalse() {
		Context c = context();

		AOp<AString> op = Cond.create(Constant.of(false), Constant.createString("trueResult"),
				Constant.createString("falseResult"));

		Context c2 = c.execute(op);

		assertEquals("falseResult", c2.getResult().toString());
		long expectedJuice = (Juice.COND_OP + Juice.CONSTANT + Juice.CONSTANT);
		assertEquals(expectedJuice, c2.getJuiceUsed());

		doOpTest(op);
	}

	@Test
	public void testCondNoResult() {
		Context c = context();

		AOp<AString> op = Cond.create(Constant.of(false), Constant.createString("trueResult"));

		Context c2 = c.execute(op);

		assertNull(c2.getResult());
		long expectedJuice = INITIAL_JUICE - (Juice.COND_OP + Juice.CONSTANT);
		assertEquals(expectedJuice, c2.getJuiceAvailable());

		doOpTest(op);
	}

	@Test
	public void testCondEnvironmentChange() {
		Context c = context();

		Symbol sym = Symbol.create("val");

		AOp<AString> op = Cond.create(Do.create(Def.create(sym, Constant.of(false)), Constant.of(false)),
				Constant.createString("1"), Lookup.create(sym), Constant.of("2"),
				Do.create(Def.create(sym, Constant.of(true)), Constant.of(false)), Constant.of("3"),
				Lookup.create(sym), Constant.of("4"), Constant.of("5"));

		Context c2 = c.execute(op);
		assertEquals("4", c2.getResult().toString());

		doOpTest(op);
	}

	@Test
	public void testInvoke() {
		Context c = context();

		Symbol sym = Symbol.create("arg0");

		Invoke<AString> op = Invoke.create(Lambda.create(Vectors.of(sym), Local.create(0)),
				Constant.createString("bar"));

		Context c2 = c.execute(op);
		assertEquals("bar", c2.getResult().toString());

		doOpTest(op);
	}

	@Test
	public void testLookup() throws InvalidDataException {
		Lookup<?> l1=Lookup.create("foo");
		assertNull(l1.getAddress());
		doOpTest(l1);

		Lookup<?> l2=Lookup.create(Constant.of(Init.CORE_ADDRESS),"count");
		assertEquals(Constant.of(Init.CORE_ADDRESS),l2.getAddress());
		doOpTest(l2);
	}

	@Test
	public void testLocal() throws InvalidDataException {
		Context c=context();
		c=c.withLocalBindings(Vectors.of(1337L));

		Local<?> op=Local.create(0);
		c=c.execute(op);
		assertEquals(RT.cvm(1337),c.getResult());
		
		// Negative local index should be invalid
		assertNull(Local.create(-1));
		
		assertEquals(Local.create(16567),ExtensionValue.create(CVMTag.OP_LOCAL,16567));

		doOpTest(op);
	}

	@Test
	public void testLambda() {
		Context c = context();

		Symbol sym = Symbol.create("arg0");

		Lambda<ACell> lam = Lambda.create(Vectors.of(Syntax.create(sym)), Lookup.create(sym));

		Context c2 = c.execute(lam);
		AClosure<ACell> fn = c2.getResult();
		assertTrue(fn.hasArity(1));
		assertFalse(fn.hasArity(2));

		doOpTest(lam);
	}
	
	@Test
	public void testEnptyInvokeOp() {
		Context c = context();
		c=step(c,"(eval #[db00])"); // Dense record that looks like an Invoke op with no values
		assertEquals(List.EMPTY,c.getResult());
	}

	@Test
	public void testLambdaString() {
		Fn<ACell> fn = Fn.create(Vectors.empty(), Constant.nil());
		assertEquals("(fn [] nil)",fn.toString());
	}
	
	@Test 
	public void testLocalRegression() throws BadFormatException {
		Blob enc=Blob.fromHex("cc0bf554"); // Local with negative index
		assertThrows(BadFormatException.class,()->Format.read(enc));
	}

	public static <T extends ACell> void doOpTest(AOp<T> op) {
		// Executing any Op should not throw
		TestState.CONTEXT.fork().execute(op);

		try {
			op.validate();
		} catch (InvalidDataException e) {
			throw Utils.sneakyThrow(e);
		}

		ObjectsTest.doAnyValueTests(op);
	}

}
