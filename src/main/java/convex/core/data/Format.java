package convex.core.data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

import convex.core.Belief;
import convex.core.Block;
import convex.core.BlockResult;
import convex.core.Order;
import convex.core.Result;
import convex.core.State;
import convex.core.crypto.Hash;
import convex.core.data.prim.CVMByte;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.AFn;
import convex.core.lang.Core;
import convex.core.lang.Ops;
import convex.core.lang.RT;
import convex.core.lang.expanders.Expander;
import convex.core.lang.impl.Fn;
import convex.core.lang.impl.MultiFn;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Call;
import convex.core.transactions.Invoke;
import convex.core.transactions.Transfer;
import convex.core.util.Utils;

/**
 * Static utility class for message format encoding
 *
 * "Standards are always out of date. That's what makes them standards." - Alan
 * Bennett
 */
public class Format {


	/**
	 * 8191 byte system-wide limit on the legal length of a data object encoding.
	 * 
	 * Technical reasons for this choice:
	 * <ul>
	 * <li>This is the max length that can be VLC encoded in a 2 byte message header. This simplifies message encoding and decoding.</li>
	 * <li>It is big enough to include a 4096-byte Blob</li>
	 * </ul>
	 */
	public static final int LIMIT_ENCODING_LENGTH = 0x1FFF; 
	
	public static final int MAX_VLC_LONG_LENGTH = 10; // 70 bits
	
	/**
	 * Maximum size in bytes of an embedded value, including tag
	 */
	public static final int MAX_EMBEDDED_LENGTH=140; // TODO: reconsider
	
	/**
	 * Maximum length in bytes of a Ref encoding (may be an embedded data object)
	 */
	public static final int MAX_REF_LENGTH = Math.max(Ref.MAX_ENCODING_LENGTH, MAX_EMBEDDED_LENGTH);

	/**
	 * Gets the length in bytes of VLC encoding for the given long value
	 */
	public static int getVLCLength(long x) {
		if ((x < 64) && (x >= -64)) {
			return 1;
		}
		int bitLength = Utils.bitLength(x);
		int blen = (bitLength + 6) / 7;
		return blen;
	}

	/**
	 * Puts a VLC encoded long into the specified bytebuffer (with no tag)
	 * 
	 * Format: 
	 * <ul>
	 * <li>MSB of each byte 0=last octet, 1=more octets</li>
	 * <li>Following MSB, 7 bits of integer representation for each octet</li>
	 * <li>Second highest bit of first byte is interpreted as the sign</li> 
	 * </ul>
	 */
	public static ByteBuffer writeVLCLong(ByteBuffer bb, long x) {
		if ((x < 64) && (x >= -64)) {
			// single byte, cleared high bit
			byte single = (byte) (x & 0x7F);
			return bb.put(single);
		}
		int bitLength = Utils.bitLength(x);
		int blen = (bitLength + 6) / 7;
		for (int i = blen - 1; i >= 1; i--) {
			byte single = (byte) (0x80 | (x >> (7 * i))); // 7 bits with high bit set
			bb = bb.put(single);
		}
		byte end = (byte) (x & 0x7F); // last 7 bits of long, high bit zero
		return bb.put(end);
	}
	
	/**
	 * Puts a variable length integer into the specified byte array (with no tag)
	 * 
	 * Format: 
	 * <ul>
	 * <li>MSB of each byte 0=last octet, 1=more octets</li>
	 * <li>Following MSB, 7 bits of integer representation for each octet</li>
	 * <li>Second highest bit of first byte is interpreted as the sign</li> 
	 * </ul>
	 * 
	 * @return - end position in byte array after writing VLC long
	 */
	public static int writeVLCLong(byte[] bs, int pos, long x) {
		if ((x < 64) && (x >= -64)) {
			// single byte, cleared high bit
			byte single = (byte) (x & 0x7F);
			bs[pos++]=single;
			return pos;
		}
		
		int bitLength = Utils.bitLength(x);
		int blen = (bitLength + 6) / 7;
		for (int i = blen - 1; i >= 1; i--) {
			byte single = (byte) (0x80 | (x >> (7 * i))); // 7 bits with high bit set
			bs[pos++]=single;
		}
		byte end = (byte) (x & 0x7F); // last 7 bits of long, high bit zero
		bs[pos++]=end;
		return pos;
	}

