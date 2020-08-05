package convex.core.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import convex.core.data.AArrayBlob;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.IObject;
import convex.core.data.IRefContainer;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.exceptions.TODOException;

public class Utils {
	public static final byte[] EMPTY_BYTES = new byte[0];

	/**
	 * Converts an array of bytes into an unsigned BigInteger
	 * 
	 * Assumes big-endian format as per new BigInteger(int, byte[]);
	 * 
	 * @param data Array of bytes containing an unsigned integer (big-endian)
	 * @return A new non-negative BigInteger
	 */
	public static BigInteger toBigInteger(byte[] data) {
		return new BigInteger(1, data);
	}

	/**
	 * Converts an array of bytes into a signed BigInteger
	 * 
	 * Assumes two's-complement big-endian binary representation format as per new
	 * BigInteger(byte[]);
	 * 
	 * @param data
	 * @return A signed BigInteger
	 */
	public static BigInteger toSignedBigInteger(byte[] data) {
		return new BigInteger(data);
	}

	/**
	 * Converts an int to a hex string e.g. "80cafe80"
	 * 
	 * @param val
	 * @return Lowercase hex string
	 */
	public static String toHexString(int val) {
		StringBuffer sb = new StringBuffer(8);
		for (int i = 0; i < 8; i++) {
			sb.append(Utils.toHexChar((val >> ((7 - i) * 4)) & 0xf));
		}
		return sb.toString();
	}

	public static String toHexString(short val) {
		StringBuffer sb = new StringBuffer(4);
		for (int i = 0; i < 4; i++) {
			sb.append(Utils.toHexChar((val >> ((3 - i) * 4)) & 0xf));
		}
		return sb.toString();
	}

	/**
	 * Converts a byte to a two-character hex string
	 * 
	 * @param value
	 * @return Lowercase hex string
	 */
	public static String toHexString(byte value) {
		StringBuffer sb = new StringBuffer(2);
		sb.append(toHexChar((((int) value) & 0xF0) >>> 4));
		sb.append(toHexChar(((int) value) & 0xF));
		return sb.toString();
	}

	/**
	 * Converts a long value to a 16 character hex string
	 * 
	 * @param x
	 * @return Hex string for the given long
	 */
	public static String toHexString(long x) {
		StringBuffer sb = new StringBuffer(16);
		for (int i = 15; i >= 0; i--) {
			sb.append(toHexChar(((int) (x >> (4 * i))) & 0xF));
		}
		return sb.toString();
	}

	/**
	 * Reads an int from a specified location in a byte array Assumes 4-byte
	 * big-endian representation
	 * 
	 * @param data Byte array from which to read the 4-byte int representation
	 * @return int value from array
	 */
	public static int readInt(byte[] data, int offset) {
		int result = data[offset];
		for (int i = 1; i <= 3; i++) {
			result = (result << 8) + (data[offset + i] & 0xFF);
		}
		return result;
	}

	public static long readLong(byte[] data, int offset) {
		long result = data[offset];
		for (int i = 1; i <= 7; i++) {
			result = (result << 8) + (data[offset + i] & 0xFF);
		}
		return result;
	}

	/**
	 * Reads a short from a specified location in a byte array Assumes 2-byte
	 * big-endian representation
	 * 
	 * @param data Byte array from which to read the 2-byte short representation
	 * @return short value from array
	 */
	public static short readShort(byte[] data, int offset) {
		int result = ((data[offset] & 0xFF) << 8) + (data[offset + 1] & 0xFF);
		return (short) result;
	}

	/**
	 * Writes an int to a byte array in 4 byte big-endian representation
	 * 
	 * @param value  int value to write to the array
	 * @param data   Byte array into which to write the given int
	 * @param offset Offset into the array at which the int will be written
	 */
	public static void writeInt(int value, byte[] data, int offset) {
		for (int i = 0; i <= 3; i++) {
			data[offset + i] = (byte) ((value >> (8 * (3 - i))) & 0xFF);
		}
	}

