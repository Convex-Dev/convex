package convex.util;

import static convex.core.lang.TestState.STATE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Comparator;
import java.util.function.Function;

import org.junit.Test;

import convex.core.Block;
import convex.core.Peer;
import convex.core.State;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.TestState;
import convex.core.init.InitConfigTest;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Bits;
import convex.core.util.Utils;

public class UtilsTest {

	@Test
	public void testToBigInteger() {
		assertEquals(BigInteger.valueOf(255), Utils.toBigInteger(new byte[] { -1 }));
		assertEquals(BigInteger.valueOf(256), Utils.toBigInteger(new byte[] { 1, 0 }));
		assertEquals(BigInteger.valueOf(65536), Utils.toBigInteger(new byte[] { 1, 0, 0 }));
	}

	@Test
	public void testHexChar() {
		assertEquals('f', Utils.toHexChar(15));
		assertEquals('a', Utils.toHexChar(10));
		assertEquals('9', Utils.toHexChar(9));
		assertEquals('0', Utils.toHexChar(0));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testBadHexCharNegative() {
		Utils.toHexChar(-1);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testBadHexChar1() {
		Utils.toHexChar(16);
	}

	@Test
	public void testHexString() {
		assertEquals("ff", Utils.toHexString(new byte[] { -1 }));
		assertEquals("81", Utils.toHexString(new byte[] { -127 }));
		assertEquals("7f", Utils.toHexString(new byte[] { 127 }));
		assertEquals("7c", Utils.toHexString(new byte[] { 124 }));
		assertEquals("0012457c", Utils.toHexString(0x0012457c));
	}

	@Test
	public void testHexToBytes() {
		byte[] header = Utils.hexToBytes(
				"0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c");
		assertEquals(80, header.length);
		assertEquals(1, header[0]);
		assertEquals(124, header[79]);
		assertEquals("7c", Utils.toHexString(header[79]));
		assertEquals(0xdeadbeef, Utils.readInt(Utils.hexToBytes("deadbeef", 8), 0));

		assertNull(Utils.hexToBytes("deadbeef", 10)); // wrong length
		assertNull(Utils.hexToBytes("deadb", 5)); // odd length
		assertNull(Utils.hexToBytes("zzzz", 4)); // invalid characters
	}

	@Test
	public void testHexVals() {
		assertEquals(0, Utils.hexVal('0'));
		assertEquals(15, Utils.hexVal('f'));
		assertEquals(12, Utils.hexVal('C'));
	}

	@Test
	public void testExtractDigit() {
		assertEquals(0, Utils.extractDigit(Blob.fromHex("0123"), 0));
		assertEquals(3, Utils.extractDigit(Blob.fromHex("0123"), 3));

		assertThrows(IndexOutOfBoundsException.class, () -> Utils.extractDigit(Blob.fromHex("0123"), 4));
		assertThrows(IndexOutOfBoundsException.class, () -> Utils.extractDigit(Blob.fromHex("0123"), -1));
	}

	@Test
	public void testBigIntegerToHex() {
		assertEquals("0101", Utils.toHexString(BigInteger.valueOf(257), 4));
		assertEquals("0", Utils.toHexString(BigInteger.valueOf(0), 1));

		assertThrows(IllegalArgumentException.class, () -> Utils.toHexString(BigInteger.valueOf(-100), 4));
		assertThrows(IllegalArgumentException.class, () -> Utils.toHexString(BigInteger.valueOf(257), 2));
	}

	@Test
	public void testHexVal() {
		for (int i = -128; i <= 127; i++) {
			char c = (char) i;
			try {
				char rt = Utils.toHexChar(Utils.hexVal(c));
				assertEquals(Character.toLowerCase(c), rt);
			} catch (IllegalArgumentException t) {
				assert (!"0123456789abcdefABCDEF".contains(Character.toString(c)));
			}
		}
	}

	@Test
	public void testBitLength() {
		assertEquals(1, Utils.bitLength(0)); // binary 0
		assertEquals(1, Utils.bitLength(-1)); // binary 1
		assertEquals(2, Utils.bitLength(1)); // binary 01
		assertEquals(2, Utils.bitLength(-2)); // binary 10
		assertEquals(3, Utils.bitLength(2)); // binary 010
		assertEquals(3, Utils.bitLength(-3)); // binary 101
		assertEquals(64, Utils.bitLength(Long.MAX_VALUE)); // max value
		assertEquals(64, Utils.bitLength(Long.MAX_VALUE + 1)); // overflow
	}

	@Test
	public void testIntLeadingZeros() {
		assertEquals(32, Bits.leadingZeros(0x00000000));
		assertEquals(16, Bits.leadingZeros(0x00008000));
		assertEquals(0, Bits.leadingZeros(-1));
	}

	@Test
	public void testLongLeadingZeros() {
		assertEquals(64, Bits.leadingZeros(0L));
		assertEquals(48, Bits.leadingZeros(0x00008000L));
		assertEquals(0, Bits.leadingZeros(-1L));
	}

	@Test
	public void testWriteUInt256() {
		BigInteger n = BigInteger.valueOf(7);
		ByteBuffer b = ByteBuffer.allocate(32);
		Utils.writeUInt256(b, n);
		b.flip();
		assertEquals(32, b.remaining());
		byte[] bs = Utils.toByteArray(b);
		assertEquals(32, bs.length);
		assertEquals(0, b.remaining());
		assertEquals("0000000000000000000000000000000000000000000000000000000000000007", Utils.toHexString(bs));
	}

	@Test
	public void testWriteBigUInt() {
		byte[] ds = new byte[4];
		assertEquals("00000000", Utils.toHexString(ds));
		Utils.writeUInt(BigInteger.valueOf(7), ds, 0, 4);
		assertEquals("00000007", Utils.toHexString(ds));
		assertEquals((short) 7, Utils.readShort(ds, 2)); // check short encoding
		Utils.writeUInt(BigInteger.valueOf(0xffffffffl), ds, 0, 4);
		assertEquals("ffffffff", Utils.toHexString(ds));

		assertThrows(IllegalArgumentException.class,
				() -> Utils.writeUInt(BigInteger.valueOf(0x100000000L), ds, 0, 32));
		assertThrows(IllegalArgumentException.class, () -> Utils.writeUInt(BigInteger.valueOf(-1), ds, 0, 32));
	}

	@Test
	public void testToByteArray() {
		ByteBuffer buf = ByteBuffer.allocate(1000);
		byte[] bs1 = Utils.hexToBytes("cafebabe");
		buf.put(bs1);

		buf.flip();
		byte[] bs2 = Utils.toByteArray(buf);
		assertArrayEquals(bs1, bs2);
		assertEquals(0, buf.remaining());
	}

	@Test
	public void testExtractBitsPositive() {
		byte[] bs = Utils.hexToBytes("0FF107");
		assertEquals(0, Utils.extractBits(bs, 5, 23)); // 4 bits beyond end, should be zero-extended
		assertEquals(0, Utils.extractBits(bs, 0, 23)); // zero length of bits
		assertEquals(0xFF, Utils.extractBits(bs, 8, 12)); // the ff part
		assertEquals(1, Utils.extractBits(bs, 1, 0)); // lowest bit
		assertEquals(2, Utils.extractBits(bs, 2, 7)); // pick out the 1 in second bit
	}

	@Test
	public void testExtractBitsNegative() {
		byte[] bs = Utils.hexToBytes("80F107");
		assertEquals(0x1F, Utils.extractBits(bs, 5, 23)); // 4 bits beyond end, should be sign-extended
		assertEquals(0x0F, Utils.extractBits(bs, 8, 12)); // the 0f part
		assertEquals(0, Utils.extractBits(bs, 0, 8)); // zero length of bits
		assertEquals(1, Utils.extractBits(bs, 1, 0)); // lowest bit
		assertEquals(2, Utils.extractBits(bs, 2, 7)); // pick out the 1 in second bit
		assertEquals(7, Utils.extractBits(bs, 3, 70)); // pick 3 bits beyond array
		assertEquals(-1, Utils.extractBits(bs, 32, 70)); // pick 32 bits beyond array
	}

	@Test
	public void testSetBitsPositive() {
		byte[] bs = Utils.hexToBytes("0ff107");
		Utils.setBits(bs, 4, 0, 9); // set lowest hex digit to 9
		assertEquals("0ff109", Utils.toHexString(bs));
		Utils.setBits(bs, 6, 13, 0); // set 6 bits in middle of ff to zero
		assertEquals("081109", Utils.toHexString(bs));
		Utils.setBits(bs, 8, 23, 255); // set 8 bits to one starting from highest bit
		assertEquals("881109", Utils.toHexString(bs));
		Utils.setBits(bs, 8, 23, 0); // set 8 bits to zero starting from highest bit
		assertEquals("081109", Utils.toHexString(bs));
		Utils.setBits(bs, 8, 24, 0xFF); // set 8 bits to one beyond end of array
		assertEquals("081109", Utils.toHexString(bs));
		Utils.setBits(bs, 24, 0, 0xFFFFFF); // set all 24 bits to 1
		assertEquals("ffffff", Utils.toHexString(bs));
	}

	@Test
	public void testReadWriteInt() {
		byte[] bs = new byte[8];
		assertEquals(0, Utils.readInt(bs, 0));
		int a = 0xcafebabe;
		Utils.writeInt(bs, 0, a);
		assertEquals(a, Utils.readInt(bs, 0));
		Utils.writeInt(bs, 4, a);
		assertEquals(0xbabecafe, Utils.readInt(bs, 2));
		assertEquals(0xcafebabe, Utils.readInt(bs, 4));
	}

	@Test
	public void testReadWriteLong() {
		byte[] bs = new byte[20];
		assertEquals(0, Utils.readLong(bs, 0));
		long a = 0xffffffffcafebabeL;
		Utils.writeLong(bs, 0, a);
		assertEquals(a, Utils.readLong(bs, 0));
		Utils.writeLong(bs, 4, a);
		assertEquals(0xffffffffffffffffL, Utils.readLong(bs, 0));
		assertEquals(0xcafebabe00000000L, Utils.readLong(bs, 8));
	}

	@Test
	public void testCheckedCasts() {
		assertEquals(-1, Utils.checkedInt(-1));
	}

	@Test
	public void testToInt() {
		assertEquals(1, Utils.toInt(1));
		assertEquals(7, Utils.toInt(7.0f));
		assertEquals(8, Utils.toInt("8"));
		assertEquals(-1, Utils.toInt("-1"));
	}

	@Test
	public void testInetSocketAddress() {
		String s = "http://www.something-unusual.com:18888";
		InetSocketAddress sa = Utils.toInetSocketAddress(s);
		assertNotNull(sa);
		
		assertNotNull(Utils.toInetSocketAddress("localhost:8080"));
		assertNotNull(Utils.toInetSocketAddress("12.13.14.15:8080"));
		assertNotNull(Utils.toInetSocketAddress("http:12.13.14.15:8080"));
		
		assertNull(Utils.toInetSocketAddress("@@@"));

	}

	@Test
	public void testEdnString() {
		assertEquals("#int 1", Utils.ednString(1));
		assertEquals("100", Utils.ednString(100L));
		assertEquals("true", Utils.ednString(true));
		assertEquals("\"foo\\bar\"", Utils.ednString("foo\\bar"));
		assertEquals("{1 2}", Utils.ednString(Maps.of(1L, 2L)));
		assertEquals("#inst \"1970-01-01T00:00:00Z\"", Utils.ednString(Instant.ofEpochMilli(0)));

		assertThrows(Error.class, () -> Utils.ednString(ByteBuffer.allocate(3)));

	}

	@Test
	public void testBinarySearchLeftmost() {
		AVector<CVMLong> L = Vectors.of(
				CVMLong.create(1),
				CVMLong.create(2),
				CVMLong.create(2),
				CVMLong.create(3)
		);

		// No match.
		assertNull(Utils.binarySearchLeftmost(L, Function.identity(), Comparator.comparingLong(CVMLong::longValue), CVMLong.create(0)));

		// Exact match.
		assertEquals(
				CVMLong.create(2),
				Utils.binarySearchLeftmost(L, Function.identity(), Comparator.comparingLong(CVMLong::longValue), CVMLong.create(2))
		);

		assertEquals(
				CVMLong.create(3),
				Utils.binarySearchLeftmost(L, Function.identity(), Comparator.comparingLong(CVMLong::longValue), CVMLong.create(3))
		);

		// Approximate match: 3 is the leftmost element.
		assertEquals(
				CVMLong.create(3),
				Utils.binarySearchLeftmost(L, Function.identity(), Comparator.comparingLong(CVMLong::longValue), CVMLong.create(1000))
		);
	}

	@Test
	public void testBinarySearchLeftmost2() {
		AVector<AVector<CVMLong>> L = Vectors.of(
				Vectors.of(1, 1),
				Vectors.of(1, 2),
				Vectors.of(2, 1),
				Vectors.of(2, 2),
				Vectors.of(2, 3),
				Vectors.of(3, 1)
		);

		assertEquals(
				Vectors.of(2, 1),
				Utils.binarySearchLeftmost(L, a -> a.get(0), Comparator.comparingLong(CVMLong::longValue), CVMLong.create(2))
		);

		assertEquals(
				Vectors.of(1, 1),
				Utils.binarySearchLeftmost(L, a -> a.get(0), Comparator.comparingLong(CVMLong::longValue), CVMLong.create(1))
		);

	}

	@Test
	public void testBinarySearchLeftmost3() {
		assertNull(Utils.binarySearchLeftmost(Vectors.empty(), Function.identity(), Comparator.comparingLong(CVMLong::longValue), CVMLong.create(2)));
	}

	@Test
	public void testStatesAsOfRange() throws BadSignatureException {
		Peer peer = Peer.create(InitConfigTest.FIRST_PEER_KEYPAIR, TestState.STATE);

		AVector<State> states = Vectors.of(STATE);

		for (int i = 0; i < 10; i++) {
			State state0 = states.get(states.count() - 1);

			long timestamp = state0.getTimeStamp().longValue() + 100;

			String command = "(def x " + timestamp + ")";
			SignedData<ATransaction> data = peer.sign(Invoke.create(InitConfigTest.HERO_ADDRESS, timestamp, command));

			Block block = Block.of(timestamp, InitConfigTest.FIRST_PEER_KEY, data);

			State state1 = state0.applyBlock(block).getState();

			states = states.conj(state1);
		}

		AVector<State> statesInRange = Utils.statesAsOfRange(states, STATE.getTimeStamp(), 1000, 2);

		assertEquals(2, statesInRange.count());

		// First State in range must be the INITIAL value.
		assertEquals(STATE, statesInRange.get(0));

		// Since each iteration creates a snapshot of State advances by 100 milliseconds,
		// the last State's timestamp in the range is the same as the initial timestamp + 1000 milliseconds.
		assertEquals(
				CVMLong.create(STATE.getTimeStamp().longValue() + 1000),
				statesInRange.get(statesInRange.count() - 1).getTimeStamp()
		);
	}

}
