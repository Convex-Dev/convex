package convex.dlfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

public class ByteRangeTest {

	private static final long SIZE=1000;

	@Test
	public void testSatisfiableRanges() {
		assertEquals(new ByteRange(0,99),ByteRange.parse("bytes=0-99",SIZE));
		assertEquals(new ByteRange(500,999),ByteRange.parse("bytes=500-",SIZE));
		assertEquals(new ByteRange(900,999),ByteRange.parse("bytes=-100",SIZE));
		assertEquals(new ByteRange(999,999),ByteRange.parse("bytes=999-999",SIZE));

		// Ends beyond the representation are clamped, as are long suffixes
		assertEquals(new ByteRange(10,999),ByteRange.parse("bytes=10-5000",SIZE));
		assertEquals(new ByteRange(10,999),ByteRange.parse("bytes=10-99999999999999999999999",SIZE));
		assertEquals(new ByteRange(0,999),ByteRange.parse("bytes=-5000",SIZE));

		// Unit is case-insensitive, with optional whitespace around the range set
		assertEquals(new ByteRange(1,2),ByteRange.parse("Bytes = 1-2 ",SIZE));

		assertEquals("bytes 100-199/1000",ByteRange.parse("bytes=100-199",SIZE).contentRange(SIZE));
	}

	@Test
	public void testUnsatisfiableRanges() {
		assertSame(ByteRange.UNSATISFIABLE,ByteRange.parse("bytes=1000-",SIZE));
		assertSame(ByteRange.UNSATISFIABLE,ByteRange.parse("bytes=1000-2000",SIZE));
		assertSame(ByteRange.UNSATISFIABLE,ByteRange.parse("bytes=-0",SIZE));
		assertSame(ByteRange.UNSATISFIABLE,ByteRange.parse("bytes=99999999999999999999999-",SIZE));
	}

	@Test
	public void testIgnoredHeaders() {
		assertNull(ByteRange.parse(null,SIZE));
		assertNull(ByteRange.parse("bytes=0-10",0));
		assertNull(ByteRange.parse("items=0-10",SIZE));
		assertNull(ByteRange.parse("bytes=0-10,20-30",SIZE));
		assertNull(ByteRange.parse("bytes=10-5",SIZE));
		assertNull(ByteRange.parse("bytes=5",SIZE));
		assertNull(ByteRange.parse("bytes=-",SIZE));
		assertNull(ByteRange.parse("bytes=--5",SIZE));
		assertNull(ByteRange.parse("bytes=+5-10",SIZE));
		assertNull(ByteRange.parse("bytes=a-b",SIZE));
		assertNull(ByteRange.parse("bytes 0-10",SIZE));
	}
}