	/**
	 * Writes a long to a byte array in 8 byte big-endian representation.
	 * 
	 * @param value  long value to write to the array
	 * @param data   Byte array into which to write the given long
	 * @param offset Offset into the array at which the long will be written
	 * 
	 * @throws IndexOutOfBoundsException If long reaches outside the destination
	 *                                   byte array
	 * 
	 */
	public static void writeLong(long value, byte[] data, int offset) {
		for (int i = 0; i <= 7; i++) {
			data[offset + i] = (byte) (value >> (8 * (7 - i)));
		}
	}

	/**
	 * Reads ByteBuffer contents into a new byte array
	 * 
	 * @param b
	 */
	public static byte[] toByteArray(ByteBuffer b) {
		int len = b.remaining();
		byte[] bytes = new byte[len];
		b.get(bytes);
		return bytes;
	}

	/**
	 * Reads ByteBuffer contents into a new Data object
	 * 
	 * @param b
	 */
	public static AArrayBlob toData(ByteBuffer b) {
		return Blob.wrap(toByteArray(b));
	}

	/**
	 * Converts an int value in the range 0..15 to a hexadecimal character
	 * 
	 * @param i
	 * @return Hex digit value (lowercase)
	 */
	public static char toHexChar(int i) {
		if (i >= 0) {
			if (i <= 9) return (char) (i + 48);
			if (i <= 15) return (char) (i + 87);
		}
		throw new IllegalArgumentException("Unable to convert to single hex char: " + i);
	}

	/**
	 * Converts a hex string to a byte array. Must contain an even number of hex
	 * digits.
	 * 
	 * @param hex String containing Hex digits
	 * @return byte array with the given hex value
	 */
	public static byte[] hexToBytes(String hex) {
		return hexToBytes(hex, hex.length());
	}

	/**
	 * Converts a hex string to a byte array. Must contain an the expected number of
	 * hex digits, or else an exception will be thrown
	 * 
	 * @param hex          String containing Hex digits
	 * @param stringLength number of hex digits in the string to use
	 * @return byte array with the given hex value
	 */
	public static byte[] hexToBytes(String hex, int stringLength) {
		if (hex.length() != stringLength) {
			return null;
		}
		int N = stringLength / 2;
		if (N * 2 != stringLength) {
			return null;
		}
		byte[] result = new byte[N];

		for (int i = 0; i < N; i++) {
			char high = hex.charAt(2 * i);
			char low = hex.charAt(2 * i + 1);
			int lowD = Utils.hexVal(low);
			if (lowD < 0) return null;
			int highD = Utils.hexVal(high);
			if (highD < 0) return null;
			result[i] = (byte) (highD * 16 + lowD);
		}

		return result;
	}

	/**
	 * Converts a hex string to an unsigned big Integer
	 * 
	 * @param hex
	 * @return BigInteger
	 */
	public static BigInteger hexToBigInt(String hex) {
		return new BigInteger(1, hexToBytes(hex));
	}

	/**
	 * Gets the value of a single hex car e.g. hexVal('c') => 12
	 * 
	 * @param c Character representing a hex digit
	 * @return int in the range 0..15 inclusive, or -1 if not a hex char
	 */
	public static int hexVal(char c) {
		int v = (int) c;
		if (v <= 102) {
			if (v >= 97) return v - 87; // lowercase
			if ((v >= 65) && (v <= 70)) return v - 55; // uppercase
			if ((v >= 48) && (v <= 57)) return v - 48; // digit
		}
		return -1;
	}

	/**
	 * Converts a byte array of length N to a hex string of length 2N
	 * 
	 * @param data Array of bytes
	 * @return Hex String
	 */
	public static String toHexString(byte[] data) {
		return toHexString(data, 0, data.length);
	}

	/**
	 * Converts a byte array of length N to a hex string of length 2N
	 * 
	 * @param data Array of bytes
	 * @return Hex String
	 */
	public static String toHexString(byte[] data, int offset, int length) {
		char[] hexDigits = new char[length * 2];
		for (int i = 0; i < length; i++) {
			int v = ((int) data[i + offset]) & 0xFF;
			hexDigits[i * 2] = toHexChar(v >>> 4);
			hexDigits[i * 2 + 1] = toHexChar(v & 0xF);
		}
		return new String(hexDigits);
	}