	/**
	 * Reads a VLC encoded long from the given bytebuffer. Assumes no tag
	 * 
	 * @throws BadFormatException
	 */
	public static long readVLCLong(ByteBuffer bb) throws BadFormatException {
		byte octet = bb.get();
		long result = vlcSignExtend(octet); // sign extend 7th bit to all bits
		int bitsRead = 7;
		int sevenBits = octet & 0x7F;
		final boolean signOnly = (sevenBits == 0x00) || (sevenBits == 0x7F); // flag for continuation with sign only
		while ((octet & 0x80) != 0) {
			if (bitsRead > 64) throw new BadFormatException("VLC long encoding too long for long value");
			octet = bb.get();
			sevenBits = octet & 0x7F;
			if (signOnly && (bitsRead == 7)) { // only need to test on first iteration
				boolean signBit = (sevenBits & 0x40) != 0; // top bit from current 7 bits
				boolean resultSignBit = (result < 0L); // sign bit from first octet
				if (signBit == resultSignBit)
					throw new BadFormatException("VLC long encoding not canonical, excess leading sign byte(s)");
			}

			// continue while high bit of byte set
			result = (result << 7) | sevenBits; // shift and set next 7 lowest bits
			bitsRead += 7;
		}
		if ((bitsRead > 63) && !signOnly) {
			throw new BadFormatException("VLC long encoding not canonical, non-sign information beyond 63 bits read");
		}
		return result;
	}

	/**
	 * Sign extend 7th bit (sign) of a byte to all bits in a long
	 * 
	 * @param b
	 * @return The sign-extended byte as a long
	 */
	public static long vlcSignExtend(byte b) {
		return (((long) b) << 57) >> 57;
	}

	/**
	 * Reads a VLC encoded long as a long from the given location in a byte byte
	 * array. Assumes no tag
	 * 
	 * @throws BadFormatException If format is invalid, or reading beyond end of
	 *                            array
	 */
	public static long readVLCLong(byte[] data, int i) throws BadFormatException {
		byte octet = data[i++];
		long result = (((long) octet) << 57) >> 57; // sign extend 7th bit to 64th bit
		int bits = 7;
		while ((octet & 0x80) != 0) {
			if (i >= data.length) throw new BadFormatException("VLC encoding beyong end of array");
			if (bits > 64) throw new BadFormatException("VLC encoding too long for long value");
			octet = data[i++];
			// continue while high bit of byte set
			result = (result << 7) | (octet & 0x7F); // shift and set next 7 lowest bits
			bits += 7;
		}
		return result;
	}

	/**
	 * Peeks for a VLC encoded message length at the start of a ByteBuffer, which
	 * must contain at least 1 byte, maximum 2.
	 * 
	 * Does not move the buffer position.
	 * 
	 * @param bb ByteBuffer containing a message length
	 * @return The message length
	 * @throws BadFormatException If the ByteBuffer does not start with a valid
	 *                            message length
	 */
	public static int peekMessageLength(ByteBuffer bb) throws BadFormatException {
		if (!bb.hasRemaining()) {
			throw new BadFormatException(
					"Format.peekMessageLength: No message length field:" + Utils.readBufferData(bb));
		}
		int len = bb.get(0);

		// Zero message length not allowed
		if (len == 0) {
			throw new BadFormatException(
					"Format.peekMessageLength: Zero message length:" + Utils.readBufferData(bb));
		}

		if ((len & 0x40) != 0) {
			// sign bit from top byte looks wrong!
			String hex = Utils.toHexString((byte) len);
			throw new BadFormatException(
					"Format.peekMessageLength: Expected positive message length, got first byte [" + hex + "]");
		}

		if ((len & 0x80) == 0) {
			// 1 byte header (without high bit set)
			return len & 0x3F;
		}

		int lsb = bb.get(1);
		if ((lsb & 0x80) != 0) {
			String hex = Utils.toHexString((byte) len) + Utils.toHexString((byte) lsb);
			throw new BadFormatException(
					"Format.peekMessageLength: Max 2 bytes allowed in VLC encoded message length, got [" + hex + "]");
		}
		len = ((len & 0x3F) << 7) + lsb;

		return len;
	}

	/**
	 * Writes a message length as a VLC encoded long
	 * 
	 * @param bb  Bytebuffer with capacity available for writing
	 * @param len
	 * @return The ByteBuffer after writing the message length
	 */
	public static ByteBuffer writeMessageLength(ByteBuffer bb, int len) {
		if ((len < 0) || (len > LIMIT_ENCODING_LENGTH))
			throw new IllegalArgumentException("Invalid message length: " + len);
		return writeVLCLong(bb, len);
	}

