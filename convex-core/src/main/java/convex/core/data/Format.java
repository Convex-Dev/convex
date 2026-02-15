package convex.core.data;
 
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.Consumer;

import convex.core.Result;
import convex.core.cpos.CPoSConstants;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.Panic;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.util.Bits;
import convex.core.util.Trees;
import convex.core.util.Utils;

/**
 * Static utility class for CAD3 encoding format
 *
 * "Standards are always out of date. That's what makes them standards." - Alan Bennett
 */
public class Format {

	/**
	 * 16383 byte system-wide limit on the legal length of a data object encoding.
	 * 
	 * Technical reasons for this choice:
	 * <ul>
	 * <li>This is the maximum length that can be VLQ encoded in 2 bytes. This simplifies message encoding and decoding.</li>
	 * <li>It is big enough to include a 4096-byte Blob</li>
	 * <li>It is big enough to include a Record with 63 directly referenced fields</li>
	 * <li>It is small enough to guarantee fitting in a UDP message</li>
	 * </ul>
	 */
	public static final int LIMIT_ENCODING_LENGTH = 0x3FFF; 
	
	/**
	 * Maximum length for a VLQ encoded Long
	 */
	public static final int MAX_VLQ_LONG_LENGTH = 10; // 70 bits
	
	/**
	 * Maximum length for a VLQ encoded Count
	 */
	public static final int MAX_VLQ_COUNT_LENGTH = 9; // 63 bits
	
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
	 * Memory size of a fully embedded value (zero)
	 */
	public static final long FULL_EMBEDDED_MEMORY_SIZE = 0L;

	/**
	 * Maximum number of Refs for any single Cell
	 */
	public static final int MAX_REF_COUNT = 63;

	/**
	 * Gets the length in bytes of VLQ encoding for the given long value
	 * @param x Long value to encode
	 * @return Length of VLQ encoding in bytes
	 */
	public static int getVLQLongLength(long x) {
		if ((x<64)&&(x>=-64)) return 1;
		int bitLength = Utils.bitLength(x);
		int blen = (bitLength + 6) / 7;
		return blen;
	}
	
	/**
	 * Gets the length in bytes of VLQ count encoding for the given long value
	 * @param x Long value to encode
	 * @return Length of VLQ encoding, or 0 if value is negative (overflow case)
	 */
	public static int getVLQCountLength(long x) {
		if (x<0) return 0;
		if (x<128) return 1;
		int bitLength = Utils.bitLength(x)-1; // high zero not required
		int blen = (bitLength + 6) / 7;
		return blen;
	}

