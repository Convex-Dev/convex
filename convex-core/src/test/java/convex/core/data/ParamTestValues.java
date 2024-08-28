package convex.core.data;

import static convex.test.Assertions.assertCastError;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;
import convex.core.lang.ACVMTest;
import convex.core.lang.AOp;
import convex.core.lang.Core;
import convex.core.lang.NumericsTest;
import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Invoke;
import convex.test.Samples;

/**
 * Parameterised tests for a representative range of valid CVM values
 * 
 * Mainly focused on checking generic properties and consistency with core predicate expecations
 */
@RunWith(Parameterized.class)
public class ParamTestValues extends ACVMTest {
	private final ACell data;
	private final AOp<?> constOp;

	public ParamTestValues(String label, ACell v) {
		this.data = v;
		constOp=Constant.of(v);
	}

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] {
			{ "nil", Samples.NIL },
			
			{ "Keyword :foo", Keywords.FOO },
			{ "Symbol 'foo", Symbols.FOO },
			
			{ "Short String", Strings.create("bonnie") },
			{ "Empty String", Strings.EMPTY },
			
			{ "Null Character", CVMChar.ZERO },
			{ "Character A", CVMChar.parse("A") },
			{ "Character Max Codepoint", CVMChar.MAX_VALUE },
	
			{ "Empty Vector", Vectors.empty() },
			{ "Short Vector 16", Samples.INT_VECTOR_16 },
			{ "Big Vector 300", Samples.INT_VECTOR_300 },
			
			{ "Empty List", Lists.empty() },
			{ "Short List 16", Samples.INT_LIST_10 },
			{ "Big List 300", Samples.INT_LIST_300 },
			
			{ "Address 0", Address.ZERO },
			{ "Address MAX", Address.MAX_VALUE },
			
			{ "Long 1", CVMLong.ONE },
			{ "Long -666", CVMLong.create(-666) },
			{ "Big Integer", Samples.MIN_BIGINT },
			{ "Double 0.0", CVMDouble.ZERO },
			{ "Double 1.0", CVMDouble.ONE },
			{ "Double -Infinity", CVMDouble.NEGATIVE_INFINITY },
			{ "Double NaN", CVMDouble.NaN },
			
			{ "Empty Blob", Blobs.empty() },
			{ "Small Blob", Blob.fromHex("0xf0013456abcd") },
			{ "Full Blob", Samples.FULL_BLOB },
			{ "Blob Tree", Samples.FULL_BLOB_PLUS },

			{ "Empty map", Maps.empty() },
			{ "Single value map", Maps.of(7, 8) },
			{ "Big map", Samples.LONG_MAP_100 },
			
			{ "Empty Index", Index.none() },
			{ "Keyword Index", Index.of(Keywords.FOO,1,Keywords.BAR,2) },
			
			{ "Account status", AccountStatus.create(1000L,Samples.ACCOUNT_KEY) },
			{ "Peer status", PeerStatus.create(Address.create(11), 1000L, Maps.create(Keywords.URL,Strings.create("http://www.google.com:18888"))) },
			{ "Signed value", SignedData.sign(Samples.KEY_PAIR, Strings.create("foo")) },
			{ "Length 300 vector", Samples.INT_VECTOR_300 } });
	}

	@Test
	public void testGeneric() {
		if (data!=null) {
			assertTrue(data.isCanonical());
		}
		ObjectsTest.doAnyValueTests(data);
	}

	@Test
	public void testType() {
		AType t=Types.get(data);
		assertNotNull(t);
		assertTrue(t.check(data));
		assertTrue(Types.ANY.check(data));
	}

	@Test
	public void testHexRoundTrip() throws InvalidDataException, ValidationException, IOException {
		Cells.persist(data);
		String hex = Format.encodedBlob(data).toHexString();
		Blob d2 = Blob.fromHex(hex);
		ACell rec = Format.read(d2);
		
		assertEquals(data, rec);
		
		if (data!=null) {
			rec.validate();
			assertEquals(data.getEncoding(), rec.getEncoding());
		}
	}
	
	@Test
	public void testCountable() {
		boolean countable=RT.bool(eval(Invoke.build(Core.COUNTABLE_Q,constOp)));
		if (countable) {
			// Count should work and return a natural number
			CVMLong l=RT.ensureLong(eval(Invoke.build(Core.COUNT,constOp)));
			assertNotNull(l);
			long n=l.longValue();
			assert(n>=0);
			
			assertEquals(n,(long)RT.count(data));
			
			ACountable<?> c=RT.ensureCountable(data);
			if (c!=null) {
				assertEquals(n,c.count());
				assertEquals(n,c.size());
			} else {
				assert(n==0);
			}
			
			// should be empty? iff count is zero
			assertEquals(n==0,evalB(Invoke.build(Core.EMPTY_Q,c)));
			
			// empty should return the empty instance (or nil)
			ACell empty=eval(Invoke.build(Core.EMPTY,c));
			assertEquals(0,(long)RT.count(empty));
			
		} else {
			// if not countable....
			assertCastError(step(context(),Invoke.build(Core.COUNT,constOp)));
			assertCastError(step(context(),Invoke.build(Core.NTH,constOp,0)));
			assertNull(RT.count(data));
		}
	}
	
	@Test
	public void testNumber() {
		boolean number=RT.bool(eval(Invoke.build(Core.NUMBER_Q,constOp)));
		if (number) {
			ANumeric a=RT.ensureNumber(data);
			assertNotNull(a);
			CVMDouble dv=eval(Invoke.build(Core.DOUBLE,constOp));
			assertNotNull(dv);
			assertEquals(dv.doubleValue(),a.doubleValue(),0.0);
			
			NumericsTest.doNumberTests(a);
		
		} else {
			assertCastError(step(context(),Invoke.build(Core.INC,constOp)));
			assertCastError(step(context(),Invoke.build(Core.TIMES,1.0,constOp)));
			assertNull(RT.ensureNumber(data));
		}
	}
}