	public static ByteBuffer writeVLCBigInteger(ByteBuffer bb, BigInteger value) {
		int bitLength = value.bitLength() + 1; // bits required including sign bit
		if (bitLength <= 64) {
			return writeVLCLong(bb, value.longValue());
		}
		byte[] bs = value.toByteArray();
		int bslen = bs.length;
		int blen = (bitLength + 6) / 7; // number of octets required for encoding
		for (int i = blen - 1; i >= 1; i--) {
			int bits7 = Utils.extractBits(bs, 7, i * 7); // get 7 bits from source bytes
			byte single = (byte) (0x80 | bits7); // 7 bits with high bit set
			bb = bb.put(single);
		}
		byte end = (byte) (bs[bslen - 1] & 0x7F); // last 7 bits of last byte
		return bb.put(end);
	}

	/**
	 * Finds the first byte in a bytebuffer which is a VLC terminal byte, starting
	 * from the current position.
	 * 
	 * @param bb A ByteBuffer starting with a VLC encoded value.
	 * @return VLC terminal byte position (relative to position of bytebuffer), or
	 *         -1 if not found
	 */
	private static int findVLCTerminal(ByteBuffer bb) {
		int pos = bb.position();
		int len = bb.remaining();
		for (int i = 0; i < len; i++) {
			byte x = bb.get(pos + i);
			if ((x & 0x80) == 0) return i;
		}
		return -1;
	}

	/**
	 * Reads a BigInteger from the ByteBuffer. Assumes tag already read.
	 * 
	 * @param bb
	 * @return A BigInteger
	 * @throws BadFormatException
	 */
	public static BigInteger readVLCBigInteger(ByteBuffer bb) throws BadFormatException {
		int vlclen = findVLCTerminal(bb) + 1;
		if (vlclen == 0) throw new BadFormatException("No terminal byte found for VLC encoding of BigInteger");

		/** get bytes of VLC encoding */
		byte[] vlc = new byte[vlclen];
		bb.get(vlc);
		assert ((vlc[vlclen - 1] & 0x80) == 0); // check for terminal byte in correct position

		/** bytes needed to contain VLC encoding bits */
		int blen = (vlclen * 7 + 7) / 8;
		byte[] bs = new byte[blen];
		boolean signBit = (vlc[0] & 0x40) != 0;
		boolean signOnly = (vlc[0] == (byte) 0xFF) | (vlc[0] == (byte) 0x80); // continuation with sign
		bs[0] = (byte) (signBit ? -1 : 0); // initialise first byte with sign
		for (int i = 0; i < vlclen; i++) { // iterate over all bytes in VLC encoding starting from highest
			byte bits7 = (byte) (vlc[i] & 0x7F); // 7 bits from VLC byte

			// if the top byte could have been sign extended, need to check for canonical
			// encoding on the next highest byte
			if (signOnly && (i == 1)) {
				boolean thisSign = (bits7 & 0x40) != 0;
				if (thisSign == signBit)
					throw new BadFormatException("Non-canonical BigInteger with VLC bytes " + Blob.wrap(vlc));
			}
			Utils.setBits(bs, 7, 7 * (vlclen - 1 - i), bits7);
		}
		return new BigInteger(bs);
	}

	/**
	 * Writes a canonical object to a ByteBuffer, preceded by the appropriate tag
	 * 
	 * @param o Object value to write
	 * @return The ByteBuffer after writing the specified object
	 */
	public static ByteBuffer write(ByteBuffer bb, Object o) {
		// Generic handling for all custom writeable types
		// includes all AData instances (addresses, Symbols, Amounts, hashes etc.)
		if (o instanceof ACell) {
			return ((ACell) o).write(bb);
		}
		return writeNonCell(bb,o);
	}
	
	/**
	 * Writes a canonical object to a byte array, preceded by the appropriate tag
	 * 
	 * @param o Object value to write
	 * @return The ByteBuffer after writing the specified object
	 */
	public static int write(byte[] bs, int pos, Object o) {
		// Generic handling for all custom writeable types
		// includes all AData instances (addresses, Symbols, Amounts, hashes etc.)
		if (o instanceof ACell) {
			return ((ACell) o).write(bs,pos);
		}
		return writeNonCell(bs,pos,o);
	}
	
	/**
	 * Writes a canonical object to a byte array, preceded by the appropriate tag
	 * 
	 * @param o Object value to write (may be null)
	 * @return The ByteBuffer after writing the specified object
	 */
	public static int write(byte[] bs, int pos, ACell o) {
		if (o==null) {
			bs[pos++]=Tag.NULL;
			return pos;
		}
		return ((ACell) o).write(bs,pos);
	}
	