	/**
	 * Gets the Java hashCode of any value.
	 * 
	 * The hashCode of null is defined as zero
	 * 
	 * @return hash code
	 */
	public static int hashCode(Object a) {
		if (a == null) return 0;
		return a.hashCode();
	}

	/**
	 * Tests if two byte array regions are identical
	 * 
	 * @param a
	 * @param aOffset
	 * @param b
	 * @param bOffset
	 * @param length
	 * @return true if rray regions are equal, false otherwise
	 */
	public static boolean arrayEquals(byte[] a, int aOffset, byte[] b, int bOffset, int length) {
		if ((b == a) && (aOffset == bOffset)) return true;
		for (int i = 0; i < length; i++) {
			if (a[aOffset + i] != b[bOffset + i]) return false;
		}
		return true;
	}

	/**
	 * Compares two byte arrays on an unsigned basis. Shorter arrays will be
	 * considered "smaller".
	 * 
	 * @param a
	 * @param aOffset
	 * @param b
	 * @param bOffset
	 * @param maxLength The maximum size for comparison. If arrays are equal up to
	 *                  this length, will return 0
	 * @return Negative if a is 'smaller', 0 if a 'equals' b, positive if a is
	 *         'larger'.
	 */
	public static int compareByteArrays(byte[] a, int aOffset, byte[] b, int bOffset, int maxLength) {
		int length = Math.min(maxLength, a.length - aOffset);
		length = Math.min(maxLength, b.length - bOffset);
		for (int i = 0; i < length; i++) {
			int ai = 0xFF & a[aOffset + i];
			int bi = 0xFF & b[bOffset + i];
			if (ai < bi) return -1;
			if (ai > bi) return 1;
		}
		if (length < a.length) return 1; // longer a considered larger
		if (length < b.length) return -1; // shorter a considered smaller
		return 0;
	}

	/**
	 * Converts an unsigned BigInteger to a hex string with the given number of
	 * digits Truncates any high bytes beyond the given digits.
	 * 
	 * @param a
	 * @param digits
	 * @return String containing the hex representation
	 */
	public static String toHexString(BigInteger a, int digits) {
		if (a.signum() < 0)
			throw new IllegalArgumentException("toHexString requires a non-negative BigInteger, got :" + a);
		String s = a.toString(16); // note: only works with unsigned big integers otherwise we get "-2a" etc.
		int slen = s.length();
		if (slen > digits) throw new IllegalArgumentException("toHexString number of digits exceeded, got :" + slen);
		if (slen == digits) return s;
		StringBuffer sb = new StringBuffer(digits);
		while (slen < digits) {
			sb.append('0');
			slen++;
		}
		sb.append(s);
		return sb.toString();
	}

	/**
	 * Writes an unsigned big integer to a specific segment of a byte[] array Pads
	 * with zeros if necessary to fill the specified length
	 * 
	 * @param a
	 * @param dest
	 * @param offset
	 * @param length
	 */
	public static void writeUInt(BigInteger a, byte[] dest, int offset, int length) {
		if (a.signum() < 0) throw new IllegalArgumentException("Non-negative big integer expected!");
		if ((offset + length) > dest.length) {
			throw new IllegalArgumentException(
					"Insufficient buffer space in byte array, available = " + (dest.length - offset));
		}
		byte[] bs = a.toByteArray();
		int bl = bs.length;
		if (bl == length) {
			// expected case, correct number of bytes in unsigned representation
			System.arraycopy(bs, 0, dest, offset, length);
		} else if ((bl == (length + 1)) && (bs[0] == 0)) {
			// OK because this is just an overflow of sign bit
			// We just need to skip the zero bute that includes the sign
			System.arraycopy(bs, 1, dest, offset, length);
		} else if (bl < length) {
			// rare case, our representation is too short, so need to pad
			int pad = length - bl;
			Arrays.fill(dest, offset, offset + pad, (byte) 0);
			System.arraycopy(bs, 0, dest, offset + pad, bl);
		} else {
			throw new IllegalArgumentException("Insufficient buffer size, was " + length + " but needed " + bl);
		}
	}

