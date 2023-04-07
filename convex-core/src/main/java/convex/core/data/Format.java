package convex.core.data;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;

import convex.core.Belief;
import convex.core.Block;
import convex.core.BlockResult;
import convex.core.Order;
import convex.core.Result;
import convex.core.State;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.AFn;
import convex.core.lang.Core;
import convex.core.lang.Ops;
import convex.core.lang.RT;
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
	
	/**
	 * Maximum length for a VLC encoded Long
	 */
	public static final int MAX_VLC_LONG_LENGTH = 10; // 70 bits
	
	/**
	 * Maximum size in bytes of an embedded value, including tag
	 */
	public static final int MAX_EMBEDDED_LENGTH=140; // TODO: reconsider
	
	/**
	 * Encoded length of a null value
	 */
	public static final int NULL_ENCODING_LENGTH = 1;
	
	/**
	 * Maximum length in bytes of a Ref encoding (may be an embedded data object)
	 */
	public static final int MAX_REF_LENGTH = Math.max(Ref.INDIRECT_ENCODING_LENGTH, MAX_EMBEDDED_LENGTH);

	/**
	 * Gets the length in bytes of VLC encoding for the given long value
	 * @param x Long value to encode
	 * @return Length of VLC encoding
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
	 * @param bb ByteBuffer to write to
	 * @param x Value to VLC encode
	 * @return Updated ByteBuffer
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
	 * @param bs Byte array to write to
	 * @param pos Initial position in byte array
	 * @param x Long value to write
	 * @return end position in byte array after writing VLC long
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
	 * Reads a VLC encoded long from the given ByteBuffer. Assumes no tag
	 * 
	 * @param bb ByteBuffer from which to read
	 * @return Long value from ByteBuffer
	 * @throws BadFormatException If encoding is invalid
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
	 * @param b Byte to extend
	 * @return The sign-extended byte as a long
	 */
	public static long vlcSignExtend(byte b) {
		return (((long) b) << 57) >> 57;
	}
	
	public static long readVLCLong(AArrayBlob blob, int pos) throws BadFormatException {
		byte[] data=blob.getInternalArray();
		return readVLCLong(data,pos+blob.getInternalOffset());
	}

	/**
	 * Reads a VLC encoded long as a long from the given location in a byte byte
	 * array. Assumes no tag
	 * @param data Byte array
	 * @param pos Position from which to read in byte array
	 * @return long value from byte array
	 * @throws BadFormatException If format is invalid, or reading beyond end of
	 *                            array
	 */
	public static long readVLCLong(byte[] data, int pos) throws BadFormatException {
		byte octet = data[pos++];
		long result = (((long) octet) << 57) >> 57; // sign extend 7th bit to 64th bit
		int bits = 7;
		while ((octet & 0x80) != 0) {
			if (pos >= data.length) throw new BadFormatException("VLC encoding beyond end of array");
			if (bits > 64) throw new BadFormatException("VLC encoding too long for long value");
			octet = data[pos++];
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
	 * @return The message length, or negative if insufficient bytes
	 * @throws BadFormatException If the ByteBuffer does not start with a valid
	 *                            message length
	 */
	public static int peekMessageLength(ByteBuffer bb) throws BadFormatException {
		int remaining=bb.limit();
		if (remaining==0) return -1;
		
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
					"Format.peekMessageLength: Expected positive VLC message length, got first byte [" + hex + "]");
		}
		
		// Quick check for 1 byte message length
		if ((len & 0x80) == 0) {
			// 1 byte header (without high bit set)
			return len & 0x3F;
		}
		
		// Clear high bit
		len &=0x7f;

		for (int i=1; i<Format.MAX_VLC_LONG_LENGTH; i++) {
			if (i>=remaining) return -1; // we are expecting more bytes, but none available yet....
			int lsb = bb.get(i);
			len = (len << 7) + (lsb&0x7f);
			if ((lsb & 0x80) == 0) {
				return len;
			}
		}

		throw new BadFormatException("Format.peekMessageLength: Too many bytes in length encoding");
	}

	/**
	 * Writes a message length as a VLC encoded long
	 * 
	 * @param bb  ByteBuffer with capacity available for writing
	 * @param len Length of message to write
	 * @return The ByteBuffer after writing the message length
	 */
	public static ByteBuffer writeMessageLength(ByteBuffer bb, int len) {
		return writeVLCLong(bb, len);
	}

	/**
	 * Writes a canonical object to a ByteBuffer, preceded by the appropriate tag
	 * 
	 * @param bb ByteBuffer to write to
	 * @param cell Cell to write (may be null)
	 * @return The ByteBuffer after writing the specified object
	 */
	public static ByteBuffer write(ByteBuffer bb, ACell cell) {
		// first check for null
		if (cell == null) {
			return bb.put(Tag.NULL);
		}
		// Generic handling for all non-null CVM types
		return cell.write(bb);
	}
	
	/**
	 * Writes a canonical object to a byte array, preceded by the appropriate tag
	 * 
	 * @param bs Byte array to write to
	 * @param pos Starting position to write in byte array
	 * @param cell Cell to write (may be null)
	 * @return Position in byte array after writing the specified object
	 */
	public static int write(byte[] bs, int pos, ACell cell) {
		if (cell==null) {
			bs[pos++]=Tag.NULL;
			return pos;
		}
		return cell.encode(bs,pos);
	}

	/**
	 * Writes a UTF-8 String to the byteBuffer. Includes string tag and length
	 * 
	 * @param bb ByteBuffer to write to
	 * @param s String to write
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
	 * @param bb ByteBuffer to write to
	 * @param s String to write
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
	 * @param bs Byte array
	 * @param pos Starting position to write in byte array
	 * @param s String to write
	 * @return Position in byte array after writing
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

	/**
	 * Reads a UTF-8 String from a ByteBuffer. Assumes any tag has already been read
	 * 
	 * @param bb ByteBuffer to read from
	 * @param len Number of UTF-8 bytes to read
	 * @return String from ByteBuffer
	 * @throws BadFormatException If encoding is invalid
	 */
	public static AString readUTF8String(ByteBuffer bb, int len) throws BadFormatException {
		try {
			if (len == 0) return Strings.empty();

			byte[] bs = new byte[len];
			bb.get(bs);

			AString s = Strings.create(Blob.wrap(bs));
			return s;
			// return new String(bs, StandardCharsets.UTF_8);
		} catch (BufferUnderflowException e) {
			throw new BadFormatException("Buffer underflow", e);
		}
	}
	
	/**
	 * Reads UTF-8 String data from a Blob. Assumes any tag has already been read
	 * @param blob Blob data to read from
	 * @param pos Position of first UTF-8 byte
	 * @param len Number of UTF-8 bytes to read
	 * @return String from ByteBuffer
	 * @throws BadFormatException If encoding is invalid
	 */
	public static AString readUTF8String(Blob blob, int pos, int len) throws BadFormatException {
		if (len == 0) return Strings.empty();
		if (blob.count()<pos+len) throw new BadFormatException("Insufficient bytes in blob to read UTF-8 bytes");

		byte[] bs = new byte[len];
		System.arraycopy(blob.getInternalArray(), blob.getInternalOffset()+pos, bs, 0, len);

		AString s = Strings.create(Blob.wrap(bs));
		return s;
	}

	public static ByteBuffer writeLength(ByteBuffer bb, int i) {
		bb = writeVLCLong(bb, i);
		return bb;
	}

	/**
	 * Writes a 64-bit long as 8 bytes to the ByteBuffer provided
	 * 
	 * @param bb Destination ByteBuffer
	 * @param value Value to write
	 * @return ByteBuffer after writing
	 */
	public static ByteBuffer writeLong(ByteBuffer bb, long value) {
		return bb.putLong(value);
	}

	/**
	 * Reads a 64-bit long as 8 bytes from the ByteBuffer provided
	 * 
	 * @param bb Destination ByteBuffer
	 * @return long value
	 */
	public static long readLong(ByteBuffer bb) {
		return bb.getLong();
	}

	/**
	 * Reads a Ref or embedded Cell value from the ByteBuffer.
	 * 
	 * Converts Embedded Cells to Direct Refs automatically.
	 * 
	 * @param <T> Type of referenced value
	 * @param bb ByteBuffer containing a ref to read
	 * @return Ref as read from ByteBuffer
	 * @throws BadFormatException If the data is badly formatted, or a non-embedded
	 *                            object is found.
	 */
	public static <T extends ACell> Ref<T> readRef(ByteBuffer bb) throws BadFormatException {
		byte tag=bb.get();
		if (tag==Tag.REF) return Ref.readRaw(bb);
		T cell= Format.read(tag,bb);
		if (!Format.isEmbedded(cell)) throw new BadFormatException("Non-embedded Cell found instead of ref: type = " +RT.getType(cell));
		return Ref.get(cell);
	}
	
	/**
	 * Reads a Ref or embedded Cell value from a Blob
	 * 
	 * Converts Embedded Cells to Direct Refs automatically.
	 * 
	 * @param <T> Type of referenced value
	 * @param b Blob containing a ref to read
	 * @param pos Position to read Ref from (should point to tag)
	 * @return Ref as read from ByteBuffer
	 * @throws BadFormatException If the data is badly formatted, or a non-embedded
	 *                            object is found.
	 */
	public static <T extends ACell> Ref<T> readRef(Blob b,int pos) throws BadFormatException {
		byte tag=b.byteAt(pos);
		if (tag==Tag.REF) return Ref.readRaw(b,pos+1);
		
		T cell= Format.read(tag,b,pos);
		if (!Format.isEmbedded(cell)) throw new BadFormatException("Non-embedded Cell found instead of ref: type = " +RT.getType(cell));
		return Ref.get(cell);
	}
	
	private static <T extends ACell> ADataStructure<T> readDataStructure(byte tag, Blob b, int pos) throws BadFormatException {
		if (tag == Tag.VECTOR) return Vectors.read(b,pos);

		// if (tag == Tag.MAP) return Maps.read(b,pos);

		// if (tag == Tag.SYNTAX) return Syntax.read(b,pos);
		
		//if (tag == Tag.SET) return Sets.read(b,pos);

		if (tag == Tag.LIST) return List.read(b,pos);

		//if (tag == Tag.BLOBMAP) return BlobMap.read(b,pos);

		return null;
		// TODO: reinstate this once all cases handled
		// throw new BadFormatException("Can't read data structure with tag byte: " + tag);
	}

	@SuppressWarnings("unchecked")
	private static <T extends ACell> T readDataStructure(ByteBuffer bb, byte tag) throws BadFormatException {
		if (tag == Tag.VECTOR) return (T) Vectors.read(bb);

		if (tag == Tag.MAP) return (T) Maps.read(bb);

		if (tag == Tag.SYNTAX) return (T) Syntax.read(bb);
		
		if (tag == Tag.SET) return (T) Sets.read(bb);

		if (tag == Tag.LIST) return (T) List.read(bb);

		if (tag == Tag.BLOBMAP) return (T) BlobMap.read(bb);

		throw new BadFormatException("Can't read data structure with tag byte: " + tag);
	}

	private static ACell readCode(ByteBuffer bb, byte tag) throws BadFormatException {
		if (tag == Tag.OP) return Ops.read(bb);
		if (tag == Tag.CORE_DEF) {
			Symbol sym = Symbol.read(bb);
			// TODO: consider if dependency of format on core bad?
			ACell o = Core.ENVIRONMENT.get(sym);
			if (o == null) throw new BadFormatException("Core definition not found [" + sym + "]");
			return o;
		}
		
		if (tag == Tag.FN_MULTI) {
			AFn<?> fn = MultiFn.read(bb);
			return fn;
		}

		if (tag == Tag.FN) {
			AFn<?> fn = Fn.read(bb);
			return fn;
		}

		throw new BadFormatException("Can't read Op with tag byte: " + Utils.toHexString(tag));
	}

	/**
	 * Decodes a single Value from a Blob. Assumes the presence of a tag.
	 * throws an exception if the Blob contents are not fully consumed
	 * 
	 * @param blob Blob representing the Encoding of the Value
	 * @return Value read from the blob of encoded data
	 * @throws BadFormatException In case of encoding error
	 */
	public static <T extends ACell> T read(Blob blob) throws BadFormatException {
		long n=blob.count();
		if (n<1) throw new BadFormatException("Attempt to decode from empty Blob");
		byte tag = blob.byteAt(0);
		T result= read(tag,blob,0);
		if (result==null) {
			if (n!=1) throw new BadFormatException("Decode of null value but blob size = "+n);
		} else {
			if (result.getEncoding().count()!=n) throw new BadFormatException("Excess bytes in read from Blob");
		}
		return result;
	}
	
	/**
	 * Helper method to read a value encoded as a hex string
	 * @param <T> Type of value to read
	 * @param hexString A valid hex String
	 * @return Value read
	 * @throws BadFormatException If encoding is invalid
	 */
	public static <T extends ACell> T read(String hexString) throws BadFormatException {
		return read(Blob.fromHex(hexString));
	}
	
	/**
	 * Read from a Blob with the specified tag, assumed to be at position 0
	 * @param <T> Type of value to read
	 * @param tag Tag to use for reading
	 * @param blob Blob to read from
	 * @param offset Offset of tag byte in blob
	 * @return Value decoded
	 * @throws BadFormatException If encoding is invalid for the given tag
	 */
	@SuppressWarnings("unchecked")
	private static <T extends ACell> T read(byte tag, Blob blob, int offset) throws BadFormatException {
		if (tag == Tag.NULL) return null;
		
		try {
			int high=(tag & 0xF0);
			if (high == 0x00) return (T) readNumeric(tag,blob,offset);
			if (high == 0x30) return (T) readBasicObject(tag,blob,offset);	
			if (high == 0x30) return (T) readBasicObject(tag,blob,offset);
			
			if ((tag & 0xF0) == 0x80) {
				ADataStructure<ACell> ds= readDataStructure(tag,blob,offset);
				if (ds!=null) return (T)ds;
			}
		} catch (IndexOutOfBoundsException e) {
			throw new BadFormatException("Read out of blob bounds when decoding with tag "+tag);
		}

		// Fallback to read via ByteBuffer
		// TODO: maybe refactor to avoid read from byte buffers?
		ByteBuffer bb = blob.getByteBuffer().position(offset+1);
		T result;

		try {
			result = (T) read(tag,bb);
		} catch (BufferUnderflowException e) {
			throw new BadFormatException("Blob has insufficients bytes: count=" + blob.count()+ " tag="+tag+" offset="+offset, e);
		} 
		long epos=bb.position();
		if (result.cachedEncoding()==null) {
			result.attachEncoding(blob.slice(offset,epos));
		}

		return result;
	}


	private static ANumeric readNumeric(byte tag, Blob blob, int offset) throws BadFormatException {
		// TODO Auto-generated method stub
		if (tag == Tag.LONG) return CVMLong.read(tag,blob,offset);
		if (tag == Tag.INTEGER) return CVMBigInteger.read(tag,blob,offset);
		// Double is special, we enforce a canonical NaN
		if (tag == Tag.DOUBLE) return CVMDouble.read(tag,blob,offset);
		
		throw new BadFormatException("Can't read basic type with tag byte: " + tag);
	}

	/**
	 * Reads a basic type (primitives and numerics) with the given tag
	 * 
	 * @param bb ByteBuffer to read from
	 * @param tag Tag byte indicating type to read
	 * @return Cell value read

	 * @throws BadFormatException If encoding is invalid
	 * @throws BufferUnderflowException if the ByteBuffer contains insufficient bytes for Encoding
	 */
	private static ANumeric readNumeric(ByteBuffer bb, byte tag) throws BadFormatException, BufferUnderflowException {
		if (tag == Tag.LONG) return CVMLong.create(readVLCLong(bb));
		if (tag == Tag.INTEGER) return CVMBigInteger.read(bb);
			
		// Double is special, we enforce a canonical NaN
		if (tag == Tag.DOUBLE) return CVMDouble.read(bb.getDouble());

		throw new BadFormatException("Can't read basic type with tag byte: " + tag);
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends ACell> T readBasicObject(ByteBuffer bb, byte tag) throws BadFormatException, BufferUnderflowException {
		try {
			if (tag == Tag.STRING) return (T) Strings.read(bb);
			if (tag == Tag.BLOB) return (T) Blobs.read(bb);
			if (tag == Tag.SYMBOL) return (T) Symbol.read(bb);
			if (tag == Tag.KEYWORD) return (T) Keyword.read(bb);
			
			if ((tag&Tag.CHAR)==Tag.CHAR) {
				int len=CVMChar.utfByteCountFromTag(tag);
				if (len>4) throw new BadFormatException("Can't read char type with length: " + len);
				return (T) CVMChar.read(len, bb); // note tag byte already read
			}

			throw new BadFormatException("Can't read basic type with tag byte: " + tag);
		} catch (IllegalArgumentException e) {
			throw new BadFormatException("Illegal format error basic type with tag byte: " + tag);
		}
	}
	

	private static ACell readBasicObject(byte tag, Blob blob, int offset)  throws BadFormatException{
		if (tag == Tag.BLOB) return Blobs.read(blob,offset);
		if (tag == Tag.STRING) return Strings.read(blob,offset);
		if (tag == Tag.SYMBOL) return Symbol.read(blob,offset);
		if (tag == Tag.KEYWORD) return Keyword.read(blob,offset);
		
		if ((tag&Tag.CHAR)==Tag.CHAR) {
			int len=CVMChar.utfByteCountFromTag(tag);
			if (len>4) throw new BadFormatException("Can't read char type with length: " + len);
			return CVMChar.read(len, blob,offset); // skip tag byte
		}

		// TODO Auto-generated method stub
		throw new BadFormatException("Can't read basic type with tag byte: " + tag);
	}

	/**
	 * Reads a Record with the given tag
	 * 
	 * @param bb ByteBuffer to read from
	 * @param tag Tag byte indicating type to read
	 * @return Record value read
	 * @throws BadFormatException In case of a bad record encoding
	 */
	@SuppressWarnings("unchecked")
	private static <T extends ACell> T readRecord(ByteBuffer bb, byte tag) throws BadFormatException {
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
	 * Reads one complete Cell from a ByteBuffer.
	 * </p>
	 * 
	 * <p>
	 * May return any valid Cell (including null)
	 * </p>
	 * 
	 * <p>
	 * Assumes the presence of an object tag.
	 * </p>
	 * 
	 * @param bb ByteBuffer from which to read
	 * @return Value read from the ByteBuffer
	 * @throws BadFormatException If encoding is invalid
	 */
	public static <T extends ACell> T read(ByteBuffer bb) throws BadFormatException {
		byte tag = bb.get();
		return read(tag,bb);
	}
	
	/**
	 * Read an arbitrary cell data from a ByteBuffer. Assumes tag already read.
	 * @param <T>
	 * @param tag Tag byte
	 * @param bb ByteBuffer to read from
	 * @return Cell value (may be null)
	 * @throws BadFormatException If the encoding is malformed in any way
	 */
	@SuppressWarnings("unchecked")
	static <T extends ACell> T read(byte tag,ByteBuffer bb) throws BadFormatException {
		if (tag==Tag.NULL) return null;
		try {
			int high=(tag & 0xF0);
			if (high == 0x00) return (T) readNumeric(bb, tag);

			if (high ==0x30) return readBasicObject(bb,tag);

			if (tag == Tag.TRUE) return (T) CVMBool.TRUE;
			if (tag == Tag.FALSE) return (T) CVMBool.FALSE;

			if (tag == Tag.ADDRESS) return (T) Address.readRaw(bb);
			if (tag == Tag.SIGNED_DATA) return (T) SignedData.read(bb);

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

	static ATransaction readTransaction(ByteBuffer bb, byte tag) throws BadFormatException {
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
	 * @param o Value to test
	 * @return true if object is canonical, false otherwise.
	 */
	public static boolean isCanonical(ACell o) {
		if (o==null) return true;
		return o.isCanonical();
	}

	/**
	 * Determines if an object should be embedded directly in the encoding rather
	 * than referenced with a Ref / hash. Defined to be true for most small objects.
	 * 
	 * @param cell Value to test
	 * @return true if object is embedded, false otherwise
	 */
	public static boolean isEmbedded(ACell cell) {
		if (cell == null) return true;
		return cell.isEmbedded();
	}

	/**
	 * Gets the encoded Blob for an object in canonical message format
	 * 
	 * @param o The object to encode
	 * @return Encoded data as a blob
	 */
	public static Blob encodedBlob(ACell o) {
		if (o==null) return Blob.NULL_ENCODING;
		return o.getEncoding();
	}

	/**
	 * Gets an new encoded ByteBuffer for an Cell in wire format
	 * 
	 * @param cell The Cell to encode
	 * @return A ByteBuffer ready to read (i.e. already flipped)
	 */
	public static ByteBuffer encodedBuffer(ACell cell) {
		return Format.encodedBlob(cell).getByteBuffer();
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
	 * @param start Start position (in hex digits)
	 * @param length Length (in hex digits)
	 * @param bb ByteBuffer to read from
	 * @return byte array containing hex digits
	 * @throws BadFormatException In case of bad Encoding format
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
	 * @param cell Any cell
	 * @return Hex String
	 */
	public static String encodedString(ACell cell) {
		return encodedBlob(cell).toHexString();
	}
	
	/**
	 * Gets a hex String representing an object's encoding. Used in testing only.
	 * @param o Any object, will be cast to appropriate CVM type
	 * @return Hex String
	 */
	public static String encodedString(Object o) {
		return encodedString(RT.cvm(o));
	}

	public static int estimateSize(ACell cell) {
		if (cell==null) return 1;
		return cell.estimatedEncodingSize();
	}

	/**
	 * Reads a cell from a Blob of data, allowing for non-embedded children following the first cell
	 * @param data Data to decode
	 * @return Cell instance
	 * @throws BadFormatException If encoding format is invalid
	 */
	public static <T extends ACell> T decodeMultiCell(Blob data) throws BadFormatException {
		long ml=data.count();
		if (ml<1) throw new BadFormatException("Attempt to decode from empty Blob");
		byte tag = data.byteAt(0);
		T result= read(tag,data,0);
		int rl=Utils.checkedInt(result.getEncodingLength());
		if (rl==ml) return result; // Already complete
		
		HashMap<Hash,Ref<?>> hm=new HashMap<>();
		for (int ix=rl; ix<ml;) {
			ACell c=read(tag,data,ix);
			Ref<?> cr=Ref.get(c);
			Hash h=cr.getHash();
			hm.put(h, cr);
			ix+=c.getEncodingLength();
		}
		return result;
	}

}