	public static int writeNonCell(byte[] bs, int pos, Object o) {
		if (o == null) {
			bs[pos++]=Tag.NULL;
			return pos;
		}

		//if (o instanceof String) {
		//	return Strings.create((String)o).write(bb);
		//}

		if (o instanceof Character) {
			bs[pos++]=Tag.CHAR;
			return Utils.writeChar(bs,pos,((char)o));
		}
		if (o instanceof Boolean) {
			bs[pos++]=(((boolean) o) ? Tag.TRUE : Tag.FALSE);
			return pos;
		}

		throw new IllegalArgumentException("Can't encode to ByteBuffer: " + o.getClass());
	}

	public static ByteBuffer writeNonCell(ByteBuffer bb, Object o) {
		if (o == null) {
			return bb.put(Tag.NULL);
		}

		//if (o instanceof String) {
		//	return Strings.create((String)o).write(bb);
		//}

		if (o instanceof Character) {
			bb = bb.put(Tag.CHAR);
			return bb.putChar((char) o);
		}
		if (o instanceof Boolean) {
			return bb.put((byte) (((boolean) o) ? Tag.TRUE : Tag.FALSE));
		}

		throw new IllegalArgumentException("Can't encode to ByteBuffer: " + o.getClass());
	}

	public static ByteBuffer writeVLCBigDecimal(ByteBuffer bb, BigDecimal value) {
		bb = bb.put((byte) value.scale());
		bb = writeVLCBigInteger(bb, value.unscaledValue());
		return bb;
	}

	public static BigDecimal readVLCBigDecimal(ByteBuffer bb) throws BadFormatException {
		byte scale = bb.get();
		BigInteger value = readVLCBigInteger(bb);
		return new BigDecimal(value, scale);
	}

	/**
	 * Writes a UTF-8 String to the byteBuffer. Includes string tag and length
	 * 
	 * @param bb
	 * @param s
	 * @return ByteBuffer after writing
	 */
	public static ByteBuffer writeUTF8String(ByteBuffer bb, String s) {
		bb = bb.put(Tag.STRING);
		return writeRawUTF8String(bb, s);
	}

	/**
	 * Writes a raw string without tag to the byteBuffer. Includes length in bytes
	 * of UTF-8 representation
	 * 
	 * @param bb
	 * @param s
	 * @return ByteBuffer after writing
	 */
	public static ByteBuffer writeRawUTF8String(ByteBuffer bb, String s) {
		if (s.length() == 0) {
			bb = writeLength(bb, 0);
		} else {
			byte[] bs = Utils.toByteArray(s);
			bb = writeLength(bb, bs.length);
			bb = bb.put(bs);
		}
		return bb;
	}
	
	/**
	 * Writes a raw string without tag to the byte array. Includes length in bytes
	 * of UTF-8 representation
	 * 
	 * @param bb
	 * @param s
	 * @return ByteBuffer after writing
	 */
	public static int writeRawUTF8String(byte[] bs, int pos, String s) {
		if (s.length() == 0) {
			// zero length, no string bytes
			return writeVLCLong(bs,pos,0);
		} 
		
		byte[] sBytes = Utils.toByteArray(s);
		int n=sBytes.length;
		pos = writeVLCLong(bs,pos, sBytes.length);
		System.arraycopy(sBytes, 0, bs, pos, n);
		return pos+n;
	}

	public static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
	public static final ThreadLocal<CharsetDecoder> UTF8_DECODERS = new ThreadLocal<CharsetDecoder>() {
		@Override
		protected CharsetDecoder initialValue() {
			CharsetDecoder dec = UTF8_CHARSET.newDecoder();
			dec.onUnmappableCharacter(CodingErrorAction.REPORT);
			dec.onMalformedInput(CodingErrorAction.REPORT);
			return dec;
		}
	};
	
	public static final ThreadLocal<CharsetEncoder> UTF8_ENCODERS = new ThreadLocal<CharsetEncoder>() {
		@Override
		protected CharsetEncoder initialValue() {
			CharsetEncoder dec = UTF8_CHARSET.newEncoder();
			dec.onUnmappableCharacter(CodingErrorAction.REPORT);
			dec.onMalformedInput(CodingErrorAction.REPORT);
			return dec;
		}
	};

	/**
	 * Largest non-cell encoding. Currently a maximum VLC long plus one byte?
	 */
	private static final int MAX_NON_CELL_LENGTH = 12;

