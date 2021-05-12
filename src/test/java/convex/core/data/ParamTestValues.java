package convex.core.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;
import convex.test.Samples;

@RunWith(Parameterized.class)
public class ParamTestValues {
	private ACell data;

	public ParamTestValues(String label, ACell v) {
		this.data = v;
	}

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { 
			{ "Keyword :foo", Samples.FOO }, 
			{ "Empty Vector", Vectors.empty() },
			{ "Long", CVMLong.ONE },
			{ "Single value map", Maps.of(7, 8) },
			{ "Account status", AccountStatus.create(1000L,Samples.ACCOUNT_KEY) },
			{ "Peer status", PeerStatus.create(1000L, Strings.create("http://www.google.com:18888")) },
			{ "Signed value", SignedData.create(Samples.KEY_PAIR, Strings.create("foo")) },
			{ "Length 300 vector", Samples.INT_VECTOR_300 } });
	}

	@Test
	public void testCanonical() {
		assertTrue(data.isCanonical());
	}
	
	@Test
	public void testType() {
		AType t=data.getType();
		assertNotNull(t);
		assertTrue(t.check(data));
		assertTrue(Types.ANY.check(data));
	}

	@Test
	public void testHexRoundTrip() throws InvalidDataException, ValidationException {
		ACell.createPersisted(data);
		String hex = data.getEncoding().toHexString();
		Blob d2 = Blob.fromHex(hex);
		ACell rec = Format.read(d2);
		rec.validate();
		assertEquals(data, rec);
		assertEquals(data.getEncoding(), rec.getEncoding());
	}
}
