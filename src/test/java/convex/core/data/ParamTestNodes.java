package convex.core.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;
import convex.test.Samples;

@RunWith(Parameterized.class)
public class ParamTestNodes {
	private ACell data;

	public ParamTestNodes(String label, ACell v) {
		this.data = v;
	}

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { { "Keyword :foo", Samples.FOO }, { "Empty Vector", Vectors.empty() },
				{ "Zero Amount", Amount.create(0) }, { "Single value map", Maps.of(7, 8) },
				{ "Account status", AccountStatus.create(Amount.create(1000)) },
				{ "Peer status", PeerStatus.create(Amount.create(1000), Strings.create("http://www.google.com:18888")) },
				{ "Signed value", SignedData.create(Samples.KEY_PAIR, "foo") },
				{ "Length 300 vector", Samples.INT_VECTOR_300 } });
	}

	@Test
	public void testCanonical() {
		assertTrue(data.isCanonical());
	}

	@Test
	public void testHexRoundTrip() throws InvalidDataException, ValidationException {
		Ref.createPersisted(data);
		String hex = data.getEncoding().toHexString();
		Blob d2 = Blob.fromHex(hex);
		ACell rec = Format.read(d2);
		rec.validate();
		assertEquals(data, rec);
		assertEquals(data.getEncoding(), rec.getEncoding());
	}
}