	/**
	 * Reads a UTF-8 String from a ByteBuffer. Assumes the object tag has already been
	 * read
	 * 
	 * @param bb
	 * @return String from ByteBuffer
	 * @throws BadFormatException
	 */
	public static String readUTF8String(ByteBuffer bb) throws BadFormatException {
		try {
			int len = readLength(bb);
			if (len == 0) return "";

			byte[] bs = new byte[len];
			bb.get(bs);

			String s = UTF8_DECODERS.get().decode(ByteBuffer.wrap(bs)).toString();
			return s;
			// return new String(bs, StandardCharsets.UTF_8);
		} catch (BufferUnderflowException e) {
			throw new BadFormatException("Buffer underflow", e);
		} catch (CharacterCodingException e) {
			throw new BadFormatException("Bad UTF-8 format", e);
		}
	}

	/**
	 * Reads a Symbol from a ByteBuffer. Assumes the object tag has already been
	 * read
	 * 
	 * @param bb ByteBuffer from which to read a Symbol
	 * @return Symbol read from ByteBuffer
	 * @throws BadFormatException
	 */
	public static Symbol readSymbol(ByteBuffer bb) throws BadFormatException {
		return Symbol.read(bb);
	}

	public static ByteBuffer writeLength(ByteBuffer bb, int i) {
		bb = writeVLCLong(bb, i);
		return bb;
	}

	/**
	 * Read an int length field (used for Strings etc.)
	 * 
	 * @param bb
	 * @return Length field
	 * @throws BadFormatException
	 */
	public static int readLength(ByteBuffer bb) throws BadFormatException {
		// our strategy to to read along, then test if it is a valid non-negative int
		long l = readVLCLong(bb);
		int li = (int) l;
		if (l != li) throw new BadFormatException("Bad length, out of integer range: " + l);
		if (li < 0) throw new BadFormatException("Negative length: " + li);
		return li;
	}

	/**
	 * Writes a 64-bit long as 8 bytes to the ByteBuffer provided
	 * 
	 * @param value
	 * @return ByteBuffer after writing
	 */
	public static ByteBuffer writeLong(ByteBuffer bb, long value) {
		return bb.putLong(value);
	}

	/**
	 * Reads a 64-bit long as 8 bytes from the ByteBuffer provided
	 * 
	 * @return long value
	 */
	public static long readLong(ByteBuffer bb) {
		return bb.getLong();
	}

	/**
	 * Reads a Ref<T> from the ByteBuffer.
	 * 
	 * Converts embedded objects to RefDirects automatically.
	 * 
	 * @param bb ByteBuffer containing a ref to read
	 * @throws BadFormatException If the data is badly formatted, or a non-embedded
	 *                            object is found.
	 */
	@SuppressWarnings("unchecked")
	public static <T> Ref<T> readRef(ByteBuffer bb) throws BadFormatException {
		T o = Format.read(bb);
		if (o instanceof Ref) return (Ref<T>) o;
		return Ref.get(o);
	}

	@SuppressWarnings("unchecked")
	private static <T> T readDataStructure(ByteBuffer bb, byte tag) throws BadFormatException {
		if (tag == Tag.VECTOR) return (T) Vectors.read(bb);

		if (tag == Tag.MAP) return (T) Maps.read(bb);
		if (tag == Tag.MAP_ENTRY) return (T) MapEntry.read(bb);

		if (tag == Tag.SYNTAX) return (T) Syntax.read(bb);
		
		if (tag == Tag.SET) return (T) Set.read(bb);

		if (tag == Tag.LIST) return (T) List.read(bb);

		if (tag == Tag.BLOBMAP) return (T) BlobMap.read(bb);

		throw new BadFormatException("Can't read data structure with tag byte: " + tag);
	}

	private static Object readCode(ByteBuffer bb, byte tag) throws BadFormatException {
		if (tag == Tag.OP) return Ops.read(bb);
		if (tag == Tag.CORE_DEF) {
			Symbol sym = Symbol.read(bb);
			// TODO: consider if dependency of format on core bad?
			Syntax o = Core.CORE_NAMESPACE.get(sym);
			if (o == null) throw new BadFormatException("Core definition not found [" + sym + "]");
			return o.getValue();
		}
		
		if (tag == Tag.FN_MULTI) {
			AFn<?> fn = MultiFn.read(bb);
			return fn;
		}

		if (tag == Tag.FN) {
			AFn<?> fn = Fn.read(bb);
			return fn;
		}

		if (tag == Tag.EXPANDER) {
			AFn<Object> fn = read(bb);
			if (fn == null) throw new BadFormatException("Can't create expander with null function");
			return Expander.wrap(fn);
		}

		throw new BadFormatException("Can't read Op with tag byte: " + Utils.toHexString(tag));
	}