	/**
	 * Converts a String to a byte array using UTF-8 encoding
	 * 
	 * @param s Any String
	 * @return Byte array
	 */
	public static byte[] toByteArray(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Converts any array to an Object[] array
	 * 
	 * @param anyArray
	 * @return Object[] array
	 */
	public static Object[] toObjectArray(Object anyArray) {
		if (anyArray instanceof Object[]) return (Object[]) anyArray;
		int n = Array.getLength(anyArray);
		Object[] result = new Object[n];
		for (int i = 0; i < n; i++) {
			result[i] = Array.get(anyArray, i);
		}
		return result;
	}

	/**
	 * Equality method allowing for nulls
	 * 
	 * @param a
	 * @param b
	 * @return true if arguments are equal, false otherwise
	 */
	public static boolean equals(Object a, Object b) {
		if (a == b) return true;
		if (a == null) return false; // b can't be null because of above line
		return a.equals(b); // fall back to Object equality
	}

	/**
	 * Gets a hex digit as an integer 0-15 value from a Data object
	 * 
	 * @param data     Blob containing byte values
	 * @param hexDigit Position of hex digit to extract (from start of blob)
	 * @return Hex digit value as an integer 0..15 inclusive
	 */
	public static int extractDigit(ABlob data, int hexDigit) {
		int b = data.get(hexDigit >> 1);
		b = ((hexDigit & 1) == 0) ? (b >>> 4) & 15 : b & 15;
		return b;
	}

	/**
	 * Gets the class of an Object, or null if the value is null
	 * 
	 * @param o
	 * @return Class of the object
	 */
	public static Class<?> getClass(Object o) {
		if (o == null) return null;
		return o.getClass();
	}

	/**
	 * Gets the class name of an Object, or "null" if the value is null
	 * 
	 * @param o
	 * @return Class name of the object
	 */
	public static String getClassName(Object o) {
		Class<?> klass = getClass(o);
		return (klass == null) ? "null" : klass.getName();
	}

	/**
	 * Converts a long to an int, throws error if out of allowable range.
	 * 
	 * @param a
	 * @return int value of the long if in valid Integer range
	 */
	public static int checkedInt(long a) {
		int i = (int) a;
		if (a != i) throw new IllegalArgumentException(Errors.sizeOutOfRange(a));
		return i;
	}

	/**
	 * Converts a long to a short, throws error if out of allowable range.
	 * 
	 * @param a
	 * @return short value of the long if in valid Short range
	 */
	public static short checkedShort(long a) {
		short s = (short) a;
		if (s != a) throw new IllegalArgumentException(Errors.sizeOutOfRange(a));
		return s;
	}

	/**
	 * Converts a long to a byte, throws error if out of allowable range.
	 * 
	 * @param a
	 * @return byte value of the long if in valid Byte range
	 */
	public static byte checkedByte(long a) {
		byte b = (byte) a;
		if (b != a) throw new IllegalArgumentException(Errors.sizeOutOfRange(a));
		return b;
	}

	/**
	 * Writes an unsigned BigInteger as 32 bytes into a ByteBuffer
	 * 
	 * @param b A ByteBuffer with at least 32 bytes capacity
	 * @param v A BigInteger in the unsigned 256 bit integer range
	 * @return The ByteBuffer with 32 bytes written
	 */
	public static ByteBuffer writeUInt256(ByteBuffer b, BigInteger v) {
		if (v.signum() < 0) throw new IllegalArgumentException("Non-negative integer expected");
		byte[] bs = v.toByteArray();
		byte[] buf = new byte[32];
		int blen = bs.length; // length to use
		if (blen <= 32) {
			System.arraycopy(bs, 0, buf, 32 - blen, blen);
		} else if ((blen == 33) && (bs[0] == 0)) {
			// OK since this is UInt256 range, take last 32 bytes
			System.arraycopy(bs, blen - 32, buf, 0, 32);
		} else {
			throw new IllegalArgumentException("BigInteger too large for UInt256, length in bytes=" + blen);
		}
		return b.put(buf);
	}

	/**
	 * Reads an unsigned BigInteger as 32 bytes from a ByteBuffer
	 * 
	 * @param b ByteBuffer from which to extract 32 bytes
	 * @return A non-negative BigInteger containing the unsigned big-endian value
	 *         from the 32 bytes read
	 */
	public static BigInteger readUInt256(ByteBuffer b) {
		byte[] buf = new byte[32];
		b.get(buf);
		return new BigInteger(1, buf);
	}

	/**
	 * Returns the minimal number of bits to represent the signed twos complement
	 * long value. Return value will be at least 1, max 64
	 * 
	 * @param x
	 * @return Number of bits required for representation, in the range 1..64
	 *         inclusive
	 */
	public static int bitLength(long x) {
		long ux = (x >= 0) ? x : -x - 1;
		return 1 + (64 - Bits.leadingZeros(ux)); // sign bit plus number of used bits in positive representation
	}

	/**
	 * Converts an object to an int value, handling Strings and arbitrary numbers.
	 *
	 * @param v An object representing a valid int value
	 * @return The converted int value of the object
	 * @throws IllegalArgumentException If the argument cannot be converted to an
	 *                                  int
	 */
	public static int toInt(Object v) {
		if (v instanceof Integer) return (Integer) v;
		if (v instanceof String) {
			return Integer.parseInt((String) v);
		}
		if (v instanceof Number) {
			Number number = (Number) v;
			int value = (int) number.longValue();
			// following is safe, because double can represent any int
			if (value != number.doubleValue()) throw new IllegalArgumentException("Cannot coerce to int without loss:");
			return value;
		}
		throw new IllegalArgumentException("Can't convert to int: " + v);
	}

	/**
	 * Gets a resource as a String.
	 * 
	 * @param path Path to resource, e.g "actors/token.con"
	 * @return String content of resource file
	 * @throws IOException
	 */
	public static String readResourceAsString(String path) throws IOException {
		ClassLoader classLoader = ClassLoader.getSystemClassLoader();
		try (InputStream inputStream = classLoader.getResourceAsStream(path)) {
			if (inputStream == null) throw new IOException("Resource not found: " + path);
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				return reader.lines().collect(Collectors.joining(System.lineSeparator()));
			}
		}
	}