	/**
	 * Puts a VLQ encoded count into the specified bytebuffer (with no tag)
	 * 
	 * Format: 
	 * <ul>
	 * <li>MSB of each byte 0=last octet, 1=more octets</li>
	 * <li>Following MSB, 7 bits of integer representation for each octet</li>
	 * </ul>
	 * @param bb ByteBuffer to write to
	 * @param x Value to VLQ encode
	 * @return Updated ByteBuffer
	 */
	public static ByteBuffer writeVLQCount(ByteBuffer bb, long x) {
		if (x<128) {
			if (x<0) throw new IllegalArgumentException("Negative count!");
			// single byte
			byte single = (byte) (x);
			return bb.put(single);
		}
		int bitLength = 64-Bits.leadingZeros(x);
		int blen = (bitLength + 6) / 7; // 8 bits overflows to 2 bytes etc.
		for (int i = blen - 1; i >= 1; i--) {
			byte single = (byte) (0x80 | (x >>> (7 * i))); // 7 bits
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
	 * @return end position in byte array after writing VLQ long
	 */
	public static int writeVLQLong(byte[] bs, int pos, long x) {
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
	 * @return end position in byte array after writing VLQ long
	 */
	public static int writeVLQCount(byte[] bs, int pos, long x) {
		if (x<0) throw new IllegalArgumentException("VLQ Count cannot be negative but got: "+x);
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
	 * Sign extend 7th bit (sign in a VLQ byte) of a byte to all bits in a long
	 * 
	 * i.e. sign extend excluding the continuation bit:
	 * where VLQ Byte = csxxxxxx 
	 * 
	 * @param b Byte to extend
	 * @return The sign-extended byte as a long
	 */
	static long signExtendVLQ(byte b) {
		return (((long) b) << 57) >> 57;
	}
	
	/**
	 * Checks if VLQ continues from given byte, i.e. if high bit is set
	 * @param octet
	 * @return True if VLQ coding continues, false otherwise
	 */
	static boolean vlqContinuesFrom(byte octet) {
		return (octet & 0x80) != 0;
	}
	
	public static long readVLQLong(AArrayBlob blob, int pos) throws BadFormatException {
		byte[] data=blob.getInternalArray();
		return readVLQLong(data,pos+blob.getInternalOffset());
	}

	/**
	 * Reads a VLQ encoded long as a long from the given location in a byte
	 * array. Assumes no tag
	 * @param data Byte array
	 * @param pos Position from which to read in byte array
	 * @return long value from byte array
	 * @throws BadFormatException If format is invalid, or reading beyond end of
	 *                            array
	 */
	public static long readVLQLong(byte[] data, int pos) throws BadFormatException {
		byte octet = data[pos++];
		long result = signExtendVLQ(octet); // sign extend 7th bit to 64th bit
		int bits = 7;
		while (vlqContinuesFrom(octet)) {
			if (pos >= data.length) throw new BadFormatException("VLQ encoding beyond end of array");
			if (bits > 64) throw new BadFormatException("VLQ encoding too long for long value");
			octet = data[pos++];
			// continue while high bit of byte set
			result = (result << 7) | (octet & 0x7F); // shift and set next 7 lowest bits
			bits += 7;
		}
		return result;
	}
	
	/**
	 * Reads a VLQ encoded count (non-negative integer) as a long from the given location in a byte
	 * array. Assumes no tag
	 * @param data Byte array
	 * @param pos Position from which to read in byte array
	 * @return long value from byte array
	 * @throws BadFormatException If format is invalid, or reading beyond end of
	 *                            array
	 */
	public static long readVLQCount(byte[] data, int pos) throws BadFormatException {
		byte octet = data[pos++];
		if ((octet&0xff)==0x80) throw new BadFormatException("Superfluous leading zero on VLQ count");
		long result = octet&0x7f;
		int bits = 7;
		while (vlqContinuesFrom(octet)) {
			if (pos >= data.length) {
				throw new BadFormatException("VLQ encoding beyond end of array");
			}
			if (bits > 64) throw new BadFormatException("VLQ encoding too long for long value");
			octet = data[pos++];
			// continue while high bit of byte set
			result = (result << 7) | (octet & 0x7F); // shift and set next 7 lowest bits
			bits += 7;
		}
		if (bits>63) throw new BadFormatException("VLQ Count overflow");
		return result;
	}
	
	public static long readVLQCount(AArrayBlob blob, int pos) throws BadFormatException {
		byte[] data=blob.getInternalArray();
		return readVLQCount(data,pos+blob.getInternalOffset());
	}


	/**
	 * Peeks for a VLQ encoded message length at the start of a ByteBuffer, which
	 * must contain at least 1 byte to succeed
	 * 
	 * Does not move the buffer position.
	 * 
	 * @param bb ByteBuffer containing a message length
	 * @return The message length, or negative if insufficient bytes available
	 * @throws BadFormatException If the ByteBuffer does not start with a valid
	 *                            message length, or length exceeds Integer.MAX_VALUE
	 */
	public static int peekMessageLength(ByteBuffer bb) throws BadFormatException {
		int remaining=bb.limit();
		if (remaining==0) return -1;
		
		long len = bb.get(0);

		// Quick check for 1 byte message length
		if ((len & 0x80) == 0) {
			// Zero message length not allowed
			if (len == 0) {
				throw new BadFormatException("Format.peekMessageLength: Zero message length");
			}

			// 1 byte length (without high bit set)
			return (int)(len & 0x7F);
		}
		
		// Clear high bits
		len &=0x7f;

		if (len==0) throw new BadFormatException("Format.peekMessageLength: Excess leading zeros");

		for (int i=1; i<Format.MAX_VLQ_COUNT_LENGTH; i++) {
			if (i>=remaining) return -1; // we are expecting more bytes, but none available yet....
			int lsb = bb.get(i);
			len = (len << 7) | (lsb&0x7f);
			if ((lsb & 0x80) == 0) {
				break;
			}
		}

		int result=(int)len;
		if (result!=len) throw new BadFormatException("Format.peekMessageLength: Message too long: "+len);
		return result;
	}

	/**
	 * Writes a message length as a VLQ encoded long
	 * 
	 * @param bb  ByteBuffer with capacity available for writing
	 * @param len Length of message to write
	 * @return The ByteBuffer after writing the message length
	 */
	public static ByteBuffer writeMessageLength(ByteBuffer bb, int len) {
		return writeVLQCount(bb, len);
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
			return writeVLQLong(bs,pos,0);
		} 
		
		byte[] sBytes = Utils.toByteArray(s);
		int n=sBytes.length;
		pos = writeVLQLong(bs,pos, sBytes.length);
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
	 * @param blob Blob data to read from. Must be immutable.
	 * @param pos Position of first UTF-8 byte
	 * @param len Number of UTF-8 bytes to read
	 * @return String from ByteBuffer
	 * @throws BadFormatException If there are insufficient bytes in Blob to read
	 */
	public static AString readUTF8String(Blob blob, int pos, int len) throws BadFormatException {
		if (len == 0) return Strings.empty();
		if (blob.count()<pos+len) throw new BadFormatException("Insufficient bytes in blob to read UTF-8 bytes");

		AString s = Strings.create(blob.slice(pos,pos+len));
		return s;
	}
	
	/**
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
		pos = Format.writeVLQLong(bs,pos, start);
		pos = Format.writeVLQLong(bs,pos, length);
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
	public static ACell[] decodeCells(Blob data, AStore store) throws BadFormatException {
		long ml=data.count();
		if (ml>CPoSConstants.MAX_MESSAGE_LENGTH) throw new BadFormatException("Message too long: "+ml);
		if (ml==0) return Cells.EMPTY_ARRAY;

		ArrayList<ACell> cells=new ArrayList<>();
		// First cell: decode from start of blob (may have trailing cells)
		AEncoder.DecodeState ds = new AEncoder.DecodeState(data);
		ACell first = store.getEncoder().read(ds);
		if (first!=null) first.attachEncoding(data.slice(0, ds.pos - data.getInternalOffset()));
		cells.add(first);
		int pos = ds.pos - data.getInternalOffset();
		while (pos<ml) {
			long encLength=Format.readVLQCount(data.getInternalArray(), data.getInternalOffset()+pos);
			pos+=Format.getVLQCountLength(encLength);
			Blob enc=data.slice(pos, pos+encLength);
			ACell result=store.decode(enc);
			pos+=enc.count;
			cells.add(result);
		}
		return cells.toArray(ACell[]::new);
	}
	
	/**
	 * Encode a Cell completely in multi-cell message format. Format places top level
	 * cell first, following cells in arbitrary order.
	 * 
	 * @param a Cell to Encode
	 * @param everything If true, attempt to traverse the entire Cell tree
	 * @return Blob encoding
	 */
	public static Blob encodeMultiCell(ACell a, boolean everything) {
		if (a==null) return Blob.NULL_ENCODING;
		if (a.getRefCount()==0) return a.getEncoding();
		

		// Add any non-embedded child cells to stack
		ArrayList<Ref<?>> cells=new ArrayList<Ref<?>>();
		Consumer<Ref<?>> addToStackFunc=r->{cells.add(r);};
		Cells.visitBranchRefs(a, addToStackFunc);
		if (cells.isEmpty()) {
			// single cell only
			return a.getEncoding();
		}
		return encodeMultiCell(a,cells,everything);
	}

	private static Blob encodeMultiCell(ACell topCell, ArrayList<Ref<?>> branches, boolean everything) {
		Blob topCellEncoding=Cells.encode(topCell);
		Consumer<Ref<?>> addToStackFunc=r->{branches.add(r);};
		
		// Visit refs in stack to add to message, accumulating message size required
		int[] ml=new int[] {topCellEncoding.size()}; // Array mutation trick for accumulator. Ugly but works....
		HashSet<Ref<?>> refs=new HashSet<>();
		Trees.visitStack(branches, cr->{
			if (!refs.contains(cr)) {
				ACell c=cr.getValue();
				int encLength=c.getEncodingLength();
				int lengthFieldSize=Format.getVLQCountLength(encLength);
				
				int cellLength=lengthFieldSize+encLength;
				
				int newLength=ml[0]+cellLength;
				if (newLength>CPoSConstants.MAX_MESSAGE_LENGTH) {
					System.err.println("Exceeded max message length when encoding");
					return;
				}
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
			Blob enc=Cells.encode(c);
			int encLength=enc.size();
			
			// Write count then Blob encoding
			ix=Format.writeVLQCount(msg, ix, encLength);
			ix=enc.getBytes(msg, ix);
		}
		if (ix!=messageLength) throw new IllegalArgumentException("Bad message length expected "+ml[0]+" but was: "+ix);
		
		return Blob.wrap(msg);
	}
	
	/**
	 * Encode a Result down to the encodings of each vector element
	 * @param result Result containing a Vector value
	 * @return Multi-cell encoding of Result
	 */
	public static Blob encodeDataResult(Result result) {
		AVector<?> v=RT.ensureVector(result.getValue());
		if (v==null) throw new IllegalArgumentException("Data result must contain a vector value");
		
		ArrayList<Ref<?>> cells=new ArrayList<Ref<?>>();
		
		// Add the top level vector as a branch iff it is not embedded in the Result
		if (!v.isEmbedded()) {
			cells.add(v.getRef());
		}
		
		v.visitAllChildren(vc->{
			Ref<?> r=vc.getRef();
			if (!r.isEmbedded()) {
				// only add non-embedded children
				cells.add(r);
			};
		}
		);
		v.visitElementRefs(r->{
			if (!r.isEmbedded()) {
				// only add non-embedded children
				cells.add(r);
			};
		});
		
		// Note false to prevent traversing all extra branches
		return encodeMultiCell(result,cells,false);
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
			Blob enc=Cells.encode(a); // can be null in some cases, e.g. in DATA responses signalling missing data
			int elen=enc.size();
			if (ml>0) ml+=Format.getVLQCountLength(elen);
			ml+=elen;
		}
		
		byte[] msg=new byte[ml];
		int ix=0;
		for (ACell a:cells) {
			Blob enc=Cells.encode(a); // can be null in some cases, e.g. in DATA responses signalling missing data;
			int elen=enc.size();
			if (ix>0) ix=Format.writeVLQCount(msg,ix,elen);
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
				ml+=Format.getVLQCountLength(clen);
			}
		}
		
		byte[] msg=new byte[ml];
		int ix=0;
		// Note we reverse the order since we want the main item first
		for (int i=n-1; i>=0; i--) {
			Blob enc=cells.get(i).getEncoding();
			int elen=enc.size();
			
			if (i<(n-1)) {
				ix=Format.writeVLQCount(msg, ix, elen);
			}
			
			ix=enc.getBytes(msg,ix);
		}
		if (ix!=ml) throw new Panic("Bad message length expected "+ml+" but was: "+ix);
		
		return Blob.wrap(msg);
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
		return readLong(blob.getInternalArray(), blob.getInternalOffset()+offset, length);
	}

	/**
	 * Reads a Long value from a byte array at the specified offset.
	 * @param bs Byte array to read from
	 * @param offset Offset in the byte array
	 * @param length Number of bytes to read (1-8)
	 * @return Decoded long value
	 * @throws BadFormatException If the Long format is not canonical
	 */
	public static long readLong(byte[] bs, int offset, int length) throws BadFormatException {
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
