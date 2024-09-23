package convex.core.data;
 
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

import convex.core.Belief;
import convex.core.Block;
import convex.core.BlockResult;
import convex.core.Order;
import convex.core.Receipt;
import convex.core.Result;
import convex.core.State;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.AFn;
import convex.core.lang.AOp;
import convex.core.lang.Core;
import convex.core.lang.Ops;
import convex.core.lang.RT;
import convex.core.lang.impl.Fn;
import convex.core.lang.impl.MultiFn;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.Call;
import convex.core.transactions.Invoke;
import convex.core.transactions.Multi;
import convex.core.transactions.Transfer;
import convex.core.util.Bits;
import convex.core.util.Trees;
import convex.core.util.Utils;

import convex.core.exceptions.Panic;

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
	 * Maximum length for a VLC encoded Count
	 */
	public static final int MAX_VLC_COUNT_LENGTH = 9; // 63 bits
	
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
	 * Maximum allowed encoded message length in bytes
	 */
	public static final long MAX_MESSAGE_LENGTH = 20000000;

	/**
	 * Memory size of a fully embedded value (zero)
	 */
	public static final long FULL_EMBEDDED_MEMORY_SIZE = 0L;

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
	 * Gets the length in bytes of VLC count encoding for the given long value
	 * @param x Long value to encode
	 * @return Length of VLC encoding
	 */
	public static int getVLCCountLength(long x) {
		if (x<0) throw new IllegalArgumentException("Negative Count");
		if (x < 128) {
			return 1;
		}
		int bitLength = Utils.bitLength(x)-1; // high zero not required
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
	 * Puts a variable length count into the specified byte array (with no tag)
	 * 
	 * Format: 
	 * <ul>
	 * <li>MSB of each byte 0=last octet, 1=more octets</li>
	 * <li>Following MSB, 7 bits of integer representation for each octet</li>
	 * </ul>
	 * 
	 * @param bs Byte array to write to
	 * @param pos Initial position in byte array
	 * @param x Long value to write
	 * @return end position in byte array after writing VLC long
	 */
	public static int writeVLCCount(byte[] bs, int pos, long x) {
		if (x<0) throw new IllegalArgumentException("VLC Count cannot be negative but got: "+x);
		if (x < 128) {
			// single byte, cleared high bit
			byte single = (byte) (x & 0x7F);
			bs[pos++]=single;
			return pos;
		}
		
		int bitLength = Utils.bitLength(x)-1; // max 63, we don't need high bit (always 0)
		int blen = (bitLength + 6) / 7; // number of octets required
		for (int i = blen - 1; i >= 1; i--) {
			byte single = (byte) (0x80 | (x >> (7 * i))); // 7 bits with high bit set
			bs[pos++]=single;
		}
		byte end = (byte) (x & 0x7F); // last 7 bits of long, high bit zero
		bs[pos++]=end;
		return pos;
	}

	/**
	 * Sign extend 7th bit (sign in a VLC byte) of a byte to all bits in a long
	 * 
	 * i.e. sign extend excluding the continuation bit:
	 * where VLC Byte = csxxxxxx 
	 * 
	 * @param b Byte to extend
	 * @return The sign-extended byte as a long
	 */
	public static long vlcSignExtend(byte b) {
		return (((long) b) << 57) >> 57;
	}
	
	/**
	 * Checks if VLC continues from given byte, i.e. if high bit is set
	 * @param octet
	 * @return True if VLC coding continues, false otherwise
	 */
	protected static boolean vlcContinuesFrom(byte octet) {
		return (octet & 0x80) != 0;
	}
	
	public static long readVLCLong(AArrayBlob blob, int pos) throws BadFormatException {
		byte[] data=blob.getInternalArray();
		return readVLCLong(data,pos+blob.getInternalOffset());
	}

	/**
	 * Reads a VLC encoded long as a long from the given location in a byte
	 * array. Assumes no tag
	 * @param data Byte array
	 * @param pos Position from which to read in byte array
	 * @return long value from byte array
	 * @throws BadFormatException If format is invalid, or reading beyond end of
	 *                            array
	 */
	public static long readVLCLong(byte[] data, int pos) throws BadFormatException {
		byte octet = data[pos++];
		long result = vlcSignExtend(octet); // sign extend 7th bit to 64th bit
		int bits = 7;
		while (vlcContinuesFrom(octet)) {
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
	 * Reads a VLC encoded count (non-negative integer) as a long from the given location in a byte
	 * array. Assumes no tag
	 * @param data Byte array
	 * @param pos Position from which to read in byte array
	 * @return long value from byte array
	 * @throws BadFormatException If format is invalid, or reading beyond end of
	 *                            array
	 */
	public static long readVLCCount(byte[] data, int pos) throws BadFormatException {
		byte octet = data[pos++];
		if (octet==0x80) throw new BadFormatException("Superfluous leading zero on VLC count");
		long result = octet&0x7f;
		int bits = 7;
		while (vlcContinuesFrom(octet)) {
			if (pos >= data.length) {
				throw new BadFormatException("VLC encoding beyond end of array");
			}
			if (bits > 64) throw new BadFormatException("VLC encoding too long for long value");
			octet = data[pos++];
			// continue while high bit of byte set
			result = (result << 7) | (octet & 0x7F); // shift and set next 7 lowest bits
			bits += 7;
		}
		if (result<0) throw new BadFormatException("VLC Count netative overflow");
		return result;
	}
	
	public static long readVLCCount(AArrayBlob blob, int pos) throws BadFormatException {
		byte[] data=blob.getInternalArray();
		return readVLCCount(data,pos+blob.getInternalOffset());
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
	 * Writes a cell encoding to a byte array, preceded by the appropriate tag
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
		Blob enc=cell.cachedEncoding();
		
		if (enc!=null) {
			pos=enc.getBytes(bs, pos);
			return pos;
		} else {
			return cell.encode(bs,pos);
		}
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
	 * @throws BadFormatException If there are insufficient bytes in Blob to read
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
	 * Reads a Ref or embedded Cell value from a Blob
	 * 
	 * Converts Embedded Cells to Direct Refs automatically.
	 * 
	 * @param <T> Type of referenced value
	 * @param b Blob containing a ref to read
	 * @param pos Position to read Ref from (should point to tag)
	 * @return Ref as read from ByteBuffer
	 * @throws BadFormatException If the data is badly formatted, or a non-embedded
	 *                            value is found where it should be a Ref.
	 */
	public static <T extends ACell> Ref<T> readRef(Blob b,int pos) throws BadFormatException {
		byte tag=b.byteAt(pos);
		if (tag==Tag.REF) return Ref.readRaw(b,pos+1);
		
		if (tag==Tag.NULL) return Ref.nil();
		
		// Looks like an embedded cell, so try to read this
		T cell= Format.read(tag,b,pos);
		if (!Format.isEmbedded(cell)) throw new BadFormatException("Non-embedded Cell found instead of ref: type = " +RT.getType(cell));
		return Ref.get(cell);
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends ACell> T readDataStructure(byte tag, Blob b, int pos) throws BadFormatException {
		if (tag == Tag.VECTOR) return (T) Vectors.read(b,pos);

		if (tag == Tag.MAP) return (T) Maps.read(b,pos);

		if (tag == Tag.SYNTAX) return (T) Syntax.read(b,pos);
		
		if (tag == Tag.SET) return (T) Sets.read(b,pos);

		if (tag == Tag.LIST) return (T) List.read(b,pos);

		if (tag == Tag.INDEX) return (T) Index.read(b,pos);

		throw new BadFormatException("Can't read data structure with tag byte: " + tag);
	}

	private static ACell readCode(byte tag, Blob b, int pos) throws BadFormatException {
		if (tag == Tag.CORE_DEF) return Core.read(b, pos);
		
		if (tag == Tag.FN_MULTI) {
			AFn<?> fn = MultiFn.read(b,pos);
			return fn;
		}

		if (tag == Tag.FN) {
			AFn<?> fn = Fn.read(b,pos);
			return fn;
		}
		
		if (tag == Tag.PEER_STATUS) return PeerStatus.read(b,pos);
		if (tag == Tag.ACCOUNT_STATUS) return AccountStatus.read(b,pos); 

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
			if (n!=1) throw new BadFormatException("Decode of nil value but blob size = "+n);
		} else {
			if (result.getEncoding().count()!=n) throw new BadFormatException("Excess bytes in read from Blob");
		}
		return result;
	}
	
	/**
	 * Decodes a single Value from a Blob, starting at a given offset Assumes the presence of a tag.
	 * throws an exception if the Blob contents are not fully consumed
	 * 
	 * @param blob Blob representing the Encoding of the Value
	 * @param offset Offset of tag byte in blob
	 * @return Value read from the blob of encoded data
	 * @throws BadFormatException In case of encoding error
	 */
	public static <T extends ACell> T read(Blob blob, int offset) throws BadFormatException {
		byte tag = blob.byteAt(offset);
		T result= read(tag,blob,offset);
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

		// Fast paths for common one-byte instances. TODO: might switch have better performance if compiled correctly into a table?
		if (tag==Tag.NULL) return null;
		if (tag==Tag.TRUE) return (T) CVMBool.TRUE;
		if (tag==Tag.FALSE) return (T) CVMBool.FALSE;
		if (tag==Tag.INTEGER) return (T) CVMLong.ZERO; 

		try {
			int high=(tag & 0xF0);
			if (high == 0x10) return (T) readNumeric(tag,blob,offset);
			
			if (high == 0x30) return (T) readBasicObject(tag,blob,offset);
			
			if (tag == Tag.ADDRESS) return (T) Address.read(blob,offset);
			
			if (high == 0xE0) return (T) readOp(tag,blob,offset);
			
			if (high == 0xC0) return (T) readCode(tag,blob,offset);

			if (high == 0x80) return readDataStructure(tag,blob,offset);
			
			if (high == 0x90) return (T) readSignedData(tag,blob, offset); 

			if (high == 0xD0) return (T) readTransaction(tag, blob, offset);

			if (high == 0xA0) return (T) readRecord(tag,blob,offset);
		} catch (BadFormatException e) {
			throw e;
		} catch (IndexOutOfBoundsException e) {
			throw new BadFormatException("Read out of blob bounds when decoding with tag 0x"+Utils.toHexString(tag));
		} catch (MissingDataException e) {
			throw e;
		} catch (Exception e) {
			throw new BadFormatException("Unexpected Exception when decoding ("+tag+"): "+e.getMessage(), e);
		}
		throw new BadFormatException(badTagMessage(tag));
	}

	private static <T extends ACell> AOp<T> readOp(byte tag, Blob blob, int offset) throws BadFormatException {
		return Ops.read(blob, offset, (byte) (tag&0x0f));
	}

	private static <T extends ACell> SignedData<T> readSignedData(byte tag,Blob blob, int offset) throws BadFormatException {
		if (tag==Tag.SIGNED_DATA) return SignedData.read(blob,offset,true);	
		if (tag==Tag.SIGNED_DATA_SHORT) return SignedData.read(blob,offset,false);	
		throw new BadFormatException(badTagMessage(tag));
	}

	private static String badTagMessage(byte tag) {
		return "Unrecognised tag byte 0x"+Utils.toHexString(tag);
	}

	private static ANumeric readNumeric(byte tag, Blob blob, int offset) throws BadFormatException {
		// TODO Auto-generated method stub
		if (tag<0x19) return CVMLong.read(tag,blob,offset);
		if (tag == 0x19) return CVMBigInteger.read(blob,offset);
		// Double is special, we enforce a canonical NaN
		if (tag == Tag.DOUBLE) return CVMDouble.read(tag,blob,offset);
		
		throw new BadFormatException(badTagMessage(tag));
	}

	private static ACell readBasicObject(byte tag, Blob blob, int offset)  throws BadFormatException{
		if (tag == Tag.SYMBOL) return Symbol.read(blob,offset);
		if (tag == Tag.KEYWORD) return Keyword.read(blob,offset);
		if (tag == Tag.BLOB) return Blobs.read(blob,offset);
		if (tag == Tag.STRING) return Strings.read(blob,offset);
		
		if ((tag&Tag.CHAR)==Tag.CHAR) {
			int len=CVMChar.byteCountFromTag(tag);
			if (len>4) throw new BadFormatException("Can't read char type with length: " + len);
			return CVMChar.read(len, blob,offset); // skip tag byte
		}

		throw new BadFormatException(badTagMessage(tag));
	}

	
	/**
	 * Reads a Record with the given tag
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	@SuppressWarnings("unchecked")
	private static <T extends ACell> T readRecord(byte tag, Blob b, int pos) throws BadFormatException {
		if (tag == Tag.BLOCK) {
			return (T) Block.read(b,pos);
		}
		if (tag == Tag.STATE) {
			return (T) State.read(b,pos);
		}
		if (tag == Tag.ORDER) {
			return (T) Order.read(b,pos);
		}
		if (tag == Tag.BELIEF) {
			return (T) Belief.read(b,pos);
		}
		
		if (tag == Tag.RESULT) {
			return (T) Result.read(b,pos);
		}
		
		if (tag == Tag.BLOCK_RESULT) {
			return (T) BlockResult.read(b,pos);
		}

		throw new BadFormatException(badTagMessage(tag));
	}

	@SuppressWarnings("unchecked")
	private static <T extends ACell> T readTransaction(byte tag, Blob b, int pos) throws BadFormatException {
		if ((byte)(tag & Tag.RECEIPT_MASK) == Tag.RECEIPT) {
			return (T) Receipt.read(tag,b,pos);
		}

		if (tag == Tag.INVOKE) {
			return (T) Invoke.read(b,pos);
		} else if (tag == Tag.TRANSFER) {
			return (T) Transfer.read(b,pos);
		} else if (tag == Tag.CALL) {
			return (T) Call.read(b,pos);
		} else if (tag == Tag.MULTI) {
			return (T) Multi.read(b,pos);
		}
		throw new BadFormatException(badTagMessage(tag));
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

	/**
	 * Estimate the encoding size of a Cell value. Useful for pre-sizing buffers.
	 * @param cell Cell to estimate encoding size for
	 * @return Estimated encoding size. May not be precise.
	 */
	public static int estimateEncodingSize(ACell cell) {
		if (cell==null) return 1;
		return cell.estimatedEncodingSize();
	}
	
	/**
	 * Decodes an array of Cells packed in a Blob. Cells may be partial.
	 * @param data Data containing cell encodings in multi-cell format
	 * @return Array of decoded cells
	 * @throws BadFormatException In event of any encoding error detected
	 */
	public static ACell[] decodeCells(Blob data) throws BadFormatException {
		long ml=data.count();
		if (ml>Format.MAX_MESSAGE_LENGTH) throw new BadFormatException("Message too long: "+ml);
		if (ml==0) return Cells.EMPTY_ARRAY;
		
		ArrayList<ACell> cells=new ArrayList<>();
		ACell first=Format.read(data, 0);
		cells.add(first);
		int pos=first.getEncodingLength();
		AStore store=Stores.current();
		while (pos<ml) {
			long encLength=Format.readVLCCount(data.getInternalArray(), data.getInternalOffset()+pos);
			pos+=Format.getVLCCountLength(encLength);
			Blob enc=data.slice(pos, pos+encLength);
			ACell result=store.decode(enc);
			// ACell result=Format.read(enc);
			pos+=enc.count;
			cells.add(result);
		}
		return cells.toArray(ACell[]::new);
	}
	
	/**
	 * Reads a cell from a Blob of data, allowing for non-embedded children following the first cell
	 * @param data Data to decode
	 * @return Cell instance
	 * @throws BadFormatException If encoding format is invalid
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T decodeMultiCell(Blob data) throws BadFormatException {
		long ml=data.count();
		if (ml>Format.MAX_MESSAGE_LENGTH) throw new BadFormatException("Message too long: "+ml);
		if (ml<1) throw new BadFormatException("Attempt to decode from empty Blob");
		
		// read first cell
		T result= Format.read(data,0);
		if (result==null) return null; // null value OK at top level
		
		int rl=Utils.checkedInt(result.getEncodingLength());
		if (rl==ml) return result; // Fast path if already complete
		
		// read remaining cells
		HashMap<Hash,ACell> hm=new HashMap<>();
		decodeCells(hm,data.slice(rl,ml));

		HashMap<Hash,ACell> done=new HashMap<Hash,ACell>();		
		ArrayList<ACell> stack=new ArrayList<>();

		IRefFunction func=new IRefFunction() {
			@Override
			public Ref<?> apply(Ref<?> r) {
				if (r.isEmbedded()) {
					ACell cc=r.getValue();
					if (cc==null) return r;
					ACell nc=cc.updateRefs(this);
					if (cc==nc) return r;
					return nc.getRef();
				} else {
					Hash h=r.getHash();
					
					// if done, just replace with done version
					ACell doneVal=done.get(h);
					if (doneVal!=null) return doneVal.getRef();
					
					// if in map, push cell to stack
					ACell part=hm.get(h);
					if (part!=null) {
						stack.add(part);
						return part.getRef();
					}
					
					// not in message, must be partial
					return r;
				}
			} 
		};
		
		stack.add(result); 
		Trees.visitStackMaybePopping(stack, new Predicate<ACell>() {
			@Override
			public boolean test(ACell c) {
				Hash h=c.getHash();
				if (done.containsKey(h)) return true;
				
				int pos=stack.size();
				// Update Refs, adding new non-embedded cells to stack
				ACell nc=c.updateRefs(func);
				
				if (stack.size()==pos) {
					// we must be done since nothing new added to stack
					done.put(h,nc);
					return true;
				} else {
					// something extra on the stack to handle first
					stack.set(pos-1,nc);
					return false;
				}
			}
		});
		
		// ACell cc=done.get(check);
		
		result=(T) done.get(result.getHash());
		
		return result;
	}
	
	/**
	 * Decode encoded non-embedded Cells into an accumulator HashMap
	 * @param acc Accumulator for Cells, keyed by Hash
	 * @param data Encoding to read
	 * @throws BadFormatException In case of bad format, including any embedded values
	 */
	public static void decodeCells(HashMap<Hash,ACell> acc, Blob data) throws BadFormatException {
		long dataLength=data.count();
		try {
			int ix=0;
			AStore store=Stores.current();
			while( ix<dataLength) {
				long encLength=Format.readVLCCount(data,ix);
				ix+=Format.getVLCCountLength(encLength);
				
				Blob enc=data.slice(ix, ix+encLength);
				Hash h=enc.getContentHash();
				
				// Check store for Ref - avoids duplicate objects in many cases
				ACell c=store.decode(enc);
				
				if (c==null) {
					throw new BadFormatException("Null child encoding in Message");
				}
				if (c.isEmbedded()) throw new BadFormatException("Embedded Cell provided in Message");
				
				acc.put(h, c);
				ix+=encLength;
			}
			if (ix!=dataLength) throw new BadFormatException("Bad message length when decoding");
		} catch (IndexOutOfBoundsException e) {
			throw new BadFormatException("Insufficient bytes to decode Cells");
		}
	}

	/**
	 * Encode a Cell completely in multi-cell message format. Format places top level
	 * cell first, following cells in arbitrary order.
	 * 
	 * @param a Cell to Encode
	 * @param everything If true, traverse the entire Cell tree
	 * @return Blob encoding
	 */
	public static Blob encodeMultiCell(ACell a, boolean everything) {
		Blob topCellEncoding=Format.encodedBlob(a);
		if (a.getRefCount()==0) return topCellEncoding;

		// Add any non-embedded child cells to stack
		ArrayList<Ref<?>> cells=new ArrayList<Ref<?>>();
		Consumer<Ref<?>> addToStackFunc=r->{cells.add(r);};
		Cells.visitBranchRefs(a, addToStackFunc);
		if (cells.isEmpty()) {
			// single cell only
			return topCellEncoding;
		}

		int[] ml=new int[] {topCellEncoding.size()}; // Array mutation trick for accumulator. Ugly but works....
		HashSet<Ref<?>> refs=new HashSet<>();
		Trees.visitStack(cells, cr->{
			if (!refs.contains(cr)) {
				ACell c=cr.getValue();
				int encLength=c.getEncodingLength();
				int lengthFieldSize=Format.getVLCCountLength(encLength);
				
				int cellLength=lengthFieldSize+encLength;
				
				int newLength=ml[0]+cellLength;
				if (newLength>Format.MAX_MESSAGE_LENGTH) return;
				ml[0]=newLength;
				refs.add(cr);
				if (everything) Cells.visitBranchRefs(c, addToStackFunc);
			}
		});
		int messageLength=ml[0];
		byte[] msg=new byte[messageLength];
		
		// Write top encoding, then ensure we add each unique child
		topCellEncoding.getBytes(msg, 0);
		int ix=topCellEncoding.size();
		for (Ref<?> r: refs) {
			ACell c=r.getValue();
			Blob enc=Format.encodedBlob(c);
			int encLength=enc.size();
			
			// Write count then Blob encoding
			ix=Format.writeVLCCount(msg, ix, encLength);
			ix=enc.getBytes(msg, ix);
		}
		if (ix!=messageLength) throw new IllegalArgumentException("Bad message length expected "+ml[0]+" but was: "+ix);
		
		return Blob.wrap(msg);
	}
	
	
	/**
	 * Encodes a flat list of cells in order specified in multi-cell format
	 * 
	 * @param cells Cells to Encode
	 * @return Blob containing multi-cell encoding
	 */
	public static Blob encodeCells(java.util.List<ACell> cells) {
		int ml=0;
		for (ACell a:cells) {
			Blob enc=Format.encodedBlob(a); // can be null in some cases, e.g. in DATA responses signalling missing data
			int elen=enc.size();
			if (ml>0) ml+=Format.getVLCCountLength(elen);
			ml+=elen;
		}
		
		byte[] msg=new byte[ml];
		int ix=0;
		for (ACell a:cells) {
			Blob enc=Format.encodedBlob(a); // can be null in some cases, e.g. in DATA responses signalling missing data;
			int elen=enc.size();
			if (ix>0) ix=Format.writeVLCCount(msg,ix,elen);
			ix=enc.getBytes(msg, ix);
		}
		if (ix!=ml) throw new Panic("Bad message length expected "+ml+" but was: "+ix);
		return Blob.wrap(msg);
	}

	/**
	 * Encode a list of cells as a delta message. Encodes list in reverse order
	 * @param cells Cells to encode
	 * @return Encoded multi-cell blob containing the given cells
	 */
	public static Blob encodeDelta(java.util.List<ACell> cells) {
		int n=cells.size();
		int ml=0;
		for (int i=0; i<n; i++) {
			int clen=cells.get(i).getEncodingLength();
			ml+=clen;
			
			if (i<(n-1)) {
				// include length field
				ml+=Format.getVLCCountLength(clen);
			}
		}
		
		byte[] msg=new byte[ml];
		int ix=0;
		// Note we reverse the order since we want the main item first
		for (int i=n-1; i>=0; i--) {
			Blob enc=cells.get(i).getEncoding();
			int elen=enc.size();
			
			if (i<(n-1)) {
				ix=Format.writeVLCCount(msg, ix, elen);
			}
			
			ix=enc.getBytes(msg,ix);
		}
		if (ix!=ml) throw new Panic("Bad message length expected "+ml+" but was: "+ix);
		
		return Blob.wrap(msg);
	}

	public static int getEncodingLength(ACell value) {
		if (value==null) return 1;
		return value.getEncodingLength();
	}

	/**
	 * Reads a long value represented by the specified bytes in a Blob
	 * @param blob Blob instance
	 * @param offset Offset into blob
	 * @param length Length in bytes to read
	 * @return Long value
	 * @throws BadFormatException If the Long format is not canonical (i.e. starts with 0x00)
	 */
	public static long readLong(Blob blob, int offset, int length) throws BadFormatException {
		byte[] bs=blob.getInternalArray();
		offset+=blob.getInternalOffset();
			long v=(long)(bs[offset]);
		if (v==0) {
			if (length==1) throw new BadFormatException("Long encoding: 0x00 not valid");
			if (bs[offset+1]>=0) throw new BadFormatException("Excess 0x00 at start of Long encoding");
		} else if (v==-1) {
			if ((length>1)&&(bs[offset+1]<0)) {
				throw new BadFormatException("Excess 0xff at start of Long encoding");
			}	
		}
		
		// sign extend first byte
		v=(v<<56)>>56;
		
		for (int i=1; i<length; i++) {
			v=(v<<8)+(bs[offset+i]&0xFFl);
		}
		return v;
	}

	/**
	 * Gets the length of a Long in bytes (minimum bytes needs to express value
	 * @param value Long value to analyse
	 * @return Number of bytes needed to express Long
	 */
	public static int getLongLength(long value) {
		if (value==0) return 0;
		if (value>0) return 8-((Bits.leadingZeros(value)-1)/8);
		return 8-((Bits.leadingOnes(value)-1)/8);
	}



}