	/**
	 * Extract a number of bits (up to 32) from a big-endian byte array, shifting
	 * right be the specified amount. Sign extends for bits beyond range of array.
	 */
	public static int extractBits(byte[] bs, int numBits, int shift) {
		if ((numBits < 0) || (numBits > 32)) throw new IllegalArgumentException("Invalid number of bits: " + numBits);

		if (numBits > 8) {
			return extractBits(bs, 8, shift) | (extractBits(bs, numBits - 8, shift + 8) << 8);
		}
		if (shift < 0) throw new IllegalArgumentException("Negative shift: " + shift);
		int bslen = bs.length;

		int bshift = shift >> 3; // shift in number of bytes

		if (bshift >= bslen) {
			// beyond end of array, so sign extend last byte
			return ((bs[0] >= 0) ? 0 : -1) & Bits.lowBitMask(numBits);
		}

		int lowShift = (shift - (bshift << 3));
		int ix = bslen - bshift - 1; // index of low byte

		int val = bs[ix]; // low byte from array into val, sign extend
		if (ix > 0) {
			val = val & 0xFF; // clear top 3 bytes of val
			val = val | (((int) bs[ix - 1]) << 8); // high byte, sign extended
		}
		val = val >> lowShift; // shift val to position low bits correctly
		return val & Bits.lowBitMask(numBits); // return just the requested bits
	}

	/**
	 * Sets a number of bits (up to 32) in a big-endian byte array, shifting by the
	 * specified amount Ignores bits set outside the byte array
	 */
	public static void setBits(byte[] bs, int numBits, int shift, int bits) {
		if ((numBits < 0) || (numBits > 32)) {
			throw new IllegalArgumentException("Invalid number of bits: " + numBits);
		}
		if (numBits > 8) {
			setBits(bs, 8, shift, bits);
			setBits(bs, numBits - 8, shift + 8, bits >> 8);
			return;
		}
		if (shift < 0) throw new IllegalArgumentException("Negative shift: " + shift);
		int bslen = bs.length;
		int bshift = shift >> 3; // shift in number of bytes
		if (bshift >= bslen) return; // nothing to do, beyond end of byte array
		int ix = bslen - bshift - 1; // index of low byte

		// setup val with bits to set, others zero
		int lowShift = (shift - (bshift << 3));
		int lowBitMask = Bits.lowBitMask(numBits);
		int val = (bits & lowBitMask) << lowShift;

		// setup keep with bits to keep in low 16 bits
		int keepBitMask = ~(lowBitMask << lowShift); // bits to keep from original array
		int keep = (bs[ix] & 0xFF);
		if (ix > 0) {
			keep = keep | (((bs[ix - 1]) & 0xFF) << 8);
		}
		keep = keep & keepBitMask;

		val = val | keep;
		bs[ix] = (byte) (val & 0xFF);
		if (ix > 0) {
			bs[ix - 1] = (byte) ((val >> 8) & 0xFF);
		}
	}