	/**
	 * Reads a single object from a Blob. Assumes the presence of an object tag.
	 * throws an exception if the Blob contents are not fully consumed
	 * 
	 * @param blob
	 * @return Value read from the blob of encoded data
	 * @throws BadFormatException
	 */
	@SuppressWarnings("unchecked")
	public static <T> T read(Blob blob) throws BadFormatException {
		byte tag = blob.get(0);
		if (tag == Tag.BLOB) {
			return (T) Blobs.readFromBlob(blob);
		} else {
			// TODO: maybe refactor to avoid read from byte buffers?
			ByteBuffer bb = blob.getByteBuffer();
			T result;

			try {
				result = read(bb);
				if (bb.hasRemaining()) throw new BadFormatException(
						"Blob with type " + Utils.getClass(result) + " has excess bytes: " + bb.remaining());
			} catch (BufferUnderflowException e) {
				throw new BadFormatException("Blob has insufficients bytes: " + blob.length(), e);
			} 

			if (result instanceof AObject) {
				// cache the Blob in this data object, to avoid need to re-serialise
				// SECURITY: should be OK, since we have just successfully read from canonical
				// format?
				((AObject) result).attachEncoding(blob);
			}
			return result;
		}
	}

	public static <T> T read(String hexString) throws BadFormatException {
		return read(Blob.fromHex(hexString));
	}

	/**
	 * Reads a basic type (primitives and numerics) with the given tag
	 * 
	 * @throws BadFormatException
	 */
	@SuppressWarnings("unchecked")
	public static <T> T readBasicType(ByteBuffer bb, byte tag) throws BadFormatException, BufferUnderflowException {
		try {
			if (tag == Tag.NULL) return null;
			if (tag == Tag.BYTE) return (T) CVMByte.create(bb.get());
			if (tag == Tag.CHAR) return (T) (Character) bb.getChar();
			if (tag == Tag.LONG) return (T) CVMLong.create(readVLCLong(bb));
			if (tag == Tag.DOUBLE) return (T) CVMDouble.create(bb.getDouble());

			throw new BadFormatException("Can't read basic type with tag byte: " + tag);
		} catch (IllegalArgumentException e) {
			throw new BadFormatException("Format error basic type with tag byte: " + tag);
		}
	}

	/**
	 * Reads a basic type (primitives and numerics) with the given tag
	 * 
	 * @throws BadFormatException
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T readRecord(ByteBuffer bb, byte tag) throws BadFormatException {
		if (tag == Tag.BLOCK) {
			return (T) Block.read(bb);
		}
		if (tag == Tag.STATE) {
			return (T) State.read(bb);
		}
		if (tag == Tag.ORDER) {
			return (T) Order.read(bb);
		}
		if (tag == Tag.BELIEF) {
			return (T) Belief.read(bb);
		}
		
		if (tag == Tag.RESULT) {
			return (T) Result.read(bb);
		}
		
		if (tag == Tag.BLOCK_RESULT) {
			return (T) BlockResult.read(bb);
		}


		throw new BadFormatException("Can't read record type with tag byte: " + tag);
	}

	/**
	 * <p>
	 * Reads an complete data object from a ByteBuffer.
	 * </p>
	 * 
	 * <p>
	 * May return any valid data object (including null, and non), or a Ref
	 * </p>
	 * 
	 * <p>
	 * Assumes the presence of an object tag.
	 * </p>
	 * 
	 * @param bb ByteBuffer from which to read
	 * @return Value read from the ByteBuffer
	 * @throws BadFormatException
	 */
	@SuppressWarnings("unchecked")
	public static <T> T read(ByteBuffer bb) throws BadFormatException, BufferUnderflowException {
		byte tag = bb.get();

		try {
			// put this first, probably the most common, for performance
			if (tag == Tag.REF) return (T) Ref.readRaw(bb);

			if ((tag & 0xF0) == 0x00) return readBasicType(bb, tag);

			if (tag == Tag.STRING) return (T) Strings.read(bb);
			if (tag == Tag.BLOB) return (T) Blobs.read(bb);
			if (tag == Tag.SYMBOL) return (T) readSymbol(bb);
			if (tag == Tag.KEYWORD) return (T) Keyword.read(bb);
			
			if (tag == Tag.HASH) return (T) Hash.read(bb);

			if (tag == Tag.TRUE) return (T) Boolean.TRUE;
			if (tag == Tag.FALSE) return (T) Boolean.FALSE;

			if (tag == Tag.ADDRESS) return (T) Address.readRaw(bb);
			if (tag == Tag.ACCOUNT_KEY) return (T) AccountKey.readRaw(bb);
			if (tag == Tag.SIGNED_DATA) return (T) SignedData.read(bb);

			// need to product compound objects since they may get ClassCastExceptions
			// if the data format is corrupted while reading child objects
			if ((tag & 0xF0) == 0x80) return readDataStructure(bb, tag);

			if ((tag & 0xF0) == 0xA0) return (T) readRecord(bb, tag);

			if ((tag & 0xF0) == 0xD0) return (T) readTransaction(bb, tag);

			if (tag == Tag.PEER_STATUS) return (T) PeerStatus.read(bb);
			if (tag == Tag.ACCOUNT_STATUS) return (T) AccountStatus.read(bb);

			if ((tag & 0xF0) == 0xC0) return (T) readCode(bb, tag);
		} catch (IllegalArgumentException e) {
			throw new BadFormatException("Illegal argument reading encoding", e);
		} catch (ClassCastException e) {
			throw new BadFormatException("Unexpected data type when decoding: "+e.getMessage(), e);
		}

		// report error
		int pos = bb.position() - 1;
		bb.position(0);
		ABlob data = Utils.readBufferData(bb);
		throw new BadFormatException("Don't recognise tag: " + Utils.toHexString(tag) + " at position " + pos
				+ " Content: " + data.toHexString());
	}

