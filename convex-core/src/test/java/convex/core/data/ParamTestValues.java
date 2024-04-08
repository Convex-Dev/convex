package convex.core.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;
import convex.core.lang.ACVMTest;
import convex.core.lang.AOp;
import convex.core.lang.Core;
import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Invoke;
import convex.test.Samples;

import static convex.test.Assertions.*;

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
			{ "Keyword :foo", Samples.FOO },
			{ "Symbol 'foo", Symbols.FOO },
			{ "Short String", Strings.create("bonnie") },
			{ "Empty String", Strings.EMPTY },
			{ "Empty Vector", Vectors.empty() },
			{ "Short Vector 16", Samples.INT_VECTOR_16 },
			{ "Big Vector 300", Samples.INT_VECTOR_300 },
			{ "Long", CVMLong.ONE },
			{ "Double", CVMDouble.ONE },
			{ "NAN", CVMDouble.NaN },
			{ "Single value map", Maps.of(7, 8) },
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
	public void testHexRoundTrip() throws InvalidDataException, ValidationException {
		ACell.createPersisted(data);
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
			CVMLong l=RT.ensureLong(eval(Invoke.build(Core.COUNT,constOp)));
			assertNotNull(l);
			long n=l.longValue();
			assert(n>=0);
			
			assertEquals(n,(long)RT.count(data));
		} else {
			assertCastError(step(context(),Invoke.build(Core.COUNT,constOp)));
		}
	}
}