	/**
	 * Reads data from the buffer, up to the limit.
	 * @param buffer
	 * @return
	 */
	public static AArrayBlob readBufferData(ByteBuffer buffer) {
		buffer.position(0);
		int len = buffer.remaining();
		byte[] bytes = new byte[len];
		buffer.get(bytes);
		return Blob.wrap(bytes);
	}

	public static String ednString(Object v) {
		if (v == null) return "nil";
		if (v instanceof IObject) {
			StringBuilder sb = new StringBuilder();
			((IObject) v).ednString(sb);
			return sb.toString();
		}

		if (v instanceof Number) return ednString((Number) v);
		if (v instanceof Boolean) return v.toString();
		if (v instanceof String) return "\"" + v + "\"";
		if (v instanceof Character) return ednString((char) v);
		if (v instanceof Instant) return "#inst \"" + DateTimeFormatter.ISO_INSTANT.format((Instant) v) + "\"";
		throw new Error("Can't get edn string for: " + v.getClass());
	}

	public static void ednString(StringBuilder sb, Object v) {
		if (v instanceof IObject) {
			((IObject) v).ednString(sb);
		} else {
			sb.append(ednString(v));
		}
	}

	private static String ednString(Number v) {
		if (v instanceof Long) return v.toString();
		if (v instanceof Integer) return "#int " + v.toString();
		if (v instanceof Short) return "#int " + v.toString();
		if (v instanceof Byte) return "#byte " + v.toString();
		if (v instanceof Double) return v.toString();
		if (v instanceof Float) return "#float " + v.toString();
		if (v instanceof BigInteger) return v.toString() + "N";
		if (v instanceof BigDecimal) return v.toString() + "M";
		throw new Error("Can't get edn string number type: " + v.getClass());
	}

	public static String ednString(char c) {
		// Notes from edn-format definition:
		// Characters are preceded by a backslash: \c, \newline, \return, \space and
		// \tab yield
		// the corresponding characters.
		// Unicode characters are represented as in Java.
		// Backslash cannot be followed by whitespace.
		switch (c) {
		case '\n':
			return "\\newline";
		case '\r':
			return "\\return";
		case ' ':
			return "\\space";
		case '\t':
			return "\\tab";
		default:
			return Character.toString(c);
		}
	}

	/**
	 * Converts a String to an InetSocketAddress
	 * 
	 * @param s A string in the format "http://myhost.com:17888"
	 * @return A valid InetSocketAddress
	 */
	public static InetSocketAddress toInetSocketAddress(String s) {
		int colon = s.lastIndexOf(':');
		if (colon < 0) throw new IllegalArgumentException("No port in String: " + s);
		String hostName = s.substring(0, colon); // up to last colon
		int port = Utils.toInt(s.substring(colon + 1)); // up to last colon
		InetSocketAddress addr = new InetSocketAddress(hostName, port);
		return addr;
	}

	/**
	 * Filters the array, returning an array containing only the elements where the
	 * predicate returns true. May return the same array if all elements are
	 * included.
	 * 
	 * @param arr
	 * @param predicate
	 * @return Filtered array.
	 */
	public static <T> T[] filterArray(T[] arr, Predicate<T> predicate) {
		if (arr.length <= 32) return filterSmallArray(arr, predicate);
		throw new TODOException("Filter large arrays");
	}