	private static ATransaction readTransaction(ByteBuffer bb, byte tag) throws BadFormatException {
		if (tag == Tag.INVOKE) {
			return Invoke.read(bb);
		} else if (tag == Tag.TRANSFER) {
			return Transfer.read(bb);
		} else if (tag == Tag.CALL) {
			return Call.read(bb);
		}
		throw new BadFormatException("Can't read Transaction with tag " + Utils.toHexString(tag));
	}

	/**
	 * Returns true if the object is a canonical data object. Canonical data objects
	 * can be used as first class decentralised data objects.
	 * 
	 * @param o
	 * @return true if object is canonical, false otherwise.
	 */
	public static boolean isCanonical(Object o) {
		if (o instanceof ACell) {
			return ((ACell) o).isCanonical();
		}
		if (Format.isEmbedded(o)) return true;

		throw new Error("Can't determine if value of type " + Utils.getClass(o) + " is canonical: " + o);
	}

	/**
	 * Determines if an object should be embedded directly in the encoding rather
	 * than referenced with a Ref / hash. Defined to be true for most small objects.
	 * 
	 * @param o
	 * @return true if object is embedded, false otherwise
	 */
	public static boolean isEmbedded(Object o) {
		if (o == null) return true;
		if (o instanceof ACell) {
			return ((ACell)o).isEmbedded();
		} else if (o instanceof Number) {
			// six primitive number types
			if (o instanceof Byte) return true;
			if (o instanceof Short) return true;
			if (o instanceof Integer) return true;
			if (o instanceof Long) return true;
			if (o instanceof Float) return true;
			if (o instanceof Double) return true;

			// TODO: probably can't allow this - could be too big for embedding?
			// if (o instanceof BigInteger)
			// return true;
			// if (o instanceof BigDecimal)
			// return true;
		} else {
			if (o instanceof Character) return true;
			if (o instanceof Boolean) return true;
		}
		return false;
	}

	/**
	 * Gets the encoded Blob for an object in canonical message format
	 * 
	 * @param o The object to encode
	 * @return Encoded data as a blob
	 */
	public static Blob encodedBlob(Object o) {
		if (o instanceof AObject) return ((AObject) o).getEncoding();
		
		// It's not a cell, so must be embedded
		byte[] bs=new byte[MAX_NON_CELL_LENGTH];
		int pos=writeNonCell(bs,0,o);
		return Blob.wrap(bs,0,pos);
	}