	/**
	 * Return a list of values, sorted according to the score computed using the
	 * provided function, in ascending order. Ignores elements where score is null
	 * (will not be included in the resulting list)
	 * 
	 * @param scorer a Function mapping collection elements to Long values
	 * @param coll   Collection of values to compare
	 * @return The sorted collection values as an ArrayList, in ascending score
	 *         order.
	 */
	public static <T> ArrayList<T> sortListBy(Function<T, Long> scorer, Collection<T> coll) {
		// TODO can probably improve efficiency
		ArrayList<T> result = new ArrayList<>(coll.size());
		HashMap<T, Long> scores = new HashMap<>(coll.size());
		for (T c : coll) {
			Long score = scorer.apply(c);
			if (score == null) continue;
			scores.put(c, score);
			result.add(c);
		}
		result.sort(new Comparator<>() {
			@Override
			public int compare(T a, T b) {
				long comp = scores.get(a) - scores.get(b);
				return Long.signum(comp);
			}
		});
		return result;
	}

	/**
	 * Filters the array, returning an array containing only the elements where the
	 * predicate returns true. May return the same array if all elements are
	 * included.
	 * 
	 * Array must have a maximum of 32 elements
	 * 
	 * @param arr
	 * @param predicate
	 * @return
	 */
	private static <T> T[] filterSmallArray(T[] arr, Predicate<T> predicate) {
		int mask = 0;
		int n = arr.length;
		for (int i = 0; i < n; i++) {
			if (predicate.test(arr[i])) mask |= (1 << i);
		}
		return filterSmallArray(arr, mask);
	}

	@SuppressWarnings("unchecked")
	public static <T> T[] filterSmallArray(T[] arr, int mask) {
		int n = arr.length;
		if (n > 32) throw new IllegalArgumentException("Array too long to filter: " + n);
		int fullMask = (1 << n) - 1;
		if (mask == fullMask) return arr;
		int nn = Integer.bitCount(mask);
		T[] result = (T[]) Array.newInstance(arr.getClass().getComponentType(), nn);
		if (nn == 0) return result;
		int ix = 0;
		for (int i = 0; i < n; i++) {
			if ((mask & (1 << i)) != 0) {
				result[ix++] = arr[i];
			}
		}
		assert (ix == nn);
		return result;
	}

	/**
	 * Computes a bit mask of up to 16 bits by scanning a full array for which
	 * elements are included in the subset, comparing using object identity
	 * 
	 * Subset must be an ordered subset of of the full array
	 * 
	 * @param set
	 * @param subset
	 * @return Bit mask as a short
	 */
	public static <T> short computeMask(T[] set, T[] subset) {
		int n = set.length;
		if (n > 16) throw new IllegalArgumentException("Max length of 16 for mask computation, got: " + n);
		int mask = 0;
		int ix = 0;
		int subsetLength = subset.length;
		for (int i = 0; i < n; i++) {
			if (ix == subsetLength) break; // no more items to find
			if (set[i] == subset[ix]) {
				mask |= (1 << i);
				ix++;
			}
		}
		if (ix != subsetLength) throw new IllegalArgumentException("Subset not all found");
		return (short) mask;
	}

	/**
	 * Hack to convert a checked exception into an unchecked exception.
	 * 
	 * @param <T> Type of exception to return
	 * @param t   Any Throwable instance
	 * @return Throwable instance
	 * @throws T In all cases
	 */
	@SuppressWarnings("unchecked")
	public static <T extends Throwable> T sneakyThrow(Throwable t) throws T {
		throw (T) t;
	}

	@SuppressWarnings("unchecked")
	public static <T> T[] copyOfRangeExcludeNulls(T[] entries, int offset, int length) {
		int newLen = length;
		for (int i = 0; i < length; i++) {
			if (entries[offset + i] == null) newLen--;
		}
		if (newLen < length) {
			T[] result = (T[]) Array.newInstance(entries.getClass().getComponentType(), newLen);
			int ix = 0;
			for (int i = 0; i < length; i++) {
				T v = entries[offset + i];
				if (v != null) {
					result[ix++] = v;
				}
			}
			assert (ix == newLen);
			return result;
		} else {
			return Arrays.copyOfRange(entries, offset, offset + length);
		}
	}

	/**
	 * Reverse an array in place
	 */
	public static <T> void reverse(T[] arr) {
		reverse(arr, arr.length);
	}

	/**
	 * Reverse the first n elements of an array in place
	 */
	public static <T> void reverse(T[] arr, int n) {
		for (int i = 0; i < (n / 2); i++) {
			T val = arr[i];
			arr[i] = arr[n - i - 1];
			arr[n - i - 1] = val;
		}
	}

	/**
	 * Reads the full contents of an input stream into a new byte array.
	 * 
	 * @param is An arbitrary InputStream
	 * @return A byte array containing the full contents of the given InputStream
	 * @throws IOException
	 */
	public static byte[] readBytes(InputStream is) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int bytesRead;
		while ((bytesRead = is.read(buf)) >= 0) {
			bos.write(buf, 0, bytesRead);
		}
		return bos.toByteArray();
	}

	public static boolean isOdd(long x) {
		return (x & 1L) != 0;
	}

	/**
	 * Displays a String representing the given Object.
	 * 
	 * SECURITY: should *not* be used in Actor code, use RT.str(...) instead.
	 * 
	 * @param o
	 * @return String representation of object
	 */
	public static String toString(Object o) {
		if (o == null) return "nil";
		return o.toString();
	}

	public static String stripWhiteSpace(String s) {
		return s.replaceAll("\\s+", "");
	}

	/**
	 * Gets the number of Refs directly contained in an object (will be zero if the
	 * object is not a Ref container)
	 * 
	 * @param eval
	 * @return Number of Refs in the object.
	 */
	public static int refCount(Object a) {
		if (!(a instanceof ACell)) return 0;
		ACell ra = (ACell) a;
		return ra.getRefCount();
	}

	/**
	 * Counts the total number of Refs contained in a data object recursively. Will
	 * count duplicate children multiple times.
	 * 
	 * @param eval
	 * @return Total number of Refs
	 */
	public static long totalRefCount(Object a) {
		if (!(a instanceof IRefContainer)) return 0;

		IRefContainer ra = (IRefContainer) a;
		long[] count = new long[] { 0L };

		IRefContainer ra2;
		ra2 = ra.updateRefs(r -> {
			count[0] += 1 + totalRefCount(r.getValue());

			return r;
		});
		assert (ra == ra2); // check we didn't change anything!
		return count[0];
	}

	public static <R> Ref<R> getRef(Object o, int i) {
		if (o instanceof IRefContainer) {
			return ((IRefContainer) o).getRef(i);
		}
		throw new IllegalArgumentException("Bad ref index: " + i);
	}

	@SuppressWarnings("unchecked")
	public static <T> T updateRefs(Object o, IRefFunction func) {
		if (o instanceof IRefContainer) {
			return ((IRefContainer) o).updateRefs(func);
		}
		return (T) o;
	}

	public static int bitCount(short mask) {
		return Integer.bitCount(mask & 0xFFFF);
	}

	/**
	 * Runs test repeatedly, until it returns true or the timeout has elapsed
	 * 
	 * @param millis
	 * @param test
	 */
	public static void timeout(int millis, Supplier<Boolean> test) {
		long start = System.currentTimeMillis();
		long now = start;
		do {
			if (test.get()) return;
			try {
				Thread.sleep((long) ((now - start) * 0.3 + 1));
			} catch (InterruptedException e) {
				// ignore;
			}
			now = System.currentTimeMillis();
		} while ((now - start) < millis);
	}

	private static long lastTimestamp = Instant.now().toEpochMilli();

	/**
	 * Gets the current system timestamp. Guaranteed monotonic within JVM.
	 * 
	 * @return Timestamp
	 */
	public static long getCurrentTimestamp() {
		// Use Instant milliseconds
		long ts = Instant.now().toEpochMilli();
		if (ts > lastTimestamp) {
			lastTimestamp = ts;
			return ts;
		} else {
			return lastTimestamp;
		}
	}

	/**
	 * Test if the first hex digits of two bytes match
	 * 
	 * @param a Any byte value
	 * @param b Any byte value
	 * @return true if the first hex digit (high nibble) of the two bytes is equal,
	 *         false otherwise.
	 */
	public static boolean firstDigitMatch(byte a, byte b) {
		return (a & 0xF0) == (b & 0xF0);
	}

}