	/**
	 * Gets an new encoded ByteBuffer for an object in wire format
	 * 
	 * @param o The object to encode
	 * @return A ByteBuffer ready to read (i.e. already flipped)
	 */
	public static ByteBuffer encodedBuffer(Object o) {
		// estimate size of bytebuffer required, 33 bytes big enough for most small
		// stuff
		int initialLength;
		
		boolean isCell=(o instanceof ACell);

		if (isCell) {
			// check for a cached encoding, use this if available
			ACell cell = (ACell)o;
			ABlob b = cell.cachedBlob();
			if (b != null) return b.getByteBuffer();

			initialLength = cell.estimatedEncodingSize();
		} else {
			initialLength = MAX_EMBEDDED_LENGTH;
		}
		ByteBuffer bb = ByteBuffer.allocate(initialLength);
		boolean done = false;
		while (!done) {
			try {
				if ((o instanceof Ref)) {
					// necessary to handle Refs specially, since these are not cells
					bb = ((IWriteable) o).write(bb);
				} else if (isCell) {
					bb = ((ACell)o).write(bb);
				} else {
					bb=writeNonCell(bb,o);
				}
				done = true;
			} catch (BufferOverflowException be) {
				// retry with larger buffer
				bb = ByteBuffer.allocate(bb.capacity() * 2 + 10);
			}
		}
		bb.flip();
		return bb;
	}

	/**
	 * Writes hex digits from digit position start, total length
	 * 
	 * @param bb
	 * @param prefix
	 * @param start
	 * @param length
	 * @return ByteBuffer after writing
	 */
	public static ByteBuffer writeHexDigits(ByteBuffer bb, ABlob src, long start, long length) {
		bb = Format.writeVLCLong(bb, start);
		bb = Format.writeVLCLong(bb, length);
		int nBytes = Utils.checkedInt((length + 1) >> 1);
		byte[] bs = new byte[nBytes];
		for (int i = 0; i < length; i++) {
			Utils.setBits(bs, 4, 4 * ((nBytes * 2) - i - 1), src.getHexDigit(start + i));
		}
		bb = bb.put(bs);
		return bb;
	}
	
	/**
	 * Writes hex digits from digit position start, total length.
	 * 
	 * Fills final hex digit with 0 if length is odd.
	 * 
	 * @param bs Byte array
	 * @param pos Position to write into byte array
	 * @param src Source Blob for hex digits
	 * @param start Start position in source blob (hex digit number from beginning)
	 * @param length Number of hex digits to write
	 * @return position after writing
	 */
	public static int  writeHexDigits(byte[] bs, int pos, ABlob src, long start, long length) {
		pos = Format.writeVLCLong(bs,pos, start);
		pos = Format.writeVLCLong(bs,pos, length);
		int nBytes = Utils.checkedInt((length + 1) >> 1);
		byte[] bs2 = new byte[nBytes];
		for (int i = 0; i < nBytes; i++) {
			long ix=start+i*2;
			int d0=src.getHexDigit(ix);
			int d1=((i*2+1)<length)?src.getHexDigit(ix+1):0;
			bs2[i]=(byte) ((d0<<4)|(d1&0x0f));
		}
		System.arraycopy(bs2, 0, bs, pos, nBytes);
		return pos+nBytes;
	}

	/**
	 * Reads hex digits from ByteBuffer into the specified range of a new byte
	 * array. Needed for BlobMap encoding.
	 * 
	 * @param bb
	 */
	public static byte[] readHexDigits(ByteBuffer bb, long start, long length) throws BadFormatException {
		int nBytes = Utils.checkedInt((length + 1) >> 1);
		byte[] bs = new byte[nBytes];
		bb.get(bs);
		if (length < nBytes * 2) {
			// test for invalid high bits missing if we have an odd number of digits -
			// should be zero
			if (Utils.extractBits(bs, 4, 0) != 0)
				throw new BadFormatException("Bytes for " + length + " hex digits: " + Utils.toHexString(bs));
		}

		int rBytes = Utils.checkedInt((start + length + 1) >> 1); // bytes covering the specified range completely
		byte[] rs = new byte[rBytes];

		for (int i = 0; i < length; i++) {
			int digit = Utils.extractBits(bs, 4, 4 * ((nBytes * 2) - i - 1));
			int di = Utils.checkedInt(4 * ((rBytes * 2) - (start + i) - 1));
			Utils.setBits(rs, 4, di, digit);
		}

		return rs;
	}

	/**
	 * Gets a hex String representing an object's encoding
	 * @param o Any object, will be cast to appropriate CVM type
	 * @return Hex String
	 */
	public static String encodedString(Object o) {
		return encodedBlob(RT.cvm(o)).toHexString();
	}

	public static int estimateSize(Object o) {
		if (o==null) return 1;
		if (o instanceof ACell) return ((ACell)o).estimatedEncodingSize();
		return 50;
	}

}